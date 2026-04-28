package org.alkaline.taskbrain.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TabStateTest {

    private fun tab(noteId: String, displayText: String = noteId) =
        RecentTab(noteId = noteId, displayText = displayText)

    private fun current(noteId: String, displayText: String = noteId) =
        TabState.CurrentTab(noteId = noteId, displayText = displayText)

    // --- computeDisplayTabs ---

    @Test
    fun `computeDisplayTabs pins currentTab in slot 0 and dedupes the shared list`() {
        val result = TabState.computeDisplayTabs(
            current("c"),
            listOf(tab("a"), tab("b"), tab("c"), tab("d")),
        )
        assertEquals(listOf("c", "a", "b", "d"), result.map { it.noteId })
    }

    @Test
    fun `computeDisplayTabs caps at MAX_DISPLAYED total`() {
        val shared = listOf(tab("a"), tab("b"), tab("c"), tab("d"), tab("e"), tab("f"))
        val result = TabState.computeDisplayTabs(current("z"), shared)
        assertEquals(MAX_DISPLAYED, result.size)
        assertEquals("z", result.first().noteId)
        assertEquals(listOf("a", "b", "c", "d"), result.drop(1).map { it.noteId })
    }

    @Test
    fun `computeDisplayTabs keeps current pinned even when not in shared`() {
        // Other devices' writes can push this device's current past the buffer.
        val result = TabState.computeDisplayTabs(
            current("orphan"),
            listOf(tab("a"), tab("b"), tab("c"), tab("d")),
        )
        assertEquals(listOf("orphan", "a", "b", "c", "d"), result.map { it.noteId })
    }

    @Test
    fun `computeDisplayTabs with null current returns shared as-is`() {
        val shared = listOf(tab("a"), tab("b"), tab("c"), tab("d"), tab("e"), tab("f"))
        val result = TabState.computeDisplayTabs(null, shared)
        assertEquals(listOf("a", "b", "c", "d", "e"), result.map { it.noteId })
    }

    @Test
    fun `computeDisplayTabs uses currentTab displayText when shared has stale text`() {
        val result = TabState.computeDisplayTabs(
            current("c", "Fresh title"),
            listOf(tab("c", "Stale title"), tab("a")),
        )
        assertEquals("Fresh title", result.first().displayText)
    }

    // --- nextNoteIdAfterRemove ---

    @Test
    fun `nextNoteIdAfterRemove returns next-most-recent`() {
        assertEquals(
            "b",
            TabState.nextNoteIdAfterRemove("a", listOf(tab("a"), tab("b"), tab("c"))),
        )
    }

    @Test
    fun `nextNoteIdAfterRemove skips the removed id even when not at front`() {
        assertEquals(
            "a",
            TabState.nextNoteIdAfterRemove("b", listOf(tab("a"), tab("b"), tab("c"))),
        )
    }

    @Test
    fun `nextNoteIdAfterRemove returns null when shared has nothing else`() {
        assertNull(TabState.nextNoteIdAfterRemove("a", listOf(tab("a"))))
    }

    @Test
    fun `nextNoteIdAfterRemove returns null when shared is empty`() {
        assertNull(TabState.nextNoteIdAfterRemove("a", emptyList()))
    }

    // --- closeTabNavigationTarget ---

    @Test
    fun `closeTab navigates to next-most-recent when closing current`() {
        val tabs = listOf(tab("a"), tab("b"), tab("c"))
        val result = TabState.closeTabNavigationTarget(tabs, "b", "b")
        assertEquals(CloseTabResult.SwitchTo("a"), result)
    }

    @Test
    fun `closeTab navigates back when closing only tab`() {
        val result = TabState.closeTabNavigationTarget(listOf(tab("a")), "a", "a")
        assertTrue(result is CloseTabResult.NavigateBack)
    }

    @Test
    fun `closeTab stays on current when closing non-current tab`() {
        val tabs = listOf(tab("a"), tab("b"))
        val result = TabState.closeTabNavigationTarget(tabs, "b", "a")
        assertTrue(result is CloseTabResult.StayOnCurrent)
    }

    // --- extractDisplayText ---

    @Test
    fun `extractDisplayText uses first line`() {
        assertEquals("Hello", TabState.extractDisplayText("Hello\nWorld"))
    }

    @Test
    fun `extractDisplayText removes alarm symbol`() {
        assertEquals("Task", TabState.extractDisplayText("⏰ Task"))
    }

    @Test
    fun `extractDisplayText trims whitespace`() {
        assertEquals("Hello", TabState.extractDisplayText("  Hello  "))
    }

    @Test
    fun `extractDisplayText truncates long text`() {
        val long = "A".repeat(20)
        val result = TabState.extractDisplayText(long)
        assertEquals(12, result.length)
        assert(result.endsWith("…"))
    }

    @Test
    fun `extractDisplayText returns New Note for empty content`() {
        assertEquals("New Note", TabState.extractDisplayText(""))
    }

    @Test
    fun `extractDisplayText returns New Note for whitespace-only content`() {
        assertEquals("New Note", TabState.extractDisplayText("   "))
    }

    @Test
    fun `extractDisplayText returns New Note for alarm-symbol-only content`() {
        assertEquals("New Note", TabState.extractDisplayText("⏰"))
    }
}
