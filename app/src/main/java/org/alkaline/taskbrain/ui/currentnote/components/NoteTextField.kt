package org.alkaline.taskbrain.ui.currentnote.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
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
import org.alkaline.taskbrain.ui.currentnote.HangingIndentEditorState
import org.alkaline.taskbrain.ui.currentnote.rememberHangingIndentEditorState
import org.alkaline.taskbrain.dsl.directives.DirectiveResult
import org.alkaline.taskbrain.dsl.runtime.values.ButtonVal
import org.alkaline.taskbrain.dsl.ui.ButtonExecutionState

/**
 * Main text editing component for notes.
 * Uses HangingIndentEditor for proper wrapped line indentation.
 *
 * @param editorState State holder for the editor that exposes operations like indent/unindent.
 *        Create with rememberHangingIndentEditorState() and use for CommandBar operations.
 * @param controller The EditorController for managing state modifications.
 *        Create with rememberEditorController(editorState) and use for undo/redo operations.
 */
@Composable
fun NoteTextField(
    textFieldValue: TextFieldValue,
    onTextFieldValueChange: (TextFieldValue) -> Unit,
    focusRequester: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    editorState: HangingIndentEditorState = rememberHangingIndentEditorState(),
    controller: EditorController,
    isFingerDownFlow: StateFlow<Boolean>? = null,
    onSymbolTap: ((SymbolTapInfo) -> Unit)? = null,
    textColor: Color = Color.Black,
    directiveResults: Map<String, DirectiveResult> = emptyMap(),
    onDirectiveTap: ((directiveHash: String, sourceText: String) -> Unit)? = null,
    onDirectiveEditConfirm: ((lineIndex: Int, directiveHash: String, sourceText: String, newText: String) -> Unit)? = null,
    onDirectiveEditCancel: ((lineIndex: Int, directiveHash: String, sourceText: String) -> Unit)? = null,
    onDirectiveRefresh: ((lineIndex: Int, directiveKey: String, sourceText: String, newText: String) -> Unit)? = null,
    onViewNoteTap: ((directiveKey: String, noteId: String, noteContent: String) -> Unit)? = null,
    onViewEditDirective: ((directiveKey: String, sourceText: String) -> Unit)? = null,
    onButtonClick: ((directiveKey: String, buttonVal: ButtonVal, sourceText: String) -> Unit)? = null,
    buttonExecutionStates: Map<String, ButtonExecutionState> = emptyMap(),
    buttonErrors: Map<String, String> = emptyMap(),
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
                    onDirectiveTap = onDirectiveTap,
                    onDirectiveEditConfirm = onDirectiveEditConfirm,
                    onDirectiveEditCancel = onDirectiveEditCancel,
                    onDirectiveRefresh = onDirectiveRefresh,
                    onViewNoteTap = onViewNoteTap,
                    onViewEditDirective = onViewEditDirective,
                    onButtonClick = onButtonClick,
                    buttonExecutionStates = buttonExecutionStates,
                    buttonErrors = buttonErrors,
                    onSymbolTap = onSymbolTap?.let { callback ->
                        { lineIndex: Int, charOffsetInLine: Int ->
                            val content = editorState.lines.getOrNull(lineIndex)?.content ?: ""
                            val symbol = TappableSymbol.at(content, charOffsetInLine)
                            if (symbol != null) {
                                val textBefore = content.substring(0, charOffsetInLine.coerceAtMost(content.length))
                                callback(SymbolTapInfo(
                                    symbol = symbol,
                                    charOffset = charOffsetInLine,
                                    lineIndex = lineIndex,
                                    symbolIndexOnLine = textBefore.count { ch -> ch.toString() == symbol.char }
                                ))
                            }
                        }
                    },
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
