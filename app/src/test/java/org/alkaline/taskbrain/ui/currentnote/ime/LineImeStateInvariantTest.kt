package org.alkaline.taskbrain.ui.currentnote.ime

import org.alkaline.taskbrain.ui.currentnote.EditorController
import org.alkaline.taskbrain.ui.currentnote.EditorState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        val lineId = state.lines[lineIndex].tempId
        val ime = LineImeState(lineId, controller)
        ime.syncFromController()
        return Triple(state, controller, ime)
    }

    /**
     * Stage 2 contract: after a structural mutation, the LineImeState
     * stays bound to the LineState it was created for. In the unit
     * test, "the LineImeState we created" is bound to line 0's
     * tempId. After commitText("\n"), the controller splits into
     * [line 0 = "hello world", line 1 = ""]; line 0 still has the
     * same tempId. Subsequent typing on this LineImeState extends
     * line 0, not line 1. (In real Compose, the OS InputConnection
     * follows the focus transition to a new LineImeState bound to
     * line 1 — but that's a Compose-level concern; this test pins
     * the per-LineImeState contract.)
     */
    @Test
    fun `LineImeState stays bound to its line across a split`() {
        val (state, _, ime) = setup("hello world", 0)
        ime.commitText("\n", 1)
        ime.commitText("X", 1)
        ime.commitText("Y", 1)
        ime.commitText("Z", 1)

        assertEquals(2, state.lines.size)
        assertEquals("hello worldXYZ", state.lines[0].text)
        assertEquals("", state.lines[1].text)
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
     * Production-bug surface (Stage 1 fix target). Sending a newline
     * then 23 characters via the SAME LineImeState (because in tests
     * sendStringSync bypasses Compose focus transitions, and in
     * production the IME may race the focus transition) must not
     * cascade into 23 splits. Stage 1's buffer-resync after every
     * IME op closes this: subsequent chars don't carry the stale
     * `\n` forward, so updateLineContent just appends to the line
     * the LineImeState is connected to.
     *
     * Where the chars LAND (line 0 vs line 1) is a separate question
     * answered by Stage 2's lineId binding + Stage 4's operation
     * routing. Here we only assert "no phantom-line storm."
     */
    @Test
    fun `typing 23 chars after newline does not cascade into 23 splits`() {
        val (state, _, ime) = setup("seed line", 0)
        ime.commitText("\n", 1)
        val text = "AfterEnter1778186338914"
        for (ch in text) ime.commitText(ch.toString(), 1)

        // Exactly one split happened (the \n). Without Stage 1's
        // resync, there'd be 24 lines.
        assertEquals("expected exactly seed + 1 new line", 2, state.lines.size)
    }

    /**
     * If the buffer drifts, future syncs must not trip the
     * stamp-sentinel-on-empty guard either. Mirror the production
     * report's count of sentinel ids: must be 0 (only the new line)
     * instead of dozens.
     */
    /**
     * The save-shape view of the same fix: at most 1 sentinel id
     * (the new empty line from Enter) makes it into the tracked
     * shape, regardless of how many characters were typed. Mirrors
     * the production sentinel-storm metric (production save report
     * had `split=23` ids when only one split should have happened).
     */
    @Test
    fun `tracked save shape after newline-then-typing has at most 1 sentinel`() {
        val (state, _, ime) = setup("seed line", 0)
        ime.commitText("\n", 1)
        for (ch in "abcdef") ime.commitText(ch.toString(), 1)

        val tracked = state.toNoteLines()
        // [0] is the parent (real id forced by toNoteLines).
        // The remaining lines should hold at most one sentinel — the
        // new line from the Enter. Pre-Stage-1, this could be 7+
        // (one per character).
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
