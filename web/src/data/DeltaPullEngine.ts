import {
  getCountFromServer,
  getDocs,
  getDocsFromCache,
  orderBy,
  query,
  Timestamp,
  where,
  type Firestore,
  type Query,
  type QueryDocumentSnapshot,
} from 'firebase/firestore'
import { firestoreUsage } from './FirestoreUsage'
import { LastSyncStorage } from './LastSyncStorage'
import { SignalListener } from './SignalListener'
import type { Channel } from './UserDocSignal'

/**
 * Generic signal+pull engine shared by per-channel subsystems (NoteStore,
 * future AlarmRepository, ...). Owns every part of the sync protocol that
 * doesn't depend on the record type: SignalListener subscription, hydrate
 * from Firestore's persistent cache, watermarked delta pull, count()
 * divergence detection, and pull serialization.
 *
 * Subsystems implement [DeltaPullSink] for the per-collection bits: how to
 * apply records to their cache, how to read the local count, how to surface
 * sync warnings.
 *
 * Lifecycle: the host calls [start] from its attach hook (typically wired
 * to FirestoreLifecycle.subscribe), [foreground] from any "tab returned to
 * visible" trigger (which on web is the same attach hook because the SDK
 * fully tears down on idle), and [clear] from detach / sign-out.
 *
 * Mirrors the Android `DeltaPullEngine.kt`. See
 * `docs/live-cross-platform-sync.md`.
 */

export interface DeltaPullSink<T> {
  /** Replace local cache wholesale with `items` (full pull / repair). */
  applyFullPull(items: T[]): void

  /** Merge `items` into local cache (incremental delta). */
  applyDelta(items: T[]): void

  /** Count of items currently in the local cache. */
  localCount(): number

  /** Surface a sync warning to the user (count mismatch, pull failure). */
  raiseSyncWarning(message: string): void

  /** Clear a previously-raised sync warning after self-healing. */
  clearSyncWarning(): void
}

type PullReason = 'signal' | 'attach' | 'foreground' | 'repair'

/** 5-second overlap on the watermark — covers clock skew and ties. */
const PULL_OVERLAP_SECONDS = 5

export class DeltaPullEngine<T> {
  private currentDb: Firestore | null = null
  private currentUserId: string | null = null
  private signalUnsubscribe: (() => void) | null = null
  private pullTail: Promise<unknown> = Promise.resolve()
  private detectionRanThisSession = false
  private started = false
  private loaded = false
  private loadResolve: (() => void) | null = null
  private loadPromise: Promise<void> | null = null

  constructor(
    private readonly channel: Channel,
    /** Prefix for FirestoreUsage operation names and log tags. */
    private readonly tag: string,
    /** Builds the Firestore query for the channel's collection. */
    private readonly collectionRef: (db: Firestore, userId: string) => Query,
    /** Parses one snapshot to T, or null on parse failure. */
    private readonly parse: (doc: QueryDocumentSnapshot) => T | null,
    /** Reads the `updatedAt` Timestamp off a parsed record. */
    private readonly updatedAt: (item: T) => Timestamp | null | undefined,
    private readonly sink: DeltaPullSink<T>,
  ) {}

  /** Whether [start] has been called and the engine is currently attached
   *  to a (db, userId). Hosts use this to gate re-attach in their own
   *  lifecycle wiring. */
  isAttached(): boolean {
    return this.started
  }

  /** Idempotent. Subscribes to the channel and launches the initial
   *  hydrate+pull+detect chain. On web this is the single per-visibility
   *  trigger (FirestoreLifecycle invokes the host's attach on every
   *  visibility-visible cycle, which detaches and re-starts the engine).
   *  On Android the engine outlives ON_START cycles via its own observer. */
  start(db: Firestore, userId: string): void {
    if (this.started) return
    this.started = true
    this.currentDb = db
    this.currentUserId = userId

    SignalListener.attach(db, userId)
    this.signalUnsubscribe = SignalListener.subscribe(this.channel, () => {
      void this.pullDelta('signal')
    })

    void (async () => {
      await this.hydrateFromCache()
      await this.pullDelta('attach')
      await this.runDetectionOnce()
    })()
  }

  /** Tear down the subscription, cancel in-flight pulls, drop engine state. */
  clear(): void {
    this.signalUnsubscribe?.()
    this.signalUnsubscribe = null
    this.started = false
    this.loaded = false
    this.detectionRanThisSession = false
    this.loadPromise = null
    this.loadResolve = null
    this.currentDb = null
    this.currentUserId = null
  }

  /** Resolves once the initial hydrate+pull has completed. */
  ensureLoaded(): Promise<void> {
    if (this.loaded) return Promise.resolve()
    if (!this.loadPromise) {
      this.loadPromise = new Promise<void>((resolve) => {
        this.loadResolve = resolve
      })
    }
    return this.loadPromise
  }

  isLoaded(): boolean {
    return this.loaded
  }

  /** Test seam: mark the engine as started + loaded so subsequent calls
   *  short-circuit and tests pre-populating the sink don't trigger real
   *  Firestore pulls. */
  markLoadedForTest(): void {
    this.started = true
    this.markLoaded()
  }

  private async hydrateFromCache(): Promise<void> {
    const db = this.currentDb
    const uid = this.currentUserId
    if (!db || !uid) return
    if (this.sink.localCount() > 0) return
    let snap
    try {
      snap = await getDocsFromCache(this.collectionRef(db, uid))
    } catch (e) {
      console.debug(`[${this.tag}] hydrateFromCache skipped`, e)
      return
    }
    if (snap.empty) return
    const items: T[] = []
    for (const d of snap.docs) {
      const item = this.parse(d)
      if (item) items.push(item)
    }
    firestoreUsage.recordRead(`${this.tag}.hydrate`, 'HYDRATE_CACHED', items.length)
    this.sink.applyFullPull(items)
  }

  /** Serialized through pullTail so attach + signal don't double-pull. */
  private pullDelta(reason: PullReason): Promise<void> {
    const next = this.pullTail.catch(() => undefined).then(() => this.pullDeltaInner(reason))
    this.pullTail = next.catch(() => undefined)
    return next
  }

  private async pullDeltaInner(reason: PullReason): Promise<void> {
    const db = this.currentDb
    const uid = this.currentUserId
    if (!db || !uid) return
    const localEmpty = this.sink.localCount() === 0
    const watermark = LastSyncStorage.read(this.channel, uid)
    const watermarkUnset = watermark.seconds === 0 && watermark.nanoseconds === 0
    // Cold tab refresh: localStorage retains the watermark but the sink is
    // empty. Treat as full pull so the delta doesn't return a near-empty
    // result that leaves the cache stale.
    const isFullPull = watermarkUnset || localEmpty
    const q: Query = isFullPull
      ? this.collectionRef(db, uid)
      : query(
          this.collectionRef(db, uid),
          where('updatedAt', '>', overlapBuffered(watermark)),
          orderBy('updatedAt'),
        )
    let snap
    try {
      snap = await getDocs(q)
    } catch (e) {
      console.error(`[${this.tag}] pullDelta(reason=${reason}, full=${isFullPull}) failed`, e)
      this.sink.raiseSyncWarning(e instanceof Error ? e.message : 'Sync failed')
      this.markLoaded()
      return
    }
    firestoreUsage.recordRead(
      `${this.tag}.pull.${reason}`,
      isFullPull ? 'PULL_FULL_REPAIR' : 'PULL_DELTA',
      snap.size,
    )
    const items: T[] = []
    for (const d of snap.docs) {
      const item = this.parse(d)
      if (item) items.push(item)
    }
    if (isFullPull) this.sink.applyFullPull(items)
    else this.sink.applyDelta(items)
    const maxTs = this.maxUpdatedAt(items)
    if (maxTs) LastSyncStorage.write(this.channel, uid, maxTs)
    this.markLoaded()
  }

  private markLoaded(): void {
    if (this.loaded) return
    this.loaded = true
    this.loadResolve?.()
    this.loadResolve = null
  }

  /** Once-per-session count() vs local size; full-repair pull on mismatch. */
  private async runDetectionOnce(): Promise<void> {
    if (this.detectionRanThisSession) return
    if (!this.isLoaded()) return
    this.detectionRanThisSession = true
    const db = this.currentDb
    const uid = this.currentUserId
    if (!db || !uid) return
    let serverCount: number
    try {
      const aggSnap = await getCountFromServer(this.collectionRef(db, uid))
      serverCount = aggSnap.data().count
    } catch (e) {
      console.error(`[${this.tag}] detection count() failed`, e)
      this.detectionRanThisSession = false // allow retry on next foreground
      return
    }
    firestoreUsage.recordRead(`${this.tag}.detection`, 'COUNT_AGGREGATION', 1)
    const localCount = this.sink.localCount()
    if (serverCount === localCount) return
    console.warn(
      `[${this.tag}] detection: count mismatch (local=${localCount}, server=${serverCount}); ` +
      `triggering full repair pull`,
    )
    this.sink.raiseSyncWarning(
      `Sync inconsistency: local has ${localCount}, server has ${serverCount}. Re-syncing.`,
    )
    LastSyncStorage.clear(this.channel, uid)
    await this.pullDelta('repair')
    this.sink.clearSyncWarning()
  }

  private maxUpdatedAt(items: T[]): Timestamp | null {
    // Seconds * 1e9 + nanoseconds fits in Number's 2^53 precision until
    // ~year 2255; far cheaper than allocating a BigInt per item.
    let maxTs: Timestamp | null = null
    let maxNs = 0
    for (const item of items) {
      const ts = this.updatedAt(item)
      if (!ts) continue
      const ns = ts.seconds * 1_000_000_000 + ts.nanoseconds
      if (ns > maxNs) {
        maxNs = ns
        maxTs = ts
      }
    }
    return maxTs
  }
}

function overlapBuffered(watermark: Timestamp): Timestamp {
  const seconds = watermark.seconds - PULL_OVERLAP_SECONDS
  if (seconds < 0) return new Timestamp(0, 0)
  return new Timestamp(seconds, watermark.nanoseconds)
}
