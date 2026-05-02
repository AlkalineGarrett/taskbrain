package org.alkaline.taskbrain.ui.currentnote.undo

import org.alkaline.taskbrain.ui.currentnote.initFromText
import com.google.firebase.Timestamp
import org.alkaline.taskbrain.data.Alarm
import org.alkaline.taskbrain.ui.currentnote.EditorController
import org.alkaline.taskbrain.ui.currentnote.EditorState
import org.alkaline.taskbrain.ui.currentnote.LineState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Date

class UndoManagerTest {

    private lateinit var undoManager: UndoManager
    private lateinit var editorState: EditorState

    @Before
    fun setUp() {
        undoManager = UndoManager()
        editorState = EditorState()
    }

    // ==================== Basic Undo/Redo State ====================

    @Test
    fun `initially canUndo is false`() {
        assertFalse(undoManager.canUndo)
    }

    @Test
    fun `initially canRedo is false`() {
        assertFalse(undoManager.canRedo)
    }

    @Test
    fun `canUndo is true after committing changed state`() {
        editorState.initFromText("initial")
        undoManager.beginEditingLine(editorState, 0)

        editorState.initFromText("modified")
        undoManager.commitPendingUndoState(editorState)

        assertTrue(undoManager.canUndo)
    }

    @Test
    fun `canUndo is false if no content change`() {
        editorState.initFromText("initial")
        undoManager.beginEditingLine(editorState, 0)

        // No change to content
        undoManager.commitPendingUndoState(editorState)

        assertFalse(undoManager.canUndo)
    }

    // ==================== Undo Operation ====================

    @Test
    fun `undo returns snapshot to restore`() {
        editorState.initFromText("initial")
        undoManager.beginEditingLine(editorState, 0)

        editorState.initFromText("modified")
        undoManager.commitPendingUndoState(editorState)

        val snapshot = undoManager.undo(editorState)
        assertNotNull(snapshot)
        assertEquals(listOf("initial"), snapshot!!.lineContents)
    }

    @Test
    fun `undo returns null when stack is empty`() {
        editorState.initFromText("content")
        val snapshot = undoManager.undo(editorState)
        assertNull(snapshot)
    }

    @Test
    fun `after undo canRedo is true`() {
        editorState.initFromText("initial")
        undoManager.beginEditingLine(editorState, 0)

        editorState.initFromText("modified")
        undoManager.commitPendingUndoState(editorState)

        undoManager.undo(editorState)
        assertTrue(undoManager.canRedo)
    }

    @Test
    fun `multiple undos walk back through history`() {
        // State 1
        editorState.initFromText("state1")
        undoManager.beginEditingLine(editorState, 0)

        // State 2
        editorState.initFromText("state2")
        undoManager.commitPendingUndoState(editorState)
        undoManager.beginEditingLine(editorState, 0)

        // State 3
        editorState.initFromText("state3")
        undoManager.commitPendingUndoState(editorState)

        // Undo to state2
        val snapshot1 = undoManager.undo(editorState)
        assertEquals(listOf("state2"), snapshot1!!.lineContents)

        // Simulate restoring state2
        editorState.initFromText("state2")

        // Undo to state1
        val snapshot2 = undoManager.undo(editorState)
        assertEquals(listOf("state1"), snapshot2!!.lineContents)
    }

    // ==================== Redo Operation ====================

    @Test
    fun `redo returns snapshot to restore`() {
        editorState.initFromText("initial")
        undoManager.beginEditingLine(editorState, 0)

        editorState.initFromText("modified")
        undoManager.commitPendingUndoState(editorState)

        undoManager.undo(editorState)
        editorState.initFromText("initial")

        val snapshot = undoManager.redo(editorState)
        assertNotNull(snapshot)
        assertEquals(listOf("modified"), snapshot!!.lineContents)
    }

    @Test
    fun `redo returns null when stack is empty`() {
        editorState.initFromText("content")
        val snapshot = undoManager.redo(editorState)
        assertNull(snapshot)
    }

    @Test
    fun `clearRedoStack clears redo history`() {
        editorState.initFromText("initial")
        undoManager.beginEditingLine(editorState, 0)

        editorState.initFromText("modified")
        undoManager.commitPendingUndoState(editorState)

        undoManager.undo(editorState)
        assertTrue(undoManager.canRedo)

        undoManager.clearRedoStack()
        assertFalse(undoManager.canRedo)
    }

    // ==================== Focus Change Triggers Boundary ====================

    @Test
    fun `beginEditingLine captures pending snapshot`() {
        editorState.initFromText("line1\nline2")
        undoManager.beginEditingLine(editorState, 0)

        // Modify and focus different line
        editorState.initFromText("modified\nline2")
        undoManager.commitPendingUndoState(editorState)
        undoManager.beginEditingLine(editorState, 1)

        assertTrue(undoManager.canUndo)
    }

    @Test
    fun `beginEditingLine on same line does not create new snapshot`() {
        editorState.initFromText("content")
        undoManager.beginEditingLine(editorState, 0)
        undoManager.beginEditingLine(editorState, 0) // Same line

        // No content change
        undoManager.commitPendingUndoState(editorState)
        assertFalse(undoManager.canUndo)
    }

    // ==================== Command Grouping ====================

    @Test
    fun `bullet command creates separate undo step`() {
        editorState.initFromText("item")
        undoManager.recordCommand(editorState, CommandType.BULLET)
        editorState.toggleBulletInternal()
        undoManager.commitAfterCommand(editorState, CommandType.BULLET)

        assertTrue(undoManager.canUndo)
        val snapshot = undoManager.undo(editorState)
        assertEquals(listOf("item"), snapshot!!.lineContents)
    }

    @Test
    fun `checkbox command creates separate undo step`() {
        editorState.initFromText("task")
        undoManager.recordCommand(editorState, CommandType.CHECKBOX)
        editorState.toggleCheckboxInternal()
        undoManager.commitAfterCommand(editorState, CommandType.CHECKBOX)

        assertTrue(undoManager.canUndo)
    }

    @Test
    fun `consecutive indent commands are grouped`() {
        editorState.initFromText("item")

        // First indent
        undoManager.recordCommand(editorState, CommandType.INDENT)
        editorState.indentInternal()

        // Second indent (should be grouped)
        undoManager.recordCommand(editorState, CommandType.INDENT)
        editorState.indentInternal()

        // Third indent (should be grouped)
        undoManager.recordCommand(editorState, CommandType.INDENT)
        editorState.indentInternal()

        // Commit the grouped changes
        undoManager.commitPendingUndoState(editorState)

        // Should be one undo step that restores original
        val snapshot = undoManager.undo(editorState)
        assertEquals(listOf("item"), snapshot!!.lineContents)

        // No more undo steps
        assertFalse(undoManager.canUndo)
    }

    @Test
    fun `consecutive unindent commands are grouped`() {
        editorState.initFromText("\t\t\titem")

        // Multiple unindents
        undoManager.recordCommand(editorState, CommandType.UNINDENT)
        editorState.unindentInternal()

        undoManager.recordCommand(editorState, CommandType.UNINDENT)
        editorState.unindentInternal()

        undoManager.commitPendingUndoState(editorState)

        // Should be one undo step
        val snapshot = undoManager.undo(editorState)
        assertEquals(listOf("\t\t\titem"), snapshot!!.lineContents)
        assertFalse(undoManager.canUndo)
    }

    @Test
    fun `indent and unindent sequence is grouped`() {
        editorState.initFromText("\titem")

        // Indent then unindent to different level (net +1 indent)
        undoManager.recordCommand(editorState, CommandType.INDENT)
        editorState.indentInternal()

        undoManager.recordCommand(editorState, CommandType.INDENT)
        editorState.indentInternal()

        undoManager.recordCommand(editorState, CommandType.UNINDENT)
        editorState.unindentInternal()

        undoManager.commitPendingUndoState(editorState)

        // Net change is +1 indent, should be one undo step
        assertTrue(undoManager.canUndo)
        val snapshot = undoManager.undo(editorState)
        assertEquals(listOf("\titem"), snapshot!!.lineContents)
        assertFalse(undoManager.canUndo)
    }

    @Test
    fun `bullet breaks indent sequence`() {
        editorState.initFromText("item")

        // First indent
        undoManager.recordCommand(editorState, CommandType.INDENT)
        editorState.indentInternal()

        // Bullet breaks the sequence
        undoManager.recordCommand(editorState, CommandType.BULLET)
        editorState.toggleBulletInternal()
        undoManager.commitAfterCommand(editorState, CommandType.BULLET)

        // Two separate undo steps
        assertTrue(undoManager.canUndo)
        val snapshot1 = undoManager.undo(editorState)
        assertEquals(listOf("\titem"), snapshot1!!.lineContents)

        editorState.initFromText("\titem")

        assertTrue(undoManager.canUndo)
        val snapshot2 = undoManager.undo(editorState)
        assertEquals(listOf("item"), snapshot2!!.lineContents)
    }

    // ==================== Alarm Tracking ====================

    @Test
    fun `recordAlarmCreation attaches alarm to last undo entry`() {
        editorState.initFromText("initial")
        undoManager.beginEditingLine(editorState, 0)

        editorState.initFromText("task with alarm")
        undoManager.commitPendingUndoState(editorState)

        val alarmSnapshot = AlarmSnapshot(
            id = "alarm123",
            noteId = "note456",
            lineContent = "task with alarm",
            dueTime = Timestamp(Date()),
            stages = Alarm.DEFAULT_STAGES
        )
        undoManager.recordAlarmCreation(alarmSnapshot, editorState)

        val snapshot = undoManager.undo(editorState)
        assertNotNull(snapshot!!.createdAlarm)
        assertEquals("alarm123", snapshot.createdAlarm!!.id)
    }

    @Test
    fun `updateLastUndoAlarmId updates alarm ID`() {
        editorState.initFromText("initial")
        undoManager.beginEditingLine(editorState, 0)

        editorState.initFromText("task")
        undoManager.commitPendingUndoState(editorState)

        val alarmSnapshot = AlarmSnapshot(
            id = "old_id",
            noteId = "note",
            lineContent = "task",
            dueTime = null,
            stages = Alarm.DEFAULT_STAGES
        )
        undoManager.recordAlarmCreation(alarmSnapshot, editorState)

        undoManager.updateLastUndoAlarmId("new_id")

        val snapshot = undoManager.undo(editorState)
        assertEquals("new_id", snapshot!!.createdAlarm!!.id)
    }

    @Test
    fun `recordAlarmCreation creates implicit undo point when stack is empty`() {
        // Simulates: user creates alarm on empty document with no prior edits
        // The alarm creation should still be undoable

        editorState.initFromText("task line")
        // Begin editing but don't commit - pending snapshot exists but undo stack is empty
        undoManager.beginEditingLine(editorState, 0)

        // Verify undo stack is empty
        assertFalse(undoManager.canUndo)

        val alarmSnapshot = AlarmSnapshot(
            id = "alarm123",
            noteId = "note456",
            lineContent = "task line",
            dueTime = Timestamp(Date()),
            stages = Alarm.DEFAULT_STAGES
        )
        undoManager.recordAlarmCreation(alarmSnapshot, editorState)

        // Now undo should be possible - the pending snapshot was committed
        assertTrue(undoManager.canUndo)

        val snapshot = undoManager.undo(editorState)
        assertNotNull(snapshot!!.createdAlarm)
        assertEquals("alarm123", snapshot.createdAlarm!!.id)
    }

    // ==================== Reset ====================

    @Test
    fun `reset clears all history`() {
        editorState.initFromText("initial")
        undoManager.beginEditingLine(editorState, 0)

        editorState.initFromText("modified")
        undoManager.commitPendingUndoState(editorState)

        undoManager.undo(editorState)

        assertTrue(undoManager.canUndo || undoManager.canRedo)

        undoManager.reset()

        assertFalse(undoManager.canUndo)
        assertFalse(undoManager.canRedo)
    }

    // ==================== History Size Limit ====================

    @Test
    fun `history size is limited`() {
        val smallManager = UndoManager(maxHistorySize = 3)

        // Create 5 undo entries
        for (i in 1..5) {
            editorState.initFromText("state$i")
            smallManager.beginEditingLine(editorState, 0)
            editorState.initFromText("state${i + 1}")
            smallManager.commitPendingUndoState(editorState)
        }

        // Should only have 3 undo entries (oldest dropped)
        var undoCount = 0
        while (smallManager.canUndo) {
            smallManager.undo(editorState)
            undoCount++
        }
        assertEquals(3, undoCount)
    }

    // ==================== Multi-line Snapshots ====================

    @Test
    fun `snapshot captures all lines`() {
        editorState.initFromText("line1\nline2\nline3")
        undoManager.beginEditingLine(editorState, 0)

        editorState.initFromText("modified1\nmodified2")
        undoManager.commitPendingUndoState(editorState)

        val snapshot = undoManager.undo(editorState)
        assertEquals(listOf("line1", "line2", "line3"), snapshot!!.lineContents)
    }

    @Test
    fun `snapshot captures cursor position`() {
        editorState.initFromText("hello")
        editorState.focusedLineIndex = 0
        editorState.lines[0].updateFull("hello", 3) // Cursor at position 3

        undoManager.beginEditingLine(editorState, 0)

        editorState.initFromText("modified")
        undoManager.commitPendingUndoState(editorState)

        val snapshot = undoManager.undo(editorState)
        assertEquals(3, snapshot!!.cursorPosition)
    }

    @Test
    fun `snapshot captures focused line index`() {
        editorState.initFromText("line1\nline2\nline3")
        editorState.focusedLineIndex = 1

        undoManager.beginEditingLine(editorState, 1)

        editorState.initFromText("line1\nmodified\nline3")
        undoManager.commitPendingUndoState(editorState)

        val snapshot = undoManager.undo(editorState)
        assertEquals(1, snapshot!!.focusedLineIndex)
    }

    // ==================== Export/Import State ====================

    @Test
    fun `exportState captures complete state`() {
        editorState.initFromText("initial")
        undoManager.beginEditingLine(editorState, 0)

        editorState.initFromText("modified")
        undoManager.commitPendingUndoState(editorState)

        // Before undo: undoStack=1, redoStack=0
        val stateBefore = undoManager.exportState()
        assertEquals(1, stateBefore.undoStack.size)
        assertEquals(0, stateBefore.redoStack.size)

        undoManager.undo(editorState)

        // After undo: undoStack=0, redoStack=1
        val stateAfter = undoManager.exportState()
        assertEquals(0, stateAfter.undoStack.size)
        assertEquals(1, stateAfter.redoStack.size)
    }

    @Test
    fun `importState restores complete state`() {
        // Create some history
        editorState.initFromText("initial")
        undoManager.beginEditingLine(editorState, 0)
        editorState.initFromText("modified")
        undoManager.commitPendingUndoState(editorState)

        // Export
        val exportedState = undoManager.exportState()

        // Reset and import
        undoManager.reset()
        assertFalse(undoManager.canUndo)

        undoManager.importState(exportedState)
        assertTrue(undoManager.canUndo)
    }

    // ==================== Enter Key (splitLine) Scenarios ====================

    @Test
    fun `enter after typing - creates two undo steps`() {
        // Simulates: user focuses line, types text, hits Enter (no typing after Enter)
        // First undo: removes the Enter (restores to just before Enter)
        // Second undo: removes the typing (restores to before typing)
        //
        // Note: This uses prepareForStructuralChange + continueAfterStructuralChange
        // which matches the actual EditorController.splitLine implementation.

        // Initial state: indented bullet line
        editorState.initFromText("\t• ")
        editorState.focusedLineIndex = 0
        editorState.lines[0].updateFull("\t• ", 3) // Cursor at end

        // User focuses the line (simulates tap)
        undoManager.beginEditingLine(editorState, 0)

        // User types "item"
        editorState.lines[0].updateFull("\t• item", 7)

        // User hits Enter - prepareForStructuralChange commits typing, captures pre-split state
        undoManager.prepareForStructuralChange(editorState)

        // Perform the split (would be done by EditorController.splitLine)
        editorState.lines[0].updateFull("\t• item", 7)
        editorState.lines.add(1, LineState("\t• ", 3))
        editorState.focusedLineIndex = 1

        // Continue pending state on new line (groups Enter with any subsequent typing)
        undoManager.continueAfterStructuralChange(1)

        // First undo: should restore to just before Enter (with typed text)
        val snapshot1 = undoManager.undo(editorState)
        assertNotNull(snapshot1)
        assertEquals(listOf("\t• item"), snapshot1!!.lineContents)
        assertEquals(0, snapshot1.focusedLineIndex)
        assertEquals(7, snapshot1.cursorPosition)

        // Simulate restoring that state
        editorState.initFromText("\t• item")
        editorState.focusedLineIndex = 0

        // Second undo: should restore to before typing
        assertTrue(undoManager.canUndo)
        val snapshot2 = undoManager.undo(editorState)
        assertNotNull(snapshot2)
        assertEquals(listOf("\t• "), snapshot2!!.lineContents)
        assertEquals(0, snapshot2.focusedLineIndex)
        assertEquals(3, snapshot2.cursorPosition)

        // No more undo steps
        assertFalse(undoManager.canUndo)
    }

    @Test
    fun `enter immediately after focus - single undo step`() {
        // Simulates: user taps line, immediately hits Enter (no typing before or after)
        // Only one undo step needed (just the Enter)
        //
        // Note: Uses prepareForStructuralChange + continueAfterStructuralChange
        // to match actual EditorController.splitLine implementation.
        // The undo button handler calls commitUndoState() before undo(), so we
        // simulate that here.

        // Initial state
        editorState.initFromText("\t• item")
        editorState.focusedLineIndex = 0
        editorState.lines[0].updateFull("\t• item", 7) // Cursor at end

        // User focuses the line
        undoManager.beginEditingLine(editorState, 0)

        // User immediately hits Enter - no prior typing to commit
        undoManager.prepareForStructuralChange(editorState)

        // Perform the split
        editorState.lines[0].updateFull("\t• item", 7)
        editorState.lines.add(1, LineState("\t• ", 3))
        editorState.focusedLineIndex = 1

        // Continue pending state on new line
        undoManager.continueAfterStructuralChange(1)

        // User clicks undo - the undo button handler commits first
        // (pending state is "\t• item", current is "\t• item\n\t• ", they differ)
        undoManager.commitPendingUndoState(editorState)

        // Now Enter should be undoable
        assertTrue(undoManager.canUndo)

        val snapshot = undoManager.undo(editorState)
        assertNotNull(snapshot)
        assertEquals(listOf("\t• item"), snapshot!!.lineContents)
        assertEquals(0, snapshot.focusedLineIndex)
        assertEquals(7, snapshot.cursorPosition)

        // Only one undo step since there was no typing
        assertFalse(undoManager.canUndo)
    }

    @Test
    fun `enter is separate undo step from typing`() {
        // Verify: typing creates one undo step, Enter (+ subsequent typing) creates another
        // Uses prepareForStructuralChange + continueAfterStructuralChange to match
        // actual EditorController.splitLine implementation.

        // Start with empty line
        editorState.initFromText("")
        undoManager.beginEditingLine(editorState, 0)

        // User types "hello"
        editorState.initFromText("hello")

        // User hits Enter - commits typing, captures pre-split state
        undoManager.prepareForStructuralChange(editorState)
        editorState.initFromText("hello\n")
        editorState.focusedLineIndex = 1
        undoManager.continueAfterStructuralChange(1)

        // Should have one committed undo step (typing), plus pending (Enter state)
        assertTrue(undoManager.canUndo)

        // First undo: removes Enter (commits pending first, then undoes)
        val snapshot1 = undoManager.undo(editorState)
        assertEquals(listOf("hello"), snapshot1!!.lineContents)
        assertTrue(undoManager.canUndo)

        // Restore state
        editorState.initFromText("hello")

        // Second undo: removes typing
        val snapshot2 = undoManager.undo(editorState)
        assertEquals(listOf(""), snapshot2!!.lineContents)

        // No more undos
        assertFalse(undoManager.canUndo)
    }

    @Test
    fun `importState restores alarm snapshots`() {
        editorState.initFromText("initial")
        undoManager.beginEditingLine(editorState, 0)
        editorState.initFromText("modified")
        undoManager.commitPendingUndoState(editorState)

        val alarm = AlarmSnapshot(
            id = "alarm123",
            noteId = "note",
            lineContent = "task",
            dueTime = Timestamp(Date(3000000)),
            stages = org.alkaline.taskbrain.data.Alarm.DEFAULT_STAGES
        )
        undoManager.recordAlarmCreation(alarm, editorState)

        val exportedState = undoManager.exportState()

        undoManager.reset()
        undoManager.importState(exportedState)

        val snapshot = undoManager.undo(editorState)
        assertNotNull(snapshot!!.createdAlarm)
        assertEquals("alarm123", snapshot.createdAlarm!!.id)
        assertEquals(3000000, snapshot.createdAlarm!!.dueTime!!.toDate().time)
    }

    // ==================== Enter + Subsequent Typing Merged ====================

    @Test
    fun `enter plus subsequent typing - merged as one undo step`() {
        // Simulates: user types on line 1, hits Enter, types on line 2
        // Expected: typing before Enter = one undo step, Enter + typing after = one undo step

        // Initial state
        editorState.initFromText("")
        undoManager.beginEditingLine(editorState, 0)

        // User types "hello" on line 1
        editorState.initFromText("hello")

        // User hits Enter - using prepareForStructuralChange (commits prior typing)
        undoManager.prepareForStructuralChange(editorState)

        // Perform the split
        editorState.initFromText("hello\n")
        editorState.focusedLineIndex = 1

        // Continue pending state on new line (groups Enter + subsequent typing)
        undoManager.continueAfterStructuralChange(1)

        // User types "world" on line 2
        editorState.initFromText("hello\nworld")

        // First undo: should restore to "hello" (before Enter, undoes Enter + "world")
        val snapshot1 = undoManager.undo(editorState)
        assertNotNull(snapshot1)
        assertEquals(listOf("hello"), snapshot1!!.lineContents)

        // Restore state
        editorState.initFromText("hello")

        // Second undo: should restore to "" (before typing "hello")
        assertTrue(undoManager.canUndo)
        val snapshot2 = undoManager.undo(editorState)
        assertEquals(listOf(""), snapshot2!!.lineContents)

        // No more undos
        assertFalse(undoManager.canUndo)
    }

    @Test
    fun `enter without subsequent typing - still undoable`() {
        // Simulates: user types on line 1, hits Enter, immediately undoes
        // Expected: first undo restores to before Enter (with typed text)

        editorState.initFromText("")
        undoManager.beginEditingLine(editorState, 0)

        // User types "hello"
        editorState.initFromText("hello")

        // User hits Enter
        undoManager.prepareForStructuralChange(editorState)
        editorState.initFromText("hello\n")
        editorState.focusedLineIndex = 1
        undoManager.continueAfterStructuralChange(1)

        // Immediately undo (no typing after Enter)
        val snapshot1 = undoManager.undo(editorState)
        assertNotNull(snapshot1)
        assertEquals(listOf("hello"), snapshot1!!.lineContents)

        editorState.initFromText("hello")

        // Second undo restores to before typing
        val snapshot2 = undoManager.undo(editorState)
        assertEquals(listOf(""), snapshot2!!.lineContents)

        assertFalse(undoManager.canUndo)
    }

    @Test
    fun `multiple enters - each creates undo boundary`() {
        // User types "a", Enter, types "b", Enter, types "c"
        // Should have 3 undo points: a, a+b, a+b+c

        editorState.initFromText("")
        undoManager.beginEditingLine(editorState, 0)

        // Type "a"
        editorState.initFromText("a")

        // First Enter
        undoManager.prepareForStructuralChange(editorState)
        editorState.initFromText("a\n")
        editorState.focusedLineIndex = 1
        undoManager.continueAfterStructuralChange(1)

        // Type "b"
        editorState.initFromText("a\nb")

        // Second Enter
        undoManager.prepareForStructuralChange(editorState)
        editorState.initFromText("a\nb\n")
        editorState.focusedLineIndex = 2
        undoManager.continueAfterStructuralChange(2)

        // Type "c"
        editorState.initFromText("a\nb\nc")

        // First undo: removes Enter2 + "c", restores to a\nb
        val snapshot1 = undoManager.undo(editorState)
        assertEquals(listOf("a", "b"), snapshot1!!.lineContents)

        editorState.initFromText("a\nb")

        // Second undo: removes Enter1 + "b", restores to a
        val snapshot2 = undoManager.undo(editorState)
        assertEquals(listOf("a"), snapshot2!!.lineContents)

        editorState.initFromText("a")

        // Third undo: removes "a", restores to empty
        val snapshot3 = undoManager.undo(editorState)
        assertEquals(listOf(""), snapshot3!!.lineContents)

        assertFalse(undoManager.canUndo)
    }

    // ==================== Line Merge Scenarios ====================

    @Test
    fun `merge creates separate undo step from typing`() {
        // User types, then backspaces to merge lines
        // Merge should be its own undo step

        editorState.initFromText("line1\nline2")
        editorState.focusedLineIndex = 1
        undoManager.beginEditingLine(editorState, 1)

        // User types on line 2
        editorState.initFromText("line1\nline2 modified")

        // User backspaces at start to merge - captureStateBeforeChange creates immediate undo point
        undoManager.captureStateBeforeChange(editorState)

        // Perform merge
        editorState.initFromText("line1line2 modified")
        editorState.focusedLineIndex = 0

        // Begin editing merged line
        undoManager.beginEditingLine(editorState, 0)

        // First undo: restores to before merge
        val snapshot1 = undoManager.undo(editorState)
        assertEquals(listOf("line1", "line2 modified"), snapshot1!!.lineContents)

        editorState.initFromText("line1\nline2 modified")

        // Second undo: restores to before typing
        val snapshot2 = undoManager.undo(editorState)
        assertEquals(listOf("line1", "line2"), snapshot2!!.lineContents)

        assertFalse(undoManager.canUndo)
    }

    @Test
    fun `typing after merge is separate undo step`() {
        // Merge happens, then user types on merged line
        // Merge and subsequent typing should be separate undo steps

        editorState.initFromText("line1\nline2")
        editorState.focusedLineIndex = 1
        undoManager.beginEditingLine(editorState, 1)

        // Merge immediately (no prior typing on line 2)
        undoManager.captureStateBeforeChange(editorState)
        editorState.initFromText("line1line2")
        editorState.focusedLineIndex = 0
        undoManager.beginEditingLine(editorState, 0)

        // User types after merge
        editorState.initFromText("line1line2 extra")

        // Commit the typing
        undoManager.commitPendingUndoState(editorState)

        // First undo: removes typing after merge
        val snapshot1 = undoManager.undo(editorState)
        assertEquals(listOf("line1line2"), snapshot1!!.lineContents)

        editorState.initFromText("line1line2")

        // Second undo: undoes the merge
        val snapshot2 = undoManager.undo(editorState)
        assertEquals(listOf("line1", "line2"), snapshot2!!.lineContents)

        assertFalse(undoManager.canUndo)
    }

    // ==================== Baseline (Floor) State ====================
    // These tests verify the baseline mechanism that prevents accidentally
    // undoing to an empty or wrong document state.

    @Test
    fun `setBaseline captures current state`() {
        editorState.initFromText("loaded content")
        editorState.focusedLineIndex = 0
        editorState.lines[0].updateFull("loaded content", 7) // cursor at position 7

        undoManager.setBaseline(editorState)

        assertTrue(undoManager.hasBaseline)
    }

    @Test
    fun `hasBaseline is false initially`() {
        assertFalse(undoManager.hasBaseline)
    }

    @Test
    fun `canUndo is false right after setBaseline - nothing to undo yet`() {
        editorState.initFromText("baseline content")
        undoManager.setBaseline(editorState)

        // No undo history and we're at baseline - nothing to undo
        assertFalse("canUndo should be false right after setBaseline", undoManager.canUndo)
    }

    @Test
    fun `undo returns baseline after making an edit`() {
        editorState.initFromText("baseline content")
        undoManager.setBaseline(editorState)

        // Make an edit (must go through beginEditingLine/commit flow)
        undoManager.beginEditingLine(editorState, 0)
        editorState.initFromText("modified content")
        undoManager.commitPendingUndoState(editorState)

        // Now canUndo should be true
        assertTrue("canUndo should be true after making an edit", undoManager.canUndo)

        // Undo should return baseline
        val snapshot = undoManager.undo(editorState)
        assertNotNull(snapshot)
        assertEquals(listOf("baseline content"), snapshot!!.lineContents)
    }

    @Test
    fun `baseline is floor - multiple undos stop at baseline`() {
        // Set baseline
        editorState.initFromText("baseline")
        undoManager.setBaseline(editorState)

        // Create some history
        undoManager.beginEditingLine(editorState, 0)
        editorState.initFromText("edit1")
        undoManager.commitPendingUndoState(editorState)
        undoManager.beginEditingLine(editorState, 0)
        editorState.initFromText("edit2")
        undoManager.commitPendingUndoState(editorState)

        // First undo: edit2 -> edit1
        val snapshot1 = undoManager.undo(editorState)
        assertEquals(listOf("edit1"), snapshot1!!.lineContents)
        editorState.initFromText("edit1")

        // Second undo: edit1 -> baseline
        val snapshot2 = undoManager.undo(editorState)
        assertEquals(listOf("baseline"), snapshot2!!.lineContents)
        editorState.initFromText("baseline")

        // Third undo: canUndo is now false because we're at baseline
        assertFalse("canUndo should be false when at baseline", undoManager.canUndo)
        val snapshot3 = undoManager.undo(editorState)
        assertNull("undo should return null when already at baseline", snapshot3)
    }

    @Test
    fun `baseline prevents undoing to empty document`() {
        // This is the critical safety test - loaded content should never be lost

        // Simulate note loading with content
        editorState.initFromText("Important user data\nLine 2\nLine 3")
        undoManager.setBaseline(editorState)
        undoManager.beginEditingLine(editorState, 0)

        // User makes edits
        editorState.initFromText("Modified data")
        undoManager.commitPendingUndoState(editorState)

        // Undo all the way - should stop at baseline, never empty
        var undoCount = 0
        while (undoManager.canUndo && undoCount < 100) { // safety limit
            val snapshot = undoManager.undo(editorState)
            assertNotNull(snapshot)
            assertTrue("Undo should never result in empty content",
                snapshot!!.lineContents.isNotEmpty())
            assertTrue("Undo should never lose all content",
                snapshot.lineContents.any { it.isNotEmpty() })
            editorState.initFromText(snapshot.lineContents.joinToString("\n"))
            undoCount++
        }

        // Should have reached baseline
        assertEquals(listOf("Important user data", "Line 2", "Line 3"),
            editorState.lines.map { it.text })
    }

    @Test
    fun `reset clears baseline`() {
        editorState.initFromText("baseline")
        undoManager.setBaseline(editorState)
        assertTrue(undoManager.hasBaseline)

        undoManager.reset()

        assertFalse(undoManager.hasBaseline)
        assertFalse(undoManager.canUndo)
    }

    @Test
    fun `exportState includes baseline`() {
        editorState.initFromText("baseline content")
        undoManager.setBaseline(editorState)

        val exported = undoManager.exportState()

        assertNotNull(exported.baselineSnapshot)
        assertEquals(listOf("baseline content"), exported.baselineSnapshot!!.lineContents)
    }

    @Test
    fun `importState restores baseline`() {
        editorState.initFromText("baseline content")
        undoManager.setBaseline(editorState)

        val exported = undoManager.exportState()

        // Reset and import
        undoManager.reset()
        assertFalse(undoManager.hasBaseline)

        undoManager.importState(exported)

        assertTrue(undoManager.hasBaseline)
        // canUndo is false because we imported state that was at baseline
        assertFalse("canUndo should be false after importing baseline state", undoManager.canUndo)

        // Since we're at baseline, undo returns null
        val snapshot = undoManager.undo(editorState)
        assertNull("undo should return null when at baseline", snapshot)
    }

    @Test
    fun `paste scenario - baseline protects loaded content`() {
        // This tests the exact scenario that caused the original bug:
        // 1. Note loads with content
        // 2. User pastes (without prior typing)
        // 3. User undoes
        // Expected: should restore to loaded content, not empty

        // Step 1: Note loads with content, baseline set
        editorState.initFromText("Original note content")
        undoManager.setBaseline(editorState)
        undoManager.beginEditingLine(editorState, 0)

        // Step 2: User pastes immediately (simulating the bug scenario)
        // With the old bug, commitUndoState wouldn't create an undo point
        // because no content had changed yet. With baseline, we're protected.

        // captureStateBeforeChange is now used for paste
        undoManager.captureStateBeforeChange(editorState)

        // Paste happens
        editorState.initFromText("PASTED CONTENT")
        undoManager.beginEditingLine(editorState, 0)

        // Step 3: User undoes
        val snapshot = undoManager.undo(editorState)

        // Should restore to original content, not empty
        assertNotNull(snapshot)
        assertEquals(listOf("Original note content"), snapshot!!.lineContents)
    }

    @Test
    fun `paste with captureStateBeforeChange creates proper undo point`() {
        // This tests the correct paste handling: captureStateBeforeChange creates
        // an undo point so we can undo back to the state before paste.

        // Note loads with content, baseline set
        editorState.initFromText("Original content")
        undoManager.setBaseline(editorState)
        undoManager.beginEditingLine(editorState, 0)

        // Correct paste handling: captureStateBeforeChange creates undo point
        undoManager.captureStateBeforeChange(editorState)

        // Paste happens
        editorState.initFromText("Pasted stuff")
        undoManager.beginEditingLine(editorState, 0)

        // Undo should restore original content
        val snapshot = undoManager.undo(editorState)

        assertNotNull(snapshot)
        assertEquals(listOf("Original content"), snapshot!!.lineContents)
    }

    @Test
    fun `baseline cursor position is restored`() {
        editorState.initFromText("content")
        editorState.focusedLineIndex = 0
        editorState.lines[0].updateFull("content", 4) // cursor at position 4

        undoManager.setBaseline(editorState)

        // Make a proper edit through the undo system
        undoManager.beginEditingLine(editorState, 0)
        editorState.initFromText("modified")
        undoManager.commitPendingUndoState(editorState)

        // Undo
        val snapshot = undoManager.undo(editorState)

        assertEquals(4, snapshot!!.cursorPosition)
        assertEquals(0, snapshot.focusedLineIndex)
    }

    @Test
    fun `baseline with multiline content`() {
        editorState.initFromText("line1\nline2\nline3")
        editorState.focusedLineIndex = 1
        undoManager.setBaseline(editorState)

        // Make a proper edit through the undo system
        undoManager.beginEditingLine(editorState, 1)
        editorState.initFromText("single line")
        undoManager.commitPendingUndoState(editorState)

        // Undo
        val snapshot = undoManager.undo(editorState)

        assertEquals(3, snapshot!!.lineContents.size)
        assertEquals("line1", snapshot.lineContents[0])
        assertEquals("line2", snapshot.lineContents[1])
        assertEquals("line3", snapshot.lineContents[2])
        assertEquals(1, snapshot.focusedLineIndex)
    }

    @Test
    fun `redo after baseline undo works correctly`() {
        // Set baseline
        editorState.initFromText("baseline")
        undoManager.setBaseline(editorState)

        // Make a proper edit through the undo system
        undoManager.beginEditingLine(editorState, 0)
        editorState.initFromText("modified")
        undoManager.commitPendingUndoState(editorState)

        // Undo to baseline
        undoManager.undo(editorState)
        editorState.initFromText("baseline")

        // Redo should restore modified state
        assertTrue(undoManager.canRedo)
        val snapshot = undoManager.redo(editorState)
        assertEquals(listOf("modified"), snapshot!!.lineContents)
    }

    @Test
    fun `new edits after baseline undo clear redo stack`() {
        // Set baseline
        editorState.initFromText("baseline")
        undoManager.setBaseline(editorState)

        // Make a proper edit through the undo system
        undoManager.beginEditingLine(editorState, 0)
        editorState.initFromText("modified")
        undoManager.commitPendingUndoState(editorState)

        // Undo to baseline
        undoManager.undo(editorState)
        editorState.initFromText("baseline")
        assertTrue(undoManager.canRedo)

        // Make new edit - should clear redo
        undoManager.beginEditingLine(editorState, 0)
        editorState.initFromText("new edit")
        undoManager.commitPendingUndoState(editorState)

        assertFalse(undoManager.canRedo)
    }

    @Test
    fun `canUndo becomes false after undoing to baseline`() {
        // Set baseline
        editorState.initFromText("baseline")
        undoManager.setBaseline(editorState)

        // Make an edit
        undoManager.beginEditingLine(editorState, 0)
        editorState.initFromText("edited")
        undoManager.commitPendingUndoState(editorState)

        assertTrue("Should be able to undo the edit", undoManager.canUndo)

        // Undo to baseline
        val snapshot = undoManager.undo(editorState)
        assertEquals(listOf("baseline"), snapshot!!.lineContents)

        // Now canUndo should be false - we're at the floor
        assertFalse("canUndo should be false after reaching baseline", undoManager.canUndo)
    }

    @Test
    fun `undo returns null when already at baseline`() {
        // Set baseline
        editorState.initFromText("baseline")
        undoManager.setBaseline(editorState)

        // Make an edit
        undoManager.beginEditingLine(editorState, 0)
        editorState.initFromText("edited")
        undoManager.commitPendingUndoState(editorState)

        // Undo to baseline
        undoManager.undo(editorState)
        editorState.initFromText("baseline")

        // Try to undo again - should return null
        val snapshot = undoManager.undo(editorState)
        assertNull("undo should return null when already at baseline", snapshot)
    }

    @Test
    fun `no-op undos do not add to redo stack`() {
        // If current state already matches the undo target, don't add to redo
        // (this prevents accumulating useless redo entries)

        editorState.initFromText("baseline")
        undoManager.setBaseline(editorState)

        // Make an edit
        undoManager.beginEditingLine(editorState, 0)
        editorState.initFromText("edited")
        undoManager.commitPendingUndoState(editorState)

        // Undo to baseline
        undoManager.undo(editorState)
        editorState.initFromText("baseline")

        // Redo stack should have exactly 1 entry (the "edited" state)
        assertTrue(undoManager.canRedo)

        // Now if we redo and undo again, redo stack should still have 1 entry
        undoManager.redo(editorState)
        editorState.initFromText("edited")
        undoManager.undo(editorState)
        editorState.initFromText("baseline")

        // Should still be able to redo
        assertTrue(undoManager.canRedo)
    }

    @Test
    fun `new edit after baseline resets isAtBaseline flag`() {
        // Set baseline
        editorState.initFromText("baseline")
        undoManager.setBaseline(editorState)

        // Make an edit
        undoManager.beginEditingLine(editorState, 0)
        editorState.initFromText("edit1")
        undoManager.commitPendingUndoState(editorState)

        // Undo to baseline
        undoManager.undo(editorState)
        editorState.initFromText("baseline")
        assertFalse("At baseline, canUndo should be false", undoManager.canUndo)

        // Make a new edit - this should reset the isAtBaseline flag
        undoManager.beginEditingLine(editorState, 0)
        editorState.initFromText("edit2")
        undoManager.commitPendingUndoState(editorState)

        // Now canUndo should be true again
        assertTrue("After new edit, canUndo should be true", undoManager.canUndo)

        // And we should be able to undo back to baseline
        val snapshot = undoManager.undo(editorState)
        assertEquals(listOf("baseline"), snapshot!!.lineContents)
    }

    @Test
    fun `exportState includes isAtBaseline flag`() {
        editorState.initFromText("baseline")
        undoManager.setBaseline(editorState)

        // Right after setBaseline, isAtBaseline is true (nothing to undo yet)
        var exported = undoManager.exportState()
        assertTrue("isAtBaseline should be true right after setBaseline", exported.isAtBaseline)

        // Make edit - isAtBaseline becomes false
        undoManager.beginEditingLine(editorState, 0)
        editorState.initFromText("edited")
        undoManager.commitPendingUndoState(editorState)

        exported = undoManager.exportState()
        assertFalse("isAtBaseline should be false after making an edit", exported.isAtBaseline)

        // Undo to baseline - isAtBaseline becomes true again
        undoManager.undo(editorState)
        editorState.initFromText("baseline")

        exported = undoManager.exportState()
        assertTrue("isAtBaseline should be true after undoing to baseline", exported.isAtBaseline)
    }

    @Test
    fun `importState restores isAtBaseline flag`() {
        editorState.initFromText("baseline")
        undoManager.setBaseline(editorState)

        // Make edit and undo to baseline
        undoManager.beginEditingLine(editorState, 0)
        editorState.initFromText("edited")
        undoManager.commitPendingUndoState(editorState)
        undoManager.undo(editorState)
        editorState.initFromText("baseline")

        // Export state (should have isAtBaseline = true)
        val exported = undoManager.exportState()
        assertTrue(exported.isAtBaseline)

        // Reset and import
        undoManager.reset()
        assertTrue("After reset, canUndo should be false", !undoManager.canUndo)

        undoManager.importState(exported)

        // canUndo should still be false because isAtBaseline was restored
        assertFalse("After import with isAtBaseline=true, canUndo should be false", undoManager.canUndo)
    }

    @Test
    fun `CRITICAL - baseline must contain actual content not empty state`() {
        // This test verifies the fix for a bug where setBaseline was called
        // before editorState was populated, resulting in an empty baseline.
        //
        // The fix requires calling editorState.initFromText() BEFORE setBaseline().
        // If someone removes that call, this test should fail.

        val loadedContent = "Important user data\nLine 2\nLine 3"

        // Simulate the CORRECT loading sequence:
        // 1. Update editorState with loaded content FIRST
        editorState.initFromText(loadedContent)
        // 2. THEN set baseline
        undoManager.setBaseline(editorState)

        // Verify baseline captured the actual content, not empty
        val exported = undoManager.exportState()
        assertNotNull("Baseline should exist", exported.baselineSnapshot)
        assertEquals(
            "Baseline must contain the loaded content",
            listOf("Important user data", "Line 2", "Line 3"),
            exported.baselineSnapshot!!.lineContents
        )
        assertTrue(
            "Baseline content should not be empty",
            exported.baselineSnapshot!!.lineContents.any { it.isNotEmpty() }
        )
    }

    @Test
    fun `CRITICAL - setting baseline on empty editorState captures empty content`() {
        // This test documents the bug behavior - if you set baseline before
        // populating editorState, you get an empty baseline which defeats the purpose.
        //
        // This is a "characterization test" - it shows what happens if you do it wrong.

        // DO NOT populate editorState - simulates the bug
        // editorState is empty by default

        undoManager.setBaseline(editorState)

        // Baseline exists but contains empty content - THIS IS THE BUG
        val exported = undoManager.exportState()
        assertNotNull(exported.baselineSnapshot)
        assertEquals(
            "Empty editorState results in empty baseline - this is why order matters!",
            listOf(""),
            exported.baselineSnapshot!!.lineContents
        )
    }

    // ==================== App Flow Tests ====================
    // These tests verify the exact flow that happens in the actual app

    @Test
    fun `app flow - typing and enter activates undo`() {
        // This test mimics the EXACT flow that happens when:
        // 1. App loads with content
        // 2. User types on the first line
        // 3. User presses Enter
        // After this, undo should be available.

        // Step 1: Simulate note loading (from CurrentNoteScreen)
        editorState.initFromText("baseline content")
        undoManager.setBaseline(editorState)
        undoManager.beginEditingLine(editorState, editorState.focusedLineIndex)
        // requestFocusUpdate() would be called here, but it doesn't affect undo manager

        // Verify initial state - canUndo should be false
        assertFalse("canUndo should be false right after load", undoManager.canUndo)

        // Step 2: Focus is received on line 0 (HangingIndentEditor calls controller.focusLine)
        // When focusLine is called with same line index, it doesn't change anything
        // (simulated by not calling anything here since lineIndex == focusedLineIndex)

        // Step 3: User types "hello" - content becomes "baseline contenthello"
        // This happens through LineImeState -> EditorController.updateLineContent -> LineState.updateContent
        editorState.lines[0].updateContent("baseline contenthello", "baseline contenthello".length)

        // Step 4: User presses Enter - this calls prepareForStructuralChange
        undoManager.prepareForStructuralChange(editorState)

        // After this, undo should be available
        assertTrue("canUndo should be true after typing and Enter", undoManager.canUndo)
    }

    @Test
    fun `app flow - focus change after typing activates undo`() {
        // Similar test but with focus change instead of Enter

        // Step 1: Load
        editorState.initFromText("line1\nline2")
        undoManager.setBaseline(editorState)
        undoManager.beginEditingLine(editorState, 0)

        assertFalse("canUndo should be false right after load", undoManager.canUndo)

        // Step 2: User types on line 0
        editorState.lines[0].updateContent("line1hello", "line1hello".length)

        // Step 3: User taps on line 1 - this triggers focus change
        // EditorController.focusLine commits pending state and begins editing new line
        undoManager.commitPendingUndoState(editorState)
        editorState.focusedLineIndex = 1
        undoManager.beginEditingLine(editorState, 1)

        // After focus change, undo should be available
        assertTrue("canUndo should be true after typing and focus change", undoManager.canUndo)
    }

    @Test
    fun `app flow - IME enter key through updateLineContent activates undo`() {
        // This test uses EditorController to verify the actual code path
        // when user presses Enter via IME (which goes through updateLineContent)

        // Step 1: Load
        editorState.initFromText("baseline content")
        val controller = EditorController(editorState)
        controller.undoManager.setBaseline(editorState)
        controller.undoManager.beginEditingLine(editorState, editorState.focusedLineIndex)

        assertFalse("canUndo should be false right after load", controller.undoManager.canUndo)

        // Step 2: User types "hello" - this is a separate IME call that modifies content
        // In real app: commitText("hello") -> updateLineContent("baseline contenthello", ...)
        controller.updateLineContent(0, "baseline contenthello", "baseline contenthello".length)

        // canUndo should now be true because there are uncommitted changes
        // (the undo button activates as soon as the user starts typing, even before focus changes)
        assertTrue("canUndo should be true after typing (uncommitted changes)", controller.undoManager.canUndo)

        // Step 3: User presses Enter - this is another IME call with newline
        // In real app: commitText("\n") -> updateLineContent adds newline to current content
        controller.updateLineContent(0, "baseline contenthello\n", "baseline contenthello\n".length)

        // After Enter, undo should be available
        assertTrue("canUndo should be true after IME Enter key", controller.undoManager.canUndo)
    }

    // ==================== Cursor Position Restoration ====================
    // These tests verify that cursor position is correctly restored after undo/redo
    // in ALL scenarios. This is critical for good UX.

    @Test
    fun `undo after typing restores cursor to position before typing`() {
        // User focuses line with cursor at position 3, types "abc"
        // Undo should restore cursor to position 3

        editorState.initFromText("hello")
        editorState.focusedLineIndex = 0
        editorState.lines[0].updateFull("hello", 3) // Cursor at position 3

        undoManager.beginEditingLine(editorState, 0)

        // User types "abc" at cursor position
        editorState.lines[0].updateFull("helabc llo", 6) // Cursor moved to 6
        undoManager.commitPendingUndoState(editorState)

        val snapshot = undoManager.undo(editorState)

        assertEquals("Cursor should be at position 3", 3, snapshot!!.cursorPosition)
        assertEquals("Focus should be on line 0", 0, snapshot.focusedLineIndex)
    }

    @Test
    fun `undo after Enter restores cursor to end of original line`() {
        // User has "hello" with cursor at end (position 5)
        // User presses Enter
        // Undo should restore cursor to position 5 on line 0

        editorState.initFromText("hello")
        editorState.focusedLineIndex = 0
        editorState.lines[0].updateFull("hello", 5) // Cursor at end

        undoManager.beginEditingLine(editorState, 0)

        // User presses Enter - prepareForStructuralChange captures state
        undoManager.prepareForStructuralChange(editorState)

        // Perform split
        editorState.lines[0].updateFull("hello", 5)
        editorState.lines.add(1, LineState("", 0))
        editorState.focusedLineIndex = 1

        undoManager.continueAfterStructuralChange(1)

        // Undo
        val snapshot = undoManager.undo(editorState)

        assertEquals("Cursor should be at position 5", 5, snapshot!!.cursorPosition)
        assertEquals("Focus should be on line 0", 0, snapshot.focusedLineIndex)
        assertEquals("Should be single line", 1, snapshot.lineContents.size)
    }

    @Test
    fun `CRITICAL - cursor position after tap then Enter - simulates real app flow`() {
        // This test simulates the EXACT real app flow:
        // 1. Note loads, beginEditingLine called with cursor at 0
        // 2. User taps to reposition cursor (cursor moves but beginEditingLine NOT called again)
        // 3. User hits Enter
        // 4. Undo should restore cursor to position where it was when Enter was pressed

        // Step 1: Note loads with cursor at position 0 (default)
        editorState.initFromText("hello")
        editorState.focusedLineIndex = 0
        editorState.lines[0].updateFull("hello", 0) // Cursor at 0 (default after load)

        // beginEditingLine is called at load time - captures cursor=0
        undoManager.beginEditingLine(editorState, 0)

        // Step 2: User taps to reposition cursor to end (position 5)
        // NOTE: In real app, this does NOT call beginEditingLine again (same line)
        editorState.lines[0].updateFull("hello", 5) // Cursor moved to 5

        // Step 3: User hits Enter
        undoManager.prepareForStructuralChange(editorState)

        // Perform split
        editorState.lines.clear()
        editorState.lines.add(LineState("hello", 5))
        editorState.lines.add(LineState("", 0))
        editorState.focusedLineIndex = 1

        undoManager.continueAfterStructuralChange(1)

        // Step 4: User hits Undo
        val snapshot = undoManager.undo(editorState)

        // CRITICAL: Cursor should be at position 5 (where it was when Enter pressed),
        // NOT position 0 (where it was when beginEditingLine was called)
        assertEquals(
            "Cursor must be at position 5 (where user pressed Enter), not 0",
            5,
            snapshot!!.cursorPosition
        )
        assertEquals("Focus should be on line 0", 0, snapshot.focusedLineIndex)
        assertEquals("Should restore to single line", listOf("hello"), snapshot.lineContents)
    }

    @Test
    fun `undo after Enter mid-line restores cursor to split position`() {
        // User has "hello world" with cursor at position 5 (after "hello")
        // User presses Enter, splitting into "hello" and " world"
        // Undo should restore cursor to position 5

        editorState.initFromText("hello world")
        editorState.focusedLineIndex = 0
        editorState.lines[0].updateFull("hello world", 5) // Cursor after "hello"

        undoManager.beginEditingLine(editorState, 0)
        undoManager.prepareForStructuralChange(editorState)

        // Perform split at cursor
        editorState.lines.clear()
        editorState.lines.add(LineState("hello", 5))
        editorState.lines.add(LineState(" world", 0))
        editorState.focusedLineIndex = 1

        undoManager.continueAfterStructuralChange(1)

        val snapshot = undoManager.undo(editorState)

        assertEquals("Cursor should be at position 5", 5, snapshot!!.cursorPosition)
        assertEquals("Focus should be on line 0", 0, snapshot.focusedLineIndex)
    }

    @Test
    fun `undo after bullet toggle restores cursor position`() {
        // User has "item" with cursor at position 2
        // User toggles bullet (becomes "• item")
        // Undo should restore cursor to position 2

        editorState.initFromText("item")
        editorState.focusedLineIndex = 0
        editorState.lines[0].updateFull("item", 2) // Cursor at position 2

        undoManager.recordCommand(editorState, CommandType.BULLET)
        editorState.toggleBulletInternal() // Now "• item"
        undoManager.commitAfterCommand(editorState, CommandType.BULLET)

        val snapshot = undoManager.undo(editorState)

        assertEquals("Cursor should be at position 2", 2, snapshot!!.cursorPosition)
        assertEquals(listOf("item"), snapshot.lineContents)
    }

    @Test
    fun `undo after indent restores cursor position`() {
        // User has "item" with cursor at position 3
        // User indents (becomes "\titem")
        // Undo should restore cursor to position 3

        editorState.initFromText("item")
        editorState.focusedLineIndex = 0
        editorState.lines[0].updateFull("item", 3)

        undoManager.recordCommand(editorState, CommandType.INDENT)
        editorState.indentInternal() // Now "\titem"
        undoManager.commitPendingUndoState(editorState)

        val snapshot = undoManager.undo(editorState)

        assertEquals("Cursor should be at position 3", 3, snapshot!!.cursorPosition)
        assertEquals(listOf("item"), snapshot.lineContents)
    }

    @Test
    fun `undo after line merge restores cursor to original position`() {
        // User has two lines, cursor at start of line 2
        // User backspaces to merge
        // Undo should restore cursor to start of line 2 (position 0)

        editorState.initFromText("line1\nline2")
        editorState.focusedLineIndex = 1
        editorState.lines[1].updateFull("line2", 0) // Cursor at start of line 2

        undoManager.beginEditingLine(editorState, 1)
        undoManager.captureStateBeforeChange(editorState)

        // Perform merge
        editorState.lines.clear()
        editorState.lines.add(LineState("line1line2", 5)) // Cursor at merge point
        editorState.focusedLineIndex = 0

        undoManager.beginEditingLine(editorState, 0)

        val snapshot = undoManager.undo(editorState)

        assertEquals("Cursor should be at position 0", 0, snapshot!!.cursorPosition)
        assertEquals("Focus should be on line 1", 1, snapshot.focusedLineIndex)
        assertEquals(listOf("line1", "line2"), snapshot.lineContents)
    }

    @Test
    fun `undo after paste restores cursor to position before paste`() {
        // User has "hello" with cursor at position 2
        // User pastes "XYZ" (becomes "heXYZllo")
        // Undo should restore cursor to position 2

        editorState.initFromText("hello")
        editorState.focusedLineIndex = 0
        editorState.lines[0].updateFull("hello", 2)

        // captureStateBeforeChange is used for paste
        undoManager.captureStateBeforeChange(editorState)

        // Perform paste
        editorState.lines[0].updateFull("heXYZllo", 5) // Cursor after pasted text
        undoManager.beginEditingLine(editorState, 0)

        val snapshot = undoManager.undo(editorState)

        assertEquals("Cursor should be at position 2", 2, snapshot!!.cursorPosition)
        assertEquals(listOf("hello"), snapshot.lineContents)
    }

    @Test
    fun `redo restores cursor to position after original action`() {
        // After undo, redo should restore cursor to where it was after the action

        editorState.initFromText("hello")
        editorState.focusedLineIndex = 0
        editorState.lines[0].updateFull("hello", 2)

        undoManager.beginEditingLine(editorState, 0)

        // Type "XY" at position 2
        editorState.lines[0].updateFull("heXYllo", 4) // Cursor now at 4
        undoManager.commitPendingUndoState(editorState)

        // Undo
        undoManager.undo(editorState)
        editorState.initFromText("hello")
        editorState.lines[0].updateFull("hello", 2)

        // Redo
        val snapshot = undoManager.redo(editorState)

        assertEquals("Cursor should be at position 4 (after typed text)", 4, snapshot!!.cursorPosition)
        assertEquals(listOf("heXYllo"), snapshot.lineContents)
    }

    @Test
    fun `undo multiline edit restores cursor to correct line and position`() {
        // User edits on line 2, undo should restore focus to line 2

        editorState.initFromText("line0\nline1\nline2")
        editorState.focusedLineIndex = 2
        editorState.lines[2].updateFull("line2", 3) // Cursor at position 3 on line 2

        undoManager.beginEditingLine(editorState, 2)

        // Edit line 2
        editorState.lines[2].updateFull("line2 modified", 14)
        undoManager.commitPendingUndoState(editorState)

        val snapshot = undoManager.undo(editorState)

        assertEquals("Cursor should be at position 3", 3, snapshot!!.cursorPosition)
        assertEquals("Focus should be on line 2", 2, snapshot.focusedLineIndex)
    }

    @Test
    fun `cursor position is coerced if line becomes shorter after undo`() {
        // Edge case: if the restored line is shorter than the saved cursor position,
        // cursor should be coerced to end of line

        editorState.initFromText("short")
        editorState.focusedLineIndex = 0
        editorState.lines[0].updateFull("short", 5)

        undoManager.beginEditingLine(editorState, 0)

        // Make line longer
        editorState.lines[0].updateFull("very long line", 14)
        undoManager.commitPendingUndoState(editorState)

        // This simulates a weird edge case - undo returns short line
        // but if cursor was saved at position 14, it needs to be coerced
        val snapshot = undoManager.undo(editorState)

        assertEquals("Cursor should be at end of line (position 5)", 5, snapshot!!.cursorPosition)
    }

    // ==================== Directive Edit Undo/Redo ====================
    // These tests verify that directive edit operations create proper undo boundaries
    // and can be undone/redone correctly.

    @Test
    fun `directive edit creates undo point via EditorController`() {
        // When a directive is edited via confirmDirectiveEdit(), it should create
        // an undo point that allows restoring the original directive text.

        // Setup: line with a directive "[42]"
        editorState.initFromText("some text [42] more text")
        val controller = EditorController(editorState)
        controller.undoManager.setBaseline(editorState)
        controller.undoManager.beginEditingLine(editorState, 0)

        // Edit the directive from "[42]" to "[99]"
        // "[42]" starts at position 10, ends at position 14
        controller.confirmDirectiveEdit(
            lineIndex = 0,
            startOffset = 10,
            endOffset = 14,
            newText = "[99]"
        )

        // Content should be updated
        assertEquals("some text [99] more text", editorState.lines[0].text)

        // Should be able to undo
        assertTrue("Should be able to undo after directive edit", controller.canUndo)

        val snapshot = controller.undo()
        assertNotNull(snapshot)
        assertEquals(listOf("some text [42] more text"), snapshot!!.lineContents)
    }

    @Test
    fun `directive edit undo restores original content`() {
        // After undoing a directive edit, the original directive text should be restored

        editorState.initFromText("result: [100]")
        val controller = EditorController(editorState)
        controller.undoManager.setBaseline(editorState)
        controller.undoManager.beginEditingLine(editorState, 0)

        // Edit "[100]" to "[999]" (starts at 8, ends at 13)
        controller.confirmDirectiveEdit(0, 8, 13, "[999]")
        assertEquals("result: [999]", editorState.lines[0].text)

        // Undo
        val snapshot = controller.undo()
        assertEquals(listOf("result: [100]"), snapshot!!.lineContents)

        // editorState should be restored via controller
        assertEquals("result: [100]", editorState.lines[0].text)
    }

    @Test
    fun `consecutive directive edits create separate undo steps`() {
        // Each confirmDirectiveEdit() call should create its own undo step,
        // unlike indent commands which are grouped.

        editorState.initFromText("values: [1] and [2]")
        val controller = EditorController(editorState)
        controller.undoManager.setBaseline(editorState)
        controller.undoManager.beginEditingLine(editorState, 0)

        // Edit first directive "[1]" -> "[10]" (starts at 8, ends at 11)
        controller.confirmDirectiveEdit(0, 8, 11, "[10]")
        assertEquals("values: [10] and [2]", editorState.lines[0].text)

        // Edit second directive "[2]" -> "[20]" (now starts at 17, ends at 20)
        controller.confirmDirectiveEdit(0, 17, 20, "[20]")
        assertEquals("values: [10] and [20]", editorState.lines[0].text)

        // First undo: should restore "[2]"
        val snapshot1 = controller.undo()
        assertEquals(listOf("values: [10] and [2]"), snapshot1!!.lineContents)

        // Second undo: should restore "[1]"
        val snapshot2 = controller.undo()
        assertEquals(listOf("values: [1] and [2]"), snapshot2!!.lineContents)
    }

    @Test
    fun `directive edit with invalid range does not modify content`() {
        // confirmDirectiveEdit should safely handle invalid ranges without modifying state
        // Note: State IS captured before the range validation, so undo may be available
        // but undoing will just restore the same content (a no-op).

        editorState.initFromText("short")
        val controller = EditorController(editorState)
        controller.undoManager.setBaseline(editorState)
        controller.undoManager.beginEditingLine(editorState, 0)

        // Try to edit with invalid range (beyond content length)
        controller.confirmDirectiveEdit(0, 10, 15, "invalid")

        // Content should not change
        assertEquals("short", editorState.lines[0].text)

        // Undo IS available (state was captured before validation),
        // but undoing will restore the same content
        assertTrue("canUndo should be true (state captured before validation)", controller.canUndo)
        val snapshot = controller.undo()
        assertEquals(listOf("short"), snapshot!!.lineContents)
    }

    @Test
    fun `directive edit with negative start offset does nothing`() {
        editorState.initFromText("[42]")
        val controller = EditorController(editorState)
        controller.undoManager.setBaseline(editorState)
        controller.undoManager.beginEditingLine(editorState, 0)

        // Try with negative start
        controller.confirmDirectiveEdit(0, -1, 4, "[99]")

        // Content should not change
        assertEquals("[42]", editorState.lines[0].text)
    }

    @Test
    fun `directive edit with start greater than end does nothing`() {
        editorState.initFromText("[42]")
        val controller = EditorController(editorState)
        controller.undoManager.setBaseline(editorState)
        controller.undoManager.beginEditingLine(editorState, 0)

        // Try with start > end
        controller.confirmDirectiveEdit(0, 3, 1, "[99]")

        // Content should not change
        assertEquals("[42]", editorState.lines[0].text)
    }

    @Test
    fun `directive edit on nonexistent line does nothing`() {
        editorState.initFromText("only one line")
        val controller = EditorController(editorState)
        controller.undoManager.setBaseline(editorState)
        controller.undoManager.beginEditingLine(editorState, 0)

        // Try to edit line that doesn't exist
        controller.confirmDirectiveEdit(5, 0, 4, "[99]")

        // Content should not change
        assertEquals("only one line", editorState.lines[0].text)
    }

    @Test
    fun `directive edit redo restores edited content`() {
        editorState.initFromText("test [42] here")
        val controller = EditorController(editorState)
        controller.undoManager.setBaseline(editorState)
        controller.undoManager.beginEditingLine(editorState, 0)

        // Edit "[42]" -> "[99]"
        controller.confirmDirectiveEdit(0, 5, 9, "[99]")
        assertEquals("test [99] here", editorState.lines[0].text)

        // Undo
        controller.undo()
        assertEquals("test [42] here", editorState.lines[0].text)

        // Redo
        assertTrue(controller.canRedo)
        val snapshot = controller.redo()
        assertEquals(listOf("test [99] here"), snapshot!!.lineContents)
        assertEquals("test [99] here", editorState.lines[0].text)
    }

    @Test
    fun `directive edit after typing creates two undo steps`() {
        // Typing before a directive edit should be a separate undo step

        editorState.initFromText("[42]")
        val controller = EditorController(editorState)
        controller.undoManager.setBaseline(editorState)
        controller.undoManager.beginEditingLine(editorState, 0)

        // Type some text before the directive
        controller.updateLineContent(0, "prefix [42]", 7)

        // Now edit the directive
        controller.confirmDirectiveEdit(0, 7, 11, "[99]")
        assertEquals("prefix [99]", editorState.lines[0].text)

        // First undo: restores directive to [42]
        val snapshot1 = controller.undo()
        assertEquals(listOf("prefix [42]"), snapshot1!!.lineContents)

        // Second undo: restores to original (before typing)
        val snapshot2 = controller.undo()
        assertEquals(listOf("[42]"), snapshot2!!.lineContents)
    }

    @Test
    fun `directive edit commits pending typing before creating undo point`() {
        // confirmDirectiveEdit should commit any pending typing first,
        // so typing before the directive edit can be undone separately.

        editorState.initFromText("start")
        val controller = EditorController(editorState)
        controller.undoManager.setBaseline(editorState)
        controller.undoManager.beginEditingLine(editorState, 0)

        // User types, adding " [42]"
        controller.updateLineContent(0, "start [42]", 10)

        // User edits the directive
        controller.confirmDirectiveEdit(0, 6, 10, "[100]")
        assertEquals("start [100]", editorState.lines[0].text)

        // First undo: restores [42] (the directive edit)
        val snapshot1 = controller.undo()
        assertEquals(listOf("start [42]"), snapshot1!!.lineContents)

        // Second undo: restores original (the typing)
        val snapshot2 = controller.undo()
        assertEquals(listOf("start"), snapshot2!!.lineContents)

        // No more undos
        assertFalse(controller.canUndo)
    }

    @Test
    fun `directive edit on line with prefix preserves prefix`() {
        // Directive edits work on content (without prefix), so prefixes should be preserved

        editorState.initFromText("• item [42] text")
        val controller = EditorController(editorState)
        controller.undoManager.setBaseline(editorState)
        controller.undoManager.beginEditingLine(editorState, 0)

        // Edit "[42]" in content - note that the line has prefix "• "
        // Full text is "• item [42] text", content is "item [42] text"
        // "[42]" is at content position 5-9
        val contentOffset = editorState.lines[0].content.indexOf("[42]")
        controller.confirmDirectiveEdit(0, contentOffset, contentOffset + 4, "[99]")

        // Check that prefix is preserved
        assertEquals("• item [99] text", editorState.lines[0].text)
        assertEquals("• ", editorState.lines[0].prefix)
        assertEquals("item [99] text", editorState.lines[0].content)

        // Undo should also preserve prefix
        val snapshot = controller.undo()
        assertEquals(listOf("• item [42] text"), snapshot!!.lineContents)
    }

    @Test
    fun `directive edit to same text creates undo point before it`() {
        // When a directive is "edited" to the same value, an undo point is still
        // created (state is captured before we check if content changed).
        // This means: first undo restores to before the directive edit attempt,
        // second undo restores to before the typing.

        editorState.initFromText("original")
        val controller = EditorController(editorState)
        controller.undoManager.setBaseline(editorState)
        controller.undoManager.beginEditingLine(editorState, 0)

        // Type some changes
        controller.updateLineContent(0, "original [42]", 13)

        // "Edit" directive to same value (user didn't change it)
        // This still captures state before the no-op, creating an undo point
        controller.confirmDirectiveEdit(0, 9, 13, "[42]")
        assertEquals("original [42]", editorState.lines[0].text)

        // First undo: restores to before the directive edit attempt
        // (which had the same content, so this is effectively a no-op restore)
        assertTrue(controller.canUndo)
        val snapshot1 = controller.undo()
        assertEquals(listOf("original [42]"), snapshot1!!.lineContents)

        // Second undo: restores to before the typing
        assertTrue(controller.canUndo)
        val snapshot2 = controller.undo()
        assertEquals(listOf("original"), snapshot2!!.lineContents)
    }

    @Test
    fun `checkbox toggle restores cursor position`() {
        editorState.initFromText("task item")
        editorState.focusedLineIndex = 0
        editorState.lines[0].updateFull("task item", 4) // Cursor at position 4

        undoManager.recordCommand(editorState, CommandType.CHECKBOX)
        editorState.toggleCheckboxInternal() // Now "☐ task item"
        undoManager.commitAfterCommand(editorState, CommandType.CHECKBOX)

        val snapshot = undoManager.undo(editorState)

        assertEquals("Cursor should be at position 4", 4, snapshot!!.cursorPosition)
        assertEquals(listOf("task item"), snapshot.lineContents)
    }

    @Test
    fun `multiple undos preserve correct cursor positions throughout`() {
        // Verify cursor position is correct through multiple undo operations

        // Initial state: cursor at position 2
        editorState.initFromText("ab")
        editorState.focusedLineIndex = 0
        editorState.lines[0].updateFull("ab", 2)
        undoManager.setBaseline(editorState)

        // Edit 1: add "c", cursor at 3
        undoManager.beginEditingLine(editorState, 0)
        editorState.lines[0].updateFull("abc", 3)
        undoManager.commitPendingUndoState(editorState)

        // Edit 2: add "d", cursor at 4
        undoManager.beginEditingLine(editorState, 0)
        editorState.lines[0].updateFull("abcd", 4)
        undoManager.commitPendingUndoState(editorState)

        // First undo: should restore cursor to 3
        val snapshot1 = undoManager.undo(editorState)
        assertEquals(3, snapshot1!!.cursorPosition)
        assertEquals(listOf("abc"), snapshot1.lineContents)

        editorState.initFromText("abc")
        editorState.lines[0].updateFull("abc", 3)

        // Second undo: should restore cursor to 2
        val snapshot2 = undoManager.undo(editorState)
        assertEquals(2, snapshot2!!.cursorPosition)
        assertEquals(listOf("ab"), snapshot2.lineContents)
    }
}
