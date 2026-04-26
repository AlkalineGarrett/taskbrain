package org.alkaline.taskbrain.ui.currentnote.ime

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import org.alkaline.taskbrain.data.Note
import org.alkaline.taskbrain.data.NoteStore
import org.alkaline.taskbrain.dsl.runtime.values.ViewVal
import org.alkaline.taskbrain.dsl.ui.DirectiveEditRow
import org.alkaline.taskbrain.ui.currentnote.EditorController
import org.alkaline.taskbrain.ui.currentnote.EditorId
import org.alkaline.taskbrain.ui.currentnote.EditorState
import org.alkaline.taskbrain.ui.currentnote.InlineEditSession
import org.alkaline.taskbrain.ui.currentnote.LocalInlineEditState
import org.alkaline.taskbrain.ui.currentnote.LocalSelectionCoordinator

private val ViewDividerColor = Color(0xFF9C27B0)

/**
 * Content from a view directive, rendered inline with inline editing support.
 * Shows the viewed notes' content with a subtle left border indicator.
 *
 * Features:
 * - Edit button at top-right to open directive editor overlay
 * - Each note section is independently editable inline
 * - Notes are separated by "---" dividers (non-editable)
 * - Saves on blur (when focus leaves the editable section)
 */
@Composable
internal fun ViewDirectiveInlineContent(
    viewVal: ViewVal,
    displayText: String,
    directiveKey: String,
    sourceText: String,
    textStyle: TextStyle,
    isDirectiveExpanded: Boolean,
    directiveError: String?,
    directiveWarning: String?,
    onNoteTap: (noteId: String, noteContent: String) -> Unit,
    onEditDirective: () -> Unit,
    onDirectiveRefresh: ((newText: String) -> Unit)?,
    onDirectiveConfirm: ((newText: String) -> Unit)?,
    onDirectiveCancel: (() -> Unit)?
) {
    val notes = viewVal.notes
    val renderedContents = viewVal.renderedContents

    var editingNoteIndex by remember { mutableStateOf<Int?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .viewIndicator(ViewIndicatorColor)
            .padding(start = 8.dp, top = 4.dp, bottom = 4.dp)
    ) {
        // Directive edit row at the TOP when expanded
        if (isDirectiveExpanded) {
            DirectiveEditRow(
                initialText = sourceText,
                textStyle = textStyle,
                errorMessage = directiveError,
                warningMessage = directiveWarning,
                onRefresh = { newText -> onDirectiveRefresh?.invoke(newText) },
                onConfirm = { newText -> onDirectiveConfirm?.invoke(newText) },
                onCancel = { onDirectiveCancel?.invoke() }
            )
        }

        Box(modifier = Modifier.fillMaxWidth()) {
            if (notes.isEmpty()) {
                Text(
                    text = displayText,
                    style = textStyle.copy(color = ViewIndicatorColor),
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Column(modifier = Modifier.fillMaxWidth()) {
                    notes.forEachIndexed { index, note ->
                        if (index > 0) NoteSeparator()
                        EditableViewNoteSection(
                            note = note,
                            editContent = note.content,
                            textStyle = textStyle,
                            isEditing = editingNoteIndex == index,
                            onStartEditing = { editingNoteIndex = index },
                            onSave = { newContent -> onNoteTap(note.id, newContent) },
                            onCancel = {
                                if (editingNoteIndex == index) editingNoteIndex = null
                            }
                        )
                    }
                }
            }

            // Gear button is last in the Box so it sits above note content in z-order and wins taps.
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onEditDirective,
                    modifier = Modifier.size(ViewEditButtonSize)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Edit view directive",
                        tint = ViewIndicatorColor,
                        modifier = Modifier.size(ViewEditIconSize)
                    )
                }
            }
        }
    }
}

/**
 * A single note section within a view directive.
 * Supports inline editing — shows the note's text in [InlineNoteEditor], which
 * becomes editable when the user taps in.
 *
 * @param editContent The raw content to edit (preserves directives like `[now]`).
 */
@Composable
private fun EditableViewNoteSection(
    note: Note,
    editContent: String,
    textStyle: TextStyle,
    isEditing: Boolean,
    onStartEditing: () -> Unit,
    onSave: (newContent: String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val inlineEditState = LocalInlineEditState.current
    val selectionCoordinator = LocalSelectionCoordinator.current
    // hasBeenFocused prevents canceling on the initial composition before any focus event.
    var hasBeenFocused by remember { mutableStateOf(false) }

    LaunchedEffect(isEditing) {
        if (!isEditing) hasBeenFocused = false
    }

    val isActiveSession = inlineEditState?.activeSession?.noteId == note.id

    // Session is eagerly created by InlineEditState.ensureSessionsForNotes() when
    // directive results are computed. Look it up from viewSessions.
    val session = inlineEditState?.viewSessions?.get(note.id) ?: remember(note.id) {
        // Fallback: create on demand if not yet in viewSessions (shouldn't happen normally).
        // Always go through initFromNoteLines (with synthesized fallback) so we never
        // touch the lossy updateFromText path.
        val s = EditorState()
        val storeLines = NoteStore.getNoteLinesByIdOrSynthesize(note.id, editContent)
        s.initFromNoteLines(storeLines, preserveCursor = false)
        InlineEditSession(
            noteId = note.id,
            originalContent = editContent,
            editorState = s,
            controller = EditorController(s)
        ).also { inlineEditState?.viewSessions?.set(note.id, it) }
    }

    // Update existing session content in place on external changes (same pattern as
    // main editor's initFromNoteLines). Skipped when the session has unsaved edits.
    if (editContent != session.originalContent && !session.isDirty) {
        val storeLines = NoteStore.getNoteLinesById(note.id)
        if (storeLines != null) {
            session.editorState.initFromNoteLines(storeLines, preserveCursor = true)
            session.syncOriginalContent(editContent)
            session.editorState.requestFocusUpdate()
        } else {
            // No structured lines available — the note vanished from NoteStore
            // (mid-save echo gap, delete, etc.). Syncing via the synthesize
            // fallback would wipe every non-root noteId in the existing session,
            // producing bare-null ids at save. Skip the sync; a subsequent
            // snapshot that restores structured lines will trigger again.
            android.util.Log.w(
                "DirectiveAwareLineInput",
                "Skipping external-change sync for ${note.id}: NoteStore has no " +
                    "structured lines (would wipe session noteIds via synthesize fallback).",
            )
        }
    }

    // When editing starts, register the SAME session as active (no new session)
    remember(isEditing, note.id, session) {
        if (isEditing && inlineEditState != null) {
            if (inlineEditState.activeSession?.noteId != note.id) {
                inlineEditState.activateExistingSession(session)
            }
        }
        Unit
    }

    Box(modifier = modifier.fillMaxWidth()) {
        InlineNoteEditor(
            session = session,
            autoFocus = isEditing && isActiveSession,
            textStyle = textStyle,
            onFocusChanged = { isFocused ->
                if (isFocused) {
                    selectionCoordinator?.activate(EditorId.View(note.id))
                    if (!isEditing) {
                        onStartEditing()
                    }
                    hasBeenFocused = true
                } else if (hasBeenFocused && isEditing && isActiveSession) {
                    // On blur: push optimistic update to NoteStore (no Firestore write).
                    // This lets other view directives referencing the same note see edits.
                    val active = inlineEditState?.activeSession
                    if (active?.isDirty == true) {
                        val existing = NoteStore.getNoteById(active.noteId)
                        if (existing != null) {
                            NoteStore.updateNote(
                                active.noteId,
                                existing.copy(content = active.currentContent),
                                persist = false
                            )
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        if (isEditing && isActiveSession) {
            val active = inlineEditState?.activeSession
            if (active != null) {
                LaunchedEffect(active.isCollapsingDirective) {
                    if (active.isCollapsingDirective) {
                        kotlinx.coroutines.delay(500)
                        active.clearCollapsingFlag()
                    }
                }
            }
        }
    }
}

/**
 * Visual separator between notes in a multi-note view.
 * Renders a dashed purple horizontal line.
 */
@Composable
private fun NoteSeparator() {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        val dashLength = 4.dp.toPx()
        val gapLength = 3.dp.toPx()
        drawLine(
            color = ViewDividerColor,
            start = Offset(0f, 0f),
            end = Offset(size.width, 0f),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashLength, gapLength))
        )
    }
}

/**
 * Modifier for view directive content — draws a left border indicator.
 * Provides a subtle visual distinction for viewed content.
 */
private fun Modifier.viewIndicator(color: Color): Modifier = this.drawBehind {
    val strokeWidth = 2.dp.toPx()
    drawLine(
        color = color,
        start = Offset(0f, 0f),
        end = Offset(0f, size.height),
        strokeWidth = strokeWidth
    )
}
