package org.alkaline.taskbrain.data

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
 * in androidTest). Pins the data-layer contract — every delta pull,
 * including ones returning our own writes, drives the `_notes.value`
 * rebuild AND emits `changedNoteIds`. Editor reload protection lives in
 * `CurrentNoteViewModel`, not here.
 *
 * The apply path is `private`; the test calls it through reflection to
 * avoid leaking test seams into production code.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoteStoreOwnEchoIncrementalTest {

    @Test
    fun `delta apply of a top-level create surfaces in notes value and changedNoteIds`() = runTest(
        // Unconfined so the SharedFlow collector subscribes synchronously
        // at launchIn and tryEmit delivers inline.
        UnconfinedTestDispatcher(),
    ) {
        val noteId = "test-note-${UUID.randomUUID()}"
        val note = Note(
            id = noteId,
            userId = "u1",
            content = "marker-content",
            containedNotes = emptyList(),
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

        NoteStore.applyDeltaForTest(listOf(note))
        runCurrent()

        assertTrue(
            "expected NoteStore.notes to contain $noteId after applyDelta " +
                "ran. New ids in _notes.value since the test started: " +
                "${NoteStore.notes.value.map { it.id }.toSet() - priorNoteIds}.",
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

}
