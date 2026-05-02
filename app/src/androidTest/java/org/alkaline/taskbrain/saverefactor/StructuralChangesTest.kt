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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

/**
 * Structural mutations that change the parent/child shape of a note tree:
 * reorder siblings, indent (depth+1), outdent (depth-1), and full subtree
 * relocation across notes. Each operation must preserve every line's doc id
 * end-to-end through save → listener → reload.
 *
 * IT-M1 reorderLinesWithinNote_preservesIdentity
 * IT-M2 indentLineUnderSibling_reparentsAndPreservesId
 * IT-M3 outdentLineToRoot_reparentsAndPreservesId
 * IT-M4 moveSubtreeIntoAnotherNote_preservesAllDescendantIdentities
 */
class StructuralChangesTest {

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
     * IT-M1 — reordering siblings flips `containedNotes`. Doc ids unchanged;
     * descendant content unchanged; root.containedNotes reflects the new
     * order.
     */
    @Test
    fun reorderLinesWithinNote_preservesIdentity() = runBlocking {
        val rootId = repo().createMultiLineNote("R\n\ta1\n\ta2\n\ta3").getOrThrow()
        waitForListener(rootId)
        val children = NoteStore.getRawNoteById(rootId)!!.containedNotes
        val (a1, a2, a3) = Triple(children[0], children[1], children[2])

        // Move a3 to top, a1 to middle: [a3, a1, a2].
        repo().saveNoteWithChildren(
            rootId,
            listOf(
                NoteLine("R", rootId),
                NoteLine("\ta3", a3),
                NoteLine("\ta1", a1),
                NoteLine("\ta2", a2),
            ),
            extraOpsBuilder = null,
            localBases = mapOf(rootId to listOf(a1, a2, a3)),
        ).getOrThrow()

        @Suppress("UNCHECKED_CAST")
        val finalContained = readRawNote(rootId)!!["containedNotes"] as List<String>
        assertEquals("reorder reflected on the wire", listOf(a3, a1, a2), finalContained)
        assertEquals("a1 doc preserved", "a1", readRawNote(a1)!!["content"])
        assertEquals("a3 doc preserved", "a3", readRawNote(a3)!!["content"])
    }

    /**
     * IT-M2 — indenting a2 under a1 reparents a2 (parentNoteId flips from
     * root to a1) and updates both root.containedNotes (drops a2) and
     * a1.containedNotes (gains a2). Identity preserved on a2.
     */
    @Test
    fun indentLineUnderSibling_reparentsAndPreservesId() = runBlocking {
        val rootId = repo().createMultiLineNote("R\n\ta1\n\ta2").getOrThrow()
        waitForListener(rootId)
        val children = NoteStore.getRawNoteById(rootId)!!.containedNotes
        val (a1, a2) = children[0] to children[1]

        // Tracked lines after Tab pressed on a2: depth=2 (under a1).
        repo().saveNoteWithChildren(
            rootId,
            listOf(
                NoteLine("R", rootId),
                NoteLine("\ta1", a1),
                NoteLine("\t\ta2", a2),
            ),
            extraOpsBuilder = null,
            localBases = mapOf(
                rootId to listOf(a1, a2),
                a1 to emptyList(),
            ),
        ).getOrThrow()

        assertEquals(a1, readRawNote(a2)!!["parentNoteId"])
        @Suppress("UNCHECKED_CAST")
        val rootContained = readRawNote(rootId)!!["containedNotes"] as List<String>
        assertEquals(listOf(a1), rootContained)
        @Suppress("UNCHECKED_CAST")
        val a1Contained = readRawNote(a1)!!["containedNotes"] as List<String>
        assertEquals(listOf(a2), a1Contained)
    }

    /**
     * IT-M3 — outdenting g1 (depth 2) to depth 1 reparents from c1 to root.
     * c1 loses g1 from containedNotes; root gains g1.
     */
    @Test
    fun outdentLineToRoot_reparentsAndPreservesId() = runBlocking {
        val rootId = repo().createMultiLineNote("R\n\tc1\n\t\tg1").getOrThrow()
        waitForListener(rootId)
        val c1 = NoteStore.getRawNoteById(rootId)!!.containedNotes.single()
        val g1 = NoteStore.getRawNoteById(c1)!!.containedNotes.single()

        // Outdent g1: depth 2 → depth 1 (sibling of c1, child of root).
        repo().saveNoteWithChildren(
            rootId,
            listOf(
                NoteLine("R", rootId),
                NoteLine("\tc1", c1),
                NoteLine("\tg1", g1),
            ),
            extraOpsBuilder = null,
            localBases = mapOf(
                rootId to listOf(c1),
                c1 to listOf(g1),
            ),
        ).getOrThrow()

        assertEquals(rootId, readRawNote(g1)!!["parentNoteId"])
        @Suppress("UNCHECKED_CAST")
        val rootContained = readRawNote(rootId)!!["containedNotes"] as List<String>
        assertEquals(listOf(c1, g1), rootContained)
        @Suppress("UNCHECKED_CAST")
        val c1Contained = readRawNote(c1)!!["containedNotes"] as List<String>
        assertEquals(emptyList<String>(), c1Contained)
    }

    /**
     * IT-M4 — cutting a subtree (c1 with grandchild g1) from A and pasting
     * into B reparents EVERY descendant. All ids preserved end-to-end.
     */
    @Test
    fun moveSubtreeIntoAnotherNote_preservesAllDescendantIdentities() = runBlocking {
        val aId = repo().createMultiLineNote("A\n\tc1\n\t\tg1").getOrThrow()
        val bId = repo().createMultiLineNote("B").getOrThrow()
        waitForListener(aId); waitForListener(bId)
        val c1 = NoteStore.getRawNoteById(aId)!!.containedNotes.single()
        val g1 = NoteStore.getRawNoteById(c1)!!.containedNotes.single()

        // Editor would record cuts for the whole subtree on a multi-line cut;
        // here only c1 gets a cut entry because the editor cuts the line
        // (its descendants follow naturally on reparent).
        NoteStore.recordCut(c1, "c1")

        // saveAll: A drops the subtree, B gains it. Tracked lines for B
        // mirror what the editor produces after paste — the entire subtree
        // is included so planSave can reparent c1 and update g1's parent
        // chain (g1's parentNoteId stays c1; rootNoteId follows c1's).
        repo().saveMultipleNotes(listOf(
            NoteRepository.SaveItem(
                noteId = aId,
                trackedLines = listOf(NoteLine("A", aId)),
                localBases = mapOf(aId to listOf(c1)),
            ),
            NoteRepository.SaveItem(
                noteId = bId,
                trackedLines = listOf(
                    NoteLine("B", bId),
                    NoteLine("\tc1", c1),
                    NoteLine("\t\tg1", g1),
                ),
                localBases = mapOf(bId to emptyList()),
            ),
        )).getOrThrow()

        // c1 reparented under B with state cleared and rootNoteId=B.
        val rawC1 = readRawNote(c1)!!
        assertEquals(bId, rawC1["parentNoteId"])
        assertEquals(bId, rawC1["rootNoteId"])
        assertNull(rawC1["state"])
        // g1 stays under c1 (parent unchanged) but rootNoteId follows.
        val rawG1 = readRawNote(g1)!!
        assertEquals(c1, rawG1["parentNoteId"])
        assertEquals(bId, rawG1["rootNoteId"])
        // Containment lists reflect the move.
        @Suppress("UNCHECKED_CAST")
        val aContained = readRawNote(aId)!!["containedNotes"] as List<String>
        assertEquals(emptyList<String>(), aContained)
        @Suppress("UNCHECKED_CAST")
        val bContained = readRawNote(bId)!!["containedNotes"] as List<String>
        assertEquals(listOf(c1), bContained)
    }
}
