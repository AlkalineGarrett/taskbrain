package org.alkaline.taskbrain.ui.currentnote.ime

import org.alkaline.taskbrain.ui.currentnote.EditorController
import org.alkaline.taskbrain.ui.currentnote.EditorState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/**
 * Pin: the IME-buffer-vs-controller divergence bug class.
 *
 * The phantom-lines bug investigated 2026-05-07 had this shape: after
 * [LineImeState.commitText] inserts `"\n"`, the controller splits the
 * line — but the IME's [EditingBuffer] still holds `"...prefix\n"`.
 * Each subsequent `commitText("X")` extends the buffer to
 * `"...prefix\nX"`, syncs it to the controller via
 * [EditorController.updateLineContent], and the `\n` triggers ANOTHER
 * split. One phantom line per character.
 *
 * These tests pin the invariant the architectural refactor must
 * uphold: **after any LineImeState IME operation finishes, the
 * buffer's text equals the controller's line content for that
 * lineIndex**.
 *
 * Currently failing tests are marked with the stage that fixes them.
 *
 * Bug cited: 2026-05-07 production save tripped content-drop guard
 * with `sentinel origins={typed=2, split=23}`.
 */
class LineImeStateInvariantTest {

    private fun setup(initialText: String, lineIndex: Int = 0): Triple<EditorState, EditorController, LineImeState> {
        val state = EditorState()
        state.initFromNoteLines(
            listOf(initialText to listOf("PARENT_REAL")),
            preserveCursor = false,
        )
        val controller = EditorController(state)
        val ime = LineImeState(lineIndex, controller)
        ime.syncFromController()
        return Triple(state, controller, ime)
    }

    /**
     * Stage 2 fix target. After a split, subsequent commitText calls
     * arrive at the new line's LineImeState (currently they keep
     * arriving at lineIndex=0 because LineImeState binds to lineIndex,
     * not lineId; Stage 2 switches the binding).
     */
    @Ignore("unblocked by Stage 2 (LineImeState bound to lineId)")
    @Test
    fun `commitText newline then commitText X produces exactly one split`() {
        val (state, _, ime) = setup("hello world", 0)
        // Cursor at end of "hello world".
        ime.commitText("\n", 1)
        ime.commitText("X", 1)
        ime.commitText("Y", 1)
        ime.commitText("Z", 1)

        // Expected:
        //   line 0 = "hello world"
        //   line 1 = "XYZ"
        // Today (with the bug) the editor ends up with phantom lines
        // because each commitText after the newline re-splits.
        assertEquals(
            "lines should have exactly the parent + one new line",
            2, state.lines.size,
        )
        assertEquals("hello world", state.lines[0].text)
        assertEquals("XYZ", state.lines[1].text)
    }

    /**
     * Stage 1 invariant: after every IME call returns, the buffer's
     * text must equal what the controller has for [lineIndex]. If the
     * controller split the line, the buffer should now hold only the
     * before-newline portion (which is still at [lineIndex]).
     */
    @Test
    fun `buffer text equals controller line content after commitText newline`() {
        val (_, controller, ime) = setup("abc", 0)
        ime.commitText("\n", 1)
        // After the split, controller's line 0 contains "abc" (before-newline).
        // Invariant: buffer.text should also be "abc".
        assertEquals(
            "buffer drifted from controller's line[0]",
            controller.getLineContent(0), ime.text,
        )
    }

    /**
     * Stage 1 invariant: same property for every IME call shape we use.
     */
    @Test
    fun `buffer text equals controller line content after each IME op`() {
        val (_, controller, ime) = setup("hello", 0)

        ime.commitText("X", 1)
        assertEquals(controller.getLineContent(0), ime.text)

        ime.setComposingText("yz", 1)
        assertEquals(controller.getLineContent(0), ime.text)

        ime.finishComposingText()
        assertEquals(controller.getLineContent(0), ime.text)

        ime.deleteSurroundingText(1, 0)
        assertEquals(controller.getLineContent(0), ime.text)
    }

    /**
     * Stage 2 fix target. The classic phantom-lines reproduction:
     * each char after a newline is committed via the same lineIndex=0
     * LineImeState today. Stage 2's lineId binding routes them to the
     * post-split line's own LineImeState.
     */
    @Ignore("unblocked by Stage 2 (LineImeState bound to lineId)")
    @Test
    fun `typing 23 chars after newline produces 1 new line not 23`() {
        val (state, _, ime) = setup("seed line", 0)
        ime.commitText("\n", 1)
        val text = "AfterEnter1778186338914"
        for (ch in text) ime.commitText(ch.toString(), 1)

        assertEquals("expected exactly seed + 1 new line", 2, state.lines.size)
        assertEquals("seed line", state.lines[0].text)
        assertEquals(text, state.lines[1].text)
    }

    /**
     * If the buffer drifts, future syncs must not trip the
     * stamp-sentinel-on-empty guard either. Mirror the production
     * report's count of sentinel ids: must be 0 (only the new line)
     * instead of dozens.
     */
    @Ignore("unblocked by Stage 2 (LineImeState bound to lineId)")
    @Test
    fun `tracked save shape after newline-then-typing has 1 sentinel`() {
        val (state, _, ime) = setup("seed line", 0)
        ime.commitText("\n", 1)
        for (ch in "abcdef") ime.commitText(ch.toString(), 1)

        val tracked = state.toNoteLines()
        // [0] is the parent (real id forced by toNoteLines).
        // [1] should be a single sentinel for the new line. Anything
        // more is a phantom.
        assertEquals("expected 2 tracked lines", 2, tracked.size)
        val sentinelCount = tracked.count {
            it.noteId.startsWith("@")
        }
        assertEquals("expected exactly 1 sentinel id (the new line)",
            1, sentinelCount)
    }

    /**
     * Boundary case: composition that spans across a newline insertion.
     * IME may setComposingText, then commit with an embedded newline.
     * Buffer should reconcile with whichever lines the controller now
     * has.
     */
    @Test
    fun `setComposingText with newline-bearing commit reconciles`() {
        val (state, _, ime) = setup("hello", 0)
        ime.setComposingText("foo", 1)
        // Compose state has "hellofoo" — composition over "foo".
        assertTrue("buffer holds composed text", ime.text.contains("foo"))

        // Commit text containing a newline — the IME might do this on
        // autocorrect with phrase replacements.
        ime.commitText("bar\nbaz", 1)

        // After the dust settles, line 0 holds "hellobar" and a new
        // line holds "baz". Buffer for line 0 reflects line 0's text.
        assertEquals(2, state.lines.size)
        assertTrue(state.lines[0].text.endsWith("bar"))
        assertEquals("baz", state.lines[1].text)
    }
}
