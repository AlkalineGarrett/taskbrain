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

/**
 * The shared list holds twice the displayed count so a deletion still leaves
 * a full row of 5 to fall back on. Stored in Firestore; per-device "current
 * tab" state (which slot is leftmost) is derived from the URL and lives only
 * on the device.
 */
const MAX_STORED = 10

function sameTabs(a: RecentTab[], b: RecentTab[]): boolean {
  if (a.length !== b.length) return false
  for (let i = 0; i < a.length; i++) {
    const x = a[i]!
    const y = b[i]!
    if (x.noteId !== y.noteId || x.displayText !== y.displayText) return false
  }
  return true
}

/**
 * Reads are served from a listener-backed cache so per-tab-open operations
 * (`getOpenTabs`, `enforceTabLimit`) don't pay a Firestore read per call.
 * Subscribers (e.g. RecentTabsBar) are notified on every cache update,
 * including local-echo snapshots — see `ensureListenerAttached`.
 *
 * Lifecycle: db/auth are bound by FirestoreLifecycle attach/detach so the
 * SDK can be terminated on idle. React subscribers persist across detach
 * so wake-ups re-deliver tab state without re-subscription on the consumer.
 */
export class RecentTabsRepository {
  private db: Firestore | null = null
  private auth: Auth | null = null
  private cachedTabs: RecentTab[] = []
  private unsubscribe: Unsubscribe | null = null
  private loadPromise: Promise<void> | null = null
  private loadResolve: (() => void) | null = null
  private subscribers = new Set<() => void>()

  attach(db: Firestore, auth: Auth): void {
    this.db = db
    this.auth = auth
  }

  detach(): void {
    this.unsubscribe?.()
    this.unsubscribe = null
    this.loadPromise = null
    this.loadResolve = null
    this.cachedTabs = []
    this.db = null
    this.auth = null
    // Subscribers persist; they'll receive updates again on the next attach.
  }

  private requireDb(): Firestore {
    if (!this.db) throw new Error('RecentTabsRepository not attached (Firestore lifecycle stopped)')
    return this.db
  }

  private requireUserId(): string {
    if (!this.auth) throw new Error('RecentTabsRepository not attached')
    const uid = this.auth.currentUser?.uid
    if (!uid) throw new Error('User not signed in')
    return uid
  }

  private openTabsCollection(userId: string) {
    return collection(this.requireDb(), 'users', userId, 'openTabs')
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
        const isLocalEcho = !isFirstSnapshot && snapshot.metadata.hasPendingWrites
        const fromCache = snapshot.metadata.fromCache
        const type = isFirstSnapshot
          ? fromCache
            ? 'LISTENER_INITIAL_CACHED'
            : 'LISTENER_INITIAL_FRESH'
          : isLocalEcho
            ? 'LISTENER_LOCAL_ECHO'
            : fromCache
              ? 'LISTENER_UPDATE_CACHED'
              : 'LISTENER_UPDATE_FRESH'
        const docCount = isFirstSnapshot ? snapshot.docs.length : snapshot.docChanges().length
        firestoreUsage.recordRead('RecentTabsRepo.listener', type, docCount)

        // Process local-echo snapshots too: subscribers (RecentTabsBar) need
        // the cache to reflect the just-written tab right away, otherwise the
        // optimistic add and the next-snapshot read race and the new tab can
        // be dropped from the display.
        const next = snapshot.docs.map((d) => {
          const data = d.data()
          return {
            noteId: (data.noteId as string) ?? '',
            displayText: (data.displayText as string) ?? '',
            lastAccessedAt: (data.lastAccessedAt as Timestamp) ?? null,
          }
        })
        // Each write fires the listener twice (local echo, then server confirm)
        // with identical content; skip the second to spare subscribers a
        // duplicate React re-render and avoid re-running `refreshDisplayTexts`'
        // potential write-back path on unchanged data.
        const changed = !sameTabs(this.cachedTabs, next)
        if (changed) this.cachedTabs = next

        if (isFirstSnapshot) {
          this.loadResolve?.()
          this.loadResolve = null
        }
        if (changed) {
          for (const sub of this.subscribers) sub()
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
   * Subscribe to cache updates. Listener fires after every snapshot (including
   * local echoes). Returns an unsubscribe function.
   */
  subscribe(listener: () => void): () => void {
    this.subscribers.add(listener)
    return () => this.subscribers.delete(listener)
  }

  /** Synchronous read of the current cache. Caller must ensure listener attached. */
  getCachedTabs(): RecentTab[] {
    return this.cachedTabs
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
   * Returns up to MAX_STORED open tabs from the listener cache.
   * Lazily attaches the listener on first call.
   */
  async getOpenTabs(): Promise<RecentTab[]> {
    await this.ensureListenerAttached()
    return this.cachedTabs.slice(0, MAX_STORED)
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
   * Trim the stored history to MAX_STORED. Reads from the listener cache, so
   * the count reflects writes from other devices that the local client has
   * already seen at least once.
   */
  private async enforceTabLimit(userId: string): Promise<void> {
    await this.ensureListenerAttached()
    const all = this.cachedTabs
    if (all.length <= MAX_STORED) return
    const excess = all.slice(MAX_STORED)
    const batch = writeBatch(this.requireDb())
    excess.forEach((t) => batch.delete(this.tabRef(userId, t.noteId)))
    await batch.commit()
    firestoreUsage.recordWrite('enforceTabLimit', 'BATCH_COMMIT', excess.length)
  }
}

/**
 * Process-wide singleton. Two listeners on the same `openTabs` collection
 * would double the listener-read count and the per-snapshot work; share one.
 *
 * Bound to a live Firestore via `attach()` by the bootstrap module on every
 * lifecycle.start(). Tests construct their own instance and call attach().
 */
export const recentTabsRepo = new RecentTabsRepository()
