package org.alkaline.taskbrain.data

import org.junit.Assert.*
import org.junit.Test

class NoteReconstructionTest {

    private fun note(
        id: String,
        content: String = "",
        containedNotes: List<String> = emptyList(),
        rootNoteId: String? = null,
        parentNoteId: String? = null,
        state: String? = null,
    ) = Note(
        id = id,
        content = content,
        containedNotes = containedNotes,
        rootNoteId = rootNoteId,
        parentNoteId = parentNoteId,
        state = state,
    )

    // --- rebuildAllNotes ---

    @Test
    fun `rebuildAll - single root with no children`() {
        val rawNotes = mapOf("a" to note("a", "Alpha"))
        val result = rebuildAllNotes(rawNotes)
        assertEquals(1, result.size)
        assertEquals("Alpha", result[0].content)
    }

    @Test
    fun `rebuildAll - root with descendants reconstructs content`() {
        val root = note("r", "Root", containedNotes = listOf("c1", "c2"), rootNoteId = null)
        val c1 = note("c1", "Child 1", rootNoteId = "r", parentNoteId = "r")
        val c2 = note("c2", "Child 2", rootNoteId = "r", parentNoteId = "r")
        val rawNotes = mapOf("r" to root, "c1" to c1, "c2" to c2)

        val result = rebuildAllNotes(rawNotes)
        assertEquals(1, result.size)
        assertTrue(result[0].content.contains("Child 1"))
        assertTrue(result[0].content.contains("Child 2"))
    }

    @Test
    fun `rebuildAll - multiple roots`() {
        val rawNotes = mapOf(
            "a" to note("a", "Alpha"),
            "b" to note("b", "Beta"),
        )
        val result = rebuildAllNotes(rawNotes)
        assertEquals(2, result.size)
        val ids = result.map { it.id }.toSet()
        assertTrue(ids.contains("a"))
        assertTrue(ids.contains("b"))
    }

    @Test
    fun `rebuildAll - orphaned child excluded from top-level`() {
        // Child points to root that doesn't exist
        val rawNotes = mapOf(
            "c1" to note("c1", "Orphan", rootNoteId = "missing", parentNoteId = "missing"),
        )
        val result = rebuildAllNotes(rawNotes)
        assertEquals(0, result.size)
    }

    @Test
    fun `rebuildAll - deleted notes included`() {
        val rawNotes = mapOf(
            "a" to note("a", "Active"),
            "b" to note("b", "Deleted", state = "deleted"),
        )
        val result = rebuildAllNotes(rawNotes)
        assertEquals(2, result.size)
    }

    @Test
    fun `rebuildAll - empty map`() {
        val result = rebuildAllNotes(emptyMap())
        assertTrue(result.isEmpty())
    }

    // --- rebuildAffectedNotes ---

    @Test
    fun `rebuildAffected - single root changed`() {
        val existing = listOf(note("a", "Old"), note("b", "Beta"))
        val rawNotes = mapOf(
            "a" to note("a", "New"),
            "b" to note("b", "Beta"),
        )
        val result = rebuildAffectedNotes(existing, setOf("a"), rawNotes)
        assertEquals(2, result.size)
        assertEquals("New", result.find { it.id == "a" }!!.content)
        assertEquals("Beta", result.find { it.id == "b" }!!.content)
    }

    @Test
    fun `rebuildAffected - root deleted`() {
        val existing = listOf(note("a", "Alpha"), note("b", "Beta"))
        val rawNotes = mapOf("b" to note("b", "Beta")) // "a" removed from rawNotes
        val result = rebuildAffectedNotes(existing, setOf("a"), rawNotes)
        assertEquals(1, result.size)
        assertEquals("b", result[0].id)
    }

    @Test
    fun `rebuildAffected - new root added`() {
        val existing = listOf(note("a", "Alpha"))
        val rawNotes = mapOf(
            "a" to note("a", "Alpha"),
            "b" to note("b", "Beta"),
        )
        val result = rebuildAffectedNotes(existing, setOf("b"), rawNotes)
        assertEquals(2, result.size)
    }

    @Test
    fun `rebuildAffected - unaffected preserved by reference`() {
        val noteA = note("a", "Alpha")
        val noteB = note("b", "Beta")
        val existing = listOf(noteA, noteB)
        val rawNotes = mapOf(
            "a" to note("a", "Changed"),
            "b" to noteB,
        )
        val result = rebuildAffectedNotes(existing, setOf("a"), rawNotes)
        // noteB should be the same object reference
        assertSame(noteB, result.find { it.id == "b" })
    }

    @Test
    fun `rebuildAffected - no changes returns same list reference`() {
        val existing = listOf(note("a", "Alpha"))
        val rawNotes = mapOf("a" to note("a", "Alpha"))
        // Affect a root that doesn't exist — nothing changes
        val result = rebuildAffectedNotes(existing, setOf("nonexistent"), rawNotes)
        assertSame(existing, result)
    }

    @Test
    fun `rebuildAffected - note becomes child removed from top-level`() {
        val existing = listOf(note("a", "Alpha"), note("b", "Beta"))
        // "b" now has a parentNoteId (no longer top-level)
        val rawNotes = mapOf(
            "a" to note("a", "Alpha"),
            "b" to note("b", "Beta", parentNoteId = "a"),
        )
        val result = rebuildAffectedNotes(existing, setOf("b"), rawNotes)
        assertEquals(1, result.size)
        assertEquals("a", result[0].id)
    }

    // --- reconstructNoteContent ---

    @Test
    fun `reconstructContent - no containedNotes returns as-is`() {
        val n = note("a", "Simple note")
        val result = reconstructNoteContent(n, null, emptyMap())
        assertSame(n, result)
    }

    @Test
    fun `reconstructContent - new format with descendants`() {
        val root = note("r", "Root", containedNotes = listOf("c1"))
        val c1 = note("c1", "Child", rootNoteId = "r", parentNoteId = "r")
        val result = reconstructNoteContent(root, listOf(c1), mapOf("r" to root, "c1" to c1))
        assertTrue(result.content.contains("Root"))
        assertTrue(result.content.contains("Child"))
    }

    @Test
    fun `reconstructContent - old format with containedNotes lookup`() {
        val root = note("r", "Root", containedNotes = listOf("c1", "c2"))
        val rawNotes = mapOf(
            "r" to root,
            "c1" to note("c1", "Child 1"),
            "c2" to note("c2", "Child 2"),
        )
        // No descendants passed (old format) — looks up from rawNotes
        val result = reconstructNoteContent(root, null, rawNotes)
        assertEquals("Root\nChild 1\nChild 2", result.content)
    }

    @Test
    fun `reconstructContent - old format with missing child`() {
        val root = note("r", "Root", containedNotes = listOf("c1", "missing"))
        val rawNotes = mapOf(
            "r" to root,
            "c1" to note("c1", "Child 1"),
        )
        val result = reconstructNoteContent(root, null, rawNotes)
        assertEquals("Root\nChild 1\n", result.content)
    }

    @Test
    fun `reconstructContent - old format with spacers`() {
        val root = note("r", "Root", containedNotes = listOf("c1", "", "c2"))
        val rawNotes = mapOf(
            "r" to root,
            "c1" to note("c1", "Child 1"),
            "c2" to note("c2", "Child 2"),
        )
        val result = reconstructNoteContent(root, null, rawNotes)
        assertEquals("Root\nChild 1\n\nChild 2", result.content)
    }

    @Test
    fun `reconstructContent - empty descendants falls back to old format`() {
        val root = note("r", "Root", containedNotes = listOf("c1"))
        val rawNotes = mapOf(
            "r" to root,
            "c1" to note("c1", "Child"),
        )
        // Empty list (not null) also falls back to old format
        val result = reconstructNoteContent(root, emptyList(), rawNotes)
        assertEquals("Root\nChild", result.content)
    }

    @Test
    fun `reconstructContent - preserves non-content fields`() {
        val root = note("r", "Root", containedNotes = listOf("c1")).copy(path = "my/path", state = "active")
        val rawNotes = mapOf(
            "r" to root,
            "c1" to note("c1", "Child"),
        )
        val result = reconstructNoteContent(root, null, rawNotes)
        assertEquals("my/path", result.path)
        assertEquals("active", result.state)
    }
}
