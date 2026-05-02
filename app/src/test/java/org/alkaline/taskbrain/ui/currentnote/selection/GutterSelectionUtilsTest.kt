package org.alkaline.taskbrain.ui.currentnote.selection

import org.alkaline.taskbrain.ui.currentnote.initFromText
import org.alkaline.taskbrain.ui.currentnote.EditorState
import org.alkaline.taskbrain.ui.currentnote.rendering.isLineInSelection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GutterSelectionUtilsTest {

    // ==================== GutterSelectionState.selectLine ====================

    @Test
    fun `selectLine selects first line`() {
        val state = EditorState()
        state.initFromText("line1\nline2\nline3")
        val gutterState = GutterSelectionState()

        gutterState.selectLine(0, state)

        assertTrue(state.hasSelection)
        assertEquals(0, state.selection.min)
        assertEquals(6, state.selection.max) // "line1" + newline
    }

    @Test
    fun `selectLine selects middle line`() {
        val state = EditorState()
        state.initFromText("line1\nline2\nline3")
        val gutterState = GutterSelectionState()

        gutterState.selectLine(1, state)

        assertTrue(state.hasSelection)
        assertEquals(6, state.selection.min) // Start of "line2"
        assertEquals(12, state.selection.max) // "line2" + newline
    }

    @Test
    fun `selectLine selects last line without newline`() {
        val state = EditorState()
        state.initFromText("line1\nline2\nline3")
        val gutterState = GutterSelectionState()

        gutterState.selectLine(2, state)

        assertTrue(state.hasSelection)
        assertEquals(12, state.selection.min) // Start of "line3"
        assertEquals(17, state.selection.max) // End of "line3"
    }

    @Test
    fun `selectLine does nothing for invalid index`() {
        val state = EditorState()
        state.initFromText("line1\nline2")
        val gutterState = GutterSelectionState()

        gutterState.selectLine(5, state) // Invalid index

        assertFalse(state.hasSelection)
    }

    @Test
    fun `selectLine does nothing for negative index`() {
        val state = EditorState()
        state.initFromText("line1\nline2")
        val gutterState = GutterSelectionState()

        gutterState.selectLine(-1, state)

        assertFalse(state.hasSelection)
    }

    @Test
    fun `selectLine handles empty line`() {
        val state = EditorState()
        state.initFromText("line1\n\nline3")
        val gutterState = GutterSelectionState()

        // Selecting empty line (index 1)
        gutterState.selectLine(1, state)

        // Empty line has no content, but newline should be selected
        assertTrue(state.hasSelection)
        assertEquals(6, state.selection.min)
        assertEquals(7, state.selection.max) // Just the newline
    }

    // ==================== GutterSelectionState.extendSelectionToLine ====================

    @Test
    fun `extendSelectionToLine extends downward`() {
        val state = EditorState()
        state.initFromText("line1\nline2\nline3")
        val gutterState = GutterSelectionState()

        gutterState.startDrag(0, state) // Start at line 0
        gutterState.extendSelectionToLine(2, state) // Extend to line 2

        assertTrue(state.hasSelection)
        assertEquals(0, state.selection.min) // Start of line 0
        assertEquals(17, state.selection.max) // End of line 2
    }

    @Test
    fun `extendSelectionToLine extends upward`() {
        val state = EditorState()
        state.initFromText("line1\nline2\nline3")
        val gutterState = GutterSelectionState()

        gutterState.startDrag(2, state) // Start at line 2
        gutterState.extendSelectionToLine(0, state) // Extend to line 0

        assertTrue(state.hasSelection)
        assertEquals(0, state.selection.min) // Start of line 0
        assertEquals(17, state.selection.max) // End of line 2
    }

    @Test
    fun `extendSelectionToLine does nothing without drag start`() {
        val state = EditorState()
        state.initFromText("line1\nline2\nline3")
        val gutterState = GutterSelectionState()

        gutterState.extendSelectionToLine(1, state) // No drag started

        assertFalse(state.hasSelection)
    }

    @Test
    fun `extendSelectionToLine does nothing for invalid line index`() {
        val state = EditorState()
        state.initFromText("line1\nline2")
        val gutterState = GutterSelectionState()

        gutterState.startDrag(0, state)
        gutterState.extendSelectionToLine(10, state) // Invalid index

        // Selection should remain at line 0 only
        assertEquals(0, state.selection.min)
        assertEquals(6, state.selection.max)
    }

    // ==================== GutterSelectionState.startDrag/endDrag ====================

    @Test
    fun `startDrag sets dragStartLine and selects line`() {
        val state = EditorState()
        state.initFromText("line1\nline2")
        val gutterState = GutterSelectionState()

        gutterState.startDrag(1, state)

        assertEquals(1, gutterState.dragStartLine)
        assertTrue(state.hasSelection)
    }

    @Test
    fun `endDrag resets dragStartLine`() {
        val state = EditorState()
        state.initFromText("line1\nline2")
        val gutterState = GutterSelectionState()

        gutterState.startDrag(0, state)
        assertEquals(0, gutterState.dragStartLine)

        gutterState.endDrag()
        assertEquals(-1, gutterState.dragStartLine)
    }

    // ==================== isLineInSelection ====================

    @Test
    fun `isLineInSelection returns false when no selection`() {
        val state = EditorState()
        state.initFromText("line1\nline2\nline3")

        assertFalse(isLineInSelection(0, state))
        assertFalse(isLineInSelection(1, state))
        assertFalse(isLineInSelection(2, state))
    }

    @Test
    fun `isLineInSelection returns true for fully selected line`() {
        val state = EditorState()
        state.initFromText("line1\nline2\nline3")
        state.setSelection(0, 17) // Select all

        assertTrue(isLineInSelection(0, state))
        assertTrue(isLineInSelection(1, state))
        assertTrue(isLineInSelection(2, state))
    }

    @Test
    fun `isLineInSelection returns true for partially selected line`() {
        val state = EditorState()
        state.initFromText("line1\nline2\nline3")
        state.setSelection(2, 8) // "ne1\nli" - partial selection

        assertTrue(isLineInSelection(0, state)) // "ne1" selected
        assertTrue(isLineInSelection(1, state)) // "li" selected
        assertFalse(isLineInSelection(2, state)) // Not selected
    }

    @Test
    fun `isLineInSelection returns false for unselected line`() {
        val state = EditorState()
        state.initFromText("line1\nline2\nline3")
        state.setSelection(0, 5) // Only "line1"

        assertTrue(isLineInSelection(0, state))
        assertFalse(isLineInSelection(1, state))
        assertFalse(isLineInSelection(2, state))
    }

    @Test
    fun `isLineInSelection handles empty line within selection`() {
        val state = EditorState()
        state.initFromText("line1\n\nline3")
        state.setSelection(0, 13) // Select all including empty line

        assertTrue(isLineInSelection(0, state))
        assertTrue(isLineInSelection(1, state)) // Empty line
        assertTrue(isLineInSelection(2, state))
    }

    @Test
    fun `isLineInSelection handles empty line at selection boundary`() {
        val state = EditorState()
        state.initFromText("line1\n\nline3")
        // Selection from line1 to just past the empty line
        state.setSelection(0, 7) // "line1\n" + newline after empty line

        assertTrue(isLineInSelection(0, state))
        assertTrue(isLineInSelection(1, state)) // Empty line at position 6
        assertFalse(isLineInSelection(2, state))
    }

    @Test
    fun `isLineInSelection returns true when selection touches line end`() {
        val state = EditorState()
        state.initFromText("line1\nline2")
        state.setSelection(4, 8) // "1\nli"

        assertTrue(isLineInSelection(0, state))
        assertTrue(isLineInSelection(1, state))
    }
}
