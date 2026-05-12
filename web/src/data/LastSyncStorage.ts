import { Timestamp } from 'firebase/firestore'
import type { Channel } from './UserDocSignal'

/**
 * Per-device, per-user, per-channel watermark for the delta-pull mechanism.
 * Stored as ms-since-epoch in localStorage. See `docs/live-cross-platform-sync.md`.
 * Missing value → returns Timestamp(0, 0) → next pull is a full pull.
 */

function key(channel: Channel, userId: string): string {
  return `lastSync_${channel}_${userId}`
}

function safeLocalStorage(): Storage | null {
  if (typeof localStorage === 'undefined') return null
  return localStorage
}

export const LastSyncStorage = {
  /** Read the watermark, or `Timestamp(0, 0)` if unset. */
  read(channel: Channel, userId: string): Timestamp {
    const ls = safeLocalStorage()
    if (!ls) return new Timestamp(0, 0)
    const raw = ls.getItem(key(channel, userId))
    if (raw == null) return new Timestamp(0, 0)
    const ms = Number(raw)
    if (!Number.isFinite(ms) || ms <= 0) return new Timestamp(0, 0)
    const seconds = Math.floor(ms / 1000)
    const nanos = (ms % 1000) * 1_000_000
    return new Timestamp(seconds, nanos)
  },

  /** Caller must invoke only after a successful apply, so a transient
   *  failure can't advance past the real `max(updatedAt)` the device has
   *  actually persisted. */
  write(channel: Channel, userId: string, timestamp: Timestamp): void {
    const ls = safeLocalStorage()
    if (!ls) return
    const ms = timestamp.seconds * 1000 + Math.floor(timestamp.nanoseconds / 1_000_000)
    ls.setItem(key(channel, userId), String(ms))
  },

  /** Used by the full-repair pull (count() detection) to start over. */
  clear(channel: Channel, userId: string): void {
    const ls = safeLocalStorage()
    if (!ls) return
    ls.removeItem(key(channel, userId))
  },
}
