package org.alkaline.taskbrain.ui.currentnote

import org.alkaline.taskbrain.ui.currentnote.undo.UndoManager
import org.alkaline.taskbrain.ui.currentnote.util.LinePrefixes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorControllerTest {

    // ==================== deleteBackward skips hidden lines ====================

    @Test
    fun `deleteBackward skips hidden lines when merging`() {
        val state = EditorState()
        val controller = EditorController(state)
        state.updateFromText("a\nhidden1\nhidden2\nb")
        controller.hiddenIndices = setOf(1, 2)
        // Focus line 3 ("b") with cursor at position 0 (start of line)
        state.focusedLineIndex = 3
        state.lines[3].updateFull("b", 0)

        controller.deleteBackward(3)

        assertEquals("ab", state.lines[0].text)
    }

    // ==================== deleteForward skips hidden lines ====================

    @Test
    fun `deleteForward skips hidden lines when merging`() {
        val state = EditorState()
        val controller = EditorController(state)
        state.updateFromText("a\nhidden1\nhidden2\nb")
        controller.hiddenIndices = setOf(1, 2)
        // Focus line 0 ("a") with cursor at end
        state.focusedLineIndex = 0
        state.lines[0].updateFull("a", 1)

        controller.deleteForward(0)

        assertEquals("ab", state.lines[0].text)
    }

    // ==================== Undo boundary after paste ====================

    @Test
    fun `paste commits undo state so subsequent operations are separate`() {
        val state = EditorState()
        val undoManager = UndoManager()
        val controller = EditorController(state, undoManager)
        state.updateFromText("hello")
        // Set up initial undo baseline
        undoManager.beginEditingLine(state, 0)

        // Paste some text
        controller.paste("world")

        // After paste, undo should be available (paste created an undo entry)
        assertTrue(undoManager.canUndo)

        // Perform another operation (e.g., toggle bullet)
        controller.toggleBullet()

        // Undo should undo the bullet toggle (not the paste)
        val snapshot = controller.undo()
        // After undoing bullet, text should be post-paste but without bullet
        assertEquals("helloworld", state.text)
    }

    // ==================== toggleCheckbox tracks recentlyCheckedIndices for selection ====================

    @Test
    fun `toggleCheckbox tracks single line`() {
        val state = EditorState()
        val controller = EditorController(state)
        state.updateFromText("${LinePrefixes.CHECKBOX_UNCHECKED}a\n${LinePrefixes.CHECKBOX_UNCHECKED}b")
        state.focusedLineIndex = 0

        controller.toggleCheckbox()

        assertTrue(controller.recentlyCheckedIndices.contains(0))
        assertFalse(controller.recentlyCheckedIndices.contains(1))
    }

    @Test
    fun `toggleCheckbox tracks all selected lines`() {
        val state = EditorState()
        val controller = EditorController(state)
        state.updateFromText("${LinePrefixes.CHECKBOX_UNCHECKED}a\n${LinePrefixes.CHECKBOX_UNCHECKED}b\n${LinePrefixes.CHECKBOX_UNCHECKED}c")
        state.setSelection(0, state.text.length)

        controller.toggleCheckbox()

        assertTrue(controller.recentlyCheckedIndices.contains(0))
        assertTrue(controller.recentlyCheckedIndices.contains(1))
        assertTrue(controller.recentlyCheckedIndices.contains(2))
    }

    @Test
    fun `toggleCheckbox does not track lines that were not unchecked`() {
        val state = EditorState()
        val controller = EditorController(state)
        state.updateFromText("${LinePrefixes.CHECKBOX_UNCHECKED}a\nplain\n${LinePrefixes.CHECKBOX_UNCHECKED}c")
        state.setSelection(0, state.text.length)

        controller.toggleCheckbox()

        assertTrue(controller.recentlyCheckedIndices.contains(0))
        assertFalse(controller.recentlyCheckedIndices.contains(1))
        assertTrue(controller.recentlyCheckedIndices.contains(2))
    }
}
