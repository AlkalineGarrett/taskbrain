package org.alkaline.taskbrain.saverefactor

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.runBlocking
import org.alkaline.taskbrain.EmulatorTestSupport
import org.alkaline.taskbrain.data.NoteLine
import org.alkaline.taskbrain.data.NoteStore
import org.alkaline.taskbrain.saverefactor.SaveRefactorTestSupport.readRawNote
import org.alkaline.taskbrain.saverefactor.SaveRefactorTestSupport.repo
import org.alkaline.taskbrain.saverefactor.SaveRefactorTestSupport.waitForListener
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

/**
 * `containedNotesBase` round-trips through the emulator — it's the anchor
 * the receiving client uses for the 3-way merge in
 * [NoteRepository.planSaveNoteWithChildren].
 */
class SchemaFieldsTest {

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
    fun saveStampsContainedNotesBase() = runBlocking {
        val rootId = repo().createMultiLineNote("hello\n\tchild").getOrThrow()
        waitForListener(rootId)

        // Save again with localBases set; verify the base was persisted.
        val childId = NoteStore.getRawNoteById(rootId)!!.containedNotes.single()
        val basesAtCapture = mapOf(rootId to listOf(childId))
        repo().saveNoteWithChildren(
            rootId,
            listOf(NoteLine("hello-edited", rootId), NoteLine("\tchild", childId)),
            extraOpsBuilder = null,
            localBases = basesAtCapture,
        ).getOrThrow()

        @Suppress("UNCHECKED_CAST")
        val baseField = readRawNote(rootId)!!["containedNotesBase"] as List<String>
        assertEquals("containedNotesBase records the local snapshot at edit start",
            listOf(childId), baseField)
    }
}
