package org.alkaline.taskbrain.ui.currentnote.util

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

// Use constants from LinePrefixes
private const val BULLET = LinePrefixes.BULLET
private const val ASTERISK_SPACE = LinePrefixes.ASTERISK_SPACE

private const val CHECKBOX_UNCHECKED = LinePrefixes.CHECKBOX_UNCHECKED
private const val CHECKBOX_CHECKED = LinePrefixes.CHECKBOX_CHECKED
private const val BRACKETS_EMPTY = LinePrefixes.BRACKETS_EMPTY
private const val BRACKETS_CHECKED = LinePrefixes.BRACKETS_CHECKED

private const val TAB = LinePrefixes.TAB

// All line prefixes that trigger continuation on Enter
private val LINE_PREFIXES = LinePrefixes.LINE_PREFIXES

/**
 * Transforms text to handle bullet list and checkbox formatting:
 *
 * Bullets:
 * - "* " at line start converts to "• "
 * - Enter after a bullet line adds a bullet to the new line
 * - Enter on an empty bullet line exits bullet mode
 * - Backspace at a bullet converts "• " back to "* "
 *
 * Checkboxes:
 * - "[]" at line start converts to "☐ " (unchecked)
 * - "[x]" at line start converts to "☑ " (checked)
 * - Enter after a checkbox line adds an unchecked checkbox to the new line
 * - Enter on an empty checkbox line exits checkbox mode
 * - Backspace at a checkbox converts back to "[]" or "[x]"
 *
 * Indentation:
 * - Space after tabs at line start (before bullet/checkbox) converts to tab (indent)
 * - Backspace on tab at line start (before bullet/checkbox) removes tab (unindent)
 */
fun transformBulletText(oldValue: TextFieldValue, newValue: TextFieldValue): TextFieldValue {
    var text = newValue.text
    var cursor = newValue.selection.start
    var wasTransformed = false

    // Track cursor adjustments from conversions
    var cursorAdjustment = 0

    // Case 1a: "* " at line start -> "• "
    val bulletResult = convertAsteriskToBullet(text, cursor)
    if (bulletResult.first != text) wasTransformed = true
    text = bulletResult.first
    cursorAdjustment += bulletResult.second

    // Case 1b: "[]" at line start -> "☐ "
    val uncheckedResult = convertBracketsToCheckbox(text, cursor + cursorAdjustment, BRACKETS_EMPTY, CHECKBOX_UNCHECKED)
    if (uncheckedResult.first != text) wasTransformed = true
    text = uncheckedResult.first
    cursorAdjustment += uncheckedResult.second

    // Case 1c: "[x]" at line start -> "☑ "
    val checkedResult = convertBracketsToCheckbox(text, cursor + cursorAdjustment, BRACKETS_CHECKED, CHECKBOX_CHECKED)
    if (checkedResult.first != text) wasTransformed = true
    text = checkedResult.first
    cursorAdjustment += checkedResult.second

    cursor += cursorAdjustment

    // Case 2: Enter handling (add prefix or exit list mode)
    val isNewlineInserted = text.length == oldValue.text.length + 1 + cursorAdjustment &&
            cursor > 0 &&
            text.getOrNull(cursor - 1) == '\n'

    if (isNewlineInserted) {
        val result = handleEnterOnPrefixedLine(text, cursor)
        if (result.first != text) wasTransformed = true
        text = result.first
        cursor = result.second
    }

    // Case 3: Backspace handling
    val isCharDeleted = text.length == oldValue.text.length - 1

    if (isCharDeleted) {
        val result = handleBackspaceOnPrefix(text, cursor)
        if (result.first != text) wasTransformed = true
        text = result.first
        cursor = result.second
    }

    // Case 4: Space to tab for indentation (space typed at start of line before bullet/checkbox)
    val isSpaceInserted = text.length == oldValue.text.length + 1 &&
            cursor > 0 &&
            text.getOrNull(cursor - 1) == ' '

    if (isSpaceInserted) {
        val result = handleSpaceToTabIndent(text, cursor)
        if (result.first != text) wasTransformed = true
        text = result.first
        cursor = result.second
    }

    // Case 5: Tab deletion for unindent
    if (isCharDeleted && !wasTransformed) {
        val result = handleTabUnindent(oldValue.text, text, cursor)
        if (result.first != text) wasTransformed = true
        text = result.first
        cursor = result.second
    }

    // If no transformation occurred, preserve the original selection (important for text selection)
    if (!wasTransformed && text == newValue.text) {
        return newValue
    }

    return TextFieldValue(text, TextRange(cursor))
}

private fun convertAsteriskToBullet(text: String, cursor: Int): Pair<String, Int> {
    // Match "* " at the start of the string or after a newline, replace all occurrences
    val newText = text.replace(Regex("(^|\\n)\\* ")) { match ->
        val prefix = if (match.value.startsWith("\n")) "\n" else ""
        "$prefix$BULLET"
    }

    // No cursor adjustment needed (same length: "* " -> "• ")
    return Pair(newText, 0)
}

private fun convertBracketsToCheckbox(
    text: String,
    cursor: Int,
    brackets: String,
    checkbox: String
): Pair<String, Int> {
    // Match brackets at the start of the string or after a newline
    val escapedBrackets = Regex.escape(brackets)
    val regex = Regex("(^|\\n)$escapedBrackets")

    // Count matches to calculate cursor adjustment
    val matchCount = regex.findAll(text).count()
    if (matchCount == 0) return Pair(text, 0)

    val newText = text.replace(regex) { match ->
        val prefix = if (match.value.startsWith("\n")) "\n" else ""
        "$prefix$checkbox"
    }

    // Cursor adjustment per match: "[]" (2 chars) -> "☐ " (2 chars) = 0
    // Cursor adjustment per match: "[x]" (3 chars) -> "☑ " (2 chars) = -1
    val adjustmentPerMatch = checkbox.length - brackets.length
    val totalAdjustment = adjustmentPerMatch * matchCount

    return Pair(newText, totalAdjustment)
}

private fun handleEnterOnPrefixedLine(text: String, cursor: Int): Pair<String, Int> {
    val newlinePos = cursor - 1
    val prevLineStart = if (newlinePos == 0) 0 else text.lastIndexOf('\n', newlinePos - 1) + 1
    val prevLine = text.substring(prevLineStart, newlinePos)

    // Extract leading tabs (indentation)
    val leadingTabs = prevLine.takeWhile { it == '\t' }
    val lineWithoutTabs = prevLine.removePrefix(leadingTabs)

    // Find which prefix the line has (after tabs)
    val matchedPrefix = LINE_PREFIXES.find { lineWithoutTabs.startsWith(it) || lineWithoutTabs == it.trimEnd() }
        ?: return Pair(text, cursor)

    val contentAfterPrefix = lineWithoutTabs.removePrefix(matchedPrefix)
    val isEmpty = contentAfterPrefix.isBlank()

    return if (isEmpty) {
        // Empty prefixed line - exit list mode or unindent
        if (leadingTabs.isNotEmpty()) {
            // Has indentation - unindent by one level instead of exiting
            val unindentedTabs = leadingTabs.dropLast(1)
            val newLineContent = unindentedTabs + matchedPrefix
            val newText = text.substring(0, prevLineStart) + newLineContent + text.substring(cursor)
            Pair(newText, prevLineStart + newLineContent.length)
        } else {
            // No indentation - exit list mode (remove prefix and newline)
            val newText = text.substring(0, prevLineStart) + text.substring(cursor)
            Pair(newText, prevLineStart)
        }
    } else {
        // Line with content - add prefix to new line with same indentation.
        // Preserve checked state when splitting mid-line (content follows cursor);
        // uncheck when cursor is at the end (new empty line after a checked item).
        val atEnd = cursor >= text.length || text.substring(cursor).all { it == '\n' }
        val newPrefix = if (matchedPrefix == CHECKBOX_CHECKED && atEnd) CHECKBOX_UNCHECKED else matchedPrefix
        val newLinePrefix = leadingTabs + newPrefix
        val newText = text.substring(0, cursor) + newLinePrefix + text.substring(cursor)
        Pair(newText, cursor + newLinePrefix.length)
    }
}

private fun handleBackspaceOnPrefix(text: String, cursor: Int): Pair<String, Int> {
    // Check for bullet without space
    val bulletResult = handleBackspaceOnBullet(text, cursor)
    if (bulletResult.first != text) {
        return bulletResult
    }

    // Check for checkbox without space
    return handleBackspaceOnCheckbox(text, cursor)
}

private fun handleBackspaceOnBullet(text: String, cursor: Int): Pair<String, Int> {
    // Look for "•" at line start without a following space (space was just deleted)
    val bulletNoSpaceRegex = Regex("(^|\\n)•(?! )")
    val match = bulletNoSpaceRegex.find(text) ?: return Pair(text, cursor)

    // Calculate position of the bullet character
    val bulletPos = if (match.value.startsWith("\n")) match.range.first + 1 else match.range.first

    // Replace "•" with "* " (restore the asterisk and space)
    val newText = text.substring(0, bulletPos) + ASTERISK_SPACE + text.substring(bulletPos + 1)

    // Adjust cursor (we added one character)
    return Pair(newText, cursor + 1)
}

private fun handleBackspaceOnCheckbox(text: String, cursor: Int): Pair<String, Int> {
    // Look for "☐" at line start without a following space
    val uncheckedNoSpaceRegex = Regex("(^|\\n)☐(?! )")
    val uncheckedMatch = uncheckedNoSpaceRegex.find(text)
    if (uncheckedMatch != null) {
        val checkboxPos = if (uncheckedMatch.value.startsWith("\n")) uncheckedMatch.range.first + 1 else uncheckedMatch.range.first
        // Replace "☐" with "[]" (restore brackets, no space added since [] doesn't have trailing space in source)
        val newText = text.substring(0, checkboxPos) + BRACKETS_EMPTY + text.substring(checkboxPos + 1)
        // Cursor adjustment: "☐" (1 char) -> "[]" (2 chars) = +1
        return Pair(newText, cursor + 1)
    }

    // Look for "☑" at line start without a following space
    val checkedNoSpaceRegex = Regex("(^|\\n)☑(?! )")
    val checkedMatch = checkedNoSpaceRegex.find(text)
    if (checkedMatch != null) {
        val checkboxPos = if (checkedMatch.value.startsWith("\n")) checkedMatch.range.first + 1 else checkedMatch.range.first
        // Replace "☑" with "[x]" (restore brackets)
        val newText = text.substring(0, checkboxPos) + BRACKETS_CHECKED + text.substring(checkboxPos + 1)
        // Cursor adjustment: "☑" (1 char) -> "[x]" (3 chars) = +2
        return Pair(newText, cursor + 2)
    }

    return Pair(text, cursor)
}

/**
 * Handles checkbox tap/toggle behavior.
 * If the cursor moved to a checkbox position without text change, toggle the checkbox.
 *
 * @return The transformed TextFieldValue if a toggle occurred, null otherwise
 */
fun handleCheckboxTap(oldValue: TextFieldValue, newValue: TextFieldValue): TextFieldValue? {
    // Only handle if text is unchanged but cursor moved (indicates tap, not typing)
    if (oldValue.text != newValue.text) return null
    if (oldValue.selection == newValue.selection) return null

    // Don't interfere with text selection:
    // - If new selection is a range, user is selecting text
    // - If old selection was a range, user is finishing/manipulating a selection
    if (!newValue.selection.collapsed) return null
    if (!oldValue.selection.collapsed) return null

    val text = newValue.text
    val cursor = newValue.selection.start

    // Find the start of the current line
    val lineStart = if (cursor == 0) 0 else text.lastIndexOf('\n', cursor - 1) + 1

    // Check if this line starts with a checkbox and cursor is on it (position 0 or 1 of checkbox)
    val cursorOffsetInLine = cursor - lineStart

    // Cursor must be at position 0 or 1 (on the checkbox character or right after it)
    if (cursorOffsetInLine > 1) return null

    val lineText = text.substring(lineStart)

    return when {
        lineText.startsWith(CHECKBOX_UNCHECKED) -> {
            // Toggle unchecked -> checked
            val newText = text.substring(0, lineStart) + CHECKBOX_CHECKED + text.substring(lineStart + CHECKBOX_UNCHECKED.length)
            // Move cursor after the checkbox
            TextFieldValue(newText, TextRange(lineStart + CHECKBOX_CHECKED.length))
        }
        lineText.startsWith(CHECKBOX_CHECKED) -> {
            // Toggle checked -> unchecked
            val newText = text.substring(0, lineStart) + CHECKBOX_UNCHECKED + text.substring(lineStart + CHECKBOX_CHECKED.length)
            // Move cursor after the checkbox
            TextFieldValue(newText, TextRange(lineStart + CHECKBOX_UNCHECKED.length))
        }
        else -> null
    }
}

/**
 * Handles space-to-tab conversion for indentation.
 * When a space is typed at the start of a line (after any existing tabs but before a bullet/checkbox),
 * convert it to a tab character.
 */
private fun handleSpaceToTabIndent(text: String, cursor: Int): Pair<String, Int> {
    if (cursor == 0) return Pair(text, cursor)

    // Find the start of the current line
    val lineStart = text.lastIndexOf('\n', cursor - 1) + 1

    // Get the content from line start to cursor
    val beforeCursor = text.substring(lineStart, cursor)

    // Check if the space was typed in the indentation area (only tabs and the new space before bullet/checkbox)
    // Pattern: tabs followed by a space, then a bullet/checkbox should follow
    if (!beforeCursor.matches(Regex("\\t* "))) return Pair(text, cursor)

    // Check what comes after the cursor - should be a bullet or checkbox
    val afterCursor = text.substring(cursor)
    val hasBulletOrCheckbox = afterCursor.startsWith(BULLET.trimEnd()) ||
            afterCursor.startsWith(CHECKBOX_UNCHECKED.trimEnd()) ||
            afterCursor.startsWith(CHECKBOX_CHECKED.trimEnd())

    if (!hasBulletOrCheckbox) return Pair(text, cursor)

    // Replace the space with a tab
    val newText = text.substring(0, cursor - 1) + TAB + text.substring(cursor)
    return Pair(newText, cursor)
}

/**
 * Handles tab deletion for unindentation.
 * When backspace deletes a tab at the start of a line (before a bullet/checkbox), that's an unindent.
 */
private fun handleTabUnindent(oldText: String, newText: String, cursor: Int): Pair<String, Int> {
    // Check if a tab was deleted (compare old and new text at cursor position)
    if (cursor >= oldText.length) return Pair(newText, cursor)

    val deletedChar = oldText.getOrNull(cursor)
    if (deletedChar != '\t') return Pair(newText, cursor)

    // Find the start of the current line in the new text
    val lineStart = if (cursor == 0) 0 else newText.lastIndexOf('\n', cursor - 1) + 1

    // Verify the tab was in the indentation area (before bullet/checkbox)
    val afterCursor = newText.substring(cursor)
    val indentArea = newText.substring(lineStart, cursor)

    // The area before cursor should be only tabs, and after should start with bullet/checkbox
    val isIndentArea = indentArea.all { it == '\t' }
    val hasBulletOrCheckbox = afterCursor.startsWith(BULLET.trimEnd()) ||
            afterCursor.startsWith(CHECKBOX_UNCHECKED.trimEnd()) ||
            afterCursor.startsWith(CHECKBOX_CHECKED.trimEnd())

    if (isIndentArea && hasBulletOrCheckbox) {
        // This is a valid unindent - the deletion already happened, just return as-is
        return Pair(newText, cursor)
    }

    return Pair(newText, cursor)
}
