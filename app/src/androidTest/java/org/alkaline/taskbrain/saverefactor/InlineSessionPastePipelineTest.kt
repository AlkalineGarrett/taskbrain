package org.alkaline.taskbrain.saverefactor

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.runBlocking
import org.alkaline.taskbrain.EmulatorTestSupport
import org.alkaline.taskbrain.data.NoteRepository
import org.alkaline.taskbrain.data.NoteState
import org.alkaline.taskbrain.data.NoteStore
import org.alkaline.taskbrain.saverefactor.SaveRefactorTestSupport.readRawNote
import org.alkaline.taskbrain.saverefactor.SaveRefactorTestSupport.repo
import org.alkaline.taskbrain.saverefactor.SaveRefactorTestSupport.waitForListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

/**
 * IT-5e — cross-session paste through the editor-side pipeline that
 * IT-5d bypassed (it built `SaveItem`s by hand). Drives the call shape
 * `CurrentNoteViewModel.saveAll` actually runs and verifies the cut doc
 * is reparented, not parked as `state='cut-delete'` while a duplicate is
 * created under the destination.
 */
class InlineSessionPastePipelineTest {

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
    fun saveAllPipeline_reparentsCutLine_doesNotParkItAsCutDelete() = runBlocking {
        val aId = repo().createMultiLineNote("A\n\ta1\n\ta2").getOrThrow()
        val cId = repo().createMultiLineNote("C\n\tc1\n\tc2").getOrThrow()
        waitForListener(aId); waitForListener(cId)
        val a1Id = NoteStore.getRawNoteById(aId)!!.containedNotes[0]
        val a2Id = NoteStore.getRawNoteById(aId)!!.containedNotes[1]
        val c1Id = NoteStore.getRawNoteById(cId)!!.containedNotes[0]
        val c2Id = NoteStore.getRawNoteById(cId)!!.containedNotes[1]

        // Editor flow: user cut a2 from A's session, then pasted at top of
        // C's session. recordCut populates the cross-session reclaim buffer;
        // C's editor LineState now carries a2Id at index 1.
        NoteStore.recordCut(a2Id, "a2")

        // Drive the same pipeline saveAll runs: prepareInlineEditTrackedLines
        // with the editor's text + per-line noteIds for each dirty session.
        val aTracked = repo().prepareInlineEditTrackedLines(
            aId, "A\n\ta1", "test",
            listOf(aId, a1Id),
        )
        val cTracked = repo().prepareInlineEditTrackedLines(
            cId, "C\n\ta2\n\tc1\n\tc2", "test",
            listOf(cId, a2Id, c1Id, c2Id),
        )

        repo().saveMultipleNotes(listOf(
            NoteRepository.SaveItem(
                noteId = aId,
                trackedLines = aTracked,
                localBases = mapOf(aId to listOf(a1Id, a2Id)),
            ),
            NoteRepository.SaveItem(
                noteId = cId,
                trackedLines = cTracked,
                localBases = mapOf(cId to listOf(c1Id, c2Id)),
            ),
        )).getOrThrow()

        val rawA2 = readRawNote(a2Id)!!
        assertEquals(
            "a2 must be reparented to C — pre-fix it was parked as cut-delete " +
                "and a fresh doc was created in C's containedNotes",
            cId, rawA2["parentNoteId"],
        )
        assertNull(
            "a2 must be live post-paste; got state=${rawA2["state"]}",
            rawA2["state"],
        )

        @Suppress("UNCHECKED_CAST")
        val cContained = readRawNote(cId)!!["containedNotes"] as List<String>
        assertEquals(
            "C's containedNotes must include the original a2Id, not a freshly-allocated id",
            listOf(a2Id, c1Id, c2Id), cContained,
        )

        // No fresh doc with content "a2" was created — that was the silent
        // pre-fix data-loss path. Walk every live doc rooted at C.
        val imposters = NoteStore.getDescendantIds(cId).filter { id ->
            val note = NoteStore.getRawNoteById(id)
            note != null && note.content == "a2" && id != a2Id && note.state != NoteState.CUT_DELETE
        }
        assertTrue("found duplicate 'a2' docs alongside the reparented original: $imposters", imposters.isEmpty())
        assertNotNull("a2 must remain in NoteStore as a live descendant", NoteStore.getRawNoteById(a2Id))
    }
}
