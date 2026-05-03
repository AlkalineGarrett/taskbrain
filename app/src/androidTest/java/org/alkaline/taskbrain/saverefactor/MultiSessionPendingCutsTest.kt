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
 * IT-5f — pending-cuts buffer lifecycle across two inline sessions.
 *
 * `CutPasteRoundTripTest` and `ViewDirectiveCutPasteTest` either drain
 * the cut buffer per-test or hand-build the post-paste tracked lines.
 * Neither exercises the real lifecycle that the editor produces:
 *
 *   recordCut → buffer holds id → tryReclaim consumes it → editorState
 *   carries the reclaimed id → prepareInlineEditTrackedLines preserves
 *   it via Phase 0 → saveMultipleNotes reparents.
 *
 * Anything that breaks `tryReclaim` ordering, drains the buffer too
 * early, or fails to thread the reclaimed id through the editor would
 * slip past every other test in the suite.
 */
class MultiSessionPendingCutsTest {

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
     * Two inline sessions. User cuts a line in session A, pastes in
     * session B (driven through `tryReclaim`), then saves both. Verifies
     * the buffer transitions correctly through every step.
     */
    @Test
    fun cutInSessionA_reclaimInSessionB_throughEditorPipeline_reparents() = runBlocking {
        val aId = repo().createMultiLineNote("A\n\ta1\n\ta2").getOrThrow()
        val bId = repo().createMultiLineNote("B\n\tb1").getOrThrow()
        waitForListener(aId); waitForListener(bId)
        val a1Id = NoteStore.getRawNoteById(aId)!!.containedNotes[0]
        val a2Id = NoteStore.getRawNoteById(aId)!!.containedNotes[1]
        val b1Id = NoteStore.getRawNoteById(bId)!!.containedNotes[0]

        // Step 1 — cut a2 from session A. Buffer now holds a2.
        NoteStore.recordCut(a2Id, "a2")
        assertEquals(
            "buffer must hold the cut id with its content",
            mapOf(a2Id to "a2"),
            NoteStore.getPendingCuts(),
        )

        // Step 2 — paste in session B. The editor calls tryReclaim per
        // pasted line; on hit it stamps the reclaimed id on the LineState.
        // We simulate just that one step here.
        val reclaimed = NoteStore.tryReclaim("a2")
        assertEquals("tryReclaim returns a2's id, not null or a fresh sentinel", a2Id, reclaimed)
        assertTrue(
            "buffer is one-shot — after reclaim the entry is gone",
            NoteStore.getPendingCuts().isEmpty(),
        )

        // Step 3 — drive both sessions through prepareInlineEditTrackedLines
        // with the editor's per-line noteIds. Session B's pasted line
        // carries a2Id (reclaimed above); Phase 0 preserves it.
        val aTracked = repo().prepareInlineEditTrackedLines(
            aId, "A\n\ta1", "test", listOf(aId, a1Id),
        )
        val bTracked = repo().prepareInlineEditTrackedLines(
            bId, "B\n\tb1\n\ta2", "test", listOf(bId, b1Id, a2Id),
        )
        assertEquals(
            "session B's pasted line must carry the reclaimed a2 id, not a sentinel",
            a2Id, bTracked[2].noteId,
        )

        // Step 4 — atomic batch save.
        repo().saveMultipleNotes(listOf(
            NoteRepository.SaveItem(
                noteId = aId,
                trackedLines = aTracked,
                localBases = mapOf(aId to listOf(a1Id, a2Id)),
            ),
            NoteRepository.SaveItem(
                noteId = bId,
                trackedLines = bTracked,
                localBases = mapOf(bId to listOf(b1Id)),
            ),
        )).getOrThrow()

        // Wire-state assertions.
        val rawA2 = readRawNote(a2Id)!!
        assertEquals("a2 reparented to B", bId, rawA2["parentNoteId"])
        assertEquals(bId, rawA2["rootNoteId"])
        assertNull("a2 must NOT be parked as cut-delete", rawA2["state"])

        @Suppress("UNCHECKED_CAST")
        assertEquals(listOf(a1Id), readRawNote(aId)!!["containedNotes"] as List<String>)
        @Suppress("UNCHECKED_CAST")
        assertEquals(listOf(b1Id, a2Id), readRawNote(bId)!!["containedNotes"] as List<String>)
        assertNotNull("a2 must remain live in NoteStore", NoteStore.getRawNoteById(a2Id))
    }

    /**
     * Cut without paste — the unreclaimed cut must be parked as
     * cut-delete on save, and the buffer drained. Mirrors the real
     * flow where the user cuts but never pastes (or pastes elsewhere
     * and then saves only the source).
     */
    @Test
    fun cutWithoutReclaim_parksAsCutDelete_andDrainsBuffer() = runBlocking {
        val aId = repo().createMultiLineNote("A\n\torphan").getOrThrow()
        waitForListener(aId)
        val orphanId = NoteStore.getRawNoteById(aId)!!.containedNotes.single()

        NoteStore.recordCut(orphanId, "orphan")

        // Save A alone — no destination claims the cut.
        val tracked = repo().prepareInlineEditTrackedLines(
            aId, "A", "test", listOf(aId),
        )
        repo().saveNoteWithChildren(
            aId, tracked, extraOpsBuilder = null,
            localBases = mapOf(aId to listOf(orphanId)),
        ).getOrThrow()

        assertEquals(NoteState.CUT_DELETE, readRawNote(orphanId)!!["state"])
        assertTrue("buffer drained on commit", NoteStore.getPendingCuts().isEmpty())
    }

    /**
     * IT-5g — the REAL editor cut+paste flow consumes pendingCuts at
     * paste time via `tryReclaim`. Pre-fix, by save time the buffer was
     * empty, so the source's planSave's toDelete formula
     * `existingDescendantIds - survivingIds - pendingCuts.keys` had no
     * way to know the moved line was being kept by another session in
     * the same batch — it added it to toDelete, racing the destination's
     * reparent write in the same Firestore batch and producing
     * `state=DELETED` + `parentNoteId=destination` (orphan ref from
     * destination, doc reads as deleted).
     *
     * The fix: `saveMultipleNotes` pre-computes `globalSurvivingIds` (the
     * union of every session's trackedLines real-id noteIds) and
     * planSave excludes those from toDelete. This test exercises the
     * exact production flow: recordCut → tryReclaim → save batch.
     */
    @Test
    fun crossSessionMove_viaTryReclaim_doesNotSoftDeleteAtSourceSide() = runBlocking {
        val aId = repo().createMultiLineNote("A\n\tmovable").getOrThrow()
        val bId = repo().createMultiLineNote("B\n\tb1").getOrThrow()
        waitForListener(aId); waitForListener(bId)
        val movable = NoteStore.getRawNoteById(aId)!!.containedNotes.single()
        val b1 = NoteStore.getRawNoteById(bId)!!.containedNotes.single()

        // Editor cut flow: recordCut populates the buffer.
        NoteStore.recordCut(movable, "movable")
        // Editor paste flow CONSUMES the buffer via tryReclaim — the
        // pre-fix bug surfaced because this happened BEFORE save, leaving
        // pendingCuts empty when the source's planSave ran.
        val reclaimed = NoteStore.tryReclaim("movable")
        assertEquals(movable, reclaimed)
        assertTrue(
            "tryReclaim must drain the buffer (one-shot semantics)",
            NoteStore.getPendingCuts().isEmpty(),
        )

        // Both sessions save in one batch. Source (A) drops `movable`
        // from its tracked lines; destination (B) carries it with the
        // reclaimed real id.
        val aTracked = repo().prepareInlineEditTrackedLines(
            aId, "A", "test", listOf(aId),
        )
        val bTracked = repo().prepareInlineEditTrackedLines(
            bId, "B\n\tb1\n\tmovable", "test", listOf(bId, b1, movable),
        )
        // Order: destination (B) FIRST, then source (A). The pre-fix bug
        // manifests when source's `toDelete` op runs AFTER destination's
        // reparent op in the batch — last writer wins on the doc's
        // `state` field. Order matches the user's reported scenario.
        repo().saveMultipleNotes(listOf(
            NoteRepository.SaveItem(bId, bTracked, mapOf(bId to listOf(b1))),
            NoteRepository.SaveItem(aId, aTracked, mapOf(aId to listOf(movable))),
        )).getOrThrow()

        val rawMovable = readRawNote(movable)!!
        assertEquals("movable reparented to B", bId, rawMovable["parentNoteId"])
        assertEquals(bId, rawMovable["rootNoteId"])
        assertNull(
            "movable must be live — pre-fix this was 'deleted' because " +
                "A's planSave added it to toDelete (pendingCuts was empty " +
                "after tryReclaim) and that write raced B's reparent in " +
                "the same batch",
            rawMovable["state"],
        )

        @Suppress("UNCHECKED_CAST")
        assertEquals(emptyList<String>(), readRawNote(aId)!!["containedNotes"] as List<String>)
        @Suppress("UNCHECKED_CAST")
        assertEquals(listOf(b1, movable), readRawNote(bId)!!["containedNotes"] as List<String>)
    }

    /**
     * Two cuts with identical content: tryReclaim is one-shot, so the
     * second paste must miss (return null) instead of double-claiming
     * the first id. Without this, two pasted copies would share a doc.
     */
    @Test
    fun tryReclaim_isOneShot_perId_notPerContent() = runBlocking {
        val aId = repo().createMultiLineNote("A\n\tdup\n\tdup").getOrThrow()
        waitForListener(aId)
        val dup1 = NoteStore.getRawNoteById(aId)!!.containedNotes[0]
        val dup2 = NoteStore.getRawNoteById(aId)!!.containedNotes[1]

        NoteStore.recordCut(dup1, "dup")
        NoteStore.recordCut(dup2, "dup")

        val first = NoteStore.tryReclaim("dup")
        val second = NoteStore.tryReclaim("dup")
        val third = NoteStore.tryReclaim("dup")

        assertTrue(
            "first reclaim returns one of the cut ids",
            first == dup1 || first == dup2,
        )
        assertTrue(
            "second reclaim returns the OTHER cut id, never the same",
            second == dup1 || second == dup2,
        )
        assertNotNull(second)
        assertTrue("first and second must differ", first != second)
        assertNull("third reclaim has nothing left to claim", third)
        assertTrue("buffer fully drained", NoteStore.getPendingCuts().isEmpty())
    }
}
