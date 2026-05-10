import type { FirebaseApp } from 'firebase/app'
import type { Auth } from 'firebase/auth'
import {
  type Firestore,
  connectFirestoreEmulator,
  initializeFirestore,
  memoryLocalCache,
  persistentLocalCache,
  persistentMultipleTabManager,
  terminate,
} from 'firebase/firestore'

/**
 * Owns the Firestore instance lifecycle so the app can put Firestore fully
 * to sleep on inactive tabs. Background tabs running an active Firestore SDK
 * contest the IndexedDB multi-tab lease; each leadership transfer triggers a
 * full collection re-snapshot, which on this user's notes collection is ~1599
 * billed reads. Terminating the SDK on idle releases the lease entirely so the
 * other tab stays primary continuously.
 *
 * Auth and the FirebaseApp are NOT torn down here — they're cheap, stateful
 * across the page session, and getAuth() returns the same instance across
 * lifecycle cycles.
 */

export interface FirestoreContext {
  readonly db: Firestore
  readonly auth: Auth
}

export type AttachHandler = (ctx: FirestoreContext) => void
export type DetachHandler = () => void | Promise<void>

interface Subscription {
  attach: AttachHandler
  detach: DetachHandler
}

export type LifecycleState = 'stopped' | 'starting' | 'started' | 'stopping'

export interface LifecycleSnapshot {
  readonly state: LifecycleState
  /** Increments on every successful start (new Firestore instance). Use as a
   *  React useMemo dep when downstream code captures `db`-derived values. */
  readonly generation: number
}

class FirestoreLifecycle {
  private app: FirebaseApp | null = null
  private auth: Auth | null = null
  private useEmulator = false
  private emulatorWired = false

  private _db: Firestore | null = null
  private _snapshot: LifecycleSnapshot = { state: 'stopped', generation: 0 }
  private subscriptions = new Set<Subscription>()
  private stateListeners = new Set<() => void>()

  // Serialize start/stop to avoid races when visibility events overlap.
  private pending: Promise<void> = Promise.resolve()

  configure(app: FirebaseApp, auth: Auth, useEmulator: boolean): void {
    if (this.app) throw new Error('FirestoreLifecycle already configured')
    this.app = app
    this.auth = auth
    this.useEmulator = useEmulator
  }

  /** Stable identity within a state — safe to use with useSyncExternalStore. */
  getSnapshot = (): LifecycleSnapshot => this._snapshot

  get state(): LifecycleState {
    return this._snapshot.state
  }

  get generation(): number {
    return this._snapshot.generation
  }

  /**
   * Returns the current Firestore instance. Throws if the SDK is not started.
   * Use this in code paths that should never run while stopped (e.g. user-
   * initiated writes from event handlers — the visibility observer guarantees
   * the SDK is started whenever the tab is visible).
   */
  requireDb(): Firestore {
    if (!this._db) {
      throw new Error('Firestore is not started; lifecycle.start() must precede this call')
    }
    return this._db
  }

  /** Stable across lifecycle cycles — Auth survives terminate(). */
  requireAuth(): Auth {
    if (!this.auth) throw new Error('FirestoreLifecycle not configured')
    return this.auth
  }

  /**
   * Register a listener pair. attach runs on every entry to `started`; detach
   * runs on every exit. If already started when subscribe is called, attach
   * is invoked synchronously with the current context.
   */
  subscribe(attach: AttachHandler, detach: DetachHandler): () => void {
    const sub: Subscription = { attach, detach }
    this.subscriptions.add(sub)
    if (this._snapshot.state === 'started' && this._db && this.auth) {
      attach({ db: this._db, auth: this.auth })
    }
    return () => {
      this.subscriptions.delete(sub)
    }
  }

  /** Fires the listener immediately and on every subsequent state transition. */
  subscribeState(listener: () => void): () => void {
    this.stateListeners.add(listener)
    return () => {
      this.stateListeners.delete(listener)
    }
  }

  /** Idempotent. Serialized with stop(). Returns the rejection from startInternal
   *  to the caller (e.g. configuration errors), but never poisons the chain
   *  because internal handler errors are caught inside startInternal/stopInternal. */
  start(): Promise<void> {
    const next = this.pending.then(() => this.startInternal())
    this.pending = next.catch(() => undefined)
    return next
  }

  /** Idempotent. Pending writes are drained by terminate() before it resolves. */
  stop(): Promise<void> {
    const next = this.pending.then(() => this.stopInternal())
    this.pending = next.catch(() => undefined)
    return next
  }

  private async startInternal(): Promise<void> {
    if (this._snapshot.state === 'started') return
    if (!this.app || !this.auth) {
      throw new Error('FirestoreLifecycle.start called before configure')
    }
    this.setSnapshot('starting', this._snapshot.generation)

    const db = initializeFirestore(this.app, {
      localCache: this.useEmulator
        ? memoryLocalCache()
        : persistentLocalCache({
            tabManager: persistentMultipleTabManager(),
          }),
    })

    if (this.useEmulator && !this.emulatorWired) {
      connectFirestoreEmulator(db, 'localhost', 8080)
      this.emulatorWired = true
    }

    this._db = db
    const ctx: FirestoreContext = { db, auth: this.auth }
    for (const sub of this.subscriptions) {
      try {
        sub.attach(ctx)
      } catch (e) {
        console.error('[FirestoreLifecycle] attach handler threw', e)
      }
    }

    this.setSnapshot('started', this._snapshot.generation + 1)
  }

  private async stopInternal(): Promise<void> {
    if (this._snapshot.state === 'stopped') return
    if (!this._db) return
    this.setSnapshot('stopping', this._snapshot.generation)

    const db = this._db
    this._db = null

    // Detach handlers run concurrently so a slow one can't block terminate.
    // Each must self-contain its errors; allSettled never rejects.
    const results = await Promise.allSettled(
      Array.from(this.subscriptions, (sub) => Promise.resolve().then(() => sub.detach())),
    )
    const failures = results.filter((r): r is PromiseRejectedResult => r.status === 'rejected')
    if (failures.length > 0) {
      console.error('[FirestoreLifecycle] detach handlers threw', failures.map((f) => f.reason))
    }

    try {
      await terminate(db)
    } catch (e) {
      console.error('[FirestoreLifecycle] terminate failed', e)
    }

    this.setSnapshot('stopped', this._snapshot.generation)
  }

  private setSnapshot(state: LifecycleState, generation: number): void {
    this._snapshot = { state, generation }
    for (const listener of this.stateListeners) {
      try {
        listener()
      } catch (e) {
        console.error('[FirestoreLifecycle] state listener threw', e)
      }
    }
  }
}

export const firestoreLifecycle = new FirestoreLifecycle()
