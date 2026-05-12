package org.alkaline.taskbrain.data

import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Per-user signal fields under `users/{uid}` that subsystem listeners watch
 * to trigger delta pulls. Each [Channel] owns its own timestamp field on the
 * same user doc; listeners observe their channel's field and ignore others.
 * Fire-and-forget — never awaited by callers. See `docs/live-cross-platform-sync.md`.
 */
object UserDocSignal {
    private const val TAG = "UserDocSignal"

    /** Per-subsystem signal field on `users/{uid}`. */
    enum class Channel(val fieldName: String) {
        NOTES("lastNoteChange"),
        ALARMS("lastAlarmChange"),
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Called after every successful write to the [channel]'s collection. */
    fun bump(db: FirebaseFirestore, userId: String, channel: Channel): Job =
        bump(db, userId, listOf(channel))

    /** Bump multiple channels in a single `set(merge=true)` write — for save
     *  paths that touch more than one channel's collection (e.g., a note
     *  save with alarm extras spliced in). One write instead of N. */
    fun bump(db: FirebaseFirestore, userId: String, channels: Collection<Channel>): Job {
        if (channels.isEmpty()) return scope.launch { /* no-op */ }
        val fields = channels.associate { it.fieldName to FieldValue.serverTimestamp() as Any }
        val op = "UserDocSignal.bump.${channels.joinToString("+") { it.name }}"
        return setUserFields(db, userId, fields, op)
    }

    /** Create-on-login so signal listeners have a doc to attach to. */
    fun ensureExists(db: FirebaseFirestore, userId: String): Job =
        setUserFields(db, userId, mapOf("uid" to userId), "UserDocSignal.ensureExists")

    private fun setUserFields(
        db: FirebaseFirestore,
        userId: String,
        fields: Map<String, Any>,
        operation: String,
    ): Job = scope.launch {
        try {
            db.collection("users").document(userId)
                .set(fields, SetOptions.merge())
                .await()
            FirestoreUsage.recordWrite(operation, FirestoreUsage.WriteType.SET)
        } catch (e: Exception) {
            Log.w(TAG, "$operation(userId=$userId) failed", e)
        }
    }
}
