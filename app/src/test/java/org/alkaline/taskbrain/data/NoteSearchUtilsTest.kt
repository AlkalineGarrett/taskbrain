package org.alkaline.taskbrain.data

import com.google.firebase.Timestamp
import org.junit.Assert.*
import org.junit.Test
import java.util.Date

class NoteSearchUtilsTest {

    private fun note(
        id: String,
        content: String = "",
        state: String? = null,
        updatedAt: Timestamp? = Timestamp(Date()),
    ) = Note(id = id, content = content, state = state, updatedAt = updatedAt)

    // ── searchNotes: basic matching ──

    @Test
    fun `searchNotes returns empty for empty query`() {
        val notes = listOf(note("1", content = "Hello"))
        val (active, deleted) = NoteSearchUtils.searchNotes(notes, "", true, true)
        assertTrue(active.isEmpty())
        assertTrue(deleted.isEmpty())
    }

    @Test
    fun `searchNotes returns empty when both criteria disabled`() {
        val notes = listOf(note("1", content = "Hello"))
        val (active, deleted) = NoteSearchUtils.searchNotes(notes, "Hello", false, false)
        assertTrue(active.isEmpty())
        assertTrue(deleted.isEmpty())
    }

    @Test
    fun `searchNotes matches name only when searchByName is true`() {
        val notes = listOf(note("1", content = "Hello\nWorld"))
        val (active, _) = NoteSearchUtils.searchNotes(notes, "Hello", true, false)
        assertEquals(1, active.size)
        assertEquals("1", active[0].note.id)
        assertTrue(active[0].nameMatches.isNotEmpty())
        assertTrue(active[0].contentSnippets.isEmpty())
    }

    @Test
    fun `searchNotes matches content only when searchByContent is true`() {
        val notes = listOf(note("1", content = "Hello\nWorld"))
        val (active, _) = NoteSearchUtils.searchNotes(notes, "World", false, true)
        assertEquals(1, active.size)
        assertTrue(active[0].nameMatches.isEmpty())
        assertTrue(active[0].contentSnippets.isNotEmpty())
    }

    @Test
    fun `searchNotes does not match first line as content`() {
        val notes = listOf(note("1", content = "Hello\nWorld"))
        val (active, _) = NoteSearchUtils.searchNotes(notes, "Hello", false, true)
        assertTrue(active.isEmpty())
    }

    @Test
    fun `searchNotes skips child notes`() {
        val notes = listOf(
            Note(id = "child", content = "Match this", parentNoteId = "parent"),
        )
        val (active, _) = NoteSearchUtils.searchNotes(notes, "Match", true, true)
        assertTrue(active.isEmpty())
    }

    // ── searchNotes: case insensitivity ──

    @Test
    fun `searchNotes is case insensitive`() {
        val notes = listOf(note("1", content = "Hello World"))
        val (active, _) = NoteSearchUtils.searchNotes(notes, "hello", true, false)
        assertEquals(1, active.size)
    }

    @Test
    fun `searchNotes matches mixed case query against mixed case text`() {
        val notes = listOf(note("1", content = "TaskBrain App"))
        val (active, _) = NoteSearchUtils.searchNotes(notes, "taskbrain", true, false)
        assertEquals(1, active.size)
        val match = active[0].nameMatches[0]
        assertEquals(0, match.matchStart)
        assertEquals(9, match.matchEnd)
    }

    // ── searchNotes: deleted notes separation ──

    @Test
    fun `searchNotes separates active and deleted results`() {
        val notes = listOf(
            note("active", content = "Find me"),
            note("deleted", content = "Find me", state = "deleted"),
        )
        val (active, deleted) = NoteSearchUtils.searchNotes(notes, "Find", true, false)
        assertEquals(1, active.size)
        assertEquals("active", active[0].note.id)
        assertEquals(1, deleted.size)
        assertEquals("deleted", deleted[0].note.id)
    }

    // ── searchNotes: ordering ──

    @Test
    fun `active results sorted by updatedAt descending`() {
        val now = Date()
        val notes = listOf(
            note("old", content = "Find", updatedAt = Timestamp(Date(now.time - 10000))),
            note("new", content = "Find", updatedAt = Timestamp(now)),
        )
        val (active, _) = NoteSearchUtils.searchNotes(notes, "Find", true, false)
        assertEquals(listOf("new", "old"), active.map { it.note.id })
    }

    @Test
    fun `deleted results sorted by updatedAt descending`() {
        val now = Date()
        val notes = listOf(
            note("old", content = "Find", state = "deleted", updatedAt = Timestamp(Date(now.time - 10000))),
            note("new", content = "Find", state = "deleted", updatedAt = Timestamp(now)),
        )
        val (_, deleted) = NoteSearchUtils.searchNotes(notes, "Find", true, false)
        assertEquals(listOf("new", "old"), deleted.map { it.note.id })
    }

    // ── Name match positions ──

    @Test
    fun `name match positions are correct`() {
        val notes = listOf(note("1", content = "Hello World"))
        val (active, _) = NoteSearchUtils.searchNotes(notes, "World", true, false)
        val match = active[0].nameMatches[0]
        assertEquals(0, match.lineIndex)
        assertEquals(6, match.matchStart)
        assertEquals(11, match.matchEnd)
    }

    @Test
    fun `multiple name matches in same line`() {
        val notes = listOf(note("1", content = "aba aba"))
        val (active, _) = NoteSearchUtils.searchNotes(notes, "aba", true, false)
        assertEquals(2, active[0].nameMatches.size)
        assertEquals(0, active[0].nameMatches[0].matchStart)
        assertEquals(4, active[0].nameMatches[1].matchStart)
    }

    // ── Content snippets ──

    @Test
    fun `content snippet includes 2 lines of context above and below`() {
        val lines = (0..10).map { "Line $it" }
        val content = lines.joinToString("\n")
        val notes = listOf(note("1", content = content))
        // "Line 5" is at index 5 in lines; content search starts at index 1
        val (active, _) = NoteSearchUtils.searchNotes(notes, "Line 5", false, true)
        assertEquals(1, active.size)
        val snippet = active[0].contentSnippets[0]
        // Context: lines 3,4,5,6,7
        assertEquals(5, snippet.lines.size)
        assertEquals(3, snippet.lines[0].lineIndex)
        assertEquals(7, snippet.lines[4].lineIndex)
    }

    @Test
    fun `content snippet clamps context at start of content`() {
        val notes = listOf(note("1", content = "Title\nLine 1\nLine 2\nLine 3"))
        // "Line 1" is at index 1; context should be lines 1,2,3 (can't go before 1)
        val (active, _) = NoteSearchUtils.searchNotes(notes, "Line 1", false, true)
        val snippet = active[0].contentSnippets[0]
        assertEquals(1, snippet.lines[0].lineIndex)
    }

    @Test
    fun `content snippet clamps context at end of content`() {
        val notes = listOf(note("1", content = "Title\nLine 1\nLine 2\nLast"))
        // "Last" is at index 3; context should be lines 1,2,3
        val (active, _) = NoteSearchUtils.searchNotes(notes, "Last", false, true)
        val snippet = active[0].contentSnippets[0]
        assertEquals(3, snippet.lines.last().lineIndex)
    }

    @Test
    fun `max 3 content matches per note`() {
        val lines = listOf("Title") + (1..10).map { "match" }
        val notes = listOf(note("1", content = lines.joinToString("\n")))
        val (active, _) = NoteSearchUtils.searchNotes(notes, "match", false, true)
        // Should have at most 3 matches across all snippets
        val totalMatches = active[0].contentSnippets.sumOf { it.matches.size }
        assertTrue(totalMatches <= 3)
    }

    @Test
    fun `overlapping context ranges are merged into single snippet`() {
        // Lines 1 and 2 both match; their contexts overlap
        val notes = listOf(note("1", content = "Title\nmatch1\nmatch2\nLine 3\nLine 4"))
        val (active, _) = NoteSearchUtils.searchNotes(notes, "match", false, true)
        // Both matches should be in a single merged snippet
        assertEquals(1, active[0].contentSnippets.size)
        assertEquals(2, active[0].contentSnippets[0].matches.size)
    }

    @Test
    fun `distant content matches produce separate snippets`() {
        val lines = mutableListOf("Title")
        lines.add("match_first")  // index 1
        repeat(10) { lines.add("filler $it") }  // indices 2-11
        lines.add("match_second")  // index 12
        val notes = listOf(note("1", content = lines.joinToString("\n")))
        val (active, _) = NoteSearchUtils.searchNotes(notes, "match_", false, true)
        assertEquals(2, active[0].contentSnippets.size)
    }

    // ── Edge cases ──

    @Test
    fun `searchNotes with no matching notes returns empty`() {
        val notes = listOf(note("1", content = "Hello"))
        val (active, deleted) = NoteSearchUtils.searchNotes(notes, "xyz", true, true)
        assertTrue(active.isEmpty())
        assertTrue(deleted.isEmpty())
    }

    @Test
    fun `searchNotes with empty notes list returns empty`() {
        val (active, deleted) = NoteSearchUtils.searchNotes(emptyList(), "test", true, true)
        assertTrue(active.isEmpty())
        assertTrue(deleted.isEmpty())
    }

    @Test
    fun `searchNotes with single-line note has no content matches`() {
        val notes = listOf(note("1", content = "Only title"))
        val (active, _) = NoteSearchUtils.searchNotes(notes, "Only", false, true)
        assertTrue(active.isEmpty())
    }

    @Test
    fun `searchNotes matches both name and content in same note`() {
        val notes = listOf(note("1", content = "Find me\nAlso find me here"))
        val (active, _) = NoteSearchUtils.searchNotes(notes, "find", true, true)
        assertEquals(1, active.size)
        assertTrue(active[0].nameMatches.isNotEmpty())
        assertTrue(active[0].contentSnippets.isNotEmpty())
    }
}
