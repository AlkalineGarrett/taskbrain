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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

/**
 * IT-1a — Phase 1 schema fields (`version`, `lastWriterOpId`,
 * `containedNotesBase`) round-trip through the emulator.
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
    fun saveStampsVersionOpIdAndContainedNotesBase() = runBlocking {
        val rootId = repo().createMultiLineNote("hello\n\tchild").getOrThrow()
        waitForListener(rootId)

        val first = readRawNote(rootId)!!
        val firstVersion = first["version"] as Long
        val firstOpId = first["lastWriterOpId"] as String
        assertNotNull(firstOpId)

        // Save again with localBases set; verify the base was persisted
        // and version/opId advanced.
        val childId = NoteStore.getRawNoteById(rootId)!!.containedNotes.single()
        val basesAtCapture = mapOf(rootId to listOf(childId))
        repo().saveNoteWithChildren(
            rootId,
            listOf(NoteLine("hello-edited", rootId), NoteLine("\tchild", childId)),
            extraOpsBuilder = null,
            localBases = basesAtCapture,
        ).getOrThrow()

        val second = readRawNote(rootId)!!
        val secondVersion = second["version"] as Long
        val secondOpId = second["lastWriterOpId"] as String
        assertEquals("version must be monotonic", firstVersion + 1, secondVersion)
        assertNotEquals("each save gets a fresh opId", firstOpId, secondOpId)
        @Suppress("UNCHECKED_CAST")
        val baseField = second["containedNotesBase"] as List<String>
        assertEquals("containedNotesBase records the local snapshot at edit start",
            listOf(childId), baseField)
    }
}
