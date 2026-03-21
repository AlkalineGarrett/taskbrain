package org.alkaline.taskbrain.ui.currentnote.util

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.alkaline.taskbrain.data.AlarmMarkers

/**
 * Utilities for managing alarm symbols in text.
 * Delegates shared constants/functions to [AlarmMarkers] in the data layer.
 */
object AlarmSymbolUtils {

    const val ALARM_SYMBOL = AlarmMarkers.ALARM_SYMBOL

    /** Regex matching alarm directives like [alarm("abc123")] */
    val ALARM_DIRECTIVE_REGEX = AlarmMarkers.ALARM_DIRECTIVE_REGEX

    /**
     * Inserts an alarm symbol at the end of the line containing the cursor.
     * Preserves the cursor position.
     */
    fun insertAlarmSymbolAtLineEnd(textFieldValue: TextFieldValue): TextFieldValue {
        val text = textFieldValue.text
        val cursorPos = textFieldValue.selection.start
        val lineEnd = TextLineUtils.findLineEnd(text, cursorPos)

        // Check if there's already an alarm symbol at the end of this line
        val lineStart = TextLineUtils.findLineStart(text, cursorPos)
        val lineContent = text.substring(lineStart, lineEnd)

        // Add space before symbol if line doesn't end with whitespace and isn't empty
        val prefix = if (lineContent.isNotEmpty() && !lineContent.last().isWhitespace()) " " else ""
        val symbolToInsert = "$prefix$ALARM_SYMBOL"

        val newText = text.substring(0, lineEnd) + symbolToInsert + text.substring(lineEnd)

        // Adjust cursor position if it was after the insertion point
        val newCursorPos = if (cursorPos >= lineEnd) cursorPos + symbolToInsert.length else cursorPos

        return TextFieldValue(
            text = newText,
            selection = TextRange(newCursorPos)
        )
    }

    /**
     * Checks if the character at the given offset is an alarm symbol.
     */
    fun isAlarmSymbol(text: String, offset: Int): Boolean {
        return offset >= 0 && offset < text.length && text[offset].toString() == ALARM_SYMBOL
    }

    /**
     * Finds all alarm symbol positions in the text.
     * Returns a list of character offsets.
     */
    fun findAllAlarmSymbols(text: String): List<Int> {
        val positions = mutableListOf<Int>()
        var index = 0
        while (index < text.length) {
            if (text[index].toString() == ALARM_SYMBOL) {
                positions.add(index)
            }
            index++
        }
        return positions
    }

    /** Creates an alarm directive string: [alarm("abc123")] */
    fun alarmDirective(alarmId: String): String = AlarmMarkers.alarmDirective(alarmId)

    /** Strips all alarm directives and plain alarm symbols from text. */
    fun stripAlarmMarkers(text: String): String = AlarmMarkers.stripAlarmMarkers(text)

    /**
     * Migrates plain ⏰ characters in a line to [alarm("id")] directives.
     *
     * Each ⏰ (left to right) is paired with the corresponding alarm ID.
     * If there are more ⏰ than alarm IDs, extra ⏰ are left as-is.
     *
     * @param line The line content
     * @param alarmIds Alarm IDs in the order they should be assigned (typically sorted by createdAt)
     * @return The migrated line, or the original if no ⏰ were found
     */
    fun migrateLine(line: String, alarmIds: List<String>): String {
        val positions = findAllAlarmSymbols(line)
        if (positions.isEmpty()) return line

        val replacements = positions.zip(alarmIds).reversed()
        if (replacements.isEmpty()) return line

        var result = line
        for ((pos, id) in replacements) {
            val directive = alarmDirective(id)
            result = result.substring(0, pos) + directive + result.substring(pos + 1)
        }
        return result
    }

    /**
     * Removes an alarm symbol at the specified position.
     * Also removes the preceding space if present.
     */
    fun removeAlarmSymbol(textFieldValue: TextFieldValue, symbolOffset: Int): TextFieldValue {
        val text = textFieldValue.text
        if (!isAlarmSymbol(text, symbolOffset)) return textFieldValue

        // Check if there's a space before the symbol
        val removeSpace = symbolOffset > 0 && text[symbolOffset - 1] == ' '
        val startOffset = if (removeSpace) symbolOffset - 1 else symbolOffset
        val endOffset = symbolOffset + 1

        val newText = text.substring(0, startOffset) + text.substring(endOffset)

        // Adjust cursor position
        val cursorPos = textFieldValue.selection.start
        val newCursorPos = when {
            cursorPos > endOffset -> cursorPos - (endOffset - startOffset)
            cursorPos > startOffset -> startOffset
            else -> cursorPos
        }

        return TextFieldValue(
            text = newText,
            selection = TextRange(newCursorPos)
        )
    }
}
