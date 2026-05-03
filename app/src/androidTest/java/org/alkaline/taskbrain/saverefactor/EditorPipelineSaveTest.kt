package org.alkaline.taskbrain.saverefactor

import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.runBlocking
import org.alkaline.taskbrain.EmulatorTestSupport
import org.alkaline.taskbrain.data.NoteRepository
import org.alkaline.taskbrain.data.NoteState
import org.alkaline.taskbrain.data.NoteStore
import org.alkaline.taskbrain.saverefactor.SaveRefactorTestSupport.readRawNote
import org.alkaline.taskbrain.saverefactor.SaveRefactorTestSupport.repo
import org.alkaline.taskbrain.saverefactor.SaveRefactorTestSupport.waitForListener
import org.alkaline.taskbrain.ui.currentnote.EditorController
import org.alkaline.taskbrain.ui.currentnote.EditorState
import org.alkaline.taskbrain.ui.currentnote.InlineEditSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

/**
 * IT-EP — editor controller → save pipeline.
 *
 * Every other emulator test in the suite stops short of the actual
 * `EditorController` mutation methods (`indent`, `unindent`, `paste`,
 * `cutSelection`, `moveUp`/`moveDown`). They simulate the post-edit
 * state by hand-building `NoteLine` objects with the right tab-indent
 * prefix or the right children list. That leaves a wide gap: a bug in
 * `EditorController.indent` that doesn't update line text correctly,
 * or in `paste` that drops a noteId, would never show up at the
 * Firestore wire level in any other test.
 *
 * These tests start from a real `InlineEditSession`, fire the actual
 * controller methods, then drive the result through
 * `prepareInlineEditTrackedLines` + `saveNoteWithChildren` — the same
 * call shape `CurrentNoteViewModel.saveAll` uses — and assert against
 * the wire state.
 */
class EditorPipelineSaveTest {

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

    /** Constructs an InlineEditSession from a freshly-loaded note. */
    private suspend fun openSession(noteId: String): InlineEditSession {
        val lines = repo().loadNoteLinesAwait(noteId).getOrThrow()
        val editorState = EditorState()
        editorState.initFromNoteLines(lines, preserveCursor = false)
        val controller = EditorController(editorState)
        return InlineEditSession(
            noteId = noteId,
            originalContent = lines.joinToString("\n") { it.content },
            editorState = editorState,
            controller = controller,
        )
    }

    /**
     * Drives a session through the same pipeline `CurrentNoteViewModel.saveAll`
     * uses — `prepareInlineEditTrackedLines` with editor-shape
     * `editorNoteIds`, then `saveNoteWithChildren` with the captured
     * `localBases`.
     */
    private suspend fun savePipeline(session: InlineEditSession): NoteRepository.SaveResult {
        val tracked = repo().prepareInlineEditTrackedLines(
            session.noteId,
            session.editorState.text,
            "EditorPipelineSaveTest",
            session.getEditorNoteIds(),
        )
        return repo().saveNoteWithChildren(
            session.noteId,
            tracked,
            extraOpsBuilder = null,
            localBases = session.getLocalBases(),
        ).getOrThrow()
    }

    /**
     * `EditorController.indent()` on c2 must reparent c2 from rootId
     * to c1Id at the wire level. Catches: indent that doesn't add the
     * tab prefix, planSave that doesn't compute parent from depth,
     * matchLinesToIds that mishandles the depth-shifted line.
     */
    @Test
    fun indent_reparentsLineFromRootToPreviousSibling() = runBlocking {
        val rootId = repo().createMultiLineNote("R\n\tc1\n\tc2").getOrThrow()
        waitForListener(rootId)
        val c1 = NoteStore.getRawNoteById(rootId)!!.containedNotes[0]
        val c2 = NoteStore.getRawNoteById(rootId)!!.containedNotes[1]

        val session = openSession(rootId)
        // Reconstruction is depth-asymmetric: direct children of the root
        // arrive WITHOUT tab prefixes (the editor adds them at render time
        // based on stack-computed depth). After `loadNoteLinesAwait` lines
        // are ["R", "c1", "c2"]. Indent on line 2 adds a single tab,
        // making c2 a depth-1 child of c1 in planSave's stack model.
        session.editorState.focusedLineIndex = 2
        session.controller.indent()

        assertEquals("\tc2", session.editorState.lines[2].text)

        savePipeline(session)

        assertEquals("c2 reparented to c1", c1, readRawNote(c2)!!["parentNoteId"])
        assertEquals(rootId, readRawNote(c2)!!["rootNoteId"])
        @Suppress("UNCHECKED_CAST")
        assertEquals(
            "rootId.containedNotes drops c2 (now a grandchild via c1)",
            listOf(c1), readRawNote(rootId)!!["containedNotes"] as List<String>,
        )
        @Suppress("UNCHECKED_CAST")
        assertEquals(
            "c1.containedNotes gains c2",
            listOf(c2), readRawNote(c1)!!["containedNotes"] as List<String>,
        )
    }

    /**
     * `EditorController.unindent()` on a grandchild promotes it to a
     * sibling of its parent. Wire state must show parentNoteId flipping
     * back to the root.
     */
    @Test
    fun unindent_promotesGrandchildToRootChild() = runBlocking {
        val rootId = repo().createMultiLineNote("R\n\tc1\n\t\tg1").getOrThrow()
        waitForListener(rootId)
        val c1 = NoteStore.getRawNoteById(rootId)!!.containedNotes.single()
        val g1 = NoteStore.getRawNoteById(c1)!!.containedNotes.single()

        val session = openSession(rootId)
        // Reconstruction depth-asymmetric: c1 (depth-0 child) → "c1",
        // g1 (depth-1 grandchild) → "\tg1". Unindenting g1 drops one tab,
        // leaving "g1" — depth 0, a sibling of c1 under root.
        session.editorState.focusedLineIndex = 2
        session.controller.unindent()

        assertEquals("g1", session.editorState.lines[2].text)

        savePipeline(session)

        assertEquals("g1 reparented to root", rootId, readRawNote(g1)!!["parentNoteId"])
        @Suppress("UNCHECKED_CAST")
        assertEquals(
            "rootId.containedNotes now has c1 + g1",
            listOf(c1, g1), readRawNote(rootId)!!["containedNotes"] as List<String>,
        )
        @Suppress("UNCHECKED_CAST")
        assertEquals(
            "c1.containedNotes loses g1",
            emptyList<String>(), readRawNote(c1)!!["containedNotes"] as List<String>,
        )
    }

    /**
     * `EditorController.paste("\ntext")` after end-of-line creates a new
     * line. Wire state must show a fresh doc with the right parent and
     * content; existing siblings untouched.
     */
    @Test
    fun paste_createsNewLine_underCorrectParent() = runBlocking {
        val rootId = repo().createMultiLineNote("R\n\tc1").getOrThrow()
        waitForListener(rootId)
        val c1 = NoteStore.getRawNoteById(rootId)!!.containedNotes.single()

        val session = openSession(rootId)
        // Cursor at end of c1's line. Pasting a line-terminated chunk
        // ("text\n") triggers PasteHandler's `applyLineInsert` path,
        // which (per current implementation, not documented in
        // docs/paste-requirements.md) inserts the complete line BEFORE
        // the cursor line — surprising for end-of-line paste; would be
        // worth confirming this is the intended UX.
        val c1Line = session.editorState.lines[1]
        c1Line.updateFull(c1Line.text, c1Line.text.length)
        session.editorState.focusedLineIndex = 1
        session.controller.paste("new-pasted-line\n")

        // Pin the actual behavior: pasted line is inserted BEFORE c1.
        assertEquals(
            listOf("R", "new-pasted-line", "c1"),
            session.editorState.lines.map { it.text },
        )

        savePipeline(session)

        @Suppress("UNCHECKED_CAST")
        val rootContained = readRawNote(rootId)!!["containedNotes"] as List<String>
        assertEquals(
            "root.containedNotes preserves paste order: pasted line first, then c1",
            2, rootContained.size,
        )
        assertEquals("pasted line is the first child", "new-pasted-line",
            readRawNote(rootContained[0])!!["content"])
        assertEquals("c1 is the second child", c1, rootContained[1])
        assertEquals(rootId, readRawNote(rootContained[0])!!["parentNoteId"])
    }

    /**
     * `EditorController.cutSelection` + `paste` within the same session.
     * The cut line's id must survive into the paste position — no fresh
     * doc allocation, no soft-delete of the moved line.
     */
    @Test
    fun cutAndPaste_sameSession_preservesNoteId() = runBlocking {
        val rootId = repo().createMultiLineNote("R\n\tmovable\n\tstatic").getOrThrow()
        waitForListener(rootId)
        val movable = NoteStore.getRawNoteById(rootId)!!.containedNotes[0]
        val static = NoteStore.getRawNoteById(rootId)!!.containedNotes[1]

        val session = openSession(rootId)
        // Reconstruction returns root's children without tab prefix
        // (depth-asymmetric). Lines: ["R", "movable", "static"].
        val line1Start = session.editorState.getLineStartOffset(1)
        val line1End = line1Start + session.editorState.lines[1].text.length + 1 // trailing \n
        session.editorState.setSelection(line1Start, line1End)

        val clipboardText = StringBuilder()
        val clipboard = object : ClipboardManager {
            override fun getText(): AnnotatedString? = AnnotatedString(clipboardText.toString())
            override fun setText(annotatedString: AnnotatedString) {
                clipboardText.clear(); clipboardText.append(annotatedString.text)
            }
        }
        val cutText = session.controller.cutSelection(clipboard)
        assertNotNull("cutSelection returns the cut text", cutText)
        assertEquals(
            "after cut, only R + static remain",
            listOf("R", "static"),
            session.editorState.lines.map { it.text },
        )
        assertTrue(
            "cut line must enter the cross-session cut buffer",
            NoteStore.getPendingCuts().containsKey(movable),
        )

        // Cursor at end of `static`, paste the cut chunk back.
        session.editorState.focusedLineIndex = 1
        val staticLine = session.editorState.lines[1]
        staticLine.updateFull(staticLine.text, staticLine.text.length)
        session.controller.paste(clipboardText.toString())

        val movedLineIdx = session.editorState.lines.indexOfFirst { it.text.endsWith("movable") }
        assertTrue("paste must reinsert a 'movable' line", movedLineIdx >= 0)
        assertEquals(
            "pasted line preserves the cut line's noteId — sharedCutLines path " +
                "OR cross-save tryReclaim must recover it; a sentinel here means " +
                "the editor allocated a fresh doc and parked the original",
            movable, session.editorState.lines[movedLineIdx].noteIds.firstOrNull(),
        )

        savePipeline(session)

        // Wire state: movable doc still live and parented to root, NOT
        // parked as cut-delete (which would mean the editor lost the id).
        val rawMovable = readRawNote(movable)!!
        assertEquals(rootId, rawMovable["parentNoteId"])
        assertNull(
            "movable must NOT be parked as cut-delete; got state=${rawMovable["state"]}",
            rawMovable["state"],
        )

        // No fresh doc with 'movable' content was created.
        val descendants = NoteStore.getDescendantIds(rootId)
        val movableImposters = descendants.filter { id ->
            val n = NoteStore.getRawNoteById(id)
            n != null && n.content == "movable" && id != movable && n.state != NoteState.CUT_DELETE
        }
        assertTrue(
            "no duplicate 'movable' doc — found imposters: $movableImposters",
            movableImposters.isEmpty(),
        )

        @Suppress("UNCHECKED_CAST")
        val finalContained = readRawNote(rootId)!!["containedNotes"] as List<String>
        // Pin the actual order — `paste` inserts the cut chunk BEFORE
        // the cursor line (line-insert semantic), so cut-then-paste at
        // end of `static` actually returns `movable` to its original
        // position above `static`. Worth flagging because the user-
        // intent of cut-then-paste-at-end is usually "move to end".
        assertEquals(
            "post-paste order — see comment in paste_createsNewLine_underCorrectParent",
            listOf(movable, static), finalContained,
        )
    }
}
