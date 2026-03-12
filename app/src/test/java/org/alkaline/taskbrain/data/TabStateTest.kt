package org.alkaline.taskbrain.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TabStateTest {

    private fun tab(noteId: String, displayText: String = noteId) =
        RecentTab(noteId = noteId, displayText = displayText)

    // --- addOrUpdate ---

    @Test
    fun `addOrUpdate adds new tab to front of empty list`() {
        val result = TabState.addOrUpdate(emptyList(), "a", "Note A")
        assertEquals(listOf(tab("a", "Note A")), result)
    }

    @Test
    fun `addOrUpdate adds new tab to front of existing list`() {
        val tabs = listOf(tab("a"), tab("b"))
        val result = TabState.addOrUpdate(tabs, "c", "Note C")
        assertEquals(listOf("c", "a", "b"), result.map { it.noteId })
    }

    @Test
    fun `addOrUpdate moves existing tab to front`() {
        val tabs = listOf(tab("a"), tab("b"), tab("c"))
        val result = TabState.addOrUpdate(tabs, "c", "Note C")
        assertEquals(listOf("c", "a", "b"), result.map { it.noteId })
    }

    @Test
    fun `addOrUpdate updates display text of existing tab`() {
        val tabs = listOf(tab("a", "Old"), tab("b"))
        val result = TabState.addOrUpdate(tabs, "a", "New")
        assertEquals("New", result.first().displayText)
    }

    @Test
    fun `addOrUpdate caps at 5 tabs when adding new`() {
        val tabs = listOf(tab("a"), tab("b"), tab("c"), tab("d"), tab("e"))
        val result = TabState.addOrUpdate(tabs, "f", "Note F")
        assertEquals(5, result.size)
        assertEquals("f", result.first().noteId)
        assertEquals(null, result.find { it.noteId == "e" })
    }

    @Test
    fun `addOrUpdate does not drop tabs when moving existing to front`() {
        val tabs = listOf(tab("a"), tab("b"), tab("c"), tab("d"), tab("e"))
        val result = TabState.addOrUpdate(tabs, "e", "Note E")
        assertEquals(5, result.size)
        assertEquals("e", result.first().noteId)
    }

    @Test
    fun `addOrUpdate preserves lastAccessedAt of existing tab`() {
        val ts = com.google.firebase.Timestamp.now()
        val tabs = listOf(RecentTab(noteId = "a", displayText = "old", lastAccessedAt = ts))
        val result = TabState.addOrUpdate(tabs, "a", "new")
        assertEquals(ts, result.first().lastAccessedAt)
    }

    @Test
    fun `addOrUpdate with tab already at front just updates text`() {
        val tabs = listOf(tab("a", "Old"), tab("b"), tab("c"))
        val result = TabState.addOrUpdate(tabs, "a", "New")
        assertEquals(listOf("a", "b", "c"), result.map { it.noteId })
        assertEquals("New", result.first().displayText)
    }

    // --- remove ---

    @Test
    fun `remove removes a tab`() {
        val tabs = listOf(tab("a"), tab("b"), tab("c"))
        val result = TabState.remove(tabs, "b")
        assertEquals(listOf("a", "c"), result.map { it.noteId })
    }

    @Test
    fun `remove with nonexistent noteId returns unchanged list`() {
        val tabs = listOf(tab("a"), tab("b"))
        val result = TabState.remove(tabs, "z")
        assertEquals(listOf("a", "b"), result.map { it.noteId })
    }

    @Test
    fun `remove from single-element list returns empty`() {
        val result = TabState.remove(listOf(tab("a")), "a")
        assertEquals(emptyList<RecentTab>(), result)
    }

    // --- updateDisplayText ---

    @Test
    fun `updateDisplayText changes text without reordering`() {
        val tabs = listOf(tab("a", "Old"), tab("b"), tab("c"))
        val result = TabState.updateDisplayText(tabs, "b", "Updated")
        assertEquals(listOf("a", "b", "c"), result.map { it.noteId })
        assertEquals("Updated", result[1].displayText)
    }

    @Test
    fun `updateDisplayText with nonexistent noteId returns unchanged`() {
        val tabs = listOf(tab("a"), tab("b"))
        val result = TabState.updateDisplayText(tabs, "z", "X")
        assertEquals(tabs, result)
    }

    // --- closeTabNavigationTarget ---

    @Test
    fun `closeTab navigates to next tab when closing current`() {
        val tabs = listOf(tab("a"), tab("b"), tab("c"))
        val result = TabState.closeTabNavigationTarget(tabs, "b", "b")
        assertEquals(CloseTabResult.SwitchTo("c"), result)
    }

    @Test
    fun `closeTab navigates to previous tab when closing last`() {
        val tabs = listOf(tab("a"), tab("b"), tab("c"))
        val result = TabState.closeTabNavigationTarget(tabs, "c", "c")
        assertEquals(CloseTabResult.SwitchTo("b"), result)
    }

    @Test
    fun `closeTab navigates to first tab when closing first`() {
        val tabs = listOf(tab("a"), tab("b"), tab("c"))
        val result = TabState.closeTabNavigationTarget(tabs, "a", "a")
        assertEquals(CloseTabResult.SwitchTo("b"), result)
    }

    @Test
    fun `closeTab navigates back when closing only tab`() {
        val tabs = listOf(tab("a"))
        val result = TabState.closeTabNavigationTarget(tabs, "a", "a")
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
