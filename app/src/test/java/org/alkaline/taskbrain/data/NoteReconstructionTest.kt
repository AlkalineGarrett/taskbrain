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
        val result = reconstructNoteContent(n, null)
        assertSame(n, result)
    }

    @Test
    fun `reconstructContent - with descendants`() {
        val root = note("r", "Root", containedNotes = listOf("c1"))
        val c1 = note("c1", "Child", rootNoteId = "r", parentNoteId = "r")
        val result = reconstructNoteContent(root, listOf(c1))
        assertTrue(result.content.contains("Root"))
        assertTrue(result.content.contains("Child"))
    }

    @Test
    fun `reconstructContent - null descendants returns as-is`() {
        val root = note("r", "Root", containedNotes = listOf("c1"))
        val result = reconstructNoteContent(root, null)
        assertSame(root, result)
    }

    @Test
    fun `reconstructContent - empty descendants returns as-is`() {
        val root = note("r", "Root", containedNotes = listOf("c1"))
        val result = reconstructNoteContent(root, emptyList())
        assertSame(root, result)
    }

    @Test
    fun `reconstructContent - preserves non-content fields`() {
        val root = note("r", "Root", containedNotes = listOf("c1")).copy(path = "my/path", state = "active")
        val c1 = note("c1", "Child", rootNoteId = "r", parentNoteId = "r")
        val result = reconstructNoteContent(root, listOf(c1))
        assertEquals("my/path", result.path)
        assertEquals("active", result.state)
    }

    // --- descendantIdsOf ---

    @Test
    fun `descendantIdsOf - returns empty set for note with no descendants`() {
        val rawNotes = mapOf("root" to note("root", "Root"))
        assertEquals(emptySet<String>(), descendantIdsOf("root", rawNotes))
    }

    @Test
    fun `descendantIdsOf - returns IDs of non-deleted descendants`() {
        val rawNotes = mapOf(
            "root" to note("root", "Root"),
            "c1" to note("c1", "Child 1", rootNoteId = "root"),
            "c2" to note("c2", "Child 2", rootNoteId = "root"),
        )
        assertEquals(setOf("c1", "c2"), descendantIdsOf("root", rawNotes))
    }

    @Test
    fun `descendantIdsOf - excludes deleted descendants`() {
        val rawNotes = mapOf(
            "root" to note("root", "Root"),
            "c1" to note("c1", "Alive", rootNoteId = "root"),
            "c2" to note("c2", "Deleted", rootNoteId = "root", state = "deleted"),
        )
        assertEquals(setOf("c1"), descendantIdsOf("root", rawNotes))
    }

    @Test
    fun `descendantIdsOf - does not include notes from other roots`() {
        val rawNotes = mapOf(
            "root" to note("root", "Root"),
            "c1" to note("c1", "My child", rootNoteId = "root"),
            "other" to note("other", "Other root"),
            "oc1" to note("oc1", "Other child", rootNoteId = "other"),
        )
        assertEquals(setOf("c1"), descendantIdsOf("root", rawNotes))
    }

    @Test
    fun `descendantIdsOf - does not include the root note itself`() {
        // rootNoteId on the root note itself is typically null, but verify
        val rawNotes = mapOf(
            "root" to note("root", "Root", rootNoteId = null),
            "c1" to note("c1", "Child", rootNoteId = "root"),
        )
        assertEquals(setOf("c1"), descendantIdsOf("root", rawNotes))
    }

    @Test
    fun `descendantIdsOf - includes deeply nested descendants`() {
        // All descendants share the same rootNoteId regardless of depth
        val rawNotes = mapOf(
            "root" to note("root", "Root"),
            "a" to note("a", "Level 1", rootNoteId = "root", parentNoteId = "root"),
            "b" to note("b", "Level 2", rootNoteId = "root", parentNoteId = "a"),
            "c" to note("c", "Level 3", rootNoteId = "root", parentNoteId = "b"),
        )
        assertEquals(setOf("a", "b", "c"), descendantIdsOf("root", rawNotes))
    }

    @Test
    fun `descendantIdsOf - includeDeleted returns all descendants`() {
        val rawNotes = mapOf(
            "root" to note("root", "Root"),
            "c1" to note("c1", "Alive", rootNoteId = "root"),
            "c2" to note("c2", "Deleted", rootNoteId = "root", state = "deleted"),
        )
        assertEquals(
            setOf("c1", "c2"),
            descendantIdsOf("root", rawNotes, includeDeleted = true)
        )
    }
}
