package org.alkaline.taskbrain.ui.currentnote

import org.alkaline.taskbrain.ui.currentnote.selection.EditorSelection
import org.alkaline.taskbrain.ui.currentnote.util.ClipboardParser
import org.alkaline.taskbrain.ui.currentnote.util.PasteHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for noteId propagation through editor operations.
 * Phase 2 of the line identity redesign.
 */
class NoteIdPropagationTest {

    // ==================== LineState noteIds ====================

    @Test
    fun `LineState preserves noteIds from constructor`() {
        val line = LineState("Hello", noteIds = listOf("note1"))
        assertEquals(listOf("note1"), line.noteIds)
    }

    @Test
    fun `LineState defaults to empty noteIds`() {
        val line = LineState("Hello")
        assertTrue(line.noteIds.isEmpty())
    }

    // ==================== EditorState.initFromNoteLines ====================

    @Test
    fun `initFromNoteLines sets noteIds on each line`() {
        val state = EditorState()
        state.initFromNoteLines(listOf(
            "First line" to listOf("note1"),
            "Second line" to listOf("note2"),
            "" to emptyList()
        ))

        assertEquals(3, state.lines.size)
        assertEquals(listOf("note1"), state.lines[0].noteIds)
        assertEquals(listOf("note2"), state.lines[1].noteIds)
        assertTrue(state.lines[2].noteIds.isEmpty())
    }

    // ==================== updateFromText preserves noteIds ====================

    @Test
    fun `updateFromText preserves noteIds via exact content match`() {
        val state = EditorState()
        state.initFromNoteLines(listOf(
            "Line A" to listOf("noteA"),
            "Line B" to listOf("noteB"),
            "Line C" to listOf("noteC")
        ))

        // Reorder: B, A, C
        state.updateFromText("Line B\nLine A\nLine C")

        assertEquals(listOf("noteB"), state.lines[0].noteIds)
        assertEquals(listOf("noteA"), state.lines[1].noteIds)
        assertEquals(listOf("noteC"), state.lines[2].noteIds)
    }

    @Test
    fun `updateFromText uses positional fallback for modified lines`() {
        val state = EditorState()
        state.initFromNoteLines(listOf(
            "Original" to listOf("note1"),
            "Line B" to listOf("note2")
        ))

        // Modify first line, keep second
        state.updateFromText("Modified\nLine B")

        assertEquals(listOf("note1"), state.lines[0].noteIds) // positional fallback
        assertEquals(listOf("note2"), state.lines[1].noteIds) // exact match
    }

    @Test
    fun `updateFromText gives empty noteIds to new lines`() {
        val state = EditorState()
        state.initFromNoteLines(listOf(
            "Line A" to listOf("noteA")
        ))

        state.updateFromText("Line A\nNew line\nAnother new")

        assertEquals(listOf("noteA"), state.lines[0].noteIds)
        assertTrue(state.lines[1].noteIds.isEmpty())
        assertTrue(state.lines[2].noteIds.isEmpty())
    }

    // ==================== Split line propagation ====================

    @Test
    fun `splitLine gives both fragments the same noteIds`() {
        val state = EditorState()
        val controller = EditorController(state)
        state.initFromNoteLines(listOf(
            "Hello World" to listOf("note1"),
            "" to emptyList()
        ))
        state.focusedLineIndex = 0
        state.lines[0].updateFull("Hello World", 5) // cursor after "Hello"

        controller.splitLine(0)

        assertEquals("Hello", state.lines[0].text)
        assertEquals(" World", state.lines[1].text)
        assertEquals(listOf("note1"), state.lines[0].noteIds)
        assertEquals(listOf("note1"), state.lines[1].noteIds)
    }

    // ==================== Merge line propagation ====================

    @Test
    fun `mergeToPreviousLine combines noteIds, longer content first`() {
        val state = EditorState()
        val controller = EditorController(state)
        state.initFromNoteLines(listOf(
            "Short" to listOf("noteA"),
            "Much longer content" to listOf("noteB"),
            "" to emptyList()
        ))
        state.focusedLineIndex = 1
        state.lines[1].updateFull("Much longer content", 0) // cursor at start

        controller.mergeToPreviousLine(1)

        assertEquals("ShortMuch longer content", state.lines[0].text)
        // noteB had longer content, so it should be first (primary)
        assertEquals(listOf("noteB", "noteA"), state.lines[0].noteIds)
    }

    @Test
    fun `mergeNextLine combines noteIds, longer content first`() {
        val state = EditorState()
        val controller = EditorController(state)
        state.initFromNoteLines(listOf(
            "Long first line here" to listOf("noteA"),
            "Short" to listOf("noteB"),
            "" to emptyList()
        ))
        state.focusedLineIndex = 0
        state.lines[0].updateFull("Long first line here", 20) // cursor at end

        controller.mergeNextLine(0)

        assertEquals("Long first line hereShort", state.lines[0].text)
        // noteA had longer content, so it should be first (primary)
        assertEquals(listOf("noteA", "noteB"), state.lines[0].noteIds)
    }

    // ==================== Move lines propagation ====================

    @Test
    fun `moveLinesInternal preserves noteIds when moving down`() {
        val state = EditorState()
        state.initFromNoteLines(listOf(
            "Line A" to listOf("noteA"),
            "Line B" to listOf("noteB"),
            "Line C" to listOf("noteC")
        ))

        // Move line 0 to after line 1 (targetIndex=2)
        state.moveLinesInternal(0..0, 2)

        assertEquals("Line B", state.lines[0].text)
        assertEquals("Line A", state.lines[1].text)
        assertEquals("Line C", state.lines[2].text)
        assertEquals(listOf("noteB"), state.lines[0].noteIds)
        assertEquals(listOf("noteA"), state.lines[1].noteIds)
        assertEquals(listOf("noteC"), state.lines[2].noteIds)
    }

    @Test
    fun `moveLinesInternal preserves noteIds when moving up`() {
        val state = EditorState()
        state.initFromNoteLines(listOf(
            "Line A" to listOf("noteA"),
            "Line B" to listOf("noteB"),
            "Line C" to listOf("noteC")
        ))

        // Move line 2 to position 0
        state.moveLinesInternal(2..2, 0)

        assertEquals("Line C", state.lines[0].text)
        assertEquals("Line A", state.lines[1].text)
        assertEquals("Line B", state.lines[2].text)
        assertEquals(listOf("noteC"), state.lines[0].noteIds)
        assertEquals(listOf("noteA"), state.lines[1].noteIds)
        assertEquals(listOf("noteB"), state.lines[2].noteIds)
    }

    // ==================== Full-line paste preserves noteIds ====================

    @Test
    fun `full-line paste inherits noteIds from deleted lines via content match`() {
        val lines = listOf(
            LineState("Line A", noteIds = listOf("noteA")),
            LineState("Line B", noteIds = listOf("noteB")),
            LineState("Line C", noteIds = listOf("noteC")),
        )
        // Full-line select lines A and B
        val selection = EditorSelection(0, "Line A\nLine B\n".length)
        val parsed = ClipboardParser.parseInternal("Line A\nLine B")
        val result = PasteHandler.execute(lines, 0, selection, parsed)

        assertEquals(listOf("noteA"), result.lines[0].noteIds)
        assertEquals(listOf("noteB"), result.lines[1].noteIds)
        assertEquals(listOf("noteC"), result.lines[2].noteIds)
    }

    @Test
    fun `full-line paste uses positional fallback for modified content`() {
        val lines = listOf(
            LineState("Original A", noteIds = listOf("noteA")),
            LineState("Original B", noteIds = listOf("noteB")),
            LineState("Line C", noteIds = listOf("noteC")),
        )
        val selection = EditorSelection(0, "Original A\nOriginal B\n".length)
        val parsed = ClipboardParser.parseInternal("Modified A\nModified B")
        val result = PasteHandler.execute(lines, 0, selection, parsed)

        assertEquals(listOf("noteA"), result.lines[0].noteIds)
        assertEquals(listOf("noteB"), result.lines[1].noteIds)
        assertEquals(listOf("noteC"), result.lines[2].noteIds)
    }

    @Test
    fun `full-line paste gives empty noteIds to extra pasted lines`() {
        val lines = listOf(
            LineState("Line A", noteIds = listOf("noteA")),
            LineState("Line B", noteIds = listOf("noteB")),
        )
        // Select only Line A
        val selection = EditorSelection(0, "Line A\n".length)
        val parsed = ClipboardParser.parseInternal("New 1\nNew 2")
        val result = PasteHandler.execute(lines, 0, selection, parsed)

        assertEquals(listOf("noteA"), result.lines[0].noteIds)
        assertTrue(result.lines[1].noteIds.isEmpty())
        assertEquals(listOf("noteB"), result.lines[2].noteIds)
    }

    // ==================== Cut-paste recovers noteIds ====================

    @Test
    fun `cut then paste to different location recovers noteIds`() {
        // Simulates: cut Line B (with noteB) → paste after Line C
        val lines = listOf(
            LineState("Line A", noteIds = listOf("noteA")),
            LineState("Line C", noteIds = listOf("noteC")),
            LineState(""),
        )

        val parsed = ClipboardParser.parseInternal("Line B")
        val cutLines = listOf(LineState("Line B", noteIds = listOf("noteB")))
        val result = PasteHandler.execute(lines, 2, EditorSelection.None, parsed, cutLines)

        assertEquals(listOf("noteA"), result.lines[0].noteIds)
        assertEquals(listOf("noteC"), result.lines[1].noteIds)
        val pastedLine = result.lines.first { it.text.contains("Line B") }
        assertEquals(listOf("noteB"), pastedLine.noteIds)
    }

    @Test
    fun `cut then paste with indent change recovers noteIds`() {
        val lines = listOf(
            LineState("Line A", noteIds = listOf("noteA")),
            LineState(""),
        )

        val parsed = ClipboardParser.parseInternal("\t- Item 1")
        val cutLines = listOf(LineState("\t\t- Item 1", noteIds = listOf("noteItem")))
        val result = PasteHandler.execute(lines, 1, EditorSelection.None, parsed, cutLines)

        val pastedLine = result.lines.first { it.text.contains("Item 1") }
        assertEquals(listOf("noteItem"), pastedLine.noteIds)
    }

    // ==================== Undo/Redo preserves noteIds ====================

    @Test
    fun `undo restores noteIds`() {
        val state = EditorState()
        val controller = EditorController(state)
        state.initFromNoteLines(listOf(
            "Line A" to listOf("noteA"),
            "Line B" to listOf("noteB")
        ))
        controller.undoManager.setBaseline(state)
        controller.undoManager.beginEditingLine(state, 0)

        // Make a change
        state.lines[0].updateFull("Modified A", 10)
        controller.commitUndoState()

        // Undo
        controller.undo()

        assertEquals("Line A", state.lines[0].text)
        assertEquals(listOf("noteA"), state.lines[0].noteIds)
        assertEquals(listOf("noteB"), state.lines[1].noteIds)
    }
}
