package org.alkaline.taskbrain.ui.currentnote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for the resolveNoteIds package-level function.
 * Verifies save-time deduplication of noteIds after splits, merges, and pastes.
 */
class ResolveNoteIdsTest {

    @Test
    fun `unique noteIds pass through unchanged`() {
        val result = resolveNoteIds(
            contentLines = listOf("Line A", "Line B", "Line C"),
            lineNoteIds = listOf(listOf("id1"), listOf("id2"), listOf("id3"))
        )

        assertEquals("id1", result[0].noteId)
        assertEquals("id2", result[1].noteId)
        assertEquals("id3", result[2].noteId)
    }

    @Test
    fun `lines without noteIds get null`() {
        val result = resolveNoteIds(
            contentLines = listOf("Line A", "New line", "Line C"),
            lineNoteIds = listOf(listOf("id1"), emptyList(), listOf("id3"))
        )

        assertEquals("id1", result[0].noteId)
        assertNull(result[1].noteId)
        assertEquals("id3", result[2].noteId)
    }

    @Test
    fun `duplicate noteIds resolved by longest content`() {
        // After a split: "Hello World" -> "Hello" + " World"
        // Both claim "id1", but " World" has more content (excluding prefix)
        val result = resolveNoteIds(
            contentLines = listOf("He", "Hello World"),
            lineNoteIds = listOf(listOf("id1"), listOf("id1"))
        )

        assertNull(result[0].noteId) // shorter content loses
        assertEquals("id1", result[1].noteId) // longer content wins
    }

    @Test
    fun `duplicate noteIds with prefix - content length excludes prefix`() {
        // Tab-indented line has less actual content but more total chars
        val result = resolveNoteIds(
            contentLines = listOf("\tShort", "Much longer content here"),
            lineNoteIds = listOf(listOf("id1"), listOf("id1"))
        )

        // "\tShort" has content "Short" (5 chars)
        // "Much longer content here" has content "Much longer content here" (24 chars)
        assertNull(result[0].noteId)
        assertEquals("id1", result[1].noteId)
    }

    @Test
    fun `merge noteIds - first (primary) noteId is used`() {
        // After merging lines with noteIds ["idA", "idB"], primary is "idA"
        val result = resolveNoteIds(
            contentLines = listOf("Merged content"),
            lineNoteIds = listOf(listOf("idA", "idB"))
        )

        assertEquals("idA", result[0].noteId)
    }

    @Test
    fun `merge noteIds deduplicated when same primary appears on multiple lines`() {
        // Line 0 merged from ["idA", "idB"], line 1 also claims "idA"
        // Line 0 has longer content, so it wins "idA"
        val result = resolveNoteIds(
            contentLines = listOf("Long merged content", "Short"),
            lineNoteIds = listOf(listOf("idA", "idB"), listOf("idA"))
        )

        assertEquals("idA", result[0].noteId) // longer content wins
        assertNull(result[1].noteId) // lost the claim
    }

    @Test
    fun `empty contentLines and lineNoteIds returns empty`() {
        val result = resolveNoteIds(emptyList(), emptyList())
        assertEquals(0, result.size)
    }

    @Test
    fun `lineNoteIds shorter than contentLines - extra lines get null`() {
        val result = resolveNoteIds(
            contentLines = listOf("Line A", "Line B", "Line C"),
            lineNoteIds = listOf(listOf("id1"))
        )

        assertEquals("id1", result[0].noteId)
        assertNull(result[1].noteId)
        assertNull(result[2].noteId)
    }

    @Test
    fun `content preserved in output NoteLines`() {
        val result = resolveNoteIds(
            contentLines = listOf("\t- Item 1", "Plain text"),
            lineNoteIds = listOf(listOf("id1"), listOf("id2"))
        )

        assertEquals("\t- Item 1", result[0].content)
        assertEquals("Plain text", result[1].content)
    }

    @Test
    fun `all lines with empty noteIds all get null`() {
        val result = resolveNoteIds(
            contentLines = listOf("A", "B", "C"),
            lineNoteIds = listOf(emptyList(), emptyList(), emptyList())
        )

        assertNull(result[0].noteId)
        assertNull(result[1].noteId)
        assertNull(result[2].noteId)
    }

    @Test
    fun `equal content length - one wins deterministically`() {
        // When two lines claim the same noteId with identical content length,
        // maxByOrNull picks one deterministically (first with max value)
        val result = resolveNoteIds(
            contentLines = listOf("ABCD", "EFGH"),
            lineNoteIds = listOf(listOf("id1"), listOf("id1"))
        )

        // Both have 4 chars content. Exactly one should win
        val winners = result.filter { it.noteId == "id1" }
        assertEquals(1, winners.size)
    }
}
