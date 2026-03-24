package org.alkaline.taskbrain.ui.currentnote

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.alkaline.taskbrain.ui.currentnote.util.LinePrefixes
import java.util.UUID

/**
 * Represents the state of a single line in the editor.
 * Handles prefix extraction, cursor position, and line operations.
 */
class LineState(
    text: String,
    cursorPosition: Int = text.length,
    noteIds: List<String> = emptyList()
) {
    var text by mutableStateOf(text)
        private set
    var cursorPosition by mutableIntStateOf(cursorPosition.coerceIn(0, text.length))
        private set

    /** Note IDs associated with this line (first = primary, rest = from merges). */
    var noteIds: List<String> = noteIds
        internal set

    /**
     * Content lengths per noteId, in text order (not noteId order).
     * When lines merge, records how many content characters each noteId contributed.
     * Used by splitNoteIds to distribute noteIds to the correct halves when a line is re-split.
     * Empty when not applicable (single noteId, loaded from server, etc.).
     */
    var noteIdContentLengths: List<Int> = emptyList()
        internal set

    /** Stable temporary ID for directive key generation before a Firestore noteId is assigned. */
    val tempId: String = UUID.randomUUID().toString()

    /** The effective ID for directive keys: noteId if available, otherwise tempId. */
    val effectiveId: String get() = noteIds.firstOrNull() ?: tempId

    /** The prefix portion (tabs + bullet/checkbox) */
    val prefix: String get() = extractPrefix(text)

    /** The content portion (after the prefix) */
    val content: String get() {
        val p = prefix
        return if (p.length < text.length) text.substring(p.length) else ""
    }

    /** Cursor position relative to content (for the TextField) */
    val contentCursorPosition: Int get() = (cursorPosition - prefix.length).coerceIn(0, content.length)

    /**
     * Updates the content portion of the line, keeping the prefix.
     */
    fun updateContent(newContent: String, newContentCursor: Int) {
        text = prefix + newContent
        cursorPosition = prefix.length + newContentCursor.coerceIn(0, newContent.length)
        noteIdContentLengths = emptyList()
    }

    /**
     * Updates the full line text and cursor position.
     */
    fun updateFull(newText: String, newCursor: Int) {
        text = newText
        cursorPosition = newCursor.coerceIn(0, newText.length)
        noteIdContentLengths = emptyList()
    }

    /**
     * Adds a tab at the beginning of the line.
     */
    fun indent() {
        text = "\t" + text
        cursorPosition = (cursorPosition + 1).coerceIn(0, text.length)
    }

    /**
     * Removes a tab from the beginning of the line (if present).
     */
    fun unindent(): Boolean {
        if (text.startsWith("\t")) {
            text = text.substring(1)
            cursorPosition = (cursorPosition - 1).coerceAtLeast(0)
            return true
        }
        return false
    }

    /**
     * Toggles bullet prefix on the line.
     */
    fun toggleBullet() {
        val tabCount = text.takeWhile { it == '\t' }.length
        val tabs = text.substring(0, tabCount)
        val afterTabs = text.substring(tabCount)

        val (newAfterTabs, cursorDelta) = when {
            afterTabs.startsWith(LinePrefixes.BULLET) -> {
                afterTabs.substring(LinePrefixes.BULLET.length) to -LinePrefixes.BULLET.length
            }
            afterTabs.startsWith(LinePrefixes.CHECKBOX_UNCHECKED) -> {
                LinePrefixes.BULLET + afterTabs.substring(LinePrefixes.CHECKBOX_UNCHECKED.length) to
                    (LinePrefixes.BULLET.length - LinePrefixes.CHECKBOX_UNCHECKED.length)
            }
            afterTabs.startsWith(LinePrefixes.CHECKBOX_CHECKED) -> {
                LinePrefixes.BULLET + afterTabs.substring(LinePrefixes.CHECKBOX_CHECKED.length) to
                    (LinePrefixes.BULLET.length - LinePrefixes.CHECKBOX_CHECKED.length)
            }
            else -> {
                LinePrefixes.BULLET + afterTabs to LinePrefixes.BULLET.length
            }
        }

        text = tabs + newAfterTabs
        cursorPosition = (cursorPosition + cursorDelta).coerceIn(0, text.length)
    }

    /**
     * Toggles checkbox prefix on the line.
     * Cycles: nothing → unchecked → checked → removed
     */
    fun toggleCheckbox() {
        val tabCount = text.takeWhile { it == '\t' }.length
        val tabs = text.substring(0, tabCount)
        val afterTabs = text.substring(tabCount)

        val (newAfterTabs, cursorDelta) = when {
            afterTabs.startsWith(LinePrefixes.CHECKBOX_UNCHECKED) -> {
                LinePrefixes.CHECKBOX_CHECKED + afterTabs.substring(LinePrefixes.CHECKBOX_UNCHECKED.length) to 0
            }
            afterTabs.startsWith(LinePrefixes.CHECKBOX_CHECKED) -> {
                afterTabs.substring(LinePrefixes.CHECKBOX_CHECKED.length) to -LinePrefixes.CHECKBOX_CHECKED.length
            }
            afterTabs.startsWith(LinePrefixes.BULLET) -> {
                LinePrefixes.CHECKBOX_UNCHECKED + afterTabs.substring(LinePrefixes.BULLET.length) to
                    (LinePrefixes.CHECKBOX_UNCHECKED.length - LinePrefixes.BULLET.length)
            }
            else -> {
                LinePrefixes.CHECKBOX_UNCHECKED + afterTabs to LinePrefixes.CHECKBOX_UNCHECKED.length
            }
        }

        text = tabs + newAfterTabs
        cursorPosition = (cursorPosition + cursorDelta).coerceIn(0, text.length)
    }

    /**
     * Toggles checkbox between checked and unchecked states only.
     * Does not remove the checkbox. Only works if line already has a checkbox.
     */
    fun toggleCheckboxState() {
        val tabCount = text.takeWhile { it == '\t' }.length
        val tabs = text.substring(0, tabCount)
        val afterTabs = text.substring(tabCount)

        val newAfterTabs = when {
            afterTabs.startsWith(LinePrefixes.CHECKBOX_UNCHECKED) -> {
                LinePrefixes.CHECKBOX_CHECKED + afterTabs.substring(LinePrefixes.CHECKBOX_UNCHECKED.length)
            }
            afterTabs.startsWith(LinePrefixes.CHECKBOX_CHECKED) -> {
                LinePrefixes.CHECKBOX_UNCHECKED + afterTabs.substring(LinePrefixes.CHECKBOX_CHECKED.length)
            }
            else -> return // Not a checkbox, do nothing
        }

        text = tabs + newAfterTabs
        // Cursor position doesn't change since checkbox length is the same
    }

    companion object {
        /**
         * Extracts the prefix (leading tabs + bullet/checkbox) from a line.
         */
        fun extractPrefix(line: String): String {
            var position = 0

            while (position < line.length && line[position] == '\t') {
                position++
            }

            val afterTabs = if (position < line.length) line.substring(position) else ""
            val prefixEnd = when {
                afterTabs.startsWith(LinePrefixes.BULLET) -> position + LinePrefixes.BULLET.length
                afterTabs.startsWith(LinePrefixes.CHECKBOX_UNCHECKED) -> position + LinePrefixes.CHECKBOX_UNCHECKED.length
                afterTabs.startsWith(LinePrefixes.CHECKBOX_CHECKED) -> position + LinePrefixes.CHECKBOX_CHECKED.length
                else -> position
            }

            return if (prefixEnd > 0) line.substring(0, prefixEnd) else ""
        }
    }
}
