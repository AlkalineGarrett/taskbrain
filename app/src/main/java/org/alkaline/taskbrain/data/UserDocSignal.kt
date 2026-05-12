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
 * Per-user signal observed by `NoteStore`'s listener to trigger delta pulls.
 * Fire-and-forget — never awaited by callers. See `docs/live-cross-platform-sync.md`.
 */
object UserDocSignal {
    private const val TAG = "UserDocSignal"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Called after every successful notes-collection write. */
    fun bump(db: FirebaseFirestore, userId: String): Job =
        setUserField(db, userId, "lastNoteChange" to FieldValue.serverTimestamp(), "UserDocSignal.bump")

    /** Create-on-login so the signal listener has a doc to attach to. */
    fun ensureExists(db: FirebaseFirestore, userId: String): Job =
        setUserField(db, userId, "uid" to userId, "UserDocSignal.ensureExists")

    private fun setUserField(
        db: FirebaseFirestore,
        userId: String,
        field: Pair<String, Any>,
        operation: String,
    ): Job = scope.launch {
        try {
            db.collection("users").document(userId)
                .set(mapOf(field), SetOptions.merge())
                .await()
            FirestoreUsage.recordWrite(operation, FirestoreUsage.WriteType.SET)
        } catch (e: Exception) {
            Log.w(TAG, "$operation(userId=$userId) failed", e)
        }
    }
}
