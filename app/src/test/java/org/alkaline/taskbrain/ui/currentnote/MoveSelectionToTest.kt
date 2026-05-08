package org.alkaline.taskbrain.ui.currentnote

import org.alkaline.taskbrain.data.NoteIdSentinel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * `moveSelectionTo` must preserve every real Firestore doc id across
 * the operation and never leave the editor in a degenerate state.
 */
class MoveSelectionToTest {

    private fun seed(vararg lines: Pair<String, String>): Pair<EditorState, EditorController> {
        val state = EditorState()
        state.initFromNoteLines(
            lines.map { (text, id) -> text to listOf(id) },
            preserveCursor = false,
        )
        return state to EditorController(state)
    }

    @Test
    fun `move preserves the selected line's noteId without allocating sentinels`() {
        val (state, controller) = seed(
            "root" to "REAL_ROOT",
            "alpha" to "REAL_A",
            "beta" to "REAL_B",
            "gamma" to "REAL_C",
        )
        // Select line 1 ("alpha") including its trailing newline.
        val alphaStart = state.getLineStartOffset(1)
        val betaStart = state.getLineStartOffset(2)
        state.setSelection(alphaStart, betaStart)

        val gammaEnd = state.getLineStartOffset(3) + state.lines[3].text.length
        controller.moveSelectionTo(gammaEnd)

        val alphaIdx = state.lines.indexOfFirst { it.text == "alpha" }
        assert(alphaIdx >= 0) { "alpha line lost: ${state.lines.map { it.text }}" }
        assertEquals("REAL_A", state.lines[alphaIdx].noteIds.firstOrNull())

        assertFalse(
            "no line should carry a sentinel after a pure move " +
                "(noteIds: ${state.lines.map { it.noteIds }})",
            state.lines.any { line ->
                line.noteIds.any { NoteIdSentinel.isSentinel(it) }
            },
        )
        assertEquals(
            setOf("root", "alpha", "beta", "gamma"),
            state.lines.map { it.text }.toSet(),
        )
    }

    @Test
    fun `move is no-op when target is inside selection`() {
        val (state, controller) = seed(
            "root" to "REAL_ROOT",
            "alpha" to "REAL_A",
            "beta" to "REAL_B",
        )
        val alphaStart = state.getLineStartOffset(1)
        val betaEnd = state.getLineStartOffset(2) + state.lines[2].text.length
        state.setSelection(alphaStart, betaEnd)
        val originalIds = state.lines.map { it.noteIds.firstOrNull() }

        controller.moveSelectionTo(alphaStart + 2)

        assertEquals(originalIds, state.lines.map { it.noteIds.firstOrNull() })
        assertEquals(listOf("root", "alpha", "beta"), state.lines.map { it.text })
    }

    @Test
    fun `move is no-op without a selection`() {
        val (state, controller) = seed("root" to "R", "alpha" to "A")
        val before = state.lines.map { it.text to it.noteIds.firstOrNull() }
        controller.moveSelectionTo(0)
        val after = state.lines.map { it.text to it.noteIds.firstOrNull() }
        assertEquals(before, after)
    }
}
