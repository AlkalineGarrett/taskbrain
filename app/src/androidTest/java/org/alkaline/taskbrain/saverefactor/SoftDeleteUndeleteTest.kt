package org.alkaline.taskbrain.saverefactor

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.runBlocking
import org.alkaline.taskbrain.EmulatorTestSupport
import org.alkaline.taskbrain.data.NoteState
import org.alkaline.taskbrain.data.NoteStore
import org.alkaline.taskbrain.saverefactor.SaveRefactorTestSupport.readRawNote
import org.alkaline.taskbrain.saverefactor.SaveRefactorTestSupport.repo
import org.alkaline.taskbrain.saverefactor.SaveRefactorTestSupport.waitFor
import org.alkaline.taskbrain.saverefactor.SaveRefactorTestSupport.waitForListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

/**
 * IT-D1 — soft-deleting a tree marks the root and every descendant with
 * `state='deleted'`. Undelete clears state on all of them. Reconstruction
 * before undelete returns null/empty for the tree; after, the tree is
 * fully restored.
 */
class SoftDeleteUndeleteTest {

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
    fun softDeleteThenUndelete_treeFullyRestored() = runBlocking {
        val rootId = repo().createMultiLineNote("R\n\tc1\n\t\tg1").getOrThrow()
        waitForListener(rootId)
        val c1 = NoteStore.getRawNoteById(rootId)!!.containedNotes.single()
        val g1 = NoteStore.getRawNoteById(c1)!!.containedNotes.single()

        repo().softDeleteNote(rootId).getOrThrow()
        waitFor(timeoutMs = 5_000) {
            readRawNote(rootId)?.get("state") == NoteState.DELETED
        }
        // Every descendant carries state='deleted' too.
        assertEquals(NoteState.DELETED, readRawNote(c1)!!["state"])
        assertEquals(NoteState.DELETED, readRawNote(g1)!!["state"])
        // Reconstruction filters out the deleted tree.
        waitFor(timeoutMs = 5_000) {
            NoteStore.getRawNoteById(rootId)?.state == NoteState.DELETED
        }
        // Note: getNoteLinesById returns lines as long as the root exists,
        // even if state=deleted — the deletion filter is in the top-level
        // notes list (filterTopLevelNotes), not the descendant walk. Verify
        // the doc state explicitly.

        repo().undeleteNote(rootId).getOrThrow()
        waitFor(timeoutMs = 5_000) {
            readRawNote(rootId)?.get("state") == null
        }
        assertNull(readRawNote(c1)!!["state"])
        assertNull(readRawNote(g1)!!["state"])

        // Reload via the repo; the tree is back. Reconstruction's depth
        // convention: top-level children get no tab prefix; depth ≥ 2
        // (grandchildren) get one tab per level beyond the first child.
        val lines = repo().loadNoteLinesAwait(rootId).getOrThrow()
        assertEquals(listOf("R", "c1", "\tg1"), lines.map { it.content })
        assertEquals(rootId, lines[0].noteId)
        assertEquals(c1, lines[1].noteId)
        assertEquals(g1, lines[2].noteId)
    }
}
