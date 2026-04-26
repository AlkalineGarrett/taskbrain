package org.alkaline.taskbrain.ui.currentnote

import org.alkaline.taskbrain.ui.currentnote.undo.UndoManager
import org.alkaline.taskbrain.ui.currentnote.util.LinePrefixes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorControllerTest {

    // ==================== findVisibleNeighbor ====================

    @Test
    fun `findVisibleNeighbor returns fromIndex when already visible`() {
        val state = EditorState()
        val controller = EditorController(state)
        state.updateFromText("a\nb\nc")
        // No hidden indices: any in-bounds index is its own neighbor.
        assertEquals(0, controller.findVisibleNeighbor(0, -1))
        assertEquals(2, controller.findVisibleNeighbor(2, 1))
    }

    @Test
    fun `findVisibleNeighbor walks down past hidden lines`() {
        val state = EditorState()
        val controller = EditorController(state)
        state.updateFromText("a\nh1\nh2\nb")
        controller.hiddenIndices = setOf(1, 2)
        assertEquals(3, controller.findVisibleNeighbor(1, 1))
    }

    @Test
    fun `findVisibleNeighbor walks up past hidden lines`() {
        val state = EditorState()
        val controller = EditorController(state)
        state.updateFromText("a\nh1\nh2\nb")
        controller.hiddenIndices = setOf(1, 2)
        assertEquals(0, controller.findVisibleNeighbor(2, -1))
    }

    @Test
    fun `findVisibleNeighbor returns null when walk runs off the top`() {
        val state = EditorState()
        val controller = EditorController(state)
        state.updateFromText("h0\nh1\nb")
        controller.hiddenIndices = setOf(0, 1)
        assertNull(controller.findVisibleNeighbor(1, -1))
    }

    @Test
    fun `findVisibleNeighbor returns null when walk runs off the bottom`() {
        val state = EditorState()
        val controller = EditorController(state)
        state.updateFromText("a\nh1\nh2")
        controller.hiddenIndices = setOf(1, 2)
        assertNull(controller.findVisibleNeighbor(1, 1))
    }

    @Test
    fun `findVisibleNeighbor returns null when fromIndex is out of bounds`() {
        val state = EditorState()
        val controller = EditorController(state)
        state.updateFromText("a\nb")
        // Mirrors how deleteBackward calls findVisibleNeighbor(-1, -1) at line 0.
        assertNull(controller.findVisibleNeighbor(-1, -1))
        assertNull(controller.findVisibleNeighbor(2, 1))
    }

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

    // ==================== sortCompletedToBottom ====================

    @Test
    fun `sortCompletedToBottom moves checked lines after unchecked`() {
        val state = EditorState()
        val controller = EditorController(state)
        state.updateFromText("Title\n${LinePrefixes.CHECKBOX_CHECKED}done\n${LinePrefixes.CHECKBOX_UNCHECKED}todo")

        val changed = controller.sortCompletedToBottom()

        assertTrue(changed)
        assertEquals("Title", state.lines[0].text)
        assertEquals("${LinePrefixes.CHECKBOX_UNCHECKED}todo", state.lines[1].text)
        assertEquals("${LinePrefixes.CHECKBOX_CHECKED}done", state.lines[2].text)
    }

    @Test
    fun `sortCompletedToBottom returns false when already sorted`() {
        val state = EditorState()
        val controller = EditorController(state)
        state.updateFromText("Title\n${LinePrefixes.CHECKBOX_UNCHECKED}todo\n${LinePrefixes.CHECKBOX_CHECKED}done")

        assertFalse(controller.sortCompletedToBottom())
    }

    @Test
    fun `sortCompletedToBottom permutes noteIds alongside text`() {
        val state = EditorState()
        val controller = EditorController(state)
        state.updateFromText("Title\n${LinePrefixes.CHECKBOX_CHECKED}done\n${LinePrefixes.CHECKBOX_UNCHECKED}todo")
        state.lines[0].noteIds = listOf("title-id")
        state.lines[1].noteIds = listOf("done-id")
        state.lines[2].noteIds = listOf("todo-id")

        controller.sortCompletedToBottom()

        assertEquals(listOf("title-id"), state.lines[0].noteIds)
        assertEquals(listOf("todo-id"), state.lines[1].noteIds)
        assertEquals(listOf("done-id"), state.lines[2].noteIds)
    }

    @Test
    fun `sortCompletedToBottom clears recentlyCheckedIndices`() {
        val state = EditorState()
        val controller = EditorController(state)
        state.updateFromText("Title\n${LinePrefixes.CHECKBOX_UNCHECKED}a")
        state.focusedLineIndex = 1
        controller.toggleCheckbox()
        assertTrue(controller.recentlyCheckedIndices.isNotEmpty())

        controller.sortCompletedToBottom()

        assertTrue(controller.recentlyCheckedIndices.isEmpty())
    }

    // ==================== Source prefix conversion ====================

    @Test
    fun `asterisk space converts to bullet prefix`() {
        val state = EditorState()
        val controller = EditorController(state)
        state.updateFromText("")

        controller.updateLineContent(0, "* ", 2)

        assertEquals("${LinePrefixes.BULLET}", state.lines[0].text)
        assertEquals(LinePrefixes.BULLET, state.lines[0].prefix)
        assertEquals("", state.lines[0].content)
    }

    @Test
    fun `asterisk space converts to bullet with trailing content`() {
        val state = EditorState()
        val controller = EditorController(state)
        state.updateFromText("")

        controller.updateLineContent(0, "* hello", 7)

        assertEquals("${LinePrefixes.BULLET}hello", state.lines[0].text)
        assertEquals("hello", state.lines[0].content)
    }

    @Test
    fun `brackets space converts to unchecked checkbox prefix`() {
        val state = EditorState()
        val controller = EditorController(state)
        state.updateFromText("")

        controller.updateLineContent(0, "[] ", 3)

        assertEquals("${LinePrefixes.CHECKBOX_UNCHECKED}", state.lines[0].text)
        assertEquals(LinePrefixes.CHECKBOX_UNCHECKED, state.lines[0].prefix)
        assertEquals("", state.lines[0].content)
        // cursor: 3 + (-1) = 2, which is end of "☐ "
        assertEquals(2, state.lines[0].cursorPosition)
    }

    @Test
    fun `brackets x space converts to checked checkbox prefix`() {
        val state = EditorState()
        val controller = EditorController(state)
        state.updateFromText("")

        controller.updateLineContent(0, "[x] ", 4)

        assertEquals("${LinePrefixes.CHECKBOX_CHECKED}", state.lines[0].text)
        assertEquals(LinePrefixes.CHECKBOX_CHECKED, state.lines[0].prefix)
        assertEquals("", state.lines[0].content)
        // cursor: 4 + (-2) = 2, which is end of "☑ "
        assertEquals(2, state.lines[0].cursorPosition)
    }

    @Test
    fun `brackets without trailing space does not convert`() {
        val state = EditorState()
        val controller = EditorController(state)
        state.updateFromText("")

        controller.updateLineContent(0, "[]", 2)

        assertEquals("[]", state.lines[0].text)
    }

    @Test
    fun `brackets x without trailing space does not convert`() {
        val state = EditorState()
        val controller = EditorController(state)
        state.updateFromText("")

        controller.updateLineContent(0, "[x]", 3)

        assertEquals("[x]", state.lines[0].text)
    }

    @Test
    fun `does not convert when line already has bullet prefix`() {
        val state = EditorState()
        val controller = EditorController(state)
        state.updateFromText("${LinePrefixes.BULLET}text")

        controller.updateLineContent(0, "* more", 6)

        assertEquals("${LinePrefixes.BULLET}* more", state.lines[0].text)
    }

    @Test
    fun `does not convert when line already has checkbox prefix`() {
        val state = EditorState()
        val controller = EditorController(state)
        state.updateFromText("${LinePrefixes.CHECKBOX_UNCHECKED}text")

        controller.updateLineContent(0, "* more", 6)

        assertEquals("${LinePrefixes.CHECKBOX_UNCHECKED}* more", state.lines[0].text)
    }

    @Test
    fun `converts asterisk space on indented line`() {
        val state = EditorState()
        val controller = EditorController(state)
        state.updateFromText("\t")

        controller.updateLineContent(0, "* hello", 7)

        assertEquals("\t${LinePrefixes.BULLET}hello", state.lines[0].text)
        assertEquals("\t${LinePrefixes.BULLET}", state.lines[0].prefix)
        assertEquals("hello", state.lines[0].content)
    }

    @Test
    fun `converts brackets space on indented line`() {
        val state = EditorState()
        val controller = EditorController(state)
        state.updateFromText("\t")

        controller.updateLineContent(0, "[] ", 3)

        assertEquals("\t${LinePrefixes.CHECKBOX_UNCHECKED}", state.lines[0].text)
        assertEquals("\t${LinePrefixes.CHECKBOX_UNCHECKED}", state.lines[0].prefix)
    }

    @Test
    fun `converts brackets x space on indented line`() {
        val state = EditorState()
        val controller = EditorController(state)
        state.updateFromText("\t")

        controller.updateLineContent(0, "[x] ", 4)

        assertEquals("\t${LinePrefixes.CHECKBOX_CHECKED}", state.lines[0].text)
        assertEquals("\t${LinePrefixes.CHECKBOX_CHECKED}", state.lines[0].prefix)
    }

    // ==================== isContentOffsetInSelection ====================
    // Per-line tap handlers consume the touch before the editor-wide gesture
    // handler can fire, so they need their own selection check to avoid clearing
    // the selection on a tap that's meant to toggle the menu.

    @Test
    fun `isContentOffsetInSelection returns false when no selection`() {
        val state = EditorState()
        val controller = EditorController(state)
        state.updateFromText("hello world")

        assertFalse(controller.isContentOffsetInSelection(0, 5))
    }

    @Test
    fun `isContentOffsetInSelection returns true for tap inside selection on same line`() {
        val state = EditorState()
        val controller = EditorController(state)
        state.updateFromText("hello world")
        state.setSelection(2, 8)

        assertTrue(controller.isContentOffsetInSelection(0, 5))
    }

    @Test
    fun `isContentOffsetInSelection returns false for tap outside selection`() {
        val state = EditorState()
        val controller = EditorController(state)
        state.updateFromText("hello world")
        state.setSelection(2, 5)

        assertFalse(controller.isContentOffsetInSelection(0, 8))
    }

    @Test
    fun `isContentOffsetInSelection returns true at selection boundary`() {
        // Match the editor-wide gesture handler's inclusive range so both paths
        // route the same boundary tap to the menu toggle.
        val state = EditorState()
        val controller = EditorController(state)
        state.updateFromText("hello world")
        state.setSelection(2, 5)

        assertTrue(controller.isContentOffsetInSelection(0, 2))
        assertTrue(controller.isContentOffsetInSelection(0, 5))
    }

    @Test
    fun `isContentOffsetInSelection accounts for prefix when computing global offset`() {
        // Bullet prefix length 2 — content offset 3 maps to global offset 5,
        // which sits inside selection [4, 7].
        val state = EditorState()
        val controller = EditorController(state)
        state.updateFromText("${LinePrefixes.BULLET}hello")
        state.setSelection(4, 7)

        assertTrue(controller.isContentOffsetInSelection(0, 3))
        assertFalse(controller.isContentOffsetInSelection(0, 0))
    }

    @Test
    fun `isContentOffsetInSelection works on a later line`() {
        val state = EditorState()
        val controller = EditorController(state)
        state.updateFromText("first\nsecond\nthird")
        // "first\n" = 6 chars; line 1 starts at offset 6. Select [8, 11] inside "second".
        state.setSelection(8, 11)

        assertTrue(controller.isContentOffsetInSelection(1, 3))
        assertFalse(controller.isContentOffsetInSelection(1, 0))
        assertFalse(controller.isContentOffsetInSelection(2, 0))
    }
}
