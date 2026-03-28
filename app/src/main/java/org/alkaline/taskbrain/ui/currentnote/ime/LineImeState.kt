package org.alkaline.taskbrain.ui.currentnote.ime

import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import android.view.inputmethod.ExtractedText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.alkaline.taskbrain.ui.currentnote.EditorController
import org.alkaline.taskbrain.ui.currentnote.LineState

/**
 * Callback interface for notifying the IME of state changes.
 */
interface ImeNotificationCallback {
    fun notifySelectionUpdate(selStart: Int, selEnd: Int, composingStart: Int, composingEnd: Int)
    fun notifyExtractedTextUpdate(token: Int, text: ExtractedText)
}

/**
 * Simple editing buffer that tracks text, selection, and composition.
 * Mirrors Compose's EditingBuffer pattern.
 */
internal class EditingBuffer(initialText: String = "", initialCursor: Int = 0) {
    private val sb = StringBuilder(initialText)

    var selectionStart: Int = initialCursor.coerceIn(0, sb.length)
        private set
    var selectionEnd: Int = initialCursor.coerceIn(0, sb.length)
        private set
    var compositionStart: Int = -1
        private set
    var compositionEnd: Int = -1
        private set

    val length: Int get() = sb.length
    val text: String get() = sb.toString()

    fun hasComposition(): Boolean = compositionStart >= 0 && compositionEnd >= 0

    var cursor: Int
        get() = selectionStart
        set(value) {
            val c = value.coerceIn(0, sb.length)
            selectionStart = c
            selectionEnd = c
        }

    fun setSelection(start: Int, end: Int) {
        selectionStart = start.coerceIn(0, sb.length)
        selectionEnd = end.coerceIn(0, sb.length)
    }

    fun setComposition(start: Int, end: Int) {
        compositionStart = start.coerceIn(0, sb.length)
        compositionEnd = end.coerceIn(0, sb.length)
    }

    fun commitComposition() {
        compositionStart = -1
        compositionEnd = -1
    }

    fun replace(start: Int, end: Int, text: String) {
        val s = start.coerceIn(0, sb.length)
        val e = end.coerceIn(0, sb.length)
        sb.replace(s, e, text)
        // Adjust composition if it overlaps
        if (hasComposition()) {
            if (compositionEnd <= s) {
                // Composition before replacement - no change needed
            } else if (compositionStart >= e) {
                // Composition after replacement - shift
                val delta = text.length - (e - s)
                compositionStart += delta
                compositionEnd += delta
            } else {
                // Composition overlaps - clear it
                commitComposition()
            }
        }
    }

    fun delete(start: Int, end: Int) {
        replace(start, end, "")
    }

    fun reset(text: String, cursor: Int) {
        sb.clear()
        sb.append(text)
        this.cursor = cursor
        commitComposition()
    }

    fun snapshot(): BufferSnapshot = BufferSnapshot(text, cursor, selectionEnd, compositionStart, compositionEnd)

    override fun toString(): String = "EditingBuffer(text='$text', cursor=$cursor, comp=$compositionStart..$compositionEnd)"
}

/**
 * Immutable snapshot of buffer state.
 */
internal data class BufferSnapshot(
    val text: String,
    val cursor: Int,
    val selectionEnd: Int,
    val compositionStart: Int,
    val compositionEnd: Int
)


/**
 * IME state for a single line that delegates all modifications to EditorController.
 *
 * INVARIANTS:
 * 1. Buffer selection/composition are always in valid ranges
 * 2. batchDepth >= 0, and if batchDepth == 0 then needsSyncAfterBatch == false
 * 3. After syncBufferToController: buffer matches controller
 * 4. After sendImeNotification: lastNotified* values match buffer
 */
class LineImeState(
    private val lineIndex: Int,
    private val controller: EditorController
) {
    // Composing region - exposed for external access
    var composingStart by mutableIntStateOf(-1)
        private set
    var composingEnd by mutableIntStateOf(-1)
        private set

    // The editing buffer - source of truth during IME operations
    private val buffer = EditingBuffer()

    // Batch edit state - during batch, we apply edits to buffer immediately
    // but defer controller sync and IME notifications until batch ends
    private var batchDepth: Int = 0
    private var needsSyncAfterBatch: Boolean = false

    // Notification state
    private var notificationCallback: ImeNotificationCallback? = null
    private var extractedTextToken: Int = 0
    private var isMonitoringExtractedText: Boolean = false
    private var lastNotifiedCursor: Int = -1
    private var lastNotifiedComposingStart: Int = -1
    private var lastNotifiedComposingEnd: Int = -1

    // Paste suppression: when we handle paste via performContextMenuAction,
    // some IMEs also deliver the pasted text via commitText. This flag tells
    // commitText to ignore that redundant delivery.
    private var suppressNextCommit: Boolean = false

    // Public read access
    val text: String get() = buffer.text
    val cursorPosition: Int get() = buffer.cursor
    val isInBatchEdit: Boolean get() = batchDepth > 0

    // =========================================================================
    // Sync and notification
    // =========================================================================

    fun syncFromController() {
        suppressNextCommit = false
        val content = controller.getLineContent(lineIndex)
        val cursor = controller.getContentCursor(lineIndex)

        // If the buffer already has this text and cursor, skip the reset to preserve
        // composition state. This happens when our own edit round-trips through the
        // controller back to us via recomposition. Resetting would clear the IME's
        // composing region, causing autocomplete to commit on every keystroke.
        if (buffer.text == content && buffer.cursor == cursor) {
            return
        }

        buffer.reset(content, cursor)
        updateExposedComposition()
    }

    fun setNotificationCallback(callback: ImeNotificationCallback?) {
        notificationCallback = callback
        if (callback == null) {
            isMonitoringExtractedText = false
            lastNotifiedCursor = -1
            lastNotifiedComposingStart = -1
            lastNotifiedComposingEnd = -1
        }
    }

    fun needsExternalNotification(): Boolean {
        return buffer.cursor != lastNotifiedCursor ||
            buffer.compositionStart != lastNotifiedComposingStart ||
            buffer.compositionEnd != lastNotifiedComposingEnd
    }

    fun startExtractedTextMonitoring(token: Int) {
        extractedTextToken = token
        isMonitoringExtractedText = true
    }

    private fun updateExposedComposition() {
        composingStart = buffer.compositionStart
        composingEnd = buffer.compositionEnd
    }

    private fun sendImeNotification() {
        val callback = notificationCallback ?: return

        updateExposedComposition()

        callback.notifySelectionUpdate(
            buffer.cursor, buffer.cursor,
            buffer.compositionStart, buffer.compositionEnd
        )

        lastNotifiedCursor = buffer.cursor
        lastNotifiedComposingStart = buffer.compositionStart
        lastNotifiedComposingEnd = buffer.compositionEnd

        if (isMonitoringExtractedText) {
            val extracted = ExtractedText().apply {
                text = buffer.text
                startOffset = 0
                selectionStart = buffer.cursor
                selectionEnd = buffer.cursor
            }
            callback.notifyExtractedTextUpdate(extractedTextToken, extracted)
        }
    }

    // =========================================================================
    // Batch edit and command execution
    // =========================================================================

    fun beginBatchEdit(): Boolean {
        batchDepth++
        return true
    }

    fun endBatchEdit(): Boolean {
        if (batchDepth > 0) {
            batchDepth--
        }

        if (batchDepth == 0 && needsSyncAfterBatch) {
            needsSyncAfterBatch = false
            syncBufferToController()
            sendImeNotification()
        }

        return batchDepth > 0
    }

    private fun applyCommand(apply: () -> Unit) {
        apply()

        if (batchDepth > 0) {
            needsSyncAfterBatch = true
        } else {
            syncBufferToController()
            sendImeNotification()
        }
    }

    private fun syncBufferToController() {
        controller.updateLineContent(lineIndex, buffer.text, buffer.cursor)
        updateExposedComposition()
    }

    // =========================================================================
    // IME InputConnection methods
    // =========================================================================

    fun commitText(text: String, newCursorPosition: Int) {
        // If a structured paste just happened via performContextMenuAction,
        // the IME may redundantly deliver the pasted text via commitText.
        // Skip it to avoid double-insertion.
        if (suppressNextCommit) {
            suppressNextCommit = false
            return
        }

        // Check for user selection - handle specially
        if (controller.hasSelection()) {
            if (text == " ") {
                controller.handleSpaceWithSelection()
            } else {
                controller.replaceSelectionNoUndo(text)
            }
            syncFromController()
            sendImeNotification()
            return
        }

        applyCommand {
            // If there's a composition, replace it; otherwise replace selection/insert at cursor
            if (buffer.hasComposition()) {
                val start = buffer.compositionStart
                buffer.replace(buffer.compositionStart, buffer.compositionEnd, text)
                buffer.cursor = if (newCursorPosition > 0) {
                    start + text.length + newCursorPosition - 1
                } else {
                    start + newCursorPosition
                }.coerceIn(0, buffer.length)
                buffer.commitComposition()
            } else {
                val start = buffer.selectionStart
                buffer.replace(buffer.selectionStart, buffer.selectionEnd, text)
                buffer.cursor = if (newCursorPosition > 0) {
                    start + text.length + newCursorPosition - 1
                } else {
                    start + newCursorPosition
                }.coerceIn(0, buffer.length)
            }
        }
    }

    fun setComposingText(text: String, newCursorPosition: Int) {
        // Any composing text means the user started typing, not a paste follow-up
        suppressNextCommit = false

        if (controller.hasSelection()) {
            controller.replaceSelectionNoUndo(text)
            syncFromController()
            buffer.setComposition(buffer.cursor - text.length, buffer.cursor)
            sendImeNotification()
            return
        }

        applyCommand {
            val start = if (buffer.hasComposition()) buffer.compositionStart else buffer.selectionStart
            val end = if (buffer.hasComposition()) buffer.compositionEnd else buffer.selectionEnd
            buffer.replace(start, end, text)
            buffer.setComposition(start, start + text.length)
            buffer.cursor = if (newCursorPosition > 0) {
                start + text.length + newCursorPosition - 1
            } else {
                start + newCursorPosition
            }.coerceIn(0, buffer.length)
        }
    }

    fun finishComposingText() {
        applyCommand {
            buffer.commitComposition()
        }
    }

    fun setComposingRegion(start: Int, end: Int) {
        applyCommand {
            buffer.commitComposition()
            if (start != end && start >= 0 && end >= 0) {
                val s = start.coerceIn(0, buffer.length)
                val e = end.coerceIn(0, buffer.length)
                if (s < e) buffer.setComposition(s, e) else buffer.setComposition(e, s)
            }
        }
    }

    fun setSelection(start: Int, end: Int) {
        if (start == end) {
            // Just cursor positioning - also update controller
            val prefix = controller.getLineText(lineIndex).let { LineState.extractPrefix(it) }
            controller.setCursor(lineIndex, prefix.length + start.coerceIn(0, buffer.length))
        }

        applyCommand {
            buffer.setSelection(
                start.coerceIn(0, buffer.length),
                end.coerceIn(0, buffer.length)
            )
        }
    }

    fun deleteSurroundingText(beforeLength: Int, afterLength: Int) {
        // Any delete means the user is interacting, not a paste follow-up
        suppressNextCommit = false

        if (controller.hasSelection()) {
            controller.deleteSelectionNoUndo()
            syncFromController()
            sendImeNotification()
            return
        }

        // Handle edge cases that need controller involvement
        if (beforeLength > 0 && buffer.cursor == 0) {
            controller.deleteBackward(lineIndex)
            syncFromController()
            sendImeNotification()
            return
        }
        if (afterLength > 0 && buffer.cursor >= buffer.length) {
            controller.deleteForward(lineIndex)
            syncFromController()
            sendImeNotification()
            return
        }

        applyCommand {
            if (beforeLength == 0 && afterLength == 0) return@applyCommand

            val cursor = buffer.cursor
            val deleteStart = (cursor - beforeLength).coerceAtLeast(0)
            val deleteEnd = (cursor + afterLength).coerceAtMost(buffer.length)
            if (deleteStart < deleteEnd) {
                buffer.delete(deleteStart, deleteEnd)
                buffer.cursor = deleteStart
            }
            buffer.commitComposition()
        }
    }

    fun handleKeyEvent(keyEvent: KeyEvent): Boolean {
        if (keyEvent.action == KeyEvent.ACTION_DOWN) {
            when (keyEvent.keyCode) {
                KeyEvent.KEYCODE_DEL -> {
                    deleteSurroundingText(1, 0)
                    return true
                }
                KeyEvent.KEYCODE_FORWARD_DEL -> {
                    deleteSurroundingText(0, 1)
                    return true
                }
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    handleEnter()
                    return true
                }
            }
        }
        return false
    }

    /**
     * Handle Enter key — finish any active composition, then insert a newline
     * via commitText so it flows through the normal newline-detection path
     * in updateLineContent.
     */
    fun handleEnter() {
        if (buffer.hasComposition()) {
            finishComposingText()
        }
        commitText("\n", 1)
    }

    // =========================================================================
    // Structured paste via IME context menu
    // =========================================================================

    /**
     * Handles paste from the soft keyboard context menu (long-press paste).
     * Reads the clipboard and routes through EditorController.paste() for
     * structured paste handling (prefix merging, indent shifting, etc.).
     *
     * Sets [suppressNextCommit] so that the redundant commitText call
     * that some IMEs send after performContextMenuAction(paste) is ignored.
     *
     * @return true if paste was handled, false if clipboard was empty
     */
    fun handlePaste(context: Context): Boolean {
        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return false
        val clip = clipboardManager.primaryClip ?: return false
        if (clip.itemCount == 0) return false

        val item = clip.getItemAt(0)
        val plainText = item.text?.toString() ?: return false
        if (plainText.isEmpty()) return false

        val htmlText = item.htmlText

        // Finish any active composition before pasting
        if (buffer.hasComposition()) {
            buffer.commitComposition()
            syncBufferToController()
        }

        // Route through EditorController.paste() for structured handling
        controller.paste(plainText, htmlText)

        // Sync our buffer from the controller's updated state
        syncFromController()
        sendImeNotification()

        // Suppress the next commitText — some IMEs deliver pasted text
        // both via performContextMenuAction AND commitText
        suppressNextCommit = true

        return true
    }
}
