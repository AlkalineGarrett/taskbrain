package org.alkaline.taskbrain.data

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

/**
 * Single shared snapshot listener on `users/{uid}` that demultiplexes per-
 * channel signal changes to subscribed callbacks. Replaces what used to be
 * one `addSnapshotListener` per subsystem (NoteStore + AlarmRepository),
 * collapsing the bookkeeping into one target.
 *
 * Each [UserDocSignal.Channel] subscriber gets fired when *its* field on
 * the user doc transitions to a new value. Fields owned by other channels
 * are ignored by that subscriber's dedup.
 *
 * Lifecycle:
 * - [attach] runs from whichever subsystem first needs the listener. Safe
 *   to call repeatedly; subsequent calls are no-ops if the listener is up.
 * - [subscribe] returns an unsubscribe function. Subsystems call it from
 *   their `clear` (logout) paths.
 * - [clear] removes the underlying listener and drops all subscribers.
 *   Call on logout from the same place that resets the other repos.
 */
object SignalListener {
    private const val TAG = "SignalListener"

    private val lock = Any()
    private var registration: ListenerRegistration? = null
    private val subscribers = mutableMapOf<UserDocSignal.Channel, MutableList<() -> Unit>>()
    private val lastObserved = mutableMapOf<UserDocSignal.Channel, Timestamp?>()

    /** Idempotent. Attaches the user-doc snapshot listener. */
    fun attach(db: FirebaseFirestore, userId: String) {
        synchronized(lock) {
            if (registration != null) return
            registration = db.collection("users").document(userId)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.e(TAG, "signal listener error for userId=$userId", error)
                        return@addSnapshotListener
                    }
                    if (snapshot == null) return@addSnapshotListener
                    if (snapshot.metadata.hasPendingWrites()) {
                        // Our own bump's local echo; the server-confirmed
                        // delivery follows.
                        FirestoreUsage.recordRead(
                            "SignalListener",
                            FirestoreUsage.ReadType.LISTENER_LOCAL_ECHO,
                            1,
                        )
                        return@addSnapshotListener
                    }
                    val fromCache = snapshot.metadata.isFromCache
                    FirestoreUsage.recordRead(
                        "SignalListener",
                        if (fromCache) FirestoreUsage.ReadType.LISTENER_UPDATE_CACHED
                        else FirestoreUsage.ReadType.LISTENER_UPDATE_FRESH,
                        1,
                    )
                    dispatch(snapshot)
                }
        }
    }

    private fun dispatch(snapshot: com.google.firebase.firestore.DocumentSnapshot) {
        // Capture firing callbacks under the lock so we don't hold it while
        // user code runs (which may itself try to subscribe/unsubscribe).
        val toFire = mutableListOf<() -> Unit>()
        synchronized(lock) {
            for (channel in UserDocSignal.Channel.values()) {
                val newSignal = snapshot.getTimestamp(channel.fieldName)
                if (newSignal == lastObserved[channel]) continue
                lastObserved[channel] = newSignal
                subscribers[channel]?.let { toFire.addAll(it) }
            }
        }
        for (cb in toFire) {
            try {
                cb()
            } catch (e: Throwable) {
                Log.e(TAG, "subscriber callback threw", e)
            }
        }
    }

    /** Register a callback for [channel]. Returns an unsubscribe function.
     *  The callback fires on every subsequent change to the channel's field;
     *  it does NOT fire retroactively for the most recent value, so callers
     *  that need initial-state behavior should perform their own initial
     *  read/pull on subscribe. */
    fun subscribe(channel: UserDocSignal.Channel, onChange: () -> Unit): () -> Unit {
        synchronized(lock) {
            subscribers.getOrPut(channel) { mutableListOf() }.add(onChange)
        }
        return {
            synchronized(lock) {
                subscribers[channel]?.remove(onChange)
            }
        }
    }

    /** Detach the listener and drop all subscribers. Call on sign-out. */
    fun clear() {
        synchronized(lock) {
            registration?.remove()
            registration = null
            subscribers.clear()
            lastObserved.clear()
        }
    }
}
