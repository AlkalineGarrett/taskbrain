package org.alkaline.taskbrain

import android.app.Application
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.memoryCacheSettings
import com.google.firebase.firestore.persistentCacheSettings
import org.alkaline.taskbrain.data.ConnectivityMonitor
import org.alkaline.taskbrain.data.FirestoreUsage
import org.alkaline.taskbrain.dsl.directives.ScheduleManager
import org.alkaline.taskbrain.service.NotificationChannels
import org.alkaline.taskbrain.ui.currentnote.undo.UndoStatePersistence

/**
 * Application class for TaskBrain.
 * Initializes notification channels, schedule manager, and clears session-scoped data on app startup.
 */
class TaskBrainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.USE_FIREBASE_EMULATOR) {
            // 10.0.2.2 is the Android emulator's alias for the host machine.
            // Wire Auth before Firestore so the Firestore SDK picks up Auth
            // emulator tokens for the very first request.
            FirebaseAuth.getInstance().useEmulator("10.0.2.2", 9099)
            val firestore = FirebaseFirestore.getInstance()
            firestore.useEmulator("10.0.2.2", 8080)
            firestore.firestoreSettings = FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(memoryCacheSettings {})
                .build()
            signInAnonymouslyForEmulator()
            Log.i(TAG, "Firebase emulators wired (Firestore 8080, Auth 9099)")
        } else {
            FirebaseFirestore.getInstance().firestoreSettings =
                FirebaseFirestoreSettings.Builder()
                    .setLocalCacheSettings(persistentCacheSettings {
                        setSizeBytes(100 * 1024 * 1024)
                    })
                    .build()
        }
        ConnectivityMonitor.start(this)
        NotificationChannels.createChannels(this)
        // Clear persisted undo state on cold start (session boundary)
        UndoStatePersistence.clearAllPersistedState(this)
        // Initialize schedule manager for periodic directive execution
        ScheduleManager.initialize(this)
        // Hook up FirestoreUsage persistence (so the Usage button on the
        // note list can show hourly/daily history that survives restarts).
        FirestoreUsage.attach(this)
    }

    private fun signInAnonymouslyForEmulator() {
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser != null) return
        auth.signInAnonymously()
            .addOnSuccessListener { result ->
                Log.i(TAG, "Emulator anonymous sign-in: uid=${result.user?.uid}")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Emulator anonymous sign-in failed", e)
            }
    }

    companion object {
        private const val TAG = "TaskBrainApplication"
    }
}
