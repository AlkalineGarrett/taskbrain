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
        assertEquals(1, result.notes.size)
        assertEquals("Alpha", result.notes[0].content)
        assertTrue(result.notesNeedingFix.isEmpty())
    }

    @Test
    fun `rebuildAll - root with descendants reconstructs content`() {
        val root = note("r", "Root", containedNotes = listOf("c1", "c2"))
        val c1 = note("c1", "Child 1", rootNoteId = "r", parentNoteId = "r")
        val c2 = note("c2", "Child 2", rootNoteId = "r", parentNoteId = "r")
        val rawNotes = mapOf("r" to root, "c1" to c1, "c2" to c2)

        val result = rebuildAllNotes(rawNotes)
        assertEquals(1, result.notes.size)
        assertEquals("Root\nChild 1\nChild 2", result.notes[0].content)
        assertTrue(result.notesNeedingFix.isEmpty())
    }

    @Test
    fun `rebuildAll - multiple roots`() {
        val rawNotes = mapOf(
            "a" to note("a", "Alpha"),
            "b" to note("b", "Beta"),
        )
        val result = rebuildAllNotes(rawNotes)
        assertEquals(2, result.notes.size)
        val ids = result.notes.map { it.id }.toSet()
        assertTrue(ids.contains("a"))
        assertTrue(ids.contains("b"))
    }

    @Test
    fun `rebuildAll - orphaned child excluded from top-level`() {
        val rawNotes = mapOf(
            "c1" to note("c1", "Orphan", rootNoteId = "missing", parentNoteId = "missing"),
        )
        val result = rebuildAllNotes(rawNotes)
        assertEquals(0, result.notes.size)
    }

    @Test
    fun `rebuildAll - deleted root still returned`() {
        val rawNotes = mapOf(
            "a" to note("a", "Active"),
            "b" to note("b", "Deleted", state = "deleted"),
        )
        val result = rebuildAllNotes(rawNotes)
        assertEquals(2, result.notes.size)
    }

    @Test
    fun `rebuildAll - empty map`() {
        val result = rebuildAllNotes(emptyMap())
        assertTrue(result.notes.isEmpty())
        assertTrue(result.notesNeedingFix.isEmpty())
    }

    @Test
    fun `rebuildAll - orphan refs in containedNotes are dropped and mark needsFix`() {
        // Parent refers to c1, c2, c3, but only c2 exists as a real child
        val root = note("r", "Root", containedNotes = listOf("c1", "c2", "c3"))
        val c2 = note("c2", "Only child", rootNoteId = "r", parentNoteId = "r")
        val rawNotes = mapOf("r" to root, "c2" to c2)

        val result = rebuildAllNotes(rawNotes)
        assertEquals(1, result.notes.size)
        assertEquals("Root\nOnly child", result.notes[0].content)
        assertEquals(setOf("r"), result.notesNeedingFix)
    }

    @Test
    fun `rebuildAll - stray child linked via parentNoteId appended at end and marks needsFix`() {
        // Parent doesn't know about c2, but c2 has parentNoteId = root
        val root = note("r", "Root", containedNotes = listOf("c1"))
        val c1 = note("c1", "Known", rootNoteId = "r", parentNoteId = "r")
        val c2 = note("c2", "Stray", rootNoteId = "r", parentNoteId = "r")
        val rawNotes = mapOf("r" to root, "c1" to c1, "c2" to c2)

        val result = rebuildAllNotes(rawNotes)
        assertEquals("Root\nKnown\nStray", result.notes[0].content)
        assertEquals(setOf("r"), result.notesNeedingFix)
    }

    @Test
    fun `rebuildAll - all declared children missing but strays present reconstructs from strays`() {
        // This is the user's scenario: containedNotes all orphaned, but parentNoteId links exist
        val root = note("r", "Alarms (dupe)", containedNotes = listOf("orphan1", "orphan2"))
        val real1 = note("real1", "Real one", parentNoteId = "r")
        val real2 = note("real2", "Real two", parentNoteId = "r")
        val rawNotes = mapOf("r" to root, "real1" to real1, "real2" to real2)

        val result = rebuildAllNotes(rawNotes)
        assertEquals("Alarms (dupe)\nReal one\nReal two", result.notes[0].content)
        assertEquals(setOf("r"), result.notesNeedingFix)
    }

    @Test
    fun `rebuildAll - deleted child dropped and marks needsFix`() {
        val root = note("r", "Root", containedNotes = listOf("c1", "c2"))
        val c1 = note("c1", "Live", parentNoteId = "r")
        val c2 = note("c2", "Deleted", parentNoteId = "r", state = "deleted")
        val rawNotes = mapOf("r" to root, "c1" to c1, "c2" to c2)

        val result = rebuildAllNotes(rawNotes)
        assertEquals("Root\nLive", result.notes[0].content)
        assertEquals(setOf("r"), result.notesNeedingFix)
    }

    @Test
    fun `rebuildAll - child with mismatched parentNoteId dropped from ref list`() {
        // Parent claims c1, but c1's parentNoteId points elsewhere
        val root = note("r", "Root", containedNotes = listOf("c1"))
        val c1 = note("c1", "Not mine", parentNoteId = "someone-else")
        val rawNotes = mapOf("r" to root, "c1" to c1)

        val result = rebuildAllNotes(rawNotes)
        assertEquals("Root", result.notes[0].content)
        assertEquals(setOf("r"), result.notesNeedingFix)
    }

    @Test
    fun `rebuildAll - nested strays render at correct depth`() {
        val root = note("r", "Root", containedNotes = listOf("c1"))
        val c1 = note("c1", "Known", containedNotes = listOf(), parentNoteId = "r")
        val grandchild = note("gc1", "Stray gc", parentNoteId = "c1")
        val rawNotes = mapOf("r" to root, "c1" to c1, "gc1" to grandchild)

        val result = rebuildAllNotes(rawNotes)
        assertEquals("Root\nKnown\n\tStray gc", result.notes[0].content)
        assertEquals(setOf("r"), result.notesNeedingFix)
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
        assertEquals(2, result.notes.size)
        assertEquals("New", result.notes.find { it.id == "a" }!!.content)
        assertEquals("Beta", result.notes.find { it.id == "b" }!!.content)
    }

    @Test
    fun `rebuildAffected - root deleted`() {
        val existing = listOf(note("a", "Alpha"), note("b", "Beta"))
        val rawNotes = mapOf("b" to note("b", "Beta"))
        val result = rebuildAffectedNotes(existing, setOf("a"), rawNotes)
        assertEquals(1, result.notes.size)
        assertEquals("b", result.notes[0].id)
    }

    @Test
    fun `rebuildAffected - new root added`() {
        val existing = listOf(note("a", "Alpha"))
        val rawNotes = mapOf(
            "a" to note("a", "Alpha"),
            "b" to note("b", "Beta"),
        )
        val result = rebuildAffectedNotes(existing, setOf("b"), rawNotes)
        assertEquals(2, result.notes.size)
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
        assertSame(noteB, result.notes.find { it.id == "b" })
    }

    @Test
    fun `rebuildAffected - no changes returns same list reference`() {
        val existing = listOf(note("a", "Alpha"))
        val rawNotes = mapOf("a" to note("a", "Alpha"))
        val result = rebuildAffectedNotes(existing, setOf("nonexistent"), rawNotes)
        assertSame(existing, result.notes)
    }

    @Test
    fun `rebuildAffected - note becomes child removed from top-level`() {
        val existing = listOf(note("a", "Alpha"), note("b", "Beta"))
        val rawNotes = mapOf(
            "a" to note("a", "Alpha"),
            "b" to note("b", "Beta", parentNoteId = "a"),
        )
        val result = rebuildAffectedNotes(existing, setOf("b"), rawNotes)
        assertEquals(1, result.notes.size)
        assertEquals("a", result.notes[0].id)
    }

    @Test
    fun `rebuildAffected - fix applied marks notesNeedingFix`() {
        val existing = listOf(note("r", "Old"))
        val root = note("r", "New", containedNotes = listOf("orphan"))
        val rawNotes = mapOf("r" to root)
        val result = rebuildAffectedNotes(existing, setOf("r"), rawNotes)
        assertEquals("New", result.notes.find { it.id == "r" }!!.content)
        assertEquals(setOf("r"), result.notesNeedingFix)
    }

    // --- reconstructNoteContent ---

    private fun childrenByParent(rawNotes: Map<String, Note>): Map<String, List<Note>> {
        val result = mutableMapOf<String, MutableList<Note>>()
        for (n in rawNotes.values) {
            val p = n.parentNoteId ?: continue
            if (n.state == "deleted") continue
            result.getOrPut(p) { mutableListOf() }.add(n)
        }
        return result
    }

    @Test
    fun `reconstructContent - no children or refs returns same instance`() {
        val n = note("a", "Simple note")
        val raw = mapOf("a" to n)
        val (result, fixed) = reconstructNoteContent(n, raw, childrenByParent(raw))
        assertSame(n, result)
        assertFalse(fixed)
    }

    @Test
    fun `reconstructContent - declared children in order`() {
        val root = note("r", "Root", containedNotes = listOf("c1", "c2"))
        val c1 = note("c1", "First", parentNoteId = "r")
        val c2 = note("c2", "Second", parentNoteId = "r")
        val raw = mapOf("r" to root, "c1" to c1, "c2" to c2)
        val (result, fixed) = reconstructNoteContent(root, raw, childrenByParent(raw))
        assertEquals("Root\nFirst\nSecond", result.content)
        assertFalse(fixed)
    }

    @Test
    fun `reconstructContent - spacer in containedNotes renders as blank line`() {
        val root = note("r", "Root", containedNotes = listOf("c1", "", "c2"))
        val c1 = note("c1", "A", parentNoteId = "r")
        val c2 = note("c2", "B", parentNoteId = "r")
        val raw = mapOf("r" to root, "c1" to c1, "c2" to c2)
        val (result, fixed) = reconstructNoteContent(root, raw, childrenByParent(raw))
        assertEquals("Root\nA\n\nB", result.content)
        assertFalse(fixed)
    }

    @Test
    fun `reconstructContent - orphan ref dropped sets fixed true`() {
        val root = note("r", "Root", containedNotes = listOf("missing"))
        val raw = mapOf("r" to root)
        val (_, fixed) = reconstructNoteContent(root, raw, childrenByParent(raw))
        assertTrue(fixed)
    }

    @Test
    fun `reconstructContent - stray child appended sets fixed true`() {
        val root = note("r", "Root", containedNotes = emptyList())
        val stray = note("s", "Stray", parentNoteId = "r")
        val raw = mapOf("r" to root, "s" to stray)
        val (result, fixed) = reconstructNoteContent(root, raw, childrenByParent(raw))
        assertEquals("Root\nStray", result.content)
        assertTrue(fixed)
    }

    @Test
    fun `reconstructContent - preserves non-content fields`() {
        val root = note("r", "Root", containedNotes = listOf("c1"))
            .copy(path = "my/path", state = "active")
        val c1 = note("c1", "Child", parentNoteId = "r")
        val raw = mapOf("r" to root, "c1" to c1)
        val (result, _) = reconstructNoteContent(root, raw, childrenByParent(raw))
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
        val rawNotes = mapOf(
            "root" to note("root", "Root", rootNoteId = null),
            "c1" to note("c1", "Child", rootNoteId = "root"),
        )
        assertEquals(setOf("c1"), descendantIdsOf("root", rawNotes))
    }

    @Test
    fun `descendantIdsOf - includes deeply nested descendants`() {
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
