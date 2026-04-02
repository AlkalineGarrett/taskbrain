package org.alkaline.taskbrain.ui.currentnote

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.alkaline.taskbrain.dsl.ui.ButtonExecutionState
import org.alkaline.taskbrain.ui.currentnote.rendering.ButtonCallbacks
import org.alkaline.taskbrain.ui.currentnote.rendering.DirectiveCallbacks

/**
 * Creates [DirectiveCallbacks] for the note editor, handling directive tap, edit,
 * confirm, cancel, refresh, and view-note interactions.
 */
@Composable
fun rememberDirectiveCallbacks(
    editorState: EditorState,
    controller: EditorController,
    currentNoteViewModel: CurrentNoteViewModel,
    recentTabsViewModel: RecentTabsViewModel,
    inlineEditState: InlineEditState,
    userContent: String,
    onContentChanged: (String) -> Unit,
    onMarkUnsaved: () -> Unit,
): DirectiveCallbacks = remember(editorState, controller, currentNoteViewModel) {
    DirectiveCallbacks(
        onDirectiveTap = { _, sourceText ->
            currentNoteViewModel.directiveManager.toggleDirectiveCollapsed(sourceText)
        },
        onViewDirectiveConfirm = { lineIndex, _, sourceText, newText ->
            handleDirectiveConfirm(
                lineIndex, sourceText, newText,
                editorState, controller, currentNoteViewModel,
                onContentChanged, onMarkUnsaved
            )
        },
        onViewDirectiveCancel = { lineIndex, _, sourceText ->
            currentNoteViewModel.directiveManager.toggleDirectiveCollapsed(sourceText)
            moveCursorToEndOfDirective(editorState, controller, lineIndex, sourceText)
        },
        onViewDirectiveRefresh = { lineIndex, _, sourceText, newText ->
            handleDirectiveRefresh(
                lineIndex, sourceText, newText,
                editorState, controller, currentNoteViewModel,
                onContentChanged, onMarkUnsaved
            )
        },
        onViewNoteTap = { _, noteId, noteContent ->
            currentNoteViewModel.directiveManager.saveInlineNoteContent(
                noteId = noteId,
                newContent = noteContent,
                onSuccess = {
                    inlineEditState.activeSession?.markSaved()
                    recentTabsViewModel.invalidateCache(noteId)
                }
            )
        },
        onViewEditDirective = { _, sourceText ->
            currentNoteViewModel.directiveManager.toggleDirectiveCollapsed(sourceText)
        },
    )
}

/**
 * Creates [ButtonCallbacks] for directive button interactions.
 */
@Composable
fun rememberButtonCallbacks(
    currentNoteViewModel: CurrentNoteViewModel,
    executionStates: Map<String, ButtonExecutionState>,
    errors: Map<String, String>,
): ButtonCallbacks = remember(currentNoteViewModel) {
    ButtonCallbacks(
        onClick = { directiveKey, buttonVal, sourceText ->
            currentNoteViewModel.directiveManager.executeButton(directiveKey, buttonVal, sourceText)
        },
        executionStates = executionStates,
        errors = errors,
    )
}

// --- Private helpers ---

private fun handleDirectiveConfirm(
    lineIndex: Int,
    sourceText: String,
    newText: String,
    editorState: EditorState,
    controller: EditorController,
    viewModel: CurrentNoteViewModel,
    onContentChanged: (String) -> Unit,
    onMarkUnsaved: () -> Unit,
) {
    val lineContent = editorState.lines.getOrNull(lineIndex)?.content ?: ""
    val startOffset = lineContent.indexOf(sourceText)

    if (sourceText != newText && startOffset >= 0) {
        val endOffset = startOffset + sourceText.length
        controller.confirmDirectiveEdit(lineIndex, startOffset, endOffset, newText)
        onContentChanged(editorState.text)
        onMarkUnsaved()
        viewModel.directiveManager.bumpDirectiveCacheGeneration()

        val cursorPos = startOffset + newText.length
        val prefixLength = editorState.lines.getOrNull(lineIndex)?.prefix?.length ?: 0
        controller.setCursor(lineIndex, prefixLength + cursorPos)

        viewModel.directiveManager.confirmDirective(newText)
    } else if (startOffset >= 0) {
        moveCursorToEndOfDirective(editorState, controller, lineIndex, sourceText)
        viewModel.directiveManager.confirmDirective(sourceText)
    }
}

private fun handleDirectiveRefresh(
    lineIndex: Int,
    sourceText: String,
    newText: String,
    editorState: EditorState,
    controller: EditorController,
    viewModel: CurrentNoteViewModel,
    onContentChanged: (String) -> Unit,
    onMarkUnsaved: () -> Unit,
) {
    val lineContent = editorState.lines.getOrNull(lineIndex)?.content ?: ""
    val startOffset = lineContent.indexOf(sourceText)

    if (sourceText != newText && startOffset >= 0) {
        val endOffset = startOffset + sourceText.length
        controller.confirmDirectiveEdit(lineIndex, startOffset, endOffset, newText)
        onContentChanged(editorState.text)
        onMarkUnsaved()
        viewModel.directiveManager.bumpDirectiveCacheGeneration()
        viewModel.directiveManager.refreshDirective(newText)
    } else {
        viewModel.directiveManager.refreshDirective(sourceText)
    }
}

private fun moveCursorToEndOfDirective(
    editorState: EditorState,
    controller: EditorController,
    lineIndex: Int,
    sourceText: String
) {
    val lineContent = editorState.lines.getOrNull(lineIndex)?.content ?: ""
    val startOffset = lineContent.indexOf(sourceText)
    if (startOffset >= 0) {
        val cursorPos = startOffset + sourceText.length
        val prefixLength = editorState.lines.getOrNull(lineIndex)?.prefix?.length ?: 0
        controller.setCursor(lineIndex, prefixLength + cursorPos)
    }
}
