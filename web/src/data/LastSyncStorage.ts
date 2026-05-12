import { Timestamp } from 'firebase/firestore'

/**
 * Per-device, per-user watermark for the delta-pull mechanism. Stored as
 * ms-since-epoch in localStorage. See `docs/live-cross-platform-sync.md`.
 * Missing value → returns Timestamp(0, 0) → next pull is a full pull.
 */

const KEY_PREFIX = 'lastSync_'

function safeLocalStorage(): Storage | null {
  if (typeof localStorage === 'undefined') return null
  return localStorage
}

export const LastSyncStorage = {
  /** Read the watermark for `userId`, or `Timestamp(0, 0)` if unset. */
  read(userId: string): Timestamp {
    const ls = safeLocalStorage()
    if (!ls) return new Timestamp(0, 0)
    const raw = ls.getItem(KEY_PREFIX + userId)
    if (raw == null) return new Timestamp(0, 0)
    const ms = Number(raw)
    if (!Number.isFinite(ms) || ms <= 0) return new Timestamp(0, 0)
    const seconds = Math.floor(ms / 1000)
    const nanos = (ms % 1000) * 1_000_000
    return new Timestamp(seconds, nanos)
  },

  /** Write the watermark for `userId`. Caller must invoke only after a
   *  successful apply, so a transient failure can't advance past the real
   *  `max(updatedAt)` the device has actually persisted. */
  write(userId: string, timestamp: Timestamp): void {
    const ls = safeLocalStorage()
    if (!ls) return
    const ms = timestamp.seconds * 1000 + Math.floor(timestamp.nanoseconds / 1_000_000)
    ls.setItem(KEY_PREFIX + userId, String(ms))
  },

  /** Used by the full-repair pull (Step 5 detection) to start over. */
  clear(userId: string): void {
    const ls = safeLocalStorage()
    if (!ls) return
    ls.removeItem(KEY_PREFIX + userId)
  },
}
