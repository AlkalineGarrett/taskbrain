package org.alkaline.taskbrain.data

private const val MAX_TABS = 5
private const val MAX_DISPLAY_LENGTH = 12

/**
 * Pure state functions for tab management.
 * Separated from ViewModel for testability.
 */
object TabState {

    /**
     * Moves an existing tab to the front (updating its display text),
     * or adds a new tab at the front. List is not capped here since
     * the repository enforces the limit on persistence.
     */
    fun addOrUpdate(
        tabs: List<RecentTab>,
        noteId: String,
        displayText: String
    ): List<RecentTab> {
        val existing = tabs.find { it.noteId == noteId }
        return if (existing != null) {
            listOf(existing.copy(displayText = displayText)) +
                tabs.filter { it.noteId != noteId }
        } else {
            (listOf(RecentTab(noteId = noteId, displayText = displayText)) + tabs)
                .take(MAX_TABS)
        }
    }

    /**
     * Removes a tab from the list.
     */
    fun remove(tabs: List<RecentTab>, noteId: String): List<RecentTab> =
        tabs.filter { it.noteId != noteId }

    /**
     * Determines which tab to navigate to after closing a tab.
     * Returns null if no tabs remain, or the noteId isn't the current tab.
     * Returns the next tab's noteId (or previous if closing the last tab).
     */
    fun closeTabNavigationTarget(
        tabs: List<RecentTab>,
        closedNoteId: String,
        currentNoteId: String
    ): CloseTabResult {
        if (closedNoteId != currentNoteId) return CloseTabResult.StayOnCurrent
        val closedIndex = tabs.indexOfFirst { it.noteId == closedNoteId }
        val remaining = tabs.filter { it.noteId != closedNoteId }
        if (remaining.isEmpty()) return CloseTabResult.NavigateBack
        val nextIndex = minOf(closedIndex, remaining.size - 1)
        return CloseTabResult.SwitchTo(remaining[nextIndex].noteId)
    }

    /**
     * Updates the display text for a specific tab without reordering.
     */
    fun updateDisplayText(
        tabs: List<RecentTab>,
        noteId: String,
        displayText: String
    ): List<RecentTab> =
        tabs.map { if (it.noteId == noteId) it.copy(displayText = displayText) else it }

    /**
     * Extracts display text from note content.
     * Cleans the first line by removing alarm symbols and truncating.
     */
    fun extractDisplayText(content: String): String {
        val firstLine = content.lines().firstOrNull() ?: ""
        val cleaned = AlarmMarkers.stripAlarmMarkers(firstLine).trim()
        return when {
            cleaned.length > MAX_DISPLAY_LENGTH -> cleaned.take(MAX_DISPLAY_LENGTH - 1) + "…"
            cleaned.isEmpty() -> "New Note"
            else -> cleaned
        }
    }
}

sealed class CloseTabResult {
    /** Closed a non-current tab; stay on the current note. */
    data object StayOnCurrent : CloseTabResult()
    /** No tabs remain; navigate back to the note list. */
    data object NavigateBack : CloseTabResult()
    /** Switch to another tab. */
    data class SwitchTo(val noteId: String) : CloseTabResult()
}
