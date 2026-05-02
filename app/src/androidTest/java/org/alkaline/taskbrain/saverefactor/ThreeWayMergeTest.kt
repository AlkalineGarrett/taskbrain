package org.alkaline.taskbrain.saverefactor

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.runBlocking
import org.alkaline.taskbrain.EmulatorTestSupport
import org.alkaline.taskbrain.data.NoteIdSentinel
import org.alkaline.taskbrain.data.NoteLine
import org.alkaline.taskbrain.data.NoteStore
import org.alkaline.taskbrain.saverefactor.SaveRefactorTestSupport.firestore
import org.alkaline.taskbrain.saverefactor.SaveRefactorTestSupport.otherClientAppendChild
import org.alkaline.taskbrain.saverefactor.SaveRefactorTestSupport.readRawNote
import org.alkaline.taskbrain.saverefactor.SaveRefactorTestSupport.repo
import org.alkaline.taskbrain.saverefactor.SaveRefactorTestSupport.waitFor
import org.alkaline.taskbrain.saverefactor.SaveRefactorTestSupport.waitForListener
import org.alkaline.taskbrain.saverefactor.SaveRefactorTestSupport.writeAsOtherClient
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.util.UUID

/**
 * Phase 4 + 9 — root and per-descendant 3-way merge of `containedNotes`
 * under simulated concurrency.
 *
 * IT-4a — concurrent root-level additions both survive.
 * IT-9a — concurrent additions to a deeply-nested descendant both
 *         survive.
 */
class ThreeWayMergeTest {

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
     * IT-4a — primary captures localBase=[c1]. A simulated second
     * client adds c2 directly. Primary then saves with [c1, c3].
     * Final wire state must contain c1, c2, c3.
     */
    @Test
    fun rootLevelConcurrentAdditions_bothSurvive() = runBlocking {
        val uid = FirebaseAuth.getInstance().currentUser!!.uid
        val rootId = repo().createMultiLineNote("R\n\tc1").getOrThrow()
        waitForListener(rootId)
        val c1 = NoteStore.getRawNoteById(rootId)!!.containedNotes.single()

        // Simulate "another client" adding c2 under root.
        val c2 = "external_${UUID.randomUUID()}"
        writeAsOtherClient(
            noteId = c2, userId = uid, content = "c2 from other",
            parentNoteId = rootId, rootNoteId = rootId,
        )
        otherClientAppendChild(parentId = rootId, childId = c2)
        // Wait for the listener to deliver the external write so our
        // localBases are still the captured pre-conflict snapshot.
        waitFor(timeoutMs = 5_000) {
            NoteStore.getRawNoteById(rootId)?.containedNotes?.contains(c2) == true
        }

        // Primary saves with the captured base [c1] and a new line c3.
        // Sentinel allocates fresh; planSave's 3-way merge folds the
        // remote-only c2 back into the final containedNotes.
        val c3Sentinel = NoteIdSentinel.new(NoteIdSentinel.Origin.TYPED)
        repo().saveNoteWithChildren(
            rootId,
            listOf(
                NoteLine("R", rootId),
                NoteLine("\tc1", c1),
                NoteLine("\tc3", c3Sentinel),
            ),
            extraOpsBuilder = null,
            localBases = mapOf(rootId to listOf(c1)),
        ).getOrThrow()

        @Suppress("UNCHECKED_CAST")
        val finalContained = readRawNote(rootId)!!["containedNotes"] as List<String>
        assertTrue("c1 survived", finalContained.contains(c1))
        assertTrue("c2 (external) survived", finalContained.contains(c2))
        assertTrue("c3 (local new) survived (under one of its assigned ids)",
            finalContained.size >= 3)
        // No soft-delete of c2.
        assertTrue("c2 must not be soft-deleted",
            readRawNote(c2)!!["state"] == null)
    }

    /**
     * IT-9a — concurrent additions at depth 2 both survive (Phase 9
     * per-descendant merge).
     */
    @Test
    fun descendantLevelConcurrentAdditions_bothSurvive() = runBlocking {
        val uid = FirebaseAuth.getInstance().currentUser!!.uid
        val rootId = repo().createMultiLineNote("R\n\tmid\n\t\tg1").getOrThrow()
        waitForListener(rootId)
        val rootNote = NoteStore.getRawNoteById(rootId)!!
        val midId = rootNote.containedNotes.single()
        val g1 = NoteStore.getRawNoteById(midId)!!.containedNotes.single()

        // Other client adds g2 under mid.
        val g2 = "external_${UUID.randomUUID()}"
        writeAsOtherClient(
            noteId = g2, userId = uid, content = "g2 from other",
            parentNoteId = midId, rootNoteId = rootId,
        )
        otherClientAppendChild(parentId = midId, childId = g2)
        waitFor(timeoutMs = 5_000) {
            NoteStore.getRawNoteById(midId)?.containedNotes?.contains(g2) == true
        }

        // Primary saves with the captured per-descendant base for mid =
        // [g1] and adds g3 locally. Per-descendant merge in Phase 9
        // folds in remote-only g2.
        val g3Sentinel = NoteIdSentinel.new(NoteIdSentinel.Origin.TYPED)
        repo().saveNoteWithChildren(
            rootId,
            listOf(
                NoteLine("R", rootId),
                NoteLine("\tmid", midId),
                NoteLine("\t\tg1", g1),
                NoteLine("\t\tg3", g3Sentinel),
            ),
            extraOpsBuilder = null,
            localBases = mapOf(
                rootId to listOf(midId),
                midId to listOf(g1),
            ),
        ).getOrThrow()

        @Suppress("UNCHECKED_CAST")
        val midContained = readRawNote(midId)!!["containedNotes"] as List<String>
        assertTrue("g1 survived", midContained.contains(g1))
        assertTrue("g2 (external) survived", midContained.contains(g2))
        assertTrue("at least 3 descendants under mid (g1, g3, g2)",
            midContained.size >= 3)
        assertTrue("g2 must not be soft-deleted",
            readRawNote(g2)!!["state"] == null)
    }

    @Suppress("unused")
    private val firestoreUnused = firestore() // keep import
}
