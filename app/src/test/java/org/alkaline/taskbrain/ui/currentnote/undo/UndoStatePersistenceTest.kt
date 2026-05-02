package org.alkaline.taskbrain.ui.currentnote.undo

import org.alkaline.taskbrain.ui.currentnote.initFromText
import com.google.firebase.Timestamp
import org.alkaline.taskbrain.ui.currentnote.EditorState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class UndoStatePersistenceTest {

    // ==================== Basic Serialization Round-Trip ====================

    @Test
    fun `serialize and deserialize empty state`() {
        val state = UndoManagerState(
            undoStack = emptyList(),
            redoStack = emptyList(),
            baselineSnapshot = null,
            pendingSnapshot = null,
            editingLineIndex = null,
            lastCommandType = null
        )

        val json = UndoStatePersistence.serializeState(state)
        val restored = UndoStatePersistence.deserializeState(json)

        assertEquals(0, restored.undoStack.size)
        assertEquals(0, restored.redoStack.size)
        assertNull(restored.pendingSnapshot)
        assertNull(restored.editingLineIndex)
        assertNull(restored.lastCommandType)
    }

    @Test
    fun `serialize and deserialize state with undo stack`() {
        val snapshot = UndoSnapshot(
            lineContents = listOf("line1", "line2"),
            focusedLineIndex = 0,
            cursorPosition = 5,
            createdAlarm = null
        )
        val state = UndoManagerState(
            undoStack = listOf(snapshot),
            redoStack = emptyList(),
            baselineSnapshot = null,
            pendingSnapshot = null,
            editingLineIndex = null,
            lastCommandType = null
        )

        val json = UndoStatePersistence.serializeState(state)
        val restored = UndoStatePersistence.deserializeState(json)

        assertEquals(1, restored.undoStack.size)
        assertEquals(listOf("line1", "line2"), restored.undoStack[0].lineContents)
        assertEquals(0, restored.undoStack[0].focusedLineIndex)
        assertEquals(5, restored.undoStack[0].cursorPosition)
    }

    @Test
    fun `serialize and deserialize state with redo stack`() {
        val snapshot = UndoSnapshot(
            lineContents = listOf("content"),
            focusedLineIndex = 0,
            cursorPosition = 3,
            createdAlarm = null
        )
        val state = UndoManagerState(
            undoStack = emptyList(),
            redoStack = listOf(snapshot),
            baselineSnapshot = null,
            pendingSnapshot = null,
            editingLineIndex = null,
            lastCommandType = null
        )

        val json = UndoStatePersistence.serializeState(state)
        val restored = UndoStatePersistence.deserializeState(json)

        assertEquals(1, restored.redoStack.size)
        assertEquals(listOf("content"), restored.redoStack[0].lineContents)
    }

    @Test
    fun `serialize and deserialize state with pending snapshot`() {
        val pending = UndoSnapshot(
            lineContents = listOf("pending content"),
            focusedLineIndex = 2,
            cursorPosition = 7,
            createdAlarm = null
        )
        val state = UndoManagerState(
            undoStack = emptyList(),
            redoStack = emptyList(),
            baselineSnapshot = null,
            pendingSnapshot = pending,
            editingLineIndex = 2,
            lastCommandType = CommandType.INDENT
        )

        val json = UndoStatePersistence.serializeState(state)
        val restored = UndoStatePersistence.deserializeState(json)

        assertNotNull(restored.pendingSnapshot)
        assertEquals(listOf("pending content"), restored.pendingSnapshot!!.lineContents)
        assertEquals(2, restored.editingLineIndex)
        assertEquals(CommandType.INDENT, restored.lastCommandType)
    }

    // ==================== Command Type Serialization ====================

    @Test
    fun `serialize and deserialize all command types`() {
        for (commandType in CommandType.values()) {
            val state = UndoManagerState(
                undoStack = emptyList(),
                redoStack = emptyList(),
                baselineSnapshot = null,
                pendingSnapshot = null,
                editingLineIndex = null,
                lastCommandType = commandType
            )

            val json = UndoStatePersistence.serializeState(state)
            val restored = UndoStatePersistence.deserializeState(json)

            assertEquals(commandType, restored.lastCommandType)
        }
    }

    // ==================== Alarm Snapshot Serialization ====================

    @Test
    fun `serialize and deserialize snapshot with alarm`() {
        val alarm = AlarmSnapshot(
            id = "alarm_123",
            noteId = "note_456",
            lineContent = "Task with alarm",
            dueTime = Timestamp(Date(3000000)),
            stages = org.alkaline.taskbrain.data.Alarm.DEFAULT_STAGES
        )
        val snapshot = UndoSnapshot(
            lineContents = listOf("Task with alarm"),
            focusedLineIndex = 0,
            cursorPosition = 0,
            createdAlarm = alarm
        )
        val state = UndoManagerState(
            undoStack = listOf(snapshot),
            redoStack = emptyList(),
            baselineSnapshot = null,
            pendingSnapshot = null,
            editingLineIndex = null,
            lastCommandType = null
        )

        val json = UndoStatePersistence.serializeState(state)
        val restored = UndoStatePersistence.deserializeState(json)

        val restoredAlarm = restored.undoStack[0].createdAlarm
        assertNotNull(restoredAlarm)
        assertEquals("alarm_123", restoredAlarm!!.id)
        assertEquals("note_456", restoredAlarm.noteId)
        assertEquals("Task with alarm", restoredAlarm.lineContent)
        assertEquals(3000000, restoredAlarm.dueTime!!.toDate().time)
        assertEquals(3, restoredAlarm.stages.size)
    }

    @Test
    fun `serialize and deserialize alarm with null dueTime`() {
        val alarm = AlarmSnapshot(
            id = "alarm_id",
            noteId = "note_id",
            lineContent = "Content",
            dueTime = null,
            stages = emptyList()
        )
        val snapshot = UndoSnapshot(
            lineContents = listOf("Content"),
            focusedLineIndex = 0,
            cursorPosition = 0,
            createdAlarm = alarm
        )
        val state = UndoManagerState(
            undoStack = listOf(snapshot),
            redoStack = emptyList(),
            baselineSnapshot = null,
            pendingSnapshot = null,
            editingLineIndex = null,
            lastCommandType = null
        )

        val json = UndoStatePersistence.serializeState(state)
        val restored = UndoStatePersistence.deserializeState(json)

        val restoredAlarm = restored.undoStack[0].createdAlarm
        assertNotNull(restoredAlarm)
        assertNull(restoredAlarm!!.dueTime)
    }

    // ==================== Multi-line Content ====================

    @Test
    fun `serialize and deserialize multi-line content`() {
        val snapshot = UndoSnapshot(
            lineContents = listOf(
                "• First bullet",
                "\t☐ Indented checkbox",
                "",
                "Plain text",
                "☑ Checked item"
            ),
            focusedLineIndex = 3,
            cursorPosition = 10,
            createdAlarm = null
        )
        val state = UndoManagerState(
            undoStack = listOf(snapshot),
            redoStack = emptyList(),
            baselineSnapshot = null,
            pendingSnapshot = null,
            editingLineIndex = null,
            lastCommandType = null
        )

        val json = UndoStatePersistence.serializeState(state)
        val restored = UndoStatePersistence.deserializeState(json)

        assertEquals(5, restored.undoStack[0].lineContents.size)
        assertEquals("• First bullet", restored.undoStack[0].lineContents[0])
        assertEquals("\t☐ Indented checkbox", restored.undoStack[0].lineContents[1])
        assertEquals("", restored.undoStack[0].lineContents[2])
        assertEquals("Plain text", restored.undoStack[0].lineContents[3])
        assertEquals("☑ Checked item", restored.undoStack[0].lineContents[4])
    }

    // ==================== Multiple Snapshots ====================

    @Test
    fun `serialize and deserialize multiple undo snapshots`() {
        val snapshots = listOf(
            UndoSnapshot(listOf("state1"), 0, 0, null),
            UndoSnapshot(listOf("state2"), 0, 1, null),
            UndoSnapshot(listOf("state3"), 0, 2, null)
        )
        val state = UndoManagerState(
            undoStack = snapshots,
            redoStack = emptyList(),
            baselineSnapshot = null,
            pendingSnapshot = null,
            editingLineIndex = null,
            lastCommandType = null
        )

        val json = UndoStatePersistence.serializeState(state)
        val restored = UndoStatePersistence.deserializeState(json)

        assertEquals(3, restored.undoStack.size)
        assertEquals(listOf("state1"), restored.undoStack[0].lineContents)
        assertEquals(listOf("state2"), restored.undoStack[1].lineContents)
        assertEquals(listOf("state3"), restored.undoStack[2].lineContents)
    }

    @Test
    fun `serialize and deserialize both undo and redo stacks`() {
        val state = UndoManagerState(
            undoStack = listOf(
                UndoSnapshot(listOf("undo1"), 0, 0, null),
                UndoSnapshot(listOf("undo2"), 0, 0, null)
            ),
            redoStack = listOf(
                UndoSnapshot(listOf("redo1"), 0, 0, null)
            ),
            baselineSnapshot = null,
            pendingSnapshot = null,
            editingLineIndex = null,
            lastCommandType = null
        )

        val json = UndoStatePersistence.serializeState(state)
        val restored = UndoStatePersistence.deserializeState(json)

        assertEquals(2, restored.undoStack.size)
        assertEquals(1, restored.redoStack.size)
        assertEquals(listOf("undo1"), restored.undoStack[0].lineContents)
        assertEquals(listOf("redo1"), restored.redoStack[0].lineContents)
    }

    // ==================== JSON Structure Validation ====================

    @Test
    fun `serialized JSON has expected structure`() {
        val state = UndoManagerState(
            undoStack = listOf(UndoSnapshot(listOf("content"), 0, 5, null)),
            redoStack = emptyList(),
            baselineSnapshot = null,
            pendingSnapshot = null,
            editingLineIndex = 0,
            lastCommandType = CommandType.BULLET
        )

        val json = UndoStatePersistence.serializeState(state)

        // Verify values via deserialization
        val restored = UndoStatePersistence.deserializeState(json)
        assertEquals(1, restored.undoStack.size)
        assertEquals(0, restored.redoStack.size)
        assertNull(restored.pendingSnapshot)
        assertEquals(0, restored.editingLineIndex)
        assertEquals(CommandType.BULLET, restored.lastCommandType)
    }

    @Test
    fun `snapshot JSON has expected structure`() {
        val snapshot = UndoSnapshot(
            lineContents = listOf("line1", "line2"),
            focusedLineIndex = 1,
            cursorPosition = 3,
            createdAlarm = null
        )
        val state = UndoManagerState(
            undoStack = listOf(snapshot),
            redoStack = emptyList(),
            baselineSnapshot = null,
            pendingSnapshot = null,
            editingLineIndex = null,
            lastCommandType = null
        )

        val json = UndoStatePersistence.serializeState(state)
        val restored = UndoStatePersistence.deserializeState(json)

        val restoredSnapshot = restored.undoStack[0]
        assertEquals(2, restoredSnapshot.lineContents.size)
        assertEquals("line1", restoredSnapshot.lineContents[0])
        assertEquals("line2", restoredSnapshot.lineContents[1])
        assertEquals(1, restoredSnapshot.focusedLineIndex)
        assertEquals(3, restoredSnapshot.cursorPosition)
        assertNull(restoredSnapshot.createdAlarm)
    }

    // ==================== Special Characters ====================

    @Test
    fun `serialize and deserialize content with special characters`() {
        val snapshot = UndoSnapshot(
            lineContents = listOf(
                "Line with \"quotes\"",
                "Line with 'apostrophe'",
                "Line with \\ backslash",
                "Line with emoji 🎉",
                "Line with unicode: café"
            ),
            focusedLineIndex = 0,
            cursorPosition = 0,
            createdAlarm = null
        )
        val state = UndoManagerState(
            undoStack = listOf(snapshot),
            redoStack = emptyList(),
            baselineSnapshot = null,
            pendingSnapshot = null,
            editingLineIndex = null,
            lastCommandType = null
        )

        val json = UndoStatePersistence.serializeState(state)
        val restored = UndoStatePersistence.deserializeState(json)

        assertEquals("Line with \"quotes\"", restored.undoStack[0].lineContents[0])
        assertEquals("Line with 'apostrophe'", restored.undoStack[0].lineContents[1])
        assertEquals("Line with \\ backslash", restored.undoStack[0].lineContents[2])
        assertEquals("Line with emoji 🎉", restored.undoStack[0].lineContents[3])
        assertEquals("Line with unicode: café", restored.undoStack[0].lineContents[4])
    }

    // ==================== Integration with UndoManager ====================

    @Test
    fun `round-trip through UndoManager export and import`() {
        val undoManager = UndoManager()
        val editorState = EditorState()

        // Create some history
        editorState.initFromText("initial")
        undoManager.beginEditingLine(editorState, 0)
        editorState.initFromText("modified")
        undoManager.commitPendingUndoState(editorState)

        // Export
        val exportedState = undoManager.exportState()

        // Serialize and deserialize
        val json = UndoStatePersistence.serializeState(exportedState)
        val deserializedState = UndoStatePersistence.deserializeState(json)

        // Import into new manager
        val newManager = UndoManager()
        newManager.importState(deserializedState)

        // Verify
        org.junit.Assert.assertTrue(newManager.canUndo)
        val snapshot = newManager.undo(editorState)
        assertEquals(listOf("initial"), snapshot!!.lineContents)
    }
}
