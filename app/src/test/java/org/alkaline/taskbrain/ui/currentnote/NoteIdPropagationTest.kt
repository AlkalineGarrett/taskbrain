package org.alkaline.taskbrain.ui.currentnote

import org.alkaline.taskbrain.data.NoteIdSentinel
import org.alkaline.taskbrain.ui.currentnote.selection.EditorSelection
import org.alkaline.taskbrain.ui.currentnote.util.ClipboardParser
import org.alkaline.taskbrain.ui.currentnote.util.PasteHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** True iff the line has no real Firestore id — either empty or only sentinels. */
private fun LineState.hasNoRealNoteId(): Boolean =
    noteIds.isEmpty() || noteIds.all { NoteIdSentinel.isSentinel(it) }

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

    @Test
    fun `LineState preserves multiple noteIds`() {
        val line = LineState("Hello", noteIds = listOf("note1", "note2"))
        assertEquals(listOf("note1", "note2"), line.noteIds)
    }

    // ==================== EditorState.initFromNoteLines ====================

    @Test
    fun `initFromNoteLines resets focus and selection`() {
        val state = EditorState()
        state.focusedLineIndex = 5
        state.initFromNoteLines(listOf(
            "Line A" to listOf("noteA"),
            "Line B" to listOf("noteB")
        ))

        assertEquals(0, state.focusedLineIndex)
        assertEquals(EditorSelection.None, state.selection)
    }

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
    fun `updateFromText handles duplicate content lines correctly`() {
        val state = EditorState()
        state.initFromNoteLines(listOf(
            "Same" to listOf("note1"),
            "Same" to listOf("note2"),
            "Different" to listOf("note3")
        ))

        state.updateFromText("Same\nSame\nDifferent")

        assertEquals(listOf("note1"), state.lines[0].noteIds)
        assertEquals(listOf("note2"), state.lines[1].noteIds)
        assertEquals(listOf("note3"), state.lines[2].noteIds)
    }

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

        // Line 0 gets parentNoteId (noteA) enforced as primary, with noteB as secondary
        assertEquals(listOf("noteA", "noteB"), state.lines[0].noteIds)
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
    fun `splitLine gives noteIds to the longer fragment when splitting in middle`() {
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
        // " World" (6 chars) > "Hello" (5 chars), so after half gets the real id;
        // the shorter "Hello" side gets a SPLIT sentinel (save allocates a fresh doc).
        assertTrue(state.lines[0].hasNoRealNoteId())
        assertEquals("split", NoteIdSentinel.originOf(state.lines[0].noteIds.firstOrNull()))
        assertEquals(listOf("note1"), state.lines[1].noteIds)
    }

    @Test
    fun `splitLine gives new empty line a fresh id when splitting at end`() {
        val state = EditorState()
        val controller = EditorController(state)
        state.initFromNoteLines(listOf(
            "Hello" to listOf("note1"),
            "" to emptyList()
        ))
        state.focusedLineIndex = 0
        state.lines[0].updateFull("Hello", 5) // cursor at end

        controller.splitLine(0)

        assertEquals("Hello", state.lines[0].text)
        assertEquals(listOf("note1"), state.lines[0].noteIds)
        // Empty lines are first-class docs now: the new empty half gets a
        // SPLIT sentinel so save allocates a fresh doc for it.
        assertTrue(state.lines[1].hasNoRealNoteId())
        assertEquals(1, state.lines[1].noteIds.size)
    }

    @Test
    fun `splitLine gives original empty line a fresh id when splitting at beginning`() {
        val state = EditorState()
        val controller = EditorController(state)
        state.initFromNoteLines(listOf(
            "Hello" to listOf("note1"),
            "" to emptyList()
        ))
        state.focusedLineIndex = 0
        state.lines[0].updateFull("Hello", 0) // cursor at beginning

        controller.splitLine(0)

        // Original line is now empty — gets a SPLIT sentinel so save allocates
        // a fresh doc for the empty line (it's no longer a containedNotes "" entry).
        assertEquals("", state.lines[0].text)
        assertTrue(state.lines[0].hasNoRealNoteId())
        assertEquals(1, state.lines[0].noteIds.size)
        // New line has the content, should keep the original noteId
        assertEquals("Hello", state.lines[1].text)
        assertEquals(listOf("note1"), state.lines[1].noteIds)
    }

    @Test
    fun `splitLine gives new empty line a fresh id when splitting prefixed line at end`() {
        val state = EditorState()
        val controller = EditorController(state)
        state.initFromNoteLines(listOf(
            "• Content" to listOf("note1"),
            "" to emptyList()
        ))
        state.focusedLineIndex = 0
        state.lines[0].updateFull("• Content", 9) // cursor at end

        controller.splitLine(0)

        assertEquals(listOf("note1"), state.lines[0].noteIds)
        assertTrue(state.lines[1].hasNoRealNoteId())
        assertEquals(1, state.lines[1].noteIds.size)
    }

    @Test
    fun `splitLine gives original line a fresh id when splitting prefixed line at prefix boundary`() {
        val state = EditorState()
        val controller = EditorController(state)
        state.initFromNoteLines(listOf(
            "• Content" to listOf("note1"),
            "" to emptyList()
        ))
        state.focusedLineIndex = 0
        state.lines[0].updateFull("• Content", 2) // cursor after prefix

        controller.splitLine(0)

        // Original line has just prefix (no content) — gets a SPLIT sentinel
        // so save allocates a fresh doc for the empty content half.
        assertTrue(state.lines[0].hasNoRealNoteId())
        assertEquals(1, state.lines[0].noteIds.size)
        // New line has the content, should keep noteIds
        assertEquals(listOf("note1"), state.lines[1].noteIds)
    }

    // ==================== updateLineContent newline (IME path) ====================

    @Test
    fun `updateLineContent gives new empty line a fresh id when newline at end`() {
        val state = EditorState()
        val controller = EditorController(state)
        state.initFromNoteLines(listOf(
            "Hello" to listOf("note1"),
            "" to emptyList()
        ))
        state.focusedLineIndex = 0

        controller.updateLineContent(0, "Hello\n", 6)

        assertEquals("Hello", state.lines[0].content)
        assertEquals(listOf("note1"), state.lines[0].noteIds)
        // Empty halves get a SPLIT sentinel under the new empty-line-as-doc model.
        assertTrue(state.lines[1].hasNoRealNoteId())
        assertEquals(1, state.lines[1].noteIds.size)
    }

    @Test
    fun `updateLineContent gives original line a fresh id when newline at beginning`() {
        val state = EditorState()
        val controller = EditorController(state)
        state.initFromNoteLines(listOf(
            "Hello" to listOf("note1"),
            "" to emptyList()
        ))
        state.focusedLineIndex = 0

        controller.updateLineContent(0, "\nHello", 0)

        assertEquals("", state.lines[0].content)
        // Empty halves get a SPLIT sentinel under the new empty-line-as-doc model.
        assertTrue(state.lines[0].hasNoRealNoteId())
        assertEquals(1, state.lines[0].noteIds.size)
        assertEquals("Hello", state.lines[1].content)
        assertEquals(listOf("note1"), state.lines[1].noteIds)
    }

    @Test
    fun `updateLineContent gives noteIds to the longer fragment when newline in middle`() {
        val state = EditorState()
        val controller = EditorController(state)
        state.initFromNoteLines(listOf(
            "HelloWorld" to listOf("note1"),
            "" to emptyList()
        ))
        state.focusedLineIndex = 0

        controller.updateLineContent(0, "Hello\nWorld", 5)

        // "Hello" (5 chars) = "World" (5 chars), so before half gets the real
        // id; the other side gets a SPLIT sentinel.
        assertEquals(listOf("note1"), state.lines[0].noteIds)
        assertTrue(state.lines[1].hasNoRealNoteId())
    }

    // ==================== updateLineContent typed-sentinel stamping ====================

    @Test
    fun `updateLineContent stamps TYPED sentinel when id-less line gains content`() {
        val state = EditorState()
        val controller = EditorController(state)
        state.initFromNoteLines(listOf(
            "Root" to listOf("rootId"),
            "" to emptyList()
        ))
        state.focusedLineIndex = 1

        controller.updateLineContent(1, "T", 1)

        assertEquals(1, state.lines[1].noteIds.size)
        assertEquals("typed", NoteIdSentinel.originOf(state.lines[1].noteIds[0]))
    }

    @Test
    fun `updateLineContent does not change noteIds when line already has an id`() {
        val state = EditorState()
        val controller = EditorController(state)
        state.initFromNoteLines(listOf(
            "Root" to listOf("rootId"),
            "Hello" to listOf("childId"),
            "" to emptyList()
        ))
        state.focusedLineIndex = 1

        controller.updateLineContent(1, "Hello!", 6)

        assertEquals(listOf("childId"), state.lines[1].noteIds)
    }

    @Test
    fun `updateLineContent does not stamp sentinel on root line 0`() {
        val state = EditorState()
        val controller = EditorController(state)
        // Brand-new note: root line has no parentNoteId yet, no noteIds.
        state.initFromNoteLines(listOf("" to emptyList()))
        state.focusedLineIndex = 0

        controller.updateLineContent(0, "First content", 13)

        // Line 0 stays id-less so the save path can allocate a fresh root doc
        // (or so toNoteLines can stamp the eventual parentNoteId).
        assertTrue(state.lines[0].noteIds.isEmpty())
    }

    @Test
    fun `updateLineContent does not stamp sentinel when content stays empty`() {
        val state = EditorState()
        val controller = EditorController(state)
        state.initFromNoteLines(listOf(
            "Root" to listOf("rootId"),
            "" to emptyList()
        ))
        state.focusedLineIndex = 1

        // Some IME paths fire updateLineContent with the unchanged empty content.
        controller.updateLineContent(1, "", 0)

        assertTrue(state.lines[1].noteIds.isEmpty())
    }

    // ==================== Merge line propagation ====================

    @Test
    fun `mergeToPreviousLine combines noteIds in text order`() {
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
        // noteIds follow text order: previous line (noteA) first
        assertEquals(listOf("noteA", "noteB"), state.lines[0].noteIds)
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

    @Test
    fun `mergeNextLine deduplicates shared noteIds`() {
        val state = EditorState()
        val controller = EditorController(state)
        state.initFromNoteLines(listOf(
            "Line A" to listOf("shared", "noteA"),
            "Line B longer" to listOf("shared", "noteB"),
            "" to emptyList()
        ))
        state.focusedLineIndex = 0

        controller.mergeNextLine(0)

        // lineA's noteIds come first (text order); 'shared' appears once
        assertEquals(listOf("shared", "noteA", "noteB"), state.lines[0].noteIds)
    }

    // ==================== Merge→split round-trip (noteId distribution) ====================

    @Test
    fun `backspace merge then enter restores original noteIds`() {
        val state = EditorState()
        val controller = EditorController(state)
        state.initFromNoteLines(listOf(
            "Hello" to listOf("noteA"),
            "World" to listOf("noteB"),
            "" to emptyList()
        ))
        state.focusedLineIndex = 1
        state.lines[1].updateFull("World", 0)

        controller.mergeToPreviousLine(1)
        assertEquals("HelloWorld", state.lines[0].text)
        assertEquals(listOf("noteA", "noteB"), state.lines[0].noteIds)

        // Split at the original boundary (after "Hello")
        // Save and restore metadata around cursor positioning (updateFull clears it)
        val savedLengths = state.lines[0].noteIdContentLengths
        state.lines[0].updateFull("HelloWorld", 5)
        state.lines[0].noteIdContentLengths = savedLengths
        controller.splitLine(0)

        assertEquals(listOf("noteA"), state.lines[0].noteIds)
        assertEquals(listOf("noteB"), state.lines[1].noteIds)
    }

    @Test
    fun `merge then split with uneven content distributes correctly`() {
        val state = EditorState()
        val controller = EditorController(state)
        state.initFromNoteLines(listOf(
            "AB" to listOf("noteA"),
            "CDEFGH" to listOf("noteB"),
            "" to emptyList()
        ))
        state.focusedLineIndex = 1
        state.lines[1].updateFull("CDEFGH", 0)

        controller.mergeToPreviousLine(1)
        assertEquals("ABCDEFGH", state.lines[0].text)

        // Split at position 3: noteA (2 chars, all before) → before; noteB (6 chars, 1 before + 5 after) → after
        val savedLengths2 = state.lines[0].noteIdContentLengths
        state.lines[0].updateFull("ABCDEFGH", 3)
        state.lines[0].noteIdContentLengths = savedLengths2
        controller.splitLine(0)

        assertEquals(listOf("noteA"), state.lines[0].noteIds)
        assertEquals(listOf("noteB"), state.lines[1].noteIds)
    }

    @Test
    fun `merge then split at original boundary with prefixed lines`() {
        val state = EditorState()
        val controller = EditorController(state)
        state.initFromNoteLines(listOf(
            "\t\u2022 First" to listOf("noteA"),
            "\t\u2022 Second" to listOf("noteB"),
            "" to emptyList()
        ))
        state.focusedLineIndex = 1
        state.lines[1].updateFull("\t\u2022 Second", 0)

        controller.mergeToPreviousLine(1)
        // After merge: "\t• FirstSecond" (content of line 1 appended without its prefix)
        assertEquals("\t\u2022 FirstSecond", state.lines[0].text)

        // Split after "First": prefix is "\t• " (3 chars), so "First" ends at position 8
        val savedLengths3 = state.lines[0].noteIdContentLengths
        state.lines[0].updateFull("\t\u2022 FirstSecond", 8)
        state.lines[0].noteIdContentLengths = savedLengths3
        controller.splitLine(0)

        assertEquals(listOf("noteA"), state.lines[0].noteIds)
        assertEquals(listOf("noteB"), state.lines[1].noteIds)
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

    @Test
    fun `moveLinesInternal preserves noteIds when moving a range`() {
        val state = EditorState()
        state.initFromNoteLines(listOf(
            "Line A" to listOf("noteA"),
            "Line B" to listOf("noteB"),
            "Line C" to listOf("noteC"),
            "Line D" to listOf("noteD")
        ))

        // Move lines 0-1 to after line 2 (targetIndex=3)
        state.moveLinesInternal(0..1, 3)

        assertEquals("Line C", state.lines[0].text)
        assertEquals("Line A", state.lines[1].text)
        assertEquals("Line B", state.lines[2].text)
        assertEquals("Line D", state.lines[3].text)
        assertEquals(listOf("noteC"), state.lines[0].noteIds)
        assertEquals(listOf("noteA"), state.lines[1].noteIds)
        assertEquals(listOf("noteB"), state.lines[2].noteIds)
        assertEquals(listOf("noteD"), state.lines[3].noteIds)
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
        assertTrue(result.lines[1].hasNoRealNoteId())
        assertEquals("paste", NoteIdSentinel.originOf(result.lines[1].noteIds.firstOrNull()))
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

    @Test
    fun `redo restores noteIds`() {
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

        // Undo then redo
        controller.undo()
        controller.redo()

        assertEquals("Modified A", state.lines[0].text)
        assertEquals(listOf("noteA"), state.lines[0].noteIds)
    }

    // ==================== Delete selection preserves noteIds ====================

    @Test
    fun `deleteSelection preserves noteIds on surviving lines`() {
        val state = EditorState()
        val controller = EditorController(state)
        state.initFromNoteLines(listOf(
            "Line A" to listOf("noteA"),
            "Line B" to listOf("noteB"),
            "Line C" to listOf("noteC")
        ))

        // Select "Line B\n" and delete
        val startOffset = "Line A\n".length
        val endOffset = "Line A\nLine B\n".length
        state.setSelection(startOffset, endOffset)
        controller.deleteSelectionWithUndo()

        assertEquals(2, state.lines.size)
        assertEquals("Line A", state.lines[0].text)
        assertEquals("Line C", state.lines[1].text)
        assertEquals(listOf("noteA"), state.lines[0].noteIds)
        assertEquals(listOf("noteC"), state.lines[1].noteIds)
    }

    // ==================== Cut then paste replaces with same noteIds ====================

    @Test
    fun `cut then full-line paste replaces with same noteIds`() {
        val lines = listOf(
            LineState("Line A", noteIds = listOf("noteA")),
            LineState("Line D", noteIds = listOf("noteD")),
        )

        // Full-line select Line D, paste Line B and Line C over it
        val selection = EditorSelection("Line A\n".length, "Line A\nLine D".length)
        val parsed = ClipboardParser.parseInternal("Line B\nLine C")
        val result = PasteHandler.execute(lines, 1, selection, parsed)

        assertEquals("Line A", result.lines[0].text)
        assertEquals(listOf("noteA"), result.lines[0].noteIds)
        // Line B gets noteD via positional fallback
        assertEquals("Line B", result.lines[1].text)
        assertEquals(listOf("noteD"), result.lines[1].noteIds)
        // Line C is extra — no deleted line to match; paste stamps a sentinel.
        assertEquals("Line C", result.lines[2].text)
        assertTrue(result.lines[2].hasNoRealNoteId())
    }

    // ==================== Full lifecycle ====================

    @Test
    fun `preserves identity through split and merge cycle`() {
        val state = EditorState()
        val controller = EditorController(state)
        state.initFromNoteLines(listOf(
            "Hello World" to listOf("note1"),
            "Other line" to listOf("note2"),
            "" to emptyList()
        ))

        // Split line 0 at position 5: "Hello" (5) vs " World" (6)
        state.focusedLineIndex = 0
        state.lines[0].updateFull("Hello World", 5)
        controller.splitLine(0)

        // Longer fragment (" World") keeps the noteId; shorter side gets a sentinel.
        assertTrue(state.lines[0].hasNoRealNoteId())
        assertEquals(listOf("note1"), state.lines[1].noteIds)

        // Merge them back
        state.focusedLineIndex = 1
        state.lines[1].updateFull(state.lines[1].text, 0)
        controller.mergeToPreviousLine(1)

        assertEquals("Hello World", state.lines[0].text)
        assertEquals(listOf("note1"), state.lines[0].noteIds)
    }

    @Test
    fun `preserves identity through move and save`() {
        val state = EditorState()
        state.initFromNoteLines(listOf(
            "Task 1" to listOf("note1"),
            "Task 2" to listOf("note2"),
            "Task 3" to listOf("note3")
        ))

        // Move task 3 to top
        state.moveLinesInternal(2..2, 0)

        assertEquals(listOf("Task 3", "Task 1", "Task 2"), state.lines.map { it.text })
        assertEquals(listOf(listOf("note3"), listOf("note1"), listOf("note2")),
            state.lines.map { it.noteIds })
    }
}
