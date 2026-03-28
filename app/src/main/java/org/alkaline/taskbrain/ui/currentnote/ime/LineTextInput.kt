package org.alkaline.taskbrain.ui.currentnote.ime

import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.alkaline.taskbrain.ui.currentnote.EditorController
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputModifierNode
import androidx.compose.ui.platform.establishTextInputSession
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * A text input component for a single line that uses EditorController for state management.
 */
@Composable
internal fun LineTextInput(
    lineIndex: Int,
    content: String,
    contentCursor: Int,
    controller: EditorController,
    isFocused: Boolean,
    hasExternalSelection: Boolean,
    textStyle: TextStyle,
    focusRequester: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    onTextLayoutResult: (TextLayoutResult) -> Unit,
    modifier: Modifier = Modifier
) {
    val hostView = LocalView.current
    val imeState = remember(lineIndex, controller) {
        LineImeState(lineIndex, controller)
    }

    LaunchedEffect(content, contentCursor) {
        // Only sync from controller if not in a batch edit - during batch,
        // the buffer is the source of truth and shouldn't be reset
        if (!imeState.isInBatchEdit) {
            imeState.syncFromController()
        }
    }

    val interactionSource = remember { MutableInteractionSource() }
    var textLayoutResultState: TextLayoutResult? by remember { mutableStateOf(null) }

    val infiniteTransition = rememberInfiniteTransition(label = "cursorBlink")
    val cursorAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursorAlpha"
    )

    Box(
        modifier = modifier
            .focusRequester(focusRequester)
            .onFocusChanged { focusState -> onFocusChanged(focusState.isFocused) }
            .lineImeConnection(imeState, contentCursor, hostView)
            .focusable(interactionSource = interactionSource)
    ) {
        BasicText(
            text = content,
            style = textStyle,
            onTextLayout = { layoutResult ->
                textLayoutResultState = layoutResult
                onTextLayoutResult(layoutResult)
            },
            modifier = Modifier
                .fillMaxWidth()
                .drawWithContent {
                    drawContent()
                    if (isFocused && !hasExternalSelection && textLayoutResultState != null) {
                        val layout = textLayoutResultState!!
                        val cursorPos = contentCursor.coerceIn(0, content.length)
                        val cursorRect = try {
                            layout.getCursorRect(cursorPos)
                        } catch (e: Exception) {
                            Rect(0f, 0f, 2.dp.toPx(), layout.size.height.toFloat())
                        }
                        drawLine(
                            color = Color.Black.copy(alpha = cursorAlpha),
                            start = Offset(cursorRect.left, cursorRect.top),
                            end = Offset(cursorRect.left, cursorRect.bottom),
                            strokeWidth = 2.dp.toPx()
                        )
                    }
                }
        )
    }
}

// =============================================================================
// IME Connection Modifier
// =============================================================================

internal data class LineImeConnectionElement(
    val state: LineImeState,
    val cursorPosition: Int,
    val hostView: View
) : ModifierNodeElement<LineImeConnectionNode>() {
    override fun create() = LineImeConnectionNode(state, hostView)
    override fun update(node: LineImeConnectionNode) = node.update(state, hostView)
    override fun InspectorInfo.inspectableProperties() { name = "lineImeConnection" }
}

internal class LineImeConnectionNode(
    private var state: LineImeState,
    private var hostView: View
) : DelegatingNode(),
    PlatformTextInputModifierNode,
    androidx.compose.ui.focus.FocusEventModifierNode {

    private var inputSessionJob: Job? = null
    private var isFocused = false
    private var inputMethodManager: InputMethodManager? = null

    private val imeNotificationCallback = object : ImeNotificationCallback {
        override fun notifySelectionUpdate(selStart: Int, selEnd: Int, composingStart: Int, composingEnd: Int) {
            inputMethodManager?.updateSelection(hostView, selStart, selEnd, composingStart, composingEnd)
        }

        override fun notifyExtractedTextUpdate(token: Int, text: ExtractedText) {
            inputMethodManager?.updateExtractedText(hostView, token, text)
        }
    }

    fun update(newState: LineImeState, newHostView: View) {
        val stateChanged = state !== newState
        val viewChanged = hostView !== newHostView

        if (stateChanged) {
            state.setNotificationCallback(null)
        }
        state = newState
        hostView = newHostView

        if (viewChanged) {
            inputMethodManager = hostView.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        }

        if (stateChanged && isFocused) {
            stopInputSession()
            startInputSession()
        } else if (isFocused && !state.isInBatchEdit && state.needsExternalNotification()) {
            // External change (e.g., undo/redo) - notify IME
            // But only if not in a batch edit - during batch, the buffer is source of truth
            state.syncFromController()
            imeNotificationCallback.notifySelectionUpdate(
                state.cursorPosition,
                state.cursorPosition,
                state.composingStart,
                state.composingEnd
            )
        }
    }

    override fun onFocusEvent(focusState: FocusState) {
        val wasFocused = isFocused
        isFocused = focusState.isFocused

        if (isFocused && !wasFocused) {
            state.syncFromController()
            startInputSession()
        } else if (!isFocused && wasFocused) {
            stopInputSession()
        }
    }

    private fun startInputSession() {
        if (inputMethodManager == null) {
            inputMethodManager = hostView.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        }
        state.setNotificationCallback(imeNotificationCallback)

        inputSessionJob?.cancel()
        inputSessionJob = coroutineScope.launch {
            establishTextInputSession {
                val request = PlatformTextInputMethodRequest { outAttrs ->
                    outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE
                    outAttrs.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                    outAttrs.initialSelStart = state.cursorPosition
                    outAttrs.initialSelEnd = state.cursorPosition
                    LineInputConnection(state, hostView.context)
                }
                startInputMethod(request)
            }
        }
    }

    private fun stopInputSession() {
        state.setNotificationCallback(null)
        inputSessionJob?.cancel()
        inputSessionJob = null
    }
}

internal fun Modifier.lineImeConnection(state: LineImeState, cursorPosition: Int, hostView: View): Modifier =
    this then LineImeConnectionElement(state, cursorPosition, hostView)

// =============================================================================
// Input Connection - Thin wrapper that logs and delegates to LineImeState
// =============================================================================

private class LineInputConnection(
    private val state: LineImeState,
    private val context: Context
) : InputConnection {

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        if (text != null) {
            state.commitText(text.toString(), newCursorPosition)
        }
        return true
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        state.setComposingText(text?.toString() ?: "", newCursorPosition)
        return true
    }

    override fun finishComposingText(): Boolean {
        state.finishComposingText()
        return true
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean {
        state.setComposingRegion(start, end)
        return true
    }

    override fun setSelection(start: Int, end: Int): Boolean {
        state.setSelection(start, end)
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        state.deleteSurroundingText(beforeLength, afterLength)
        return true
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        state.deleteSurroundingText(beforeLength, afterLength)
        return true
    }

    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? {
        val start = (state.cursorPosition - n).coerceAtLeast(0)
        return state.text.substring(start, state.cursorPosition.coerceIn(start, state.text.length))
    }

    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? {
        val cursor = state.cursorPosition.coerceIn(0, state.text.length)
        val end = (cursor + n).coerceAtMost(state.text.length)
        return state.text.substring(cursor, end)
    }

    override fun getSelectedText(flags: Int): CharSequence? = null

    override fun getCursorCapsMode(reqModes: Int): Int {
        return android.text.TextUtils.getCapsMode(state.text, state.cursorPosition, reqModes)
    }

    override fun getExtractedText(
        request: android.view.inputmethod.ExtractedTextRequest?,
        flags: Int
    ): android.view.inputmethod.ExtractedText? {
        val isMonitor = flags and InputConnection.GET_EXTRACTED_TEXT_MONITOR != 0
        if (isMonitor && request != null) {
            state.startExtractedTextMonitoring(request.token)
        }
        return android.view.inputmethod.ExtractedText().apply {
            text = state.text
            startOffset = 0
            selectionStart = state.cursorPosition
            selectionEnd = state.cursorPosition
        }
    }

    override fun sendKeyEvent(event: KeyEvent?): Boolean {
        return event != null && state.handleKeyEvent(event)
    }

    override fun commitCorrection(correctionInfo: android.view.inputmethod.CorrectionInfo?): Boolean {
        // commitCorrection is METADATA about a correction - it does NOT perform the edit.
        // The actual text change comes through a separate commitText call.
        // We must NOT modify text here, or we'll double-apply the correction.
        return true
    }

    override fun replaceText(
        start: Int,
        end: Int,
        text: CharSequence,
        newCursorPosition: Int,
        textAttribute: android.view.inputmethod.TextAttribute?
    ): Boolean {
        state.setComposingRegion(start, end)
        state.commitText(text.toString(), newCursorPosition)
        return true
    }

    override fun beginBatchEdit(): Boolean = state.beginBatchEdit()

    override fun endBatchEdit(): Boolean = state.endBatchEdit()

    override fun performEditorAction(editorAction: Int): Boolean {
        // Some IMEs (e.g., Samsung Keyboard) send Enter as performEditorAction
        // rather than commitText("\n") or sendKeyEvent(KEYCODE_ENTER).
        // We configure IME_ACTION_NONE with MULTI_LINE, so any action here
        // means the user pressed Enter.
        state.handleEnter()
        return true
    }

    override fun performContextMenuAction(id: Int): Boolean {
        return when (id) {
            android.R.id.paste, android.R.id.pasteAsPlainText -> {
                state.handlePaste(context)
                true
            }
            android.R.id.copy, android.R.id.cut -> true
            else -> false
        }
    }

    override fun clearMetaKeyStates(states: Int): Boolean = false
    override fun performPrivateCommand(action: String?, data: android.os.Bundle?): Boolean = false
    override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean = false
    override fun getHandler(): android.os.Handler? = null
    override fun closeConnection() {}
    override fun commitCompletion(text: android.view.inputmethod.CompletionInfo?): Boolean = true
    override fun commitContent(
        inputContentInfo: android.view.inputmethod.InputContentInfo,
        flags: Int,
        opts: android.os.Bundle?
    ): Boolean = false
    override fun reportFullscreenMode(enabled: Boolean): Boolean = false
    override fun takeSnapshot(): android.view.inputmethod.TextSnapshot? = null
    override fun performSpellCheck(): Boolean = false
    override fun setImeConsumesInput(imeConsumesInput: Boolean): Boolean = false
}
