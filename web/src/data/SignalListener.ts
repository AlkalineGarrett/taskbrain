import {
  doc,
  onSnapshot,
  type Firestore,
  type DocumentSnapshot,
  type Unsubscribe,
} from 'firebase/firestore'
import type { Timestamp } from 'firebase/firestore'
import { firestoreUsage } from './FirestoreUsage'
import { type Channel, channelFieldName } from './UserDocSignal'

/**
 * Single shared snapshot listener on `users/{uid}` that demultiplexes per-
 * channel signal changes to subscribed callbacks. Mirrors the Android
 * SignalListener. Each [Channel] subscriber fires when *its* field on the
 * user doc transitions to a new value; fields owned by other channels are
 * ignored by that subscriber's dedup.
 */
class SignalListenerImpl {
  private unsubscribe: Unsubscribe | null = null
  private subscribers = new Map<Channel, Set<() => void>>()
  private lastObserved = new Map<Channel, Timestamp | null>()

  /** Idempotent. Attaches the user-doc snapshot listener. */
  attach(db: Firestore, userId: string): void {
    if (this.unsubscribe) return
    const ref = doc(db, 'users', userId)
    this.unsubscribe = onSnapshot(
      ref,
      (snapshot) => {
        if (snapshot.metadata.hasPendingWrites) {
          firestoreUsage.recordRead('SignalListener', 'LISTENER_LOCAL_ECHO', 1)
          return
        }
        firestoreUsage.recordRead(
          'SignalListener',
          snapshot.metadata.fromCache ? 'LISTENER_UPDATE_CACHED' : 'LISTENER_UPDATE_FRESH',
          1,
        )
        this.dispatch(snapshot)
      },
      (error) => {
        console.error('[SignalListener] error', error)
      },
    )
  }

  private dispatch(snapshot: DocumentSnapshot): void {
    const toFire: Array<() => void> = []
    const data = snapshot.data() ?? {}
    for (const channel of channels()) {
      const newSignal = (data[channelFieldName(channel)] as Timestamp | undefined) ?? null
      if (timestampsEqual(newSignal, this.lastObserved.get(channel) ?? null)) continue
      this.lastObserved.set(channel, newSignal)
      const subs = this.subscribers.get(channel)
      if (subs) for (const cb of subs) toFire.push(cb)
    }
    // Fire callbacks outside the lock-equivalent — JS is single-threaded but
    // a callback might re-enter subscribe/unsubscribe, and iterating the Set
    // while it mutates would be undefined behaviour.
    for (const cb of toFire) {
      try {
        cb()
      } catch (e) {
        console.error('[SignalListener] subscriber callback threw', e)
      }
    }
  }

  /** Register a callback. Returns an unsubscribe function. Does not fire
   *  retroactively — callers needing initial-state behaviour should kick
   *  off their own initial read/pull on subscribe. */
  subscribe(channel: Channel, onChange: () => void): () => void {
    let set = this.subscribers.get(channel)
    if (!set) {
      set = new Set()
      this.subscribers.set(channel, set)
    }
    set.add(onChange)
    return () => {
      this.subscribers.get(channel)?.delete(onChange)
    }
  }

  /** Detach the listener and drop all subscribers. Call on sign-out / detach. */
  clear(): void {
    this.unsubscribe?.()
    this.unsubscribe = null
    this.subscribers.clear()
    this.lastObserved.clear()
  }
}

function channels(): Channel[] {
  return ['NOTES', 'ALARMS']
}

function timestampsEqual(a: Timestamp | null, b: Timestamp | null): boolean {
  if (a === b) return true
  if (a == null || b == null) return false
  return a.seconds === b.seconds && a.nanoseconds === b.nanoseconds
}

export const SignalListener = new SignalListenerImpl()
