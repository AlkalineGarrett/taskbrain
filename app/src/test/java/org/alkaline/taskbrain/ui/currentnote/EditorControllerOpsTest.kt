package org.alkaline.taskbrain.ui.currentnote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the operation-based controller API added in Stage 3 of the
 * IME architectural refactor. These ops give callers (LineImeState
 * being the primary one) a way to express intent — "replace this
 * range," "delete N chars," "move cursor here" — instead of sending
 * a full new content string and forcing the controller to diff it.
 *
 * The diff-based path was the sentinel-storm bug surface (a stale
 * buffer reused a `\n` the controller had already consumed; the
 * controller had no way to know the `\n` was already-handled).
 */
class EditorControllerOpsTest {

    private fun seedSingle(text: String, noteId: String = "REAL"): Triple<EditorState, EditorController, String> {
        val state = EditorState()
        state.initFromNoteLines(listOf(text to listOf(noteId)), preserveCursor = false)
        val controller = EditorController(state)
        return Triple(state, controller, state.lines[0].tempId)
    }

    // ==================== replaceRange ====================

    @Test
    fun `replaceRange inserts text in the middle of a line`() {
        val (state, controller, lineId) = seedSingle("hello world")
        val cursor = controller.replaceRange(lineId, 5..4, " BIG")  // empty range = insert
        assertEquals("hello BIG world", state.lines[0].text)
        assertEquals(lineId, cursor?.lineId)
        assertEquals(9, cursor?.contentOffset)
    }

    @Test
    fun `replaceRange replaces a span`() {
        val (state, controller, lineId) = seedSingle("hello world")
        controller.replaceRange(lineId, 6..10, "Earth")
        assertEquals("hello Earth", state.lines[0].text)
    }

    @Test
    fun `replaceRange with newline splits the line`() {
        val (state, controller, lineId) = seedSingle("hello world")
        // Insert "\n" at end (range start=11, empty, end=10 means empty range at offset 11).
        // We test inserting a \n in the middle to pin the split intent.
        val pos = controller.replaceRange(lineId, 5..4, "\n")
        assertEquals(2, state.lines.size)
        assertEquals("hello", state.lines[0].text)
        assertEquals(" world", state.lines[1].text)
        // Cursor lands on the post-newline line.
        assertNotNull(pos)
        assertEquals(state.lines[1].tempId, pos!!.lineId)
        assertEquals(0, pos.contentOffset)
    }

    @Test
    fun `replaceRange with multi-line text creates multiple new lines`() {
        val (state, controller, lineId) = seedSingle("AZ")
        controller.replaceRange(lineId, 1..0, "B\nC\nD")
        assertEquals(3, state.lines.size)
        assertEquals("AB", state.lines[0].text)
        assertEquals("C", state.lines[1].text)
        assertEquals("DZ", state.lines[2].text)
    }

    @Test
    fun `replaceRange returns null when lineId is gone`() {
        val (_, controller, lineId) = seedSingle("hello")
        controller.replaceRange(lineId, 0..4, "")  // doesn't remove the line — empty content stays
        // For "removed" simulation, manipulate state directly: controller has no public delete-line method.
        // Use a fake id instead.
        val result = controller.replaceRange("FAKE_ID_NOT_PRESENT", 0..0, "X")
        assertNull(result)
    }

    // ==================== deleteAroundCursor ====================

    @Test
    fun `deleteAroundCursor removes chars before the cursor`() {
        val (state, controller, lineId) = seedSingle("hello world")
        controller.setCursorByLineId(lineId, 11)  // end
        val pos = controller.deleteAroundCursor(lineId, before = 5, after = 0)
        assertEquals("hello ", state.lines[0].text)
        assertEquals(6, pos?.contentOffset)
    }

    @Test
    fun `deleteAroundCursor removes chars after the cursor`() {
        val (state, controller, lineId) = seedSingle("hello world")
        controller.setCursorByLineId(lineId, 6)
        val pos = controller.deleteAroundCursor(lineId, before = 0, after = 5)
        assertEquals("hello ", state.lines[0].text)
        assertEquals(6, pos?.contentOffset)
    }

    @Test
    fun `deleteAroundCursor at start of line crosses boundary upward`() {
        val state = EditorState()
        state.initFromNoteLines(
            listOf("first" to listOf("R1"), "second" to listOf("R2")),
            preserveCursor = false,
        )
        val controller = EditorController(state)
        val secondLineId = state.lines[1].tempId
        controller.setCursorByLineId(secondLineId, 0)  // at start of "second"

        controller.deleteAroundCursor(secondLineId, before = 1, after = 0)

        // Lines merged: "firstsecond" on the surviving line.
        assertEquals(1, state.lines.size)
        assertEquals("firstsecond", state.lines[0].text)
    }

    @Test
    fun `deleteAroundCursor returns null when lineId is gone`() {
        val (_, controller, _) = seedSingle("x")
        val result = controller.deleteAroundCursor("FAKE_ID", 1, 0)
        assertNull(result)
    }

    // ==================== setCursorByLineId ====================

    @Test
    fun `setCursorByLineId moves cursor on the named line`() {
        val state = EditorState()
        state.initFromNoteLines(
            listOf("first" to listOf("R1"), "second" to listOf("R2")),
            preserveCursor = false,
        )
        val controller = EditorController(state)
        val secondLineId = state.lines[1].tempId
        val pos = controller.setCursorByLineId(secondLineId, 3)
        assertEquals(1, state.focusedLineIndex)
        assertEquals(secondLineId, pos?.lineId)
        assertEquals(3, pos?.contentOffset)
    }

    // ==================== indexOf integration ====================

    @Test
    fun `lineId resolution is stable across structural mutations above the line`() {
        val state = EditorState()
        state.initFromNoteLines(
            listOf(
                "root" to listOf("R0"),
                "first" to listOf("R1"),
                "tracked" to listOf("R2"),
            ),
            preserveCursor = false,
        )
        val controller = EditorController(state)
        val trackedId = state.lines[2].tempId
        assertEquals(2, controller.indexOf(trackedId))

        // Insert a new line above by splitting line 0 at end.
        val rootId = state.lines[0].tempId
        controller.replaceRange(rootId, 4..3, "\n")
        // Now state.lines = ["root", "", "first", "tracked"]
        assertEquals(4, state.lines.size)
        // The trackedId is now at index 3.
        assertEquals(3, controller.indexOf(trackedId))
        // Operations on it still hit the right line.
        controller.replaceRange(trackedId, 7..6, "!")
        assertEquals("tracked!", state.lines[3].text)
    }
}
