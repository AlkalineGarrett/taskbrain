package org.alkaline.taskbrain.ui.currentnote.util

/**
 * Symbols in note text that can be tapped to trigger actions.
 * To add a new tappable symbol, add an enum entry and handle it
 * in the `when` dispatch in CurrentNoteScreen.
 */
enum class TappableSymbol(val char: String) {
    ALARM("⏰");

    companion object {
        fun at(text: String, offset: Int): TappableSymbol? {
            if (offset < 0 || offset >= text.length) return null
            val ch = text[offset].toString()
            return entries.find { it.char == ch }
        }

        fun containsAny(text: String): Boolean =
            entries.any { text.contains(it.char) }
    }
}

/**
 * Information about a tapped symbol, used to dispatch to the correct handler.
 */
data class SymbolTapInfo(
    val symbol: TappableSymbol,
    val charOffset: Int,
    val lineIndex: Int,
    val symbolIndexOnLine: Int,
    /** When tapping an alarm directive, the alarm document ID from [alarm("id")]. */
    val alarmId: String? = null
)
