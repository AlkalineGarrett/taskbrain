import {
  collection,
  doc,
  onSnapshot,
  setDoc,
  deleteDoc,
  updateDoc,
  query,
  orderBy,
  writeBatch,
  serverTimestamp,
  type Firestore,
  type Unsubscribe,
} from 'firebase/firestore'
import type { Auth } from 'firebase/auth'
import type { Timestamp } from 'firebase/firestore'
import { firestoreUsage } from './FirestoreUsage'

export interface RecentTab {
  noteId: string
  displayText: string
  lastAccessedAt: Timestamp | null
}

const MAX_TABS = 5

/**
 * Reads are served from a listener-backed cache so per-tab-open operations
 * (`getOpenTabs`, `enforceTabLimit`) don't pay a Firestore read per call.
 */
export class RecentTabsRepository {
  private cachedTabs: RecentTab[] = []
  private unsubscribe: Unsubscribe | null = null
  private loadPromise: Promise<void> | null = null
  private loadResolve: (() => void) | null = null

  constructor(
    private readonly db: Firestore,
    private readonly auth: Auth,
  ) {}

  private requireUserId(): string {
    const uid = this.auth.currentUser?.uid
    if (!uid) throw new Error('User not signed in')
    return uid
  }

  private openTabsCollection(userId: string) {
    return collection(this.db, 'users', userId, 'openTabs')
  }

  private tabRef(userId: string, noteId: string) {
    return doc(this.openTabsCollection(userId), noteId)
  }

  /**
   * Lazily attach the snapshot listener for the current user's openTabs
   * subcollection. Idempotent — subsequent calls reuse the same load promise.
   *
   * The listener has no `limit()`: the cache holds the full openTabs list so
   * `enforceTabLimit` can compute trim deletes from memory instead of hitting
   * Firestore on every tab open.
   */
  private ensureListenerAttached(): Promise<void> {
    if (this.loadPromise) return this.loadPromise
    const userId = this.requireUserId()

    this.loadPromise = new Promise<void>((resolve) => {
      this.loadResolve = resolve
    })

    const q = query(
      this.openTabsCollection(userId),
      orderBy('lastAccessedAt', 'desc'),
    )

    this.unsubscribe = onSnapshot(
      q,
      (snapshot) => {
        const isFirstSnapshot = this.loadResolve !== null
        if (!isFirstSnapshot && snapshot.metadata.hasPendingWrites) {
          firestoreUsage.recordRead(
            'RecentTabsRepo.listener',
            'LISTENER_LOCAL_ECHO',
            snapshot.docChanges().length,
          )
          return
        }
        const fromCache = snapshot.metadata.fromCache
        const type = isFirstSnapshot
          ? fromCache
            ? 'LISTENER_INITIAL_CACHED'
            : 'LISTENER_INITIAL_FRESH'
          : fromCache
            ? 'LISTENER_UPDATE_CACHED'
            : 'LISTENER_UPDATE_FRESH'
        const docCount = isFirstSnapshot ? snapshot.docs.length : snapshot.docChanges().length
        firestoreUsage.recordRead('RecentTabsRepo.listener', type, docCount)

        this.cachedTabs = snapshot.docs.map((d) => {
          const data = d.data()
          return {
            noteId: (data.noteId as string) ?? '',
            displayText: (data.displayText as string) ?? '',
            lastAccessedAt: (data.lastAccessedAt as Timestamp) ?? null,
          }
        })

        if (isFirstSnapshot) {
          this.loadResolve?.()
          this.loadResolve = null
        }
      },
      (error) => {
        console.error('[RecentTabsRepo] listener failed', error)
        if (this.loadResolve) {
          this.loadResolve()
          this.loadResolve = null
        }
      },
    )

    return this.loadPromise
  }

  /**
   * Detach the listener and drop cached tabs. Intended for logout cleanup.
   */
  clear(): void {
    this.unsubscribe?.()
    this.unsubscribe = null
    this.loadPromise = null
    this.loadResolve = null
    this.cachedTabs = []
  }

  async addOrUpdateTab(noteId: string, displayText: string): Promise<void> {
    const userId = this.requireUserId()
    await setDoc(this.tabRef(userId, noteId), {
      noteId,
      displayText,
      lastAccessedAt: serverTimestamp(),
    })
    firestoreUsage.recordWrite('addOrUpdateTab', 'SET')
    await this.enforceTabLimit(userId)
  }

  /**
   * Returns the most-recent MAX_TABS open tabs from the listener cache.
   * Lazily attaches the listener on first call.
   */
  async getOpenTabs(): Promise<RecentTab[]> {
    await this.ensureListenerAttached()
    return this.cachedTabs.slice(0, MAX_TABS)
  }

  async removeTab(noteId: string): Promise<void> {
    const userId = this.requireUserId()
    await deleteDoc(this.tabRef(userId, noteId))
    firestoreUsage.recordWrite('removeTab', 'DELETE')
  }

  async updateTabDisplayText(noteId: string, displayText: string): Promise<void> {
    const userId = this.requireUserId()
    const ref = this.tabRef(userId, noteId)
    try {
      await updateDoc(ref, { displayText })
      firestoreUsage.recordWrite('updateTabDisplayText', 'UPDATE')
    } catch {
      // Tab may not exist, ignore
    }
  }

  /**
   * Enforces the maximum tab limit by removing the oldest tabs. Reads from
   * the listener cache (no Firestore read per tab open). Awaits the first
   * snapshot so the count reflects writes from other devices that the local
   * client has seen at least once.
   */
  private async enforceTabLimit(userId: string): Promise<void> {
    await this.ensureListenerAttached()
    const all = this.cachedTabs
    if (all.length <= MAX_TABS) return
    const excess = all.slice(MAX_TABS)
    const batch = writeBatch(this.db)
    excess.forEach((t) => batch.delete(this.tabRef(userId, t.noteId)))
    await batch.commit()
    firestoreUsage.recordWrite('enforceTabLimit', 'BATCH_COMMIT', excess.length)
  }
}
