package org.alkaline.taskbrain.data

private const val MAX_DISPLAY_LENGTH = 12

/** Tabs visible in the bar. The Firestore-backed history stores 2x this. */
const val MAX_DISPLAYED = 5

/**
 * Pure state functions for tab management.
 * Separated from ViewModel for testability.
 */
object TabState {

    /** What the screen needs to provide for the pinned current-tab slot. */
    data class CurrentTab(val noteId: String, val displayText: String)

    /**
     * Build the displayed tab list. The current tab (per-device) is pinned at
     * slot 0; the shared history fills the rest with the current tab deduped.
     *
     * The shared list is the same on every device, but each device's display
     * differs because each device's currentTab is local. With the current tab
     * not in the shared list (e.g. another device pushed it past the 10-item
     * buffer), it still renders in slot 0 — we keep showing what the user is
     * actually viewing.
     */
    fun computeDisplayTabs(
        currentTab: CurrentTab?,
        sharedTabs: List<RecentTab>
    ): List<RecentTab> {
        if (currentTab == null) return sharedTabs.take(MAX_DISPLAYED)
        val rest = sharedTabs.filter { it.noteId != currentTab.noteId }
        val pinned = RecentTab(noteId = currentTab.noteId, displayText = currentTab.displayText)
        return (listOf(pinned) + rest).take(MAX_DISPLAYED)
    }

    /**
     * After removing [removedNoteId] from the shared history, the noteId the
     * caller should navigate to (next-most-recent that isn't the removed one),
     * or null when nothing is left and the caller should go home.
     */
    fun nextNoteIdAfterRemove(
        removedNoteId: String,
        sharedTabs: List<RecentTab>
    ): String? =
        sharedTabs.firstOrNull { it.noteId != removedNoteId }?.noteId

    /**
     * Determines which tab to navigate to after closing a tab.
     * Returns null if no tabs remain, or the noteId isn't the current tab.
     * Returns the next tab's noteId (or previous if closing the last tab).
     */
    fun closeTabNavigationTarget(
        sharedTabs: List<RecentTab>,
        closedNoteId: String,
        currentNoteId: String
    ): CloseTabResult {
        if (closedNoteId != currentNoteId) return CloseTabResult.StayOnCurrent
        val next = nextNoteIdAfterRemove(closedNoteId, sharedTabs)
        return if (next == null) CloseTabResult.NavigateBack else CloseTabResult.SwitchTo(next)
    }

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
