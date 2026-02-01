package org.alkaline.taskbrain.ui.currentnote.ime

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for getEffectiveDisplayContent - the core fix for the stale content bug.
 *
 * THE BUG:
 * When user taps out to save an inline edit, there's a ~1.5 second window where
 * isEditing becomes false but directiveResults still has stale content.
 * Without the fix, displayContent (from stale directiveResults) is shown.
 *
 * THE FIX:
 * During the transitional state (isEditing=false, hasActiveSession=true),
 * use sessionContent instead of displayContent.
 */
class EffectiveDisplayContentTest {

    private val oldContent = "Old content before edit"
    private val newContent = "New content after edit"

    @Test
    fun `when editing, returns displayContent`() {
        // While actively editing, displayContent should be used
        // (the editor UI is showing, not the display text)
        val result = getEffectiveDisplayContent(
            isEditing = true,
            hasActiveSession = true,
            displayContent = oldContent,
            sessionContent = newContent
        )
        assertEquals(oldContent, result)
    }

    @Test
    fun `when not editing and no active session, returns displayContent`() {
        // Normal display mode - no session active
        val result = getEffectiveDisplayContent(
            isEditing = false,
            hasActiveSession = false,
            displayContent = oldContent,
            sessionContent = null
        )
        assertEquals(oldContent, result)
    }

    @Test
    fun `BUG SCENARIO - transitional state uses session content not stale displayContent`() {
        // THIS IS THE BUG SCENARIO:
        // User tapped out, isEditing=false, but session is still active
        // (waiting for forceRefreshAllDirectives to complete).
        // displayContent is STALE (from old directiveResults).
        // sessionContent is FRESH (from the edit session).
        //
        // Without the fix: returns oldContent (BUG - user sees stale content)
        // With the fix: returns newContent (user sees their edits)
        val result = getEffectiveDisplayContent(
            isEditing = false,
            hasActiveSession = true,
            displayContent = oldContent,  // STALE - from directiveResults
            sessionContent = newContent   // FRESH - from session
        )

        // The fix ensures we use sessionContent during transitional state
        assertEquals(
            "During transitional state (isEditing=false, session active), " +
            "should use sessionContent to avoid showing stale content",
            newContent,
            result
        )
    }

    @Test
    fun `transitional state with null sessionContent falls back to displayContent`() {
        // Edge case: session is active but sessionContent is somehow null
        val result = getEffectiveDisplayContent(
            isEditing = false,
            hasActiveSession = true,
            displayContent = oldContent,
            sessionContent = null
        )
        assertEquals(oldContent, result)
    }

    @Test
    fun `after refresh completes, session ends and displayContent is used`() {
        // After forceRefreshAllDirectives completes:
        // - directiveResults is updated with fresh content
        // - session is ended (hasActiveSession=false)
        // - displayContent now contains fresh content
        val freshDisplayContent = "Fresh content from updated directiveResults"
        val result = getEffectiveDisplayContent(
            isEditing = false,
            hasActiveSession = false,  // Session ended after refresh
            displayContent = freshDisplayContent,
            sessionContent = null
        )
        assertEquals(freshDisplayContent, result)
    }
}
