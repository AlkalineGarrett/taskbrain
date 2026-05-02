package org.alkaline.taskbrain.saverefactor

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * IT-R1 cutAndPasteBackIntoSameNote_reordersWithoutOrphan
 * IT-R2 staleLocalBases_picksUpRemoteAdditions_atSave
 * IT-R3 burstOfSaves_noEchoesEmittedForOurOwnNote
 */
class ComplexResolutionTest {

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
     * IT-R1 — cut a1 from A, paste back into A at a different position.
     * The cut buffer's reclaim path (within one save) treats this as a
     * reorder; a1's doc id is preserved, no orphan, no soft-delete.
     */
    @Test
    fun cutAndPasteBackIntoSameNote_reordersWithoutOrphan() = runBlocking {
        val aId = repo().createMultiLineNote("A\n\ta1\n\ta2").getOrThrow()
        waitForListener(aId)
        val children = NoteStore.getRawNoteById(aId)!!.containedNotes
        val (a1, a2) = children[0] to children[1]

        // User cuts a1 (recorded in pending cuts), then pastes at the
        // bottom — same note, different position. The save's
        // saveMultipleNotes-style cut-delete pass sees a1 in the
        // surviving set (it's coming back as a child of A) and skips
        // the cut-delete write.
        NoteStore.recordCut(a1, "a1")
        repo().saveNoteWithChildren(
            aId,
            listOf(
                NoteLine("A", aId),
                NoteLine("\ta2", a2),
                NoteLine("\ta1", a1),
            ),
            extraOpsBuilder = null,
            localBases = mapOf(aId to listOf(a1, a2)),
        ).getOrThrow()

        // No soft-delete or cut-delete on a1.
        val rawA1 = readRawNote(a1)!!
        assertEquals(null, rawA1["state"])
        assertEquals(aId, rawA1["parentNoteId"])
        @Suppress("UNCHECKED_CAST")
        val finalContained = readRawNote(aId)!!["containedNotes"] as List<String>
        assertEquals(listOf(a2, a1), finalContained)
        // pendingCuts drained on commit.
        assertTrue(NoteStore.getPendingCuts().isEmpty())
    }

    /**
     * IT-R2 — primary captures localBases=[c1] before another client adds
     * c2 remotely. Primary edits c1's content and saves. The 3-way merge
     * folds c2 back into root.containedNotes; nothing is soft-deleted.
     */
    @Test
    fun staleLocalBases_picksUpRemoteAdditions_atSave() = runBlocking {
        val uid = FirebaseAuth.getInstance().currentUser!!.uid
        val rootId = repo().createMultiLineNote("R\n\tc1").getOrThrow()
        waitForListener(rootId)
        val c1 = NoteStore.getRawNoteById(rootId)!!.containedNotes.single()

        // Capture localBases at this point — we'll save with these later.
        val basesAtCapture = mapOf(rootId to listOf(c1))

        // Other client adds c2.
        val c2 = "ext_${UUID.randomUUID()}"
        writeAsOtherClient(
            noteId = c2, userId = uid, content = "c2",
            parentNoteId = rootId, rootNoteId = rootId,
        )
        otherClientAppendChild(parentId = rootId, childId = c2)
        waitFor(timeoutMs = 5_000) {
            NoteStore.getRawNoteById(rootId)?.containedNotes?.contains(c2) == true
        }

        // Primary saves with the STALE base — only knows c1. Content
        // edit on c1.
        repo().saveNoteWithChildren(
            rootId,
            listOf(
                NoteLine("R", rootId),
                NoteLine("\tc1-edited", c1),
            ),
            extraOpsBuilder = null,
            localBases = basesAtCapture,
        ).getOrThrow()

        @Suppress("UNCHECKED_CAST")
        val finalContained = readRawNote(rootId)!!["containedNotes"] as List<String>
        assertTrue("c1 preserved", finalContained.contains(c1))
        assertTrue("c2 (added remotely after our base) survived merge", finalContained.contains(c2))
        assertEquals(null, readRawNote(c2)!!["state"])
        assertEquals("c1-edited", readRawNote(c1)!!["content"])
    }

    /**
     * IT-R3 — three saves in close succession. Each must register and
     * release its opId such that listener echoes are suppressed for ALL
     * three. The pendingOpIds registry under load: no spurious
     * `changedNoteIds` emission for our own note id.
     */
    @Test
    fun burstOfSaves_noEchoesEmittedForOurOwnNote() = runBlocking {
        val rootId = repo().createMultiLineNote("seed").getOrThrow()
        waitForListener(rootId)

        val collected = CopyOnWriteArrayList<Set<String>>()
        val scope = CoroutineScope(Dispatchers.Default)
        val job = NoteStore.changedNoteIds.onEach { collected.add(it) }.launchIn(scope)
        try {
            collected.clear()

            for (i in 1..3) {
                repo().saveNoteWithChildren(
                    rootId,
                    listOf(NoteLine("seed v$i", rootId)),
                    extraOpsBuilder = null,
                    localBases = null,
                ).getOrThrow()
            }

            // Wait long enough for all three echoes to deliver. Echo
            // suppression should keep our note out of every emission.
            kotlinx.coroutines.delay(2_500)

            val rootEmissions = collected.count { rootId in it }
            assertEquals(
                "expected zero changedNoteIds emissions for our own note id " +
                    "across the burst; got $rootEmissions (full=$collected)",
                0, rootEmissions,
            )
            // Final wire content reflects the last write.
            assertEquals("seed v3", readRawNote(rootId)!!["content"])
        } finally {
            job.cancel()
            scope.cancel()
        }
    }
}
