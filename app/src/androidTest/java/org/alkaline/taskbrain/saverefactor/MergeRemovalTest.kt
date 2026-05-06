package org.alkaline.taskbrain.saverefactor

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.runBlocking
import org.alkaline.taskbrain.EmulatorTestSupport
import org.alkaline.taskbrain.data.NoteLine
import org.alkaline.taskbrain.data.NoteState
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
import com.google.firebase.firestore.FieldValue
import kotlinx.coroutines.tasks.await

/**
 * Phase 4 — concurrent removal honored by the 3-way merge.
 * IT-4b — primary's localBase contains a child the remote already
 * removed. Primary's save must respect that removal.
 */
class MergeRemovalTest {

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
    fun concurrentRemoteRemoval_isHonored() = runBlocking {
        val rootId = repo().createMultiLineNote("R\n\tc1\n\tc2").getOrThrow()
        waitForListener(rootId)
        val children = NoteStore.getRawNoteById(rootId)!!.containedNotes
        val c1 = children[0]
        val c2 = children[1]

        // Other client soft-deletes c1 and removes it from root's
        // containedNotes — direct Firestore writes simulate a second
        // client without going through our NoteRepository.
        firestore().collection("notes").document(c1).update(
            mapOf(
                "state" to NoteState.DELETED,
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
        ).await()
        firestore().collection("notes").document(rootId).update(
            mapOf(
                "containedNotes" to listOf(c2),
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
        ).await()
        waitFor(timeoutMs = 5_000) {
            NoteStore.getRawNoteById(rootId)?.containedNotes == listOf(c2)
        }

        // Primary's localBase still believes c1 exists. Save unrelated
        // edit (just bump root content). 3-way merge should honor the
        // remote removal.
        repo().saveNoteWithChildren(
            rootId,
            listOf(
                NoteLine("R-edited", rootId),
                NoteLine("\tc1-stale", c1),  // sees c1 still in local tracked
                NoteLine("\tc2", c2),
            ),
            extraOpsBuilder = null,
            localBases = mapOf(rootId to listOf(c1, c2)),
        ).getOrThrow()

        @Suppress("UNCHECKED_CAST")
        val finalContained = readRawNote(rootId)!!["containedNotes"] as List<String>
        assertEquals("remote removal honored: c1 dropped", listOf(c2), finalContained)
        assertFalse("merge result excludes c1", finalContained.contains(c1))
    }
}
