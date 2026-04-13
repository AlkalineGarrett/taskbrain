package org.alkaline.taskbrain

import android.app.Application
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.persistentCacheSettings
import org.alkaline.taskbrain.data.ConnectivityMonitor
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
        // Configure Firestore cache (100MB) before any Firestore operation.
        // Default is 10MB which may be too small for heavy users.
        val settings = FirebaseFirestoreSettings.Builder()
            .setLocalCacheSettings(persistentCacheSettings {
                setSizeBytes(100 * 1024 * 1024)
            })
            .build()
        FirebaseFirestore.getInstance().firestoreSettings = settings
        ConnectivityMonitor.start(this)
        NotificationChannels.createChannels(this)
        // Clear persisted undo state on cold start (session boundary)
        UndoStatePersistence.clearAllPersistedState(this)
        // Initialize schedule manager for periodic directive execution
        ScheduleManager.initialize(this)
    }
}
