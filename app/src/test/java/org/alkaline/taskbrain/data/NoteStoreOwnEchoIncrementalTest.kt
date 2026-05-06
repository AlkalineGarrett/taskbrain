package org.alkaline.taskbrain.data

import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.QueryDocumentSnapshot
import com.google.firebase.firestore.SnapshotMetadata
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * JVM-only mirror of `ViewDirectiveLocalCreatePropagationTest` (UI flavour
 * in androidTest). Pins the data-layer contract — every incremental
 * snapshot, including ones produced by our own writes, drives the
 * `_notes.value` rebuild AND emits `changedNoteIds`. Editor reload
 * protection lives in `CurrentNoteViewModel`, not here.
 *
 * The snapshot handler is `private`; the test calls it through reflection
 * to avoid leaking test seams into production code.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoteStoreOwnEchoIncrementalTest {

    @Test
    fun `incremental snapshot of a top-level create surfaces in notes value and changedNoteIds`() = runTest(
        // Unconfined so the SharedFlow collector subscribes synchronously
        // at launchIn and tryEmit delivers inline.
        UnconfinedTestDispatcher(),
    ) {
        val noteId = "test-note-${UUID.randomUUID()}"
        val change = mockAddedChange(
            noteId = noteId,
            data = mapOf(
                "userId" to "u1",
                "content" to "marker-content",
                "containedNotes" to emptyList<String>(),
            ),
        )

        // Subscribe BEFORE emission — `_changedNoteIds` is replay=0, so
        // any emission that happens before launchIn is lost forever.
        val collectedChangedIds = mutableListOf<Set<String>>()
        val collectorJob = NoteStore.changedNoteIds
            .onEach { collectedChangedIds.add(it) }
            .launchIn(this)
        runCurrent()

        // Snapshot the singleton's prior contents so the failure message
        // can show just OUR delta — other tests in the suite may have
        // left arbitrary ids in `_notes.value`.
        val priorNoteIds = NoteStore.notes.value.map { it.id }.toSet()

        invokeHandleIncrementalSnapshot(listOf(change))
        runCurrent()

        assertTrue(
            "expected NoteStore.notes to contain $noteId after the listener " +
                "delivered the snapshot. New ids in _notes.value since the test " +
                "started: ${NoteStore.notes.value.map { it.id }.toSet() - priorNoteIds}.",
            NoteStore.notes.value.any { it.id == noteId },
        )

        assertTrue(
            "expected a changedNoteIds emission containing $noteId — directive " +
                "caches in OTHER notes referencing this one rely on it. " +
                "Got emissions: $collectedChangedIds",
            collectedChangedIds.any { noteId in it },
        )

        collectorJob.cancel()
    }

    private fun mockAddedChange(noteId: String, data: Map<String, Any>): DocumentChange {
        val metadata = mockk<SnapshotMetadata>()
        every { metadata.hasPendingWrites() } returns false
        val doc = mockk<QueryDocumentSnapshot>()
        every { doc.id } returns noteId
        every { doc.metadata } returns metadata
        every { doc.data } returns data
        val change = mockk<DocumentChange>()
        every { change.document } returns doc
        every { change.type } returns DocumentChange.Type.ADDED
        return change
    }

    private fun invokeHandleIncrementalSnapshot(changes: List<DocumentChange>) {
        val method = NoteStore::class.java.getDeclaredMethod(
            "handleIncrementalSnapshot",
            List::class.java,
        )
        method.isAccessible = true
        method.invoke(NoteStore, changes)
    }
}
