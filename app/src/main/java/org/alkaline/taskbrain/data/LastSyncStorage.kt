package org.alkaline.taskbrain.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.firebase.Timestamp

/**
 * Per-device, per-user watermark for the delta-pull mechanism. Stored as
 * ms-since-epoch in SharedPreferences. See `docs/live-cross-platform-sync.md`.
 * Missing value → returns Timestamp(0, 0) → next pull is a full pull.
 */
object LastSyncStorage {
    private const val TAG = "LastSyncStorage"
    private const val PREFS_NAME = "taskbrain_prefs"

    private var prefs: SharedPreferences? = null

    /** Hook up persistence. Idempotent. Call once from Application.onCreate. */
    fun attach(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun key(channel: UserDocSignal.Channel, userId: String): String =
        "lastSync_${channel.name}_$userId"

    /** Read the watermark for ([channel], [userId]), or `Timestamp(0, 0)` if unset. */
    fun read(channel: UserDocSignal.Channel, userId: String): Timestamp {
        val p = prefs
        if (p == null) {
            Log.w(TAG, "read($channel,$userId) called before attach(); returning 0")
            return Timestamp(0, 0)
        }
        val ms = p.getLong(key(channel, userId), 0L)
        if (ms <= 0L) return Timestamp(0, 0)
        val seconds = ms / 1000
        val nanos = ((ms % 1000) * 1_000_000).toInt()
        return Timestamp(seconds, nanos)
    }

    /** Caller must invoke only after a successful apply, so a transient
     *  failure can't advance past the real `max(updatedAt)` the device has
     *  actually persisted. */
    fun write(channel: UserDocSignal.Channel, userId: String, timestamp: Timestamp) {
        val p = prefs
        if (p == null) {
            Log.w(TAG, "write($channel,$userId) called before attach(); dropping")
            return
        }
        val ms = timestamp.seconds * 1000L + timestamp.nanoseconds / 1_000_000L
        p.edit().putLong(key(channel, userId), ms).apply()
    }

    /** Used by the full-repair pull (count() detection) to start over. */
    fun clear(channel: UserDocSignal.Channel, userId: String) {
        prefs?.edit()?.remove(key(channel, userId))?.apply()
    }
}
