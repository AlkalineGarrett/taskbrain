package org.alkaline.taskbrain.ui.currentnote.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import org.alkaline.taskbrain.dsl.directives.DirectiveSegmenter
import org.alkaline.taskbrain.ui.currentnote.util.SymbolTapInfo
import org.alkaline.taskbrain.ui.currentnote.util.TappableSymbol
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.StateFlow
import org.alkaline.taskbrain.ui.currentnote.EditorConfig
import org.alkaline.taskbrain.ui.currentnote.EditorController
import org.alkaline.taskbrain.ui.currentnote.rendering.HangingIndentEditor
import org.alkaline.taskbrain.ui.currentnote.EditorState
import org.alkaline.taskbrain.ui.currentnote.rememberEditorState
import org.alkaline.taskbrain.dsl.directives.DirectiveResult
import org.alkaline.taskbrain.ui.currentnote.rendering.ButtonCallbacks
import org.alkaline.taskbrain.ui.currentnote.rendering.DirectiveCallbacks
import org.alkaline.taskbrain.ui.currentnote.util.SymbolOverlay

/**
 * Main text editing component for notes.
 * Uses HangingIndentEditor for proper wrapped line indentation.
 *
 * @param editorState State holder for the editor that exposes operations like indent/unindent.
 *        Create with rememberEditorState() and use for CommandBar operations.
 * @param controller The EditorController for managing state modifications.
 *        Create with rememberEditorController(editorState) and use for undo/redo operations.
 */
@Composable
fun NoteTextField(
    textFieldValue: TextFieldValue,
    onTextFieldValueChange: (TextFieldValue) -> Unit,
    focusRequester: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    editorState: EditorState = rememberEditorState(),
    controller: EditorController,
    isFingerDownFlow: StateFlow<Boolean>? = null,
    onSymbolTap: ((SymbolTapInfo) -> Unit)? = null,
    textColor: Color = Color.Black,
    directiveResults: Map<String, DirectiveResult> = emptyMap(),
    directiveCallbacks: DirectiveCallbacks = DirectiveCallbacks(),
    buttonCallbacks: ButtonCallbacks = ButtonCallbacks(),
    showCompleted: Boolean = true,
    symbolOverlaysProvider: ((lineIndex: Int) -> List<SymbolOverlay>)? = null,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    var editorHeightDp by remember { mutableStateOf(0.dp) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val viewportHeight = maxHeight

        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
        ) {
            val textStyle = TextStyle(
                fontSize = EditorConfig.FontSize,
                color = textColor
            )

            // Use a Column to stack editor and blank area spacer
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = viewportHeight)
            ) {
                HangingIndentEditor(
                    text = textFieldValue.text,
                    onTextChange = { newText ->
                        // Convert string change back to TextFieldValue
                        // Place cursor at end of text by default
                        onTextFieldValueChange(TextFieldValue(
                            text = newText,
                            selection = TextRange(newText.length)
                        ))
                    },
                    textStyle = textStyle,
                    state = editorState,
                    controller = controller,
                    externalFocusRequester = focusRequester,
                    onEditorFocusChanged = onFocusChanged,
                    scrollState = scrollState,
                    showGutter = true,
                    directiveResults = directiveResults,
                    directiveCallbacks = directiveCallbacks,
                    buttonCallbacks = buttonCallbacks,
                    showCompleted = showCompleted,
                    onSymbolTap = onSymbolTap?.let { callback ->
                        { lineIndex: Int, charOffsetInLine: Int ->
                            val lineState = editorState.lines.getOrNull(lineIndex)
                            val content = lineState?.content ?: ""
                            val displayResult = DirectiveSegmenter.buildDisplayText(content, directiveResults)
                            val alarmRange = displayResult.directiveDisplayRanges.find {
                                it.isAlarm && charOffsetInLine in it.sourceRange
                            }
                            if (alarmRange != null && (alarmRange.alarmId != null || alarmRange.recurringAlarmId != null)) {
                                callback(SymbolTapInfo(
                                    symbol = TappableSymbol.ALARM,
                                    charOffset = charOffsetInLine,
                                    lineIndex = lineIndex,
                                    alarmId = if (alarmRange.recurringAlarmId == null) alarmRange.alarmId else null,
                                    recurringAlarmId = alarmRange.recurringAlarmId
                                ))
                            }
                        }
                    },
                    symbolOverlaysProvider = symbolOverlaysProvider,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = EditorConfig.HorizontalPadding,
                            vertical = EditorConfig.VerticalPadding
                        )
                        .onGloballyPositioned { coordinates ->
                            editorHeightDp = with(density) { coordinates.size.height.toDp() }
                        }
                )

                // Tappable spacer below editor content - fills remaining viewport space
                BlankAreaSpacer(
                    viewportHeight = viewportHeight,
                    editorHeight = editorHeightDp,
                    onTap = {
                        // Move cursor to end of last line
                        val lastLineIndex = editorState.lines.lastIndex
                        if (lastLineIndex >= 0) {
                            val lastLine = editorState.lines[lastLineIndex]
                            controller.setCursor(lastLineIndex, lastLine.text.length)
                        }
                    }
                )
            }
        }
    }
}

/**
 * Spacer that fills the remaining viewport space below the editor.
 * Tapping it moves cursor to end of last line.
 */
@Composable
private fun BlankAreaSpacer(
    viewportHeight: Dp,
    editorHeight: Dp,
    onTap: () -> Unit
) {
    val spacerHeight = (viewportHeight - editorHeight).coerceAtLeast(0.dp)
    if (spacerHeight > 0.dp) {
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(spacerHeight)
                .pointerInput(Unit) {
                    detectTapGestures { onTap() }
                }
        )
    }
}
