package org.alkaline.taskbrain.ui.currentnote

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.StateFlow
import org.alkaline.taskbrain.data.Alarm
import org.alkaline.taskbrain.dsl.directives.DirectiveResult
import org.alkaline.taskbrain.ui.currentnote.components.NoteTextField
import org.alkaline.taskbrain.ui.currentnote.util.LocalSymbolOverlaysProvider
import org.alkaline.taskbrain.ui.currentnote.util.SymbolTapInfo

/**
 * Editor body section: composes [NoteTextField] inside the composition-local
 * providers it relies on (selection coordinator, symbol-overlay lookup, inline
 * edit state) and re-keys the field on [displayedNoteId] so tab switches reset
 * editor-internal scroll/selection state.
 *
 * Computes per-recomposition lookups (directive callbacks, button callbacks,
 * symbol-overlay lookup) inline so they're isolated to the editor's lifetime
 * and don't pollute the parent screen.
 */
@Composable
fun ColumnScope.NoteEditorBody(
    displayedNoteId: String?,
    editorContent: EditorContentState,
    coordinator: SelectionCoordinator,
    inlineEditState: InlineEditState,
    directiveResults: Map<String, DirectiveResult>,
    alarmCacheForOverlay: Map<String, Alarm>,
    recurringAlarmCacheForOverlay: Map<String, Alarm>,
    buttonExecutionStates: Map<String, org.alkaline.taskbrain.dsl.ui.ButtonExecutionState>,
    buttonErrors: Map<String, String>,
    isFingerDownFlow: StateFlow<Boolean>?,
    deletedNoteTextColor: Color,
    currentNoteViewModel: CurrentNoteViewModel,
    recentTabsViewModel: RecentTabsViewModel,
    onAlarmSymbolTap: (SymbolTapInfo) -> Unit,
    onTextFieldValueChange: (androidx.compose.ui.text.input.TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
) {
    val directiveCallbacks = rememberDirectiveCallbacks(
        editorState = editorContent.editorState,
        controller = editorContent.controller,
        currentNoteViewModel = currentNoteViewModel,
        recentTabsViewModel = recentTabsViewModel,
        inlineEditState = inlineEditState,
        userContent = editorContent.userContent,
        onContentChanged = { editorContent.updateContent(it) },
        onMarkUnsaved = { editorContent.markUnsaved() },
    )
    val buttonCallbacks = rememberButtonCallbacks(
        currentNoteViewModel = currentNoteViewModel,
        executionStates = buttonExecutionStates,
        errors = buttonErrors,
    )

    val symbolOverlaysLookup = remember(alarmCacheForOverlay, recurringAlarmCacheForOverlay) {
        { lineContent: String ->
            currentNoteViewModel.alarmManager.getSymbolOverlays(
                lineContent,
                alarmCacheForOverlay,
                recurringAlarmCacheForOverlay,
            )
        }
    }

    CompositionLocalProvider(
        LocalSelectionCoordinator provides coordinator,
        LocalSymbolOverlaysProvider provides symbolOverlaysLookup,
    ) {
        ProvideInlineEditState(inlineEditState) {
            key(displayedNoteId) {
                NoteTextField(
                    textFieldValue = editorContent.textFieldValue,
                    onTextFieldValueChange = onTextFieldValueChange,
                    focusRequester = editorContent.mainContentFocusRequester,
                    onFocusChanged = { isFocused -> editorContent.isMainContentFocused = isFocused },
                    editorState = editorContent.editorState,
                    controller = editorContent.controller,
                    isFingerDownFlow = isFingerDownFlow,
                    onSymbolTap = onAlarmSymbolTap,
                    textColor = if (editorContent.isNoteDeleted) deletedNoteTextColor else Color.Black,
                    directiveResults = directiveResults,
                    directiveCallbacks = directiveCallbacks,
                    buttonCallbacks = buttonCallbacks,
                    showCompleted = editorContent.showCompleted,
                    symbolOverlaysProvider = { lineIndex ->
                        val lineContent = editorContent.editorState.lines.getOrNull(lineIndex)?.content ?: ""
                        symbolOverlaysLookup(lineContent)
                    },
                    modifier = modifier,
                )
            }
        }
    }
}
