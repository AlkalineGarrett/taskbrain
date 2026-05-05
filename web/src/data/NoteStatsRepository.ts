import {
  collection,
  doc,
  onSnapshot,
  setDoc,
  serverTimestamp,
  increment,
  type Firestore,
  type Unsubscribe,
} from 'firebase/firestore'
import type { Auth } from 'firebase/auth'
import { db, auth } from '@/firebase/config'
import { noteStatsFromFirestore, type NoteStats } from './NoteStats'
import { firestoreUsage } from './FirestoreUsage'

function sameStats(a: Map<string, NoteStats>, b: Map<string, NoteStats>): boolean {
  if (a.size !== b.size) return false
  for (const [id, av] of a) {
    const bv = b.get(id)
    if (!bv) return false
    if (av.viewCount !== bv.viewCount) return false
    const aTs = av.lastAccessedAt
    const bTs = bv.lastAccessedAt
    if ((aTs == null) !== (bTs == null)) return false
    if (aTs != null && bTs != null && !aTs.isEqual(bTs)) return false
    const aDays = Object.keys(av.viewedDays)
    const bDays = Object.keys(bv.viewedDays)
    if (aDays.length !== bDays.length) return false
    for (const day of aDays) if (!bv.viewedDays[day]) return false
  }
  return true
}

/**
 * Per-user view tracking. Lives apart from the note doc so writes don't echo through
 * the notes collection listener and trigger the directive cache invalidation path.
 *
 * Reads are served from a listener-backed cache (lazy attach, full-collection
 * map, in-memory lookup); writes go directly to Firestore. See
 * `docs/firestore-efficiency.md` Principle 1. Mirrors the [RecentTabsRepository]
 * pattern. Use the module singleton [noteStatsRepo] — two listeners on the
 * same `noteStats` collection would double the listener-read count.
 */
export class NoteStatsRepository {
  private cachedStats: Map<string, NoteStats> = new Map()
  private unsubscribe: Unsubscribe | null = null
  private loadPromise: Promise<void> | null = null
  private loadResolve: (() => void) | null = null
  private subscribers = new Set<() => void>()

  constructor(
    private readonly db: Firestore,
    private readonly auth: Auth,
  ) {}

  private requireUserId(): string {
    const uid = this.auth.currentUser?.uid
    if (!uid) throw new Error('User not signed in')
    return uid
  }

  private statsCollection(userId: string) {
    return collection(this.db, 'users', userId, 'noteStats')
  }

  private statsRef(userId: string, noteId: string) {
    return doc(this.statsCollection(userId), noteId)
  }

  private ensureListenerAttached(): Promise<void> {
    if (this.loadPromise) return this.loadPromise
    const userId = this.requireUserId()

    this.loadPromise = new Promise<void>((resolve) => {
      this.loadResolve = resolve
    })

    this.unsubscribe = onSnapshot(
      this.statsCollection(userId),
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
        firestoreUsage.recordRead('NoteStatsRepo.listener', type, docCount)

        const next = new Map<string, NoteStats>()
        snapshot.docs.forEach((d) => {
          next.set(d.id, noteStatsFromFirestore(d.data()))
        })
        const changed = !sameStats(this.cachedStats, next)
        if (changed) this.cachedStats = next

        if (isFirstSnapshot) {
          this.loadResolve?.()
          this.loadResolve = null
        }
        if (changed) {
          for (const sub of this.subscribers) sub()
        }
      },
      (error) => {
        console.error('[NoteStatsRepo] listener failed', error)
        if (this.loadResolve) {
          this.loadResolve()
          this.loadResolve = null
        }
      },
    )

    return this.loadPromise
  }

  /** Detach the listener and drop the cache. Call on sign-out. */
  clear(): void {
    this.unsubscribe?.()
    this.unsubscribe = null
    this.loadPromise = null
    this.loadResolve = null
    this.cachedStats = new Map()
    this.subscribers.clear()
  }

  /** Subscribe to cache updates. Returns an unsubscribe function. */
  subscribe(listener: () => void): () => void {
    this.subscribers.add(listener)
    return () => this.subscribers.delete(listener)
  }

  /** Embedded `[view ...]` directive renders MUST NOT call this — only top-level note opens. */
  async recordView(noteId: string): Promise<void> {
    const userId = this.requireUserId()
    const today = todayLocalDate()
    await setDoc(
      this.statsRef(userId, noteId),
      {
        lastAccessedAt: serverTimestamp(),
        viewCount: increment(1),
        viewedDays: { [today]: true },
      },
      { merge: true },
    )
    firestoreUsage.recordWrite('recordView', 'SET')
  }

  async loadAllNoteStats(): Promise<Map<string, NoteStats>> {
    await this.ensureListenerAttached()
    return this.cachedStats
  }
}

function todayLocalDate(): string {
  const d = new Date()
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

/**
 * Process-wide singleton. Two listeners on the same `noteStats` collection
 * would double the listener-read count and the per-snapshot work; share one.
 */
export const noteStatsRepo = new NoteStatsRepository(db, auth)
