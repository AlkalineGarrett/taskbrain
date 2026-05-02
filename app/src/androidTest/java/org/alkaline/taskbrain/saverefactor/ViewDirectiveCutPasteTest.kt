package org.alkaline.taskbrain.saverefactor

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.runBlocking
import org.alkaline.taskbrain.EmulatorTestSupport
import org.alkaline.taskbrain.data.NoteLine
import org.alkaline.taskbrain.data.NoteRepository
import org.alkaline.taskbrain.data.NoteStore
import org.alkaline.taskbrain.saverefactor.SaveRefactorTestSupport.distinctLastWriterOpIds
import org.alkaline.taskbrain.saverefactor.SaveRefactorTestSupport.readRawNote
import org.alkaline.taskbrain.saverefactor.SaveRefactorTestSupport.repo
import org.alkaline.taskbrain.saverefactor.SaveRefactorTestSupport.waitForListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

/**
 * IT-5d — view directive that returns multiple notes; cut the last line
 * of one embedded note and paste at the top of another, then saveAll.
 *
 * Exercises the intersection of:
 *  - Phase 5 cross-note cut/paste identity (cut-delete reclaim).
 *  - Phase 2 atomic multi-note save (saveMultipleNotes packs the source,
 *    destination, and host all in one batch).
 *  - Async-only fix's `ensureSessionsForNotes` populating sessions for
 *    each viewed note (we simulate the post-population state directly
 *    rather than driving the Composable).
 *
 * The test bypasses the InlineEditState / Composable layer and drives
 * `saveMultipleNotes` directly with the SaveItems the editor would
 * produce after the user's cut+paste+saveAll.
 */
class ViewDirectiveCutPasteTest {

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

    @Test
    fun cutFromBottomOfOneEmbeddedNote_pasteAtTopOfAnother_saveAll() = runBlocking {
        // Three viewed notes A, B, C. Each has two child lines.
        val aId = repo().createMultiLineNote("A\n\ta1\n\ta2").getOrThrow()
        val bId = repo().createMultiLineNote("B\n\tb1\n\tb2").getOrThrow()
        val cId = repo().createMultiLineNote("C\n\tc1\n\tc2").getOrThrow()
        waitForListener(aId); waitForListener(bId); waitForListener(cId)

        val aLines = repo().loadNoteLinesAwait(aId).getOrThrow()
        val cLines = repo().loadNoteLinesAwait(cId).getOrThrow()
        // a2 is the bottom of A; c1 is the top child of C.
        val a2Id = aLines[2].noteId
        val c1Id = cLines[1].noteId
        val c2Id = cLines[2].noteId
        val a1Id = aLines[1].noteId

        // User cuts a2 from A's session. The editor records the cut and
        // a2 vanishes from A's tracked lines.
        NoteStore.recordCut(a2Id, "a2")

        // User pastes a2 at the top of C's session, above c1. The cut
        // buffer's tryReclaim returns a2Id, so C's tracked lines get a2
        // as the first child (its real id, NOT a sentinel).
        val cLinesAfterPaste = listOf(
            NoteLine("C", cId),
            NoteLine("\ta2", a2Id),
            NoteLine("\tc1", c1Id),
            NoteLine("\tc2", c2Id),
        )
        val aLinesAfterCut = listOf(
            NoteLine("A", aId),
            NoteLine("\ta1", a1Id),
        )

        // saveAll: source A + destination C in one batch. B is unedited
        // and stays out of the batch (matches CurrentNoteViewModel.saveAll
        // which only includes dirty sessions).
        repo().saveMultipleNotes(listOf(
            NoteRepository.SaveItem(
                noteId = aId,
                trackedLines = aLinesAfterCut,
                localBases = mapOf(aId to listOf(a1Id, a2Id)),
            ),
            NoteRepository.SaveItem(
                noteId = cId,
                trackedLines = cLinesAfterPaste,
                localBases = mapOf(cId to listOf(c1Id, c2Id)),
            ),
        )).getOrThrow()

        // — Wire-state assertions —
        // a2 is reparented under C with state=null (revive).
        val rawA2 = readRawNote(a2Id)!!
        assertEquals(cId, rawA2["parentNoteId"])
        assertEquals(cId, rawA2["rootNoteId"])
        assertNull(
            "a2 must NOT be soft-deleted post-paste (got state=${rawA2["state"]})",
            rawA2["state"],
        )

        // A's containedNotes loses a2; C's gains it at the top.
        @Suppress("UNCHECKED_CAST")
        val aContained = readRawNote(aId)!!["containedNotes"] as List<String>
        assertEquals(listOf(a1Id), aContained)
        @Suppress("UNCHECKED_CAST")
        val cContained = readRawNote(cId)!!["containedNotes"] as List<String>
        assertEquals(listOf(a2Id, c1Id, c2Id), cContained)

        // Atomicity: A, C, and a2 share one lastWriterOpId (proves the
        // three writes landed in a single saveMultipleNotes batch — not
        // two independent saveNoteWithChildren calls).
        val opIds = distinctLastWriterOpIds(listOf(aId, cId, a2Id))
        assertEquals("expected one shared opId across the batch, got $opIds", 1, opIds.size)

        // Reload via the same path the editor uses. Reconstruction returns
        // line content without indent tabs (the editor adds them at render
        // time based on tree depth) — assert on the stripped form.
        val aReloaded = repo().loadNoteLinesAwait(aId).getOrThrow()
        assertEquals(listOf("A", "a1"), aReloaded.map { it.content })
        val cReloaded = repo().loadNoteLinesAwait(cId).getOrThrow()
        assertEquals(listOf("C", "a2", "c1", "c2"), cReloaded.map { it.content })
        assertEquals(a2Id, cReloaded[1].noteId)

        // B is unedited — confirm we didn't touch it.
        val bReloaded = repo().loadNoteLinesAwait(bId).getOrThrow()
        assertEquals(listOf("B", "b1", "b2"), bReloaded.map { it.content })

        // pendingCuts is drained on commit (CutDeleteOps.committedCutIds).
        assertTrue(
            "pending cuts must be drained after commit, got ${NoteStore.getPendingCuts()}",
            NoteStore.getPendingCuts().isEmpty(),
        )
    }
}
