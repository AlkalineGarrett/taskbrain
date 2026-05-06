package org.alkaline.taskbrain.saverefactor

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.alkaline.taskbrain.EmulatorTestSupport
import org.alkaline.taskbrain.data.NoteRepository
import org.alkaline.taskbrain.data.NoteStore
import org.alkaline.taskbrain.saverefactor.SaveRefactorTestSupport.firestore
import org.alkaline.taskbrain.saverefactor.SaveRefactorTestSupport.readRawNote
import org.alkaline.taskbrain.saverefactor.SaveRefactorTestSupport.repo
import org.alkaline.taskbrain.saverefactor.SaveRefactorTestSupport.waitFor
import org.alkaline.taskbrain.saverefactor.SaveRefactorTestSupport.waitForListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

/**
 * IT-CH — heal Firestore-corrupt `containedNotes` via the Fix-button save.
 *
 * Real-world surfaced bug: a note's `containedNotes` list contained an
 * orphan ref (a doc id whose actual `parentNoteId` pointed elsewhere)
 * — leftover from pre-fix cross-paste corruption. Reconstruction
 * filtered the orphan out and flagged the note as needing fix; clicking
 * Fix should write a healed `containedNotes` back to Firestore.
 *
 * The existing tests verify successful save flows produce correct
 * output, but none start from a deliberately-corrupt remote state.
 * This test models that scenario directly: poison the parent's
 * `containedNotes` via a raw Firestore write, wait for the listener,
 * then drive the editor's save pipeline (the same call shape `saveAll`
 * uses when `needsFix=true`) and assert the orphan is dropped — both
 * in the resulting trackedLines and in the Firestore wire state.
 */
class CorruptContainedNotesHealTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun classSetUp() = EmulatorTestSupport.requireEmulatorAndSignIn()
    }

    @Before
    fun resetStore() {
        NoteStore.clear()
        NoteStore.clearPendingCutsForTest()
        NoteStore.start(
            FirebaseFirestore.getInstance(),
            FirebaseAuth.getInstance().currentUser!!.uid,
        )
    }

    /**
     * Poison the root's `containedNotes` with an id that exists in
     * Firestore but whose `parentNoteId` points elsewhere (the exact
     * shape of the user's broken note). Then drive the save pipeline
     * via `prepareInlineEditTrackedLines` + `saveNoteWithChildren`
     * (mirroring `useSaveCoordinator.saveAll` when `needsFix=true`)
     * and verify the orphan is healed at the wire level.
     */
    @Test
    fun loadingCorruptNote_thenSaving_healsContainedNotes() = runBlocking {
        val uid = FirebaseAuth.getInstance().currentUser!!.uid
        val rootId = repo().createMultiLineNote("R\n\tc1\n\tc2").getOrThrow()
        val foreignId = repo().createMultiLineNote("FOREIGN").getOrThrow()
        waitForListener(rootId); waitForListener(foreignId)
        val c1 = NoteStore.getRawNoteById(rootId)!!.containedNotes[0]
        val c2 = NoteStore.getRawNoteById(rootId)!!.containedNotes[1]

        // Poison: insert foreignId into rootId.containedNotes via a raw
        // Firestore write — simulating a pre-fix corruption where some
        // earlier broken save wrote an orphan ref. foreignId itself stays
        // parented to its own root, never touched.
        firestore().collection("notes").document(rootId).update(
            mapOf(
                "containedNotes" to listOf(c1, c2, foreignId),
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
        ).await()
        waitFor(timeoutMs = 5_000) {
            NoteStore.getRawNoteById(rootId)?.containedNotes == listOf(c1, c2, foreignId)
        }

        // Reconstruction filters the orphan out (it's mis-parented). The
        // editor's view of root has the clean child set [c1, c2]. The
        // Fix-button flow drives saveAll with these tracked lines.
        val tracked = repo().prepareInlineEditTrackedLines(
            rootId, "R\n\tc1\n\tc2", "test",
            editorNoteIds = listOf(rootId, c1, c2),
        )
        // The trackedLines must NOT include the foreign orphan id —
        // matchLinesToIds shouldn't try to "rescue" it via Phase 0
        // (foreignId isn't in editorNoteIds) or any other phase.
        assertFalse(
            "trackedLines must not include the foreign orphan ($foreignId): got ${tracked.map { it.noteId }}",
            tracked.any { it.noteId == foreignId },
        )

        // localBases must reflect what the editor actually captured at
        // session start — which on today's path is `snapshotLocalBases`,
        // i.e., the corrupt remote ([c1, c2, foreignId]). The save's
        // 3-way merge needs to drop the orphan despite base == remote
        // (both contain the orphan, only `local` excludes it).
        val localBases = NoteStore.snapshotLocalBases(rootId)
        assertEquals(
            "precondition: localBases captured the corrupt remote",
            listOf(c1, c2, foreignId),
            localBases[rootId],
        )

        repo().saveNoteWithChildren(
            rootId, tracked, extraOpsBuilder = null, localBases = localBases,
        ).getOrThrow()

        @Suppress("UNCHECKED_CAST")
        val finalContained = readRawNote(rootId)!!["containedNotes"] as List<String>
        assertEquals(
            "save must drop the orphan ref from rootId.containedNotes",
            listOf(c1, c2),
            finalContained,
        )

        // foreignId must be untouched — it's a top-level note with its
        // own root. Healing root must not modify foreign trees.
        val rawForeign = readRawNote(foreignId)!!
        assertEquals("foreign note untouched (top-level)", null, rawForeign["parentNoteId"])
        assertEquals(1L, rawForeign["version"]) // never written after creation
    }
}
