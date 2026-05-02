package org.alkaline.taskbrain.saverefactor

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.alkaline.taskbrain.data.NoteRepository
import org.alkaline.taskbrain.data.NoteStore
import java.util.UUID

/**
 * Shared helpers for the save-refactor emulator test suite. Built on top
 * of `EmulatorTestSupport`; adds direct-write helpers (to simulate a
 * second client without spinning up a second `FirebaseApp`), raw-doc
 * reads, and a generic `waitFor` predicate poller.
 */
internal object SaveRefactorTestSupport {

    fun firestore(): FirebaseFirestore = FirebaseFirestore.getInstance()
    fun repo(): NoteRepository =
        NoteRepository(FirebaseFirestore.getInstance(), FirebaseAuth.getInstance())

    /**
     * Read a note doc directly from Firestore, bypassing NoteStore. Used
     * for "what's actually on the wire" assertions where local mirrors
     * may not yet reflect the listener echo.
     */
    suspend fun readRawNote(noteId: String): Map<String, Any?>? {
        val snap = firestore().collection("notes").document(noteId).get().await()
        return if (snap.exists()) snap.data else null
    }

    /**
     * Poll until [predicate] returns true or [timeoutMs] elapses. Used by
     * the data-layer tests where the Compose `waitUntil` rule isn't
     * available.
     */
    suspend fun waitFor(
        timeoutMs: Long = 5_000,
        intervalMs: Long = 50,
        predicate: suspend () -> Boolean,
    ) {
        withTimeout(timeoutMs) {
            while (!predicate()) delay(intervalMs)
        }
    }

    /** Wait until NoteStore's in-memory mirror has [noteId]. */
    suspend fun waitForListener(noteId: String, timeoutMs: Long = 5_000) {
        waitFor(timeoutMs) { NoteStore.getRawNoteById(noteId) != null }
    }

    /**
     * Write a note doc directly via [FirebaseFirestore], bypassing
     * `NoteRepository.planSave`. Stamps a fresh `lastWriterOpId` that
     * the primary client cannot match against its own pending-op
     * registry, so the write is delivered as a genuine "external"
     * change. Use this to simulate concurrent clients in merge tests.
     */
    suspend fun writeAsOtherClient(
        noteId: String,
        userId: String,
        content: String,
        parentNoteId: String? = null,
        rootNoteId: String? = null,
        containedNotes: List<String> = emptyList(),
        state: String? = null,
    ) {
        val data = HashMap<String, Any?>().apply {
            put("userId", userId)
            put("content", content)
            put("parentNoteId", parentNoteId)
            put("rootNoteId", rootNoteId)
            put("containedNotes", containedNotes)
            if (state != null) put("state", state)
            put("version", 1)
            put("lastWriterOpId", "external_${UUID.randomUUID()}")
            put("createdAt", FieldValue.serverTimestamp())
            put("updatedAt", FieldValue.serverTimestamp())
        }
        firestore().collection("notes").document(noteId).set(data).await()
    }

    /**
     * Append [childId] to [parentId]'s `containedNotes` directly via
     * Firestore.update. Pairs with [writeAsOtherClient] when the test
     * needs to simulate "second client added a child to an existing
     * parent".
     */
    suspend fun otherClientAppendChild(parentId: String, childId: String) {
        firestore().collection("notes").document(parentId).update(
            mapOf(
                "containedNotes" to FieldValue.arrayUnion(childId),
                "version" to FieldValue.increment(1),
                "lastWriterOpId" to "external_${UUID.randomUUID()}",
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
        ).await()
    }

    /**
     * Detach the live listener and clear NoteStore so the next
     * `loadNoteLinesAwait` exercises the listener-gap path. Tests that
     * want the listener back must call `NoteStore.start(...)` themselves.
     */
    fun forgetListenerSnapshot() {
        NoteStore.clear()
    }

    /**
     * Save batches stamp every doc with the same `lastWriterOpId`. Read
     * all docs in [noteIds] and return the set of distinct opIds. A
     * batched save produces a set of size 1.
     */
    suspend fun distinctLastWriterOpIds(noteIds: List<String>): Set<String> {
        val ids = mutableSetOf<String>()
        for (id in noteIds) {
            val data = readRawNote(id) ?: continue
            (data["lastWriterOpId"] as? String)?.let { ids.add(it) }
        }
        return ids
    }
}
