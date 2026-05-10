package org.alkaline.taskbrain.ui.currentnote.ime

import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import android.view.inputmethod.ExtractedText
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
 * Per-line IME state, bound to a stable [lineId] (the line's
 * [LineState.tempId]) rather than a `lineIndex`. Structural mutations
 * — split, merge, reorder, paste, indent — shift line indices, but
 * the underlying [LineState] (and its tempId) survive them, so the
 * IME state stays correctly anchored.
 *
 * **No parallel buffer.** Earlier this class held an [EditingBuffer]
 * mirroring the controller's line content. Each IME operation
 * mutated the buffer first, then synced down to the controller. The
 * sync code (and the buffer's potential to drift from the controller
 * between calls) was a recurring bug surface — most notably the
 * 2026-05-07 sentinel-storm crash, where a buffer kept a stale `\n`
 * after a controller split and re-sent it on every keystroke.
 *
 * Now the controller is the single source of truth for line content,
 * cursor, and composing range. This class is a thin translator: it
 * receives `InputConnection` calls from Android, expresses them as
 * controller operations ([EditorController.replaceRange],
 * [EditorController.deleteAroundCursor], etc.), and pushes IME
 * notifications back out. Composition state is stored on
 * [LineState.composingRange]; cursor + content are read on demand.
 *
 * If [lineId] is removed (e.g., merged into a sibling), every
 * operation is a no-op — the controller's lookup-by-id returns null
 * and the call falls through. The OS-level `InputConnection` is torn
 * down separately by Compose's focus tracking when the line
 * disappears from the rendered tree.
 */
class LineImeState(
    private val lineId: String,
    private val controller: EditorController
) {
    /** Resolve [lineId] to its current line index, or -1 if removed. */
    private val lineIndex: Int get() = controller.indexOf(lineId) ?: -1

    /** Current line content (controller is the source). */
    val text: String get() = controller.getLineContent(lineIndex)

    /** Current cursor offset within content. */
    val cursorPosition: Int get() = controller.getContentCursor(lineIndex)

    val length: Int get() = text.length

    private fun composingRange(): IntRange? = controller.getComposingRange(lineId)
    private fun hasComposition(): Boolean = composingRange() != null

    /** Composing region exposed for renderers / debug. */
    val composingStart: Int get() = composingRange()?.first ?: -1
    val composingEnd: Int get() = composingRange()?.let { it.last + 1 } ?: -1

    // Batch edit state — defers IME notifications until the IME
    // ends the batch. Multiple calls between begin/endBatchEdit
    // accumulate; one notification fires at end.
    private var batchDepth: Int = 0
    private var needsNotifyAfterBatch: Boolean = false

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

    val isInBatchEdit: Boolean get() = batchDepth > 0

    // =========================================================================
    // Sync and notification
    // =========================================================================

    /**
     * Reset state on focus / external load. The controller is already
     * the source of truth; this just clears IME-side flags so the next
     * commit isn't suppressed and notification baselines refresh.
     */
    fun syncFromController() {
        suppressNextCommit = false
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
        return cursorPosition != lastNotifiedCursor ||
            composingStart != lastNotifiedComposingStart ||
            composingEnd != lastNotifiedComposingEnd
    }

    fun startExtractedTextMonitoring(token: Int) {
        extractedTextToken = token
        isMonitoringExtractedText = true
    }

    private fun sendImeNotification() {
        val callback = notificationCallback ?: return

        val cursor = cursorPosition
        val cs = composingStart
        val ce = composingEnd
        callback.notifySelectionUpdate(cursor, cursor, cs, ce)

        lastNotifiedCursor = cursor
        lastNotifiedComposingStart = cs
        lastNotifiedComposingEnd = ce

        if (isMonitoringExtractedText) {
            val extracted = ExtractedText().apply {
                text = this@LineImeState.text
                startOffset = 0
                selectionStart = cursor
                selectionEnd = cursor
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

        if (batchDepth == 0 && needsNotifyAfterBatch) {
            needsNotifyAfterBatch = false
            sendImeNotification()
        }

        return batchDepth > 0
    }

    /**
     * Run [apply] (which mutates the controller via the operation API)
     * and then send the IME notification. Inside a batch edit, defer
     * the notification until the batch ends.
     */
    private fun applyCommand(apply: () -> Unit) {
        apply()
        if (batchDepth > 0) {
            needsNotifyAfterBatch = true
        } else {
            sendImeNotification()
        }
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
                controller.replaceSelectionWithUndo(text)
            }
            sendImeNotification()
            return
        }

        applyCommand {
            // The replacement range: composition (if active) or
            // cursor-only insert. commitText finalizes any active
            // composition.
            val comp = composingRange()
            val rangeStart = comp?.first ?: cursorPosition
            val rangeEnd = comp?.let { it.last + 1 } ?: cursorPosition
            controller.setComposingRange(lineId, null)

            controller.replaceRange(lineId, rangeStart until rangeEnd, text)

            // newCursorPosition spec: >0 → start + textLen + (n-1);
            // ≤0 → start + n. Default n=1 → cursor at end-of-text,
            // which replaceRange already produces. Skip override on
            // multi-line commits — the cursor home is on a different
            // line and overriding via this lineId is meaningless.
            if (newCursorPosition != 1 && !text.contains('\n')) {
                val finalCursor = if (newCursorPosition > 0) {
                    rangeStart + text.length + newCursorPosition - 1
                } else {
                    rangeStart + newCursorPosition
                }
                controller.setCursorByLineId(lineId, finalCursor.coerceAtLeast(0))
            }
        }
    }

    fun setComposingText(text: String, newCursorPosition: Int) {
        // Any composing text means the user started typing, not a paste follow-up
        suppressNextCommit = false

        if (controller.hasSelection()) {
            controller.replaceSelectionNoUndo(text)
            // The new composition spans the just-inserted text.
            val cursor = cursorPosition
            controller.setComposingRange(lineId, (cursor - text.length) until cursor)
            sendImeNotification()
            return
        }

        applyCommand {
            val comp = composingRange()
            val rangeStart = comp?.first ?: cursorPosition
            val rangeEnd = comp?.let { it.last + 1 } ?: cursorPosition

            controller.replaceRange(lineId, rangeStart until rangeEnd, text)

            controller.setComposingRange(
                lineId,
                rangeStart until (rangeStart + text.length),
            )

            if (newCursorPosition != 1) {
                val finalCursor = if (newCursorPosition > 0) {
                    rangeStart + text.length + newCursorPosition - 1
                } else {
                    rangeStart + newCursorPosition
                }
                controller.setCursorByLineId(lineId, finalCursor.coerceAtLeast(0))
            }
        }
    }

    fun finishComposingText() {
        applyCommand {
            controller.setComposingRange(lineId, null)
        }
    }

    fun setComposingRegion(start: Int, end: Int) {
        applyCommand {
            controller.setComposingRange(lineId, null)
            if (start != end && start >= 0 && end >= 0) {
                val s = start.coerceIn(0, length)
                val e = end.coerceIn(0, length)
                if (s < e) controller.setComposingRange(lineId, s until e)
                else if (e < s) controller.setComposingRange(lineId, e until s)
            }
        }
    }

    fun setSelection(start: Int, end: Int) {
        // GBoard's Android-14+ suggestion-tap path is
        // setSelection(typoStart, typoEnd) → commitText(suggestion). The
        // range selection has to land on the controller's selection state
        // so the next commitText / setComposingText / deleteSurroundingText
        // hits the existing hasSelection branch and replaces, instead of
        // inserting at the cursor (which manifests as the suggestion being
        // appended to the typo). Real composing is mutually exclusive with
        // a real selection — clear it.
        if (start == end) {
            val prefix = controller.getLineText(lineIndex).let { LineState.extractPrefix(it) }
            controller.setCursor(lineIndex, prefix.length + start.coerceIn(0, length))
        } else if (start >= 0 && end >= 0) {
            controller.setComposingRange(lineId, null)
            val s = start.coerceIn(0, length)
            val e = end.coerceIn(0, length)
            controller.setSelectionInLine(lineIndex, minOf(s, e), maxOf(s, e))
        }
        sendImeNotification()
    }

    fun deleteSurroundingText(beforeLength: Int, afterLength: Int) {
        // Any delete means the user is interacting, not a paste follow-up
        suppressNextCommit = false

        if (controller.hasSelection()) {
            controller.deleteSelectionNoUndo()
            sendImeNotification()
            return
        }

        if (beforeLength == 0 && afterLength == 0) return

        applyCommand {
            controller.setComposingRange(lineId, null)
            // deleteAroundCursor handles cross-boundary merges
            // (cursor at start of line, delete back into previous;
            // cursor at end of line, delete forward into next).
            controller.deleteAroundCursor(lineId, beforeLength, afterLength)
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
     * Handle Enter — finalize any active composition, then insert a
     * newline. The newline routes through replaceRange in commitText,
     * which splits the line.
     */
    fun handleEnter() {
        if (hasComposition()) {
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
        controller.setComposingRange(lineId, null)

        // Route through EditorController.paste() for structured handling
        controller.paste(plainText, htmlText)

        sendImeNotification()

        // Suppress the next commitText — some IMEs deliver pasted text
        // both via performContextMenuAction AND commitText
        suppressNextCommit = true

        return true
    }
}
