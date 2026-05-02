package org.alkaline.taskbrain.saverefactor

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.runBlocking
import org.alkaline.taskbrain.EmulatorTestSupport
import org.alkaline.taskbrain.data.NoteLine
import org.alkaline.taskbrain.data.NoteStore
import org.alkaline.taskbrain.saverefactor.SaveRefactorTestSupport.otherClientAppendChild
import org.alkaline.taskbrain.saverefactor.SaveRefactorTestSupport.readRawNote
import org.alkaline.taskbrain.saverefactor.SaveRefactorTestSupport.repo
import org.alkaline.taskbrain.saverefactor.SaveRefactorTestSupport.waitFor
import org.alkaline.taskbrain.saverefactor.SaveRefactorTestSupport.waitForListener
import org.alkaline.taskbrain.saverefactor.SaveRefactorTestSupport.writeAsOtherClient
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.util.UUID

/**
 * IT-9b — when a second client adds a subtree (parent + grandchild)
 * under one of OUR descendants, our save must NOT soft-delete that
 * subtree. The Phase 9 generalization of `findConcurrentSubtree` (now
 * BFS-expanding from each anchored parent) is what saves us.
 */
class DescendantSubtreeSurvivalTest {

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

    @Test
    fun concurrentSubtreeUnderDescendant_isNotSoftDeleted() = runBlocking {
        val uid = FirebaseAuth.getInstance().currentUser!!.uid
        val rootId = repo().createMultiLineNote("R\n\tmid\n\t\tg1").getOrThrow()
        waitForListener(rootId)
        val midId = NoteStore.getRawNoteById(rootId)!!.containedNotes.single()
        val g1 = NoteStore.getRawNoteById(midId)!!.containedNotes.single()

        // Other client adds g2 under mid, with g2a as a grandchild.
        val g2 = "ext_${UUID.randomUUID()}"
        val g2a = "ext_${UUID.randomUUID()}"
        writeAsOtherClient(
            noteId = g2a, userId = uid, content = "g2a",
            parentNoteId = g2, rootNoteId = rootId,
        )
        writeAsOtherClient(
            noteId = g2, userId = uid, content = "g2",
            parentNoteId = midId, rootNoteId = rootId,
            containedNotes = listOf(g2a),
        )
        otherClientAppendChild(parentId = midId, childId = g2)
        waitFor(timeoutMs = 5_000) {
            NoteStore.getRawNoteById(midId)?.containedNotes?.contains(g2) == true
        }

        // Primary saves an unrelated edit. localBases for mid = [g1].
        // Without per-descendant survivor extension, the planSave
        // soft-delete pass would wipe g2 + g2a.
        repo().saveNoteWithChildren(
            rootId,
            listOf(
                NoteLine("R", rootId),
                NoteLine("\tmid", midId),
                NoteLine("\t\tg1-edited", g1),
            ),
            extraOpsBuilder = null,
            localBases = mapOf(
                rootId to listOf(midId),
                midId to listOf(g1),
            ),
        ).getOrThrow()

        assertNull("g2 must not be soft-deleted",
            readRawNote(g2)!!["state"])
        assertNull("g2a must not be soft-deleted",
            readRawNote(g2a)!!["state"])
    }
}
