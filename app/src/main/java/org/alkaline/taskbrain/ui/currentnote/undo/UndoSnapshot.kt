package org.alkaline.taskbrain.ui.currentnote.undo

import com.google.firebase.Timestamp
import org.alkaline.taskbrain.data.AlarmStage

/**
 * Snapshot of editor state for undo/redo operations.
 * Captures the full state at an undo boundary.
 */
data class UndoSnapshot(
    val lineContents: List<String>,
    val focusedLineIndex: Int,
    val cursorPosition: Int,
    val createdAlarm: AlarmSnapshot? = null,
    val lineNoteIds: List<List<String>> = emptyList()
)

/**
 * Snapshot of alarm data for undo/redo.
 * Stores all data needed to recreate an alarm on redo.
 */
data class AlarmSnapshot(
    val id: String,
    val noteId: String,
    val lineContent: String,
    val dueTime: Timestamp?,
    val stages: List<AlarmStage>
)
