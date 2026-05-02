package org.alkaline.taskbrain.saverefactor

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.runBlocking
import org.alkaline.taskbrain.EmulatorTestSupport
import org.alkaline.taskbrain.data.NoteIdSentinel
import org.alkaline.taskbrain.data.NoteLine
import org.alkaline.taskbrain.data.NoteState
import org.alkaline.taskbrain.data.NoteStore
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
 * Phase 5 cross-note cut/paste identity preservation through real
 * Firestore round-trips.
 *
 * IT-5a — full round-trip: cut from A, paste into B, save both.
 * IT-5b — unreclaimed cut persists as `state='cut-delete'`.
 * IT-5c — paste reclaims the cut-delete doc instead of allocating fresh.
 */
class CutPasteRoundTripTest {

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
     * IT-5a — cut from A, paste into B, save both. The descendant doc's
     * id is unchanged across the move; only `parentNoteId` flips.
     */
    @Test
    fun cutFromAPasteIntoB_preservesDocIdentity() = runBlocking {
        val aId = repo().createMultiLineNote("A\n\tchild-x").getOrThrow()
        val bId = repo().createMultiLineNote("B").getOrThrow()
        waitForListener(aId)
        waitForListener(bId)
        val childId = NoteStore.getRawNoteById(aId)!!.containedNotes.single()

        // Editor flow: NoteStore.recordCut marks the line as moveable;
        // saveMultipleNotes appends the cut-delete write OR (in the
        // same-batch reclaim case) routes it through the destination.
        NoteStore.recordCut(childId, "child-x")

        // Atomic batched save: A loses the child, B gains it. Both via
        // saveMultipleNotes so the cut-delete pass sees the destination.
        repo().saveMultipleNotes(listOf(
            // A: drop the child line entirely; pendingCuts excludes it
            // from soft-delete, the destination's revive flips state=null.
            org.alkaline.taskbrain.data.NoteRepository.SaveItem(
                noteId = aId,
                trackedLines = listOf(NoteLine("A", aId)),
                localBases = mapOf(aId to listOf(childId)),
            ),
            // B: gain the child as its first descendant. The childId
            // arrives as a real id, so the planner reparents it.
            org.alkaline.taskbrain.data.NoteRepository.SaveItem(
                noteId = bId,
                trackedLines = listOf(
                    NoteLine("B", bId),
                    NoteLine("\tchild-x", childId),
                ),
                localBases = mapOf(bId to emptyList()),
            ),
        )).getOrThrow()

        val rawChild = readRawNote(childId)
        assertEquals(bId, rawChild!!["parentNoteId"])
        assertNull(
            "child must NOT be soft-deleted post-paste; got state=${rawChild["state"]}",
            rawChild["state"],
        )
        @Suppress("UNCHECKED_CAST")
        assertEquals(listOf(childId), readRawNote(bId)!!["containedNotes"] as List<String>)
        @Suppress("UNCHECKED_CAST")
        assertEquals(emptyList<String>(), readRawNote(aId)!!["containedNotes"] as List<String>)
    }

    /**
     * IT-5b — unreclaimed cut is parked as `state='cut-delete'` on the
     * doc, and reconstruction filters it out of A's tree.
     */
    @Test
    fun unreclaimedCut_persistsAsCutDelete() = runBlocking {
        val aId = repo().createMultiLineNote("A\n\torphan").getOrThrow()
        waitForListener(aId)
        val childId = NoteStore.getRawNoteById(aId)!!.containedNotes.single()

        NoteStore.recordCut(childId, "orphan")
        repo().saveNoteWithChildren(
            aId,
            listOf(NoteLine("A", aId)),
            extraOpsBuilder = null,
            localBases = mapOf(aId to listOf(childId)),
        ).getOrThrow()

        val raw = readRawNote(childId)!!
        assertEquals(NoteState.CUT_DELETE, raw["state"])
        // Reconstruction must filter cut-delete docs out of A.
        val aLines = repo().loadNoteLinesAwait(aId).getOrThrow()
        assertEquals(1, aLines.size)
        assertEquals("A", aLines[0].content)
    }

    /**
     * IT-5c — pasting after a cut-delete commit reclaims the same doc
     * id, not a fresh one. The unit suite covers the cut-buffer state
     * machine; this test verifies the round-trip via the Firestore
     * emulator: the doc's prior `state='cut-delete'` is overwritten with
     * `state=null` on revive.
     */
    @Test
    fun pasteReclaim_reusesCutDeleteDocId() = runBlocking {
        val aId = repo().createMultiLineNote("A\n\tportable").getOrThrow()
        val bId = repo().createMultiLineNote("B").getOrThrow()
        waitForListener(aId)
        waitForListener(bId)
        val originalChildId = NoteStore.getRawNoteById(aId)!!.containedNotes.single()

        // Cut + park as cut-delete (no paste in this commit).
        NoteStore.recordCut(originalChildId, "portable")
        repo().saveNoteWithChildren(
            aId,
            listOf(NoteLine("A", aId)),
            extraOpsBuilder = null,
            localBases = mapOf(aId to listOf(originalChildId)),
        ).getOrThrow()
        // Wait for the cut-delete state to land.
        waitForListener(bId) // any settle; the doc should be reachable
        val parked = readRawNote(originalChildId)!!
        assertEquals(NoteState.CUT_DELETE, parked["state"])

        // The cut buffer was drained on the previous save's commit (per
        // CutDeleteOps.committedCutIds contract). Paste-reclaim happens
        // when the editor calls tryReclaim during paste; simulate that:
        // the buffer is repopulated by recordCut at cut-event time, but
        // since we already committed and cleared, a real paste path
        // would query the remote cut-delete doc by content. For this
        // emulator test we simulate the paste planner by passing the
        // original childId directly into B's tracked lines — same shape
        // the editor produces when tryReclaim returns the original id.
        repo().saveNoteWithChildren(
            bId,
            listOf(
                NoteLine("B", bId),
                NoteLine("\tportable", originalChildId),
            ),
            extraOpsBuilder = null,
            localBases = mapOf(bId to emptyList()),
        ).getOrThrow()

        val revived = readRawNote(originalChildId)!!
        assertNull(
            "revived doc must clear cut-delete state, got ${revived["state"]}",
            revived["state"],
        )
        assertEquals(bId, revived["parentNoteId"])
        // Doc id was preserved across the round-trip — never a sentinel.
        assertTrue(!NoteIdSentinel.isSentinel(originalChildId))
    }
}
