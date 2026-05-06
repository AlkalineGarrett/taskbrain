package org.alkaline.taskbrain.saverefactor

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.runBlocking
import org.alkaline.taskbrain.EmulatorTestSupport
import org.alkaline.taskbrain.data.NoteLine
import org.alkaline.taskbrain.data.NoteRepository
import org.alkaline.taskbrain.data.NoteStore
import org.alkaline.taskbrain.saverefactor.SaveRefactorTestSupport.readRawNote
import org.alkaline.taskbrain.saverefactor.SaveRefactorTestSupport.repo
import org.alkaline.taskbrain.saverefactor.SaveRefactorTestSupport.waitForListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

/**
 * Phase 2 atomic batched multi-note save.
 *
 * IT-2a — saveMultipleNotes commits all docs in one batch.
 * IT-2b — content-drop guard aborts the whole batch.
 */
class AtomicBatchSaveTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun classSetUp() = EmulatorTestSupport.requireEmulatorAndSignIn()
    }

    @Before
    fun resetStore() {
        NoteStore.clear()
        NoteStore.start(
            FirebaseFirestore.getInstance(),
            FirebaseAuth.getInstance().currentUser!!.uid,
        )
    }

    /**
     * IT-2a — two notes saved together commit through one batch and all
     * docs reach the wire. Atomicity is a property of Firestore's
     * `WriteBatch.commit()`; observing it from doc contents is no longer
     * possible since we don't stamp a per-batch opId, so the test instead
     * verifies every planned write landed.
     */
    @Test
    fun saveMultipleNotes_commitsAllDocsInOneBatch() = runBlocking {
        val rId1 = repo().createMultiLineNote("r1\n\tchild1").getOrThrow()
        val rId2 = repo().createMultiLineNote("r2\n\tchild2").getOrThrow()
        waitForListener(rId1); waitForListener(rId2)
        val c1 = NoteStore.getRawNoteById(rId1)!!.containedNotes.single()
        val c2 = NoteStore.getRawNoteById(rId2)!!.containedNotes.single()

        // Edit both root content AND child content so every doc is written
        // (skip-detection skips unchanged docs).
        repo().saveMultipleNotes(listOf(
            NoteRepository.SaveItem(
                noteId = rId1,
                trackedLines = listOf(NoteLine("r1-edited", rId1), NoteLine("\tchild1-edited", c1)),
                localBases = mapOf(rId1 to listOf(c1)),
            ),
            NoteRepository.SaveItem(
                noteId = rId2,
                trackedLines = listOf(NoteLine("r2-edited", rId2), NoteLine("\tchild2-edited", c2)),
                localBases = mapOf(rId2 to listOf(c2)),
            ),
        )).getOrThrow()

        assertEquals("r1-edited", readRawNote(rId1)!!["content"])
        assertEquals("r2-edited", readRawNote(rId2)!!["content"])
        assertEquals("child1-edited", readRawNote(c1)!!["content"])
        assertEquals("child2-edited", readRawNote(c2)!!["content"])
    }

    /**
     * IT-2b — when one item in the batch trips the content-drop guard,
     * NO doc is written. The benign item rolls back implicitly because
     * the guard throws before commitInBatches runs.
     */
    @Test
    fun saveMultipleNotes_contentDropGuardAbortsBatch() = runBlocking {
        // Seed r1 with 4 children so a save of just 1 trips the guard
        // (drops 3 of 4, > half — guard threshold).
        val r1 = repo().createMultiLineNote("r1\n\tA\n\tB\n\tC\n\tD").getOrThrow()
        val r2 = repo().createMultiLineNote("r2-original").getOrThrow()
        waitForListener(r1); waitForListener(r2)
        val originalR1Children = NoteStore.getRawNoteById(r1)!!.containedNotes.toList()
        assertEquals(4, originalR1Children.size)
        val firstChildId = originalR1Children.first()

        val result = repo().saveMultipleNotes(listOf(
            NoteRepository.SaveItem(
                noteId = r1,
                trackedLines = listOf(NoteLine("r1", r1), NoteLine("\tA", firstChildId)),
                localBases = mapOf(r1 to originalR1Children),
            ),
            NoteRepository.SaveItem(
                noteId = r2,
                trackedLines = listOf(NoteLine("r2-mutated", r2)),
                localBases = mapOf(r2 to emptyList()),
            ),
        ))

        assertTrue("guard must abort the batch; got $result", result.isFailure)

        // r2 must be unchanged — proves the guard fired BEFORE
        // commitInBatches landed any of the planned writes.
        assertEquals("r2-original", readRawNote(r2)!!["content"])
        // r1 still has all 4 original children (the planned drop never
        // committed).
        @Suppress("UNCHECKED_CAST")
        val r1Contained = readRawNote(r1)!!["containedNotes"] as List<String>
        assertEquals(originalR1Children, r1Contained)
    }
}
