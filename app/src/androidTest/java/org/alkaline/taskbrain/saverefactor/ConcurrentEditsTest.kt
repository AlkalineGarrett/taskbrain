package org.alkaline.taskbrain.saverefactor

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.alkaline.taskbrain.EmulatorTestSupport
import org.alkaline.taskbrain.data.NoteLine
import org.alkaline.taskbrain.data.NoteStore
import org.alkaline.taskbrain.saverefactor.SaveRefactorTestSupport.firestore
import org.alkaline.taskbrain.saverefactor.SaveRefactorTestSupport.readRawNote
import org.alkaline.taskbrain.saverefactor.SaveRefactorTestSupport.repo
import org.alkaline.taskbrain.saverefactor.SaveRefactorTestSupport.waitFor
import org.alkaline.taskbrain.saverefactor.SaveRefactorTestSupport.waitForListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.util.UUID

/**
 * Multi-source interleavings — two clients editing the same note's tree.
 * The "second client" is simulated via direct Firestore writes so it
 * appears to the primary as a foreign listener delivery.
 *
 * IT-C1 differentLineEdits_bothSurvive
 * IT-C2 sameLineEdits_lastWriterWinsOnContent
 * IT-C3 reorderVsContentEdit_orderAndContentBothPreserved
 * IT-C4 contradictoryReparents_noLineInTwoParents
 */
class ConcurrentEditsTest {

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
     * IT-C1 — Client A edits line a1's content, Client B edits a2's content.
     * Both edits land on the wire; merge doesn't lose either.
     */
    @Test
    fun differentLineEdits_bothSurvive() = runBlocking {
        val rootId = repo().createMultiLineNote("R\n\ta1\n\ta2").getOrThrow()
        waitForListener(rootId)
        val children = NoteStore.getRawNoteById(rootId)!!.containedNotes
        val (a1, a2) = children[0] to children[1]

        // Client B (simulated) edits a2 directly.
        firestore().collection("notes").document(a2).update(
            mapOf(
                "content" to "a2-from-other-client",
                "version" to FieldValue.increment(1),
                "lastWriterOpId" to "external_${UUID.randomUUID()}",
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
        ).await()
        waitFor(timeoutMs = 5_000) {
            NoteStore.getRawNoteById(a2)?.content == "a2-from-other-client"
        }

        // Client A edits a1's content. Save uses the captured base.
        repo().saveNoteWithChildren(
            rootId,
            listOf(
                NoteLine("R", rootId),
                NoteLine("\ta1-edited-by-A", a1),
                NoteLine("\ta2-from-other-client", a2),  // A's editor saw the update
            ),
            extraOpsBuilder = null,
            localBases = mapOf(rootId to listOf(a1, a2)),
        ).getOrThrow()

        assertEquals("a1-edited-by-A", readRawNote(a1)!!["content"])
        assertEquals("a2-from-other-client", readRawNote(a2)!!["content"])
    }

    /**
     * IT-C2 — both clients edit a1's content. Last-writer-wins on the doc's
     * `content` field; the other client's edit is overwritten. Documents
     * the known limitation (no per-line merge — that would need OT/CRDT).
     */
    @Test
    fun sameLineEdits_lastWriterWinsOnContent() = runBlocking {
        val rootId = repo().createMultiLineNote("R\n\ta1").getOrThrow()
        waitForListener(rootId)
        val a1 = NoteStore.getRawNoteById(rootId)!!.containedNotes.single()

        // Client B writes "B-version" first.
        firestore().collection("notes").document(a1).update(
            mapOf(
                "content" to "B-version",
                "version" to FieldValue.increment(1),
                "lastWriterOpId" to "external_${UUID.randomUUID()}",
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
        ).await()
        // Don't wait for the listener — A's localBases were captured BEFORE
        // B's write arrived. This simulates A and B saving near-simultaneously.

        // Client A saves "A-version". This commits AFTER B; A wins.
        repo().saveNoteWithChildren(
            rootId,
            listOf(NoteLine("R", rootId), NoteLine("\tA-version", a1)),
            extraOpsBuilder = null,
            localBases = mapOf(rootId to listOf(a1)),
        ).getOrThrow()

        assertEquals(
            "last writer wins on the content field",
            "A-version", readRawNote(a1)!!["content"],
        )
    }

    /**
     * IT-C3 — Client A reorders [a1, a2] → [a2, a1]; Client B edits a1's
     * content concurrently. Final state: A's reorder + B's content edit
     * (A's save overwrites root.containedNotes; B's update doesn't touch
     * containedNotes; both compose correctly).
     */
    @Test
    fun reorderVsContentEdit_orderAndContentBothPreserved() = runBlocking {
        val rootId = repo().createMultiLineNote("R\n\ta1\n\ta2").getOrThrow()
        waitForListener(rootId)
        val children = NoteStore.getRawNoteById(rootId)!!.containedNotes
        val (a1, a2) = children[0] to children[1]

        // Client B edits a1's content (no order change).
        firestore().collection("notes").document(a1).update(
            mapOf(
                "content" to "a1-edited-by-B",
                "version" to FieldValue.increment(1),
                "lastWriterOpId" to "external_${UUID.randomUUID()}",
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
        ).await()
        waitFor(timeoutMs = 5_000) {
            NoteStore.getRawNoteById(a1)?.content == "a1-edited-by-B"
        }

        // Client A reorders, picking up B's content via the listener echo.
        repo().saveNoteWithChildren(
            rootId,
            listOf(
                NoteLine("R", rootId),
                NoteLine("\ta2", a2),
                NoteLine("\ta1-edited-by-B", a1),
            ),
            extraOpsBuilder = null,
            localBases = mapOf(rootId to listOf(a1, a2)),
        ).getOrThrow()

        @Suppress("UNCHECKED_CAST")
        val rootContained = readRawNote(rootId)!!["containedNotes"] as List<String>
        assertEquals("A's reorder applied", listOf(a2, a1), rootContained)
        assertEquals("B's content survives", "a1-edited-by-B", readRawNote(a1)!!["content"])
    }

    /**
     * IT-C5 — IT-C1's scenario routed through the editor's actual save
     * pipeline (`loadNoteLinesAwait` + `prepareInlineEditTrackedLines`)
     * rather than hand-built `NoteLine` objects. After Client B's
     * external edit lands via the listener, Client A's editor reloads
     * via `loadNoteLinesAwait` — that's the call shape `useEditor`
     * uses on external-change reload — then drives the save through
     * `prepareInlineEditTrackedLines`. A regression in the loader
     * (e.g., reconstruction dropping the externally-edited line) or
     * in `matchLinesToIds` would surface here even if IT-C1 passes.
     */
    @Test
    fun differentLineEdits_throughEditorReloadAndSave_bothSurvive() = runBlocking {
        val rootId = repo().createMultiLineNote("R\n\ta1\n\ta2").getOrThrow()
        waitForListener(rootId)
        val children = NoteStore.getRawNoteById(rootId)!!.containedNotes
        val (a1, a2) = children[0] to children[1]

        firestore().collection("notes").document(a2).update(
            mapOf(
                "content" to "a2-from-other-client",
                "version" to FieldValue.increment(1),
                "lastWriterOpId" to "external_${UUID.randomUUID()}",
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
        ).await()
        waitFor(timeoutMs = 5_000) {
            NoteStore.getRawNoteById(a2)?.content == "a2-from-other-client"
        }

        // Editor's external-change handler reloads via loadNoteLinesAwait,
        // then the editor renders the reloaded lines. Verify both lines
        // are present (a regression where the loader dropped a remote-
        // edited line would be caught here).
        val reloaded = repo().loadNoteLinesAwait(rootId).getOrThrow()
        assertEquals(
            "loader returns root + 2 children with the remote edit applied",
            listOf("R", "a1", "a2-from-other-client"),
            reloaded.map { it.content },
        )

        // Editor types its own edit on a1, then saves through the
        // pipeline. trackedLines come from prepareInlineEditTrackedLines
        // — same call shape useSaveCoordinator.saveAll uses.
        val tracked = repo().prepareInlineEditTrackedLines(
            rootId, "R\n\ta1-edited-by-A\n\ta2-from-other-client", "test",
            editorNoteIds = listOf(rootId, a1, a2),
        )
        repo().saveNoteWithChildren(
            rootId, tracked, extraOpsBuilder = null,
            localBases = mapOf(rootId to listOf(a1, a2)),
        ).getOrThrow()

        assertEquals("a1-edited-by-A", readRawNote(a1)!!["content"])
        assertEquals("a2-from-other-client", readRawNote(a2)!!["content"])
    }

    /**
     * IT-C4 — Client A reparents x under c1; Client B reparents x under
     * c2. Last writer wins on x's `parentNoteId`. The wire-level
     * inconsistency where c2.containedNotes still lists x (B's write)
     * IS expected — see the "move-vs-move" entry in
     * `docs/save-refactor-followups.md`. Reconstruction resolves it by
     * dropping the orphan ref on c2 (because x.parentNoteId points
     * elsewhere). This test pins both: A's write wins on x, AND
     * `loadNoteLinesAwait` returns a tree with x under c1 only.
     */
    @Test
    fun contradictoryReparents_lastWriterWinsAndReconstructionResolves() = runBlocking {
        val rootId = repo().createMultiLineNote("R\n\tc1\n\tc2\n\tx").getOrThrow()
        waitForListener(rootId)
        val rootContained = NoteStore.getRawNoteById(rootId)!!.containedNotes
        val (c1, c2, x) = Triple(rootContained[0], rootContained[1], rootContained[2])

        // Client B reparents x under c2 first (direct write; not via repo).
        // Update x's parentNoteId, drop x from root's containedNotes, add to
        // c2's containedNotes.
        firestore().collection("notes").document(x).update(
            mapOf(
                "parentNoteId" to c2,
                "version" to FieldValue.increment(1),
                "lastWriterOpId" to "external_${UUID.randomUUID()}",
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
        ).await()
        firestore().collection("notes").document(rootId).update(
            mapOf(
                "containedNotes" to listOf(c1, c2),
                "lastWriterOpId" to "external_${UUID.randomUUID()}",
                "version" to FieldValue.increment(1),
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
        ).await()
        firestore().collection("notes").document(c2).update(
            mapOf(
                "containedNotes" to listOf(x),
                "lastWriterOpId" to "external_${UUID.randomUUID()}",
                "version" to FieldValue.increment(1),
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
        ).await()
        waitFor(timeoutMs = 5_000) {
            NoteStore.getRawNoteById(x)?.parentNoteId == c2
        }

        // Client A: save tree where x is now under c1 (via Tab indent into c1).
        // A's localBases reflect the pre-conflict state.
        repo().saveNoteWithChildren(
            rootId,
            listOf(
                NoteLine("R", rootId),
                NoteLine("\tc1", c1),
                NoteLine("\t\tx", x),
                NoteLine("\tc2", c2),
            ),
            extraOpsBuilder = null,
            localBases = mapOf(
                rootId to listOf(c1, c2, x),
                c1 to emptyList(),
            ),
        ).getOrThrow()

        // Last writer wins on x's parentNoteId.
        assertEquals(c1, readRawNote(x)!!["parentNoteId"])
        // c1 contains x.
        @Suppress("UNCHECKED_CAST")
        val c1Final = readRawNote(c1)!!["containedNotes"] as List<String>
        assertTrue("c1 owns x", c1Final.contains(x))
        // The wire's c2.containedNotes still has x (B's write that A's
        // save didn't touch). This is the documented move-vs-move
        // limitation. Reconstruction resolves it by dropping the orphan
        // ref because x.parentNoteId no longer points at c2.
        val reloaded = repo().loadNoteLinesAwait(rootId).getOrThrow()
        val xPositions = reloaded.indices.filter { reloaded[it].noteId == x }
        assertEquals(
            "reconstruction must render x exactly once: got positions $xPositions",
            1, xPositions.size,
        )
    }
}
