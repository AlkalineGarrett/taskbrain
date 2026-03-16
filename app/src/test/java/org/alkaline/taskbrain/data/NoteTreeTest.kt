package org.alkaline.taskbrain.data

import org.junit.Assert.*
import org.junit.Test

class NoteTreeTest {

    private fun note(
        id: String,
        content: String,
        containedNotes: List<String> = emptyList(),
        rootNoteId: String? = null,
        parentNoteId: String? = null,
    ) = Note(
        id = id,
        content = content,
        containedNotes = containedNotes,
        rootNoteId = rootNoteId,
        parentNoteId = parentNoteId,
    )

    // --- flattenTreeToLines ---

    @Test
    fun `flatten empty note - single line no children`() {
        val root = note("root", "Hello")
        val result = flattenTreeToLines(root, emptyList())

        assertEquals(1, result.size)
        assertEquals("Hello", result[0].content)
        assertEquals("root", result[0].noteId)
    }

    @Test
    fun `flatten flat note - direct children at depth 0`() {
        val root = note("root", "Parent", containedNotes = listOf("c1", "c2", "c3"))
        val descendants = listOf(
            note("c1", "Child 1", rootNoteId = "root"),
            note("c2", "Child 2", rootNoteId = "root"),
            note("c3", "Child 3", rootNoteId = "root"),
        )

        val result = flattenTreeToLines(root, descendants)

        assertEquals(4, result.size)
        assertEquals("Parent", result[0].content)
        assertEquals("Child 1", result[1].content)
        assertEquals("Child 2", result[2].content)
        assertEquals("Child 3", result[3].content)
        assertEquals("root", result[0].noteId)
        assertEquals("c1", result[1].noteId)
        assertEquals("c2", result[2].noteId)
        assertEquals("c3", result[3].noteId)
    }

    @Test
    fun `flatten nested 2 levels deep`() {
        val root = note("root", "Root", containedNotes = listOf("a"))
        val descendants = listOf(
            note("a", "A", containedNotes = listOf("b"), rootNoteId = "root"),
            note("b", "B", rootNoteId = "root"),
        )

        val result = flattenTreeToLines(root, descendants)

        assertEquals(3, result.size)
        assertEquals("Root", result[0].content)
        assertEquals("A", result[1].content)
        assertEquals("\tB", result[2].content)
    }

    @Test
    fun `flatten nested 3 levels deep`() {
        val root = note("root", "Root", containedNotes = listOf("a"))
        val descendants = listOf(
            note("a", "A", containedNotes = listOf("b"), rootNoteId = "root"),
            note("b", "B", containedNotes = listOf("c"), rootNoteId = "root"),
            note("c", "C", rootNoteId = "root"),
        )

        val result = flattenTreeToLines(root, descendants)

        assertEquals(4, result.size)
        assertEquals("Root", result[0].content)
        assertEquals("A", result[1].content)
        assertEquals("\tB", result[2].content)
        assertEquals("\t\tC", result[3].content)
    }

    @Test
    fun `flatten with spacers between root children`() {
        val root = note("root", "Root", containedNotes = listOf("a", "", "b"))
        val descendants = listOf(
            note("a", "A", rootNoteId = "root"),
            note("b", "B", rootNoteId = "root"),
        )

        val result = flattenTreeToLines(root, descendants)

        assertEquals(4, result.size)
        assertEquals("Root", result[0].content)
        assertEquals("A", result[1].content)
        assertEquals("", result[2].content) // spacer at depth 0
        assertNull(result[2].noteId)
        assertEquals("B", result[3].content)
    }

    @Test
    fun `flatten with spacers at nested levels`() {
        val root = note("root", "Root", containedNotes = listOf("a"))
        val descendants = listOf(
            note("a", "A", containedNotes = listOf("b", "", "c"), rootNoteId = "root"),
            note("b", "B", rootNoteId = "root"),
            note("c", "C", rootNoteId = "root"),
        )

        val result = flattenTreeToLines(root, descendants)

        assertEquals(5, result.size)
        assertEquals("A", result[1].content)
        assertEquals("\tB", result[2].content)
        assertEquals("\t", result[3].content) // spacer at depth 1
        assertEquals("\tC", result[4].content)
    }

    @Test
    fun `flatten with bullet prefixes in content`() {
        val root = note("root", "Shopping", containedNotes = listOf("c1", "c2"))
        val descendants = listOf(
            note("c1", "• Milk", rootNoteId = "root"),
            note("c2", "☐ Bread", rootNoteId = "root"),
        )

        val result = flattenTreeToLines(root, descendants)

        assertEquals(3, result.size)
        assertEquals("• Milk", result[1].content)
        assertEquals("☐ Bread", result[2].content)
    }

    @Test
    fun `flatten skips orphaned references`() {
        val root = note("root", "Root", containedNotes = listOf("a", "missing", "b"))
        val descendants = listOf(
            note("a", "A", rootNoteId = "root"),
            note("b", "B", rootNoteId = "root"),
        )

        val result = flattenTreeToLines(root, descendants)

        assertEquals(3, result.size)
        assertEquals("Root", result[0].content)
        assertEquals("A", result[1].content)
        assertEquals("B", result[2].content)
    }

    // --- buildTreeFromLines ---

    @Test
    fun `build from empty lines`() {
        val result = buildTreeFromLines("root", emptyList())

        assertEquals("", result.rootContent)
        assertTrue(result.rootContainedNoteIds.isEmpty())
        assertTrue(result.nodes.isEmpty())
    }

    @Test
    fun `build single line - root only`() {
        val lines = listOf(NoteLine("Hello", "root"))
        val result = buildTreeFromLines("root", lines)

        assertEquals("Hello", result.rootContent)
        assertTrue(result.rootContainedNoteIds.isEmpty())
        assertTrue(result.nodes.isEmpty())
    }

    @Test
    fun `build flat children at depth 0`() {
        val lines = listOf(
            NoteLine("Parent", "root"),
            NoteLine("Child 1", "c1"),
            NoteLine("Child 2", "c2"),
        )
        val result = buildTreeFromLines("root", lines)

        assertEquals("Parent", result.rootContent)
        assertEquals(listOf("c1", "c2"), result.rootContainedNoteIds)
        assertEquals(2, result.nodes.size)
        assertEquals("Child 1", result.nodes[0].content)
        assertEquals("root", result.nodes[0].parentNoteId)
        assertEquals("Child 2", result.nodes[1].content)
        assertEquals("root", result.nodes[1].parentNoteId)
    }

    @Test
    fun `build nested children`() {
        val lines = listOf(
            NoteLine("Root", "root"),
            NoteLine("A", "a"),
            NoteLine("\tB", "b"),
        )
        val result = buildTreeFromLines("root", lines)

        assertEquals("Root", result.rootContent)
        assertEquals(listOf("a"), result.rootContainedNoteIds)
        assertEquals(2, result.nodes.size)

        val nodeA = result.nodes[0]
        assertEquals("A", nodeA.content)
        assertEquals("root", nodeA.parentNoteId)
        assertEquals(listOf("b"), nodeA.containedNoteIds)

        val nodeB = result.nodes[1]
        assertEquals("B", nodeB.content)
        assertEquals("a", nodeB.parentNoteId)
        assertTrue(nodeB.containedNoteIds.isEmpty())
    }

    @Test
    fun `build 3 levels deep`() {
        val lines = listOf(
            NoteLine("Root", "root"),
            NoteLine("A", "a"),
            NoteLine("\tB", "b"),
            NoteLine("\t\tC", "c"),
        )
        val result = buildTreeFromLines("root", lines)

        assertEquals(listOf("a"), result.rootContainedNoteIds)
        assertEquals("root", result.nodes[0].parentNoteId) // A's parent is root
        assertEquals(listOf("b"), result.nodes[0].containedNoteIds)
        assertEquals("a", result.nodes[1].parentNoteId) // B's parent is A
        assertEquals(listOf("c"), result.nodes[1].containedNoteIds)
        assertEquals("b", result.nodes[2].parentNoteId) // C's parent is B
        assertTrue(result.nodes[2].containedNoteIds.isEmpty())
    }

    @Test
    fun `build with spacers between siblings at depth 0`() {
        val lines = listOf(
            NoteLine("Root", "root"),
            NoteLine("A", "a"),
            NoteLine("", null),
            NoteLine("B", "b"),
        )
        val result = buildTreeFromLines("root", lines)

        assertEquals(listOf("a", "", "b"), result.rootContainedNoteIds)
        assertEquals(2, result.nodes.size)
    }

    @Test
    fun `build with new lines - null noteIds`() {
        val lines = listOf(
            NoteLine("Root", "root"),
            NoteLine("New child", null),
            NoteLine("\tNew grandchild", null),
        )
        val result = buildTreeFromLines("root", lines)

        // New lines get placeholder IDs
        assertEquals(1, result.rootContainedNoteIds.size)
        assertEquals("placeholder_1", result.rootContainedNoteIds[0])

        assertEquals(2, result.nodes.size)
        assertNull(result.nodes[0].noteId)
        assertEquals("New child", result.nodes[0].content)
        assertEquals("root", result.nodes[0].parentNoteId)
        assertNull(result.nodes[1].noteId)
        assertEquals("New grandchild", result.nodes[1].content)
        assertEquals("placeholder_1", result.nodes[1].parentNoteId)
    }

    @Test
    fun `build with bullet and checkbox content`() {
        val lines = listOf(
            NoteLine("Shopping", "root"),
            NoteLine("• Milk", "c1"),
            NoteLine("☐ Bread", "c2"),
        )
        val result = buildTreeFromLines("root", lines)

        assertEquals("• Milk", result.nodes[0].content)
        assertEquals("☐ Bread", result.nodes[1].content)
    }

    @Test
    fun `build - unindent back to depth 0 after nesting`() {
        val lines = listOf(
            NoteLine("Root", "root"),
            NoteLine("A", "a"),
            NoteLine("\tB", "b"),
            NoteLine("C", "c"),
        )
        val result = buildTreeFromLines("root", lines)

        assertEquals(listOf("a", "c"), result.rootContainedNoteIds)
        assertEquals("A", result.nodes[0].content)
        assertEquals(listOf("b"), result.nodes[0].containedNoteIds)
        assertEquals("B", result.nodes[1].content)
        assertEquals("a", result.nodes[1].parentNoteId)
        assertEquals("C", result.nodes[2].content)
        assertEquals("root", result.nodes[2].parentNoteId)
    }

    // --- Round-trip tests ---

    @Test
    fun `round-trip flat note`() {
        val root = note("root", "Parent", containedNotes = listOf("c1", "c2"))
        val descendants = listOf(
            note("c1", "Child 1", rootNoteId = "root"),
            note("c2", "Child 2", rootNoteId = "root"),
        )

        val flattened = flattenTreeToLines(root, descendants)
        val rebuilt = buildTreeFromLines("root", flattened)

        assertEquals("Parent", rebuilt.rootContent)
        assertEquals(listOf("c1", "c2"), rebuilt.rootContainedNoteIds)
        assertEquals(2, rebuilt.nodes.size)
        assertEquals("Child 1", rebuilt.nodes[0].content)
        assertEquals("root", rebuilt.nodes[0].parentNoteId)
        assertEquals("Child 2", rebuilt.nodes[1].content)
        assertEquals("root", rebuilt.nodes[1].parentNoteId)
    }

    @Test
    fun `round-trip nested note`() {
        val root = note("root", "Root", containedNotes = listOf("a"))
        val descendants = listOf(
            note("a", "A", containedNotes = listOf("b"), rootNoteId = "root"),
            note("b", "B", rootNoteId = "root"),
        )

        val flattened = flattenTreeToLines(root, descendants)
        val rebuilt = buildTreeFromLines("root", flattened)

        assertEquals("Root", rebuilt.rootContent)
        assertEquals(listOf("a"), rebuilt.rootContainedNoteIds)
        assertEquals("A", rebuilt.nodes[0].content)
        assertEquals("root", rebuilt.nodes[0].parentNoteId)
        assertEquals(listOf("b"), rebuilt.nodes[0].containedNoteIds)
        assertEquals("B", rebuilt.nodes[1].content)
        assertEquals("a", rebuilt.nodes[1].parentNoteId)
    }

    @Test
    fun `round-trip with spacers`() {
        val root = note("root", "Root", containedNotes = listOf("a", "", "b"))
        val descendants = listOf(
            note("a", "A", rootNoteId = "root"),
            note("b", "B", rootNoteId = "root"),
        )

        val flattened = flattenTreeToLines(root, descendants)
        val rebuilt = buildTreeFromLines("root", flattened)

        assertEquals("Root", rebuilt.rootContent)
        assertEquals(listOf("a", "", "b"), rebuilt.rootContainedNoteIds)
    }

    @Test
    fun `round-trip complex tree`() {
        val root = note("root", "Root", containedNotes = listOf("a", "d"))
        val descendants = listOf(
            note("a", "A", containedNotes = listOf("b", "", "c"), rootNoteId = "root"),
            note("b", "B", rootNoteId = "root"),
            note("c", "C", rootNoteId = "root"),
            note("d", "D", containedNotes = listOf("e"), rootNoteId = "root"),
            note("e", "E", rootNoteId = "root"),
        )

        val flattened = flattenTreeToLines(root, descendants)

        // Verify flattened structure
        assertEquals(7, flattened.size)
        assertEquals("Root", flattened[0].content)
        assertEquals("A", flattened[1].content)
        assertEquals("\tB", flattened[2].content)
        assertEquals("\t", flattened[3].content) // spacer
        assertEquals("\tC", flattened[4].content)
        assertEquals("D", flattened[5].content)
        assertEquals("\tE", flattened[6].content)

        val rebuilt = buildTreeFromLines("root", flattened)

        assertEquals("Root", rebuilt.rootContent)
        assertEquals(listOf("a", "d"), rebuilt.rootContainedNoteIds)
        assertEquals(5, rebuilt.nodes.size)

        // A has children b, spacer, c
        val nodeA = rebuilt.nodes[0]
        assertEquals("A", nodeA.content)
        assertEquals("root", nodeA.parentNoteId)
        assertEquals(listOf("b", "", "c"), nodeA.containedNoteIds)

        // D has child e
        val nodeD = rebuilt.nodes[3]
        assertEquals("D", nodeD.content)
        assertEquals("root", nodeD.parentNoteId)
        assertEquals(listOf("e"), nodeD.containedNoteIds)
    }

    // --- isOldFormat ---

    @Test
    fun `isOldFormat - empty descendants returns false`() {
        assertFalse(isOldFormat(emptyList()))
    }

    @Test
    fun `isOldFormat - descendants with rootNoteId returns false`() {
        val descendants = listOf(
            note("a", "A", rootNoteId = "root"),
        )
        assertFalse(isOldFormat(descendants))
    }

    @Test
    fun `isOldFormat - descendants without rootNoteId returns true`() {
        val descendants = listOf(
            note("a", "A"),
            note("b", "B"),
        )
        assertTrue(isOldFormat(descendants))
    }

    @Test
    fun `isOldFormat - mixed descendants returns false`() {
        val descendants = listOf(
            note("a", "A", rootNoteId = "root"),
            note("b", "B"),
        )
        assertFalse(isOldFormat(descendants))
    }
}
