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
    private const val KEY_PREFIX = "lastSync_"

    private var prefs: SharedPreferences? = null

    /** Hook up persistence. Idempotent. Call once from Application.onCreate. */
    fun attach(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** Read the watermark for [userId], or `Timestamp(0, 0)` if unset. */
    fun read(userId: String): Timestamp {
        val p = prefs
        if (p == null) {
            Log.w(TAG, "read($userId) called before attach(); returning 0")
            return Timestamp(0, 0)
        }
        val ms = p.getLong(KEY_PREFIX + userId, 0L)
        if (ms <= 0L) return Timestamp(0, 0)
        val seconds = ms / 1000
        val nanos = ((ms % 1000) * 1_000_000).toInt()
        return Timestamp(seconds, nanos)
    }

    /** Write the watermark for [userId]. Caller must invoke only after a
     *  successful apply, so a transient failure can't advance past the real
     *  `max(updatedAt)` the device has actually persisted. */
    fun write(userId: String, timestamp: Timestamp) {
        val p = prefs
        if (p == null) {
            Log.w(TAG, "write($userId) called before attach(); dropping")
            return
        }
        val ms = timestamp.seconds * 1000L + timestamp.nanoseconds / 1_000_000L
        p.edit().putLong(KEY_PREFIX + userId, ms).apply()
    }

    /** Used by the full-repair pull (Step 5 detection) to start over. */
    fun clear(userId: String) {
        prefs?.edit()?.remove(KEY_PREFIX + userId)?.apply()
    }
}
