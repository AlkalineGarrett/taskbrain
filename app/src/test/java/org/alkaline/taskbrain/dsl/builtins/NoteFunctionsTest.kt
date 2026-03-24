package org.alkaline.taskbrain.dsl.builtins

import com.google.firebase.Timestamp
import org.alkaline.taskbrain.data.Note
import org.alkaline.taskbrain.dsl.language.Lexer
import org.alkaline.taskbrain.dsl.language.Parser
import org.alkaline.taskbrain.dsl.runtime.DslValue
import org.alkaline.taskbrain.dsl.runtime.Environment
import org.alkaline.taskbrain.dsl.runtime.ExecutionException
import org.alkaline.taskbrain.dsl.runtime.Executor
import org.alkaline.taskbrain.dsl.runtime.ListVal
import org.alkaline.taskbrain.dsl.runtime.NoteVal
import org.alkaline.taskbrain.dsl.runtime.ViewVal
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Date

/**
 * Tests for NoteFunctions (find, view).
 *
 * Milestone 5: find function.
 * Milestone 10: view function.
 */
class NoteFunctionsTest {

    private lateinit var executor: Executor

    @Before
    fun setUp() {
        executor = Executor()
    }

    private fun execute(source: String, notes: List<Note>? = null): DslValue {
        val tokens = Lexer(source).tokenize()
        val directive = Parser(tokens, source).parseDirective()
        val env = if (notes != null) Environment.withNotes(notes) else Environment()
        return executor.execute(directive, env)
    }

    // region Sample notes for testing

    private val testNotes = listOf(
        Note(
            id = "note1",
            userId = "user1",
            path = "2026-01-15",
            content = "Journal entry for Jan 15",
            createdAt = Timestamp(Date())
        ),
        Note(
            id = "note2",
            userId = "user1",
            path = "2026-01-16",
            content = "Journal entry for Jan 16",
            createdAt = Timestamp(Date())
        ),
        Note(
            id = "note3",
            userId = "user1",
            path = "journal/2026-01-17",
            content = "Journal entry for Jan 17",
            createdAt = Timestamp(Date())
        ),
        Note(
            id = "note4",
            userId = "user1",
            path = "tasks/inbox",
            content = "Task inbox",
            createdAt = Timestamp(Date())
        ),
        Note(
            id = "note5",
            userId = "user1",
            path = "",
            content = "Note without path",
            createdAt = Timestamp(Date())
        )
    )

    // endregion

    // region Find with no notes

    @Test
    fun `find with no notes returns empty list`() {
        val result = execute("[find(path: \"anything\")]")

        assertTrue(result is ListVal)
        assertTrue((result as ListVal).isEmpty())
    }

    @Test
    fun `find with null environment notes returns empty list`() {
        val result = execute("[find(path: \"anything\")]", notes = null)

        assertTrue(result is ListVal)
        assertTrue((result as ListVal).isEmpty())
    }

    @Test
    fun `find with empty notes list returns empty list`() {
        val result = execute("[find(path: \"anything\")]", notes = emptyList())

        assertTrue(result is ListVal)
        assertTrue((result as ListVal).isEmpty())
    }

    // endregion

    // region Find with exact path match

    @Test
    fun `find with exact path returns matching note`() {
        val result = execute("[find(path: \"2026-01-15\")]", notes = testNotes)

        assertTrue(result is ListVal)
        val list = result as ListVal
        assertEquals(1, list.size)

        val noteVal = list[0] as NoteVal
        assertEquals("note1", noteVal.note.id)
        assertEquals("2026-01-15", noteVal.note.path)
    }

    @Test
    fun `find with exact path returns empty when no match`() {
        val result = execute("[find(path: \"2099-12-31\")]", notes = testNotes)

        assertTrue(result is ListVal)
        assertTrue((result as ListVal).isEmpty())
    }

    @Test
    fun `find with exact nested path`() {
        val result = execute("[find(path: \"tasks/inbox\")]", notes = testNotes)

        assertTrue(result is ListVal)
        val list = result as ListVal
        assertEquals(1, list.size)

        val noteVal = list[0] as NoteVal
        assertEquals("note4", noteVal.note.id)
    }

    // endregion

    // region Find with pattern match

    @Test
    fun `find with date pattern returns matching notes`() {
        // Pattern: digit*4 "-" digit*2 "-" digit*2 matches "2026-01-15", "2026-01-16"
        val result = execute(
            "[find(path: pattern(digit*4 \"-\" digit*2 \"-\" digit*2))]",
            notes = testNotes
        )

        assertTrue(result is ListVal)
        val list = result as ListVal
        assertEquals(2, list.size)

        val paths = list.items.map { (it as NoteVal).note.path }.toSet()
        assertTrue(paths.contains("2026-01-15"))
        assertTrue(paths.contains("2026-01-16"))
    }

    @Test
    fun `find with prefix pattern`() {
        // Pattern: "journal/" any*(1..) matches "journal/2026-01-17"
        val result = execute(
            "[find(path: pattern(\"journal/\" any*(1..)))]",
            notes = testNotes
        )

        assertTrue(result is ListVal)
        val list = result as ListVal
        assertEquals(1, list.size)

        val noteVal = list[0] as NoteVal
        assertEquals("journal/2026-01-17", noteVal.note.path)
    }

    @Test
    fun `find with pattern returns empty when no match`() {
        val result = execute(
            "[find(path: pattern(\"archive/\" any*(1..)))]",
            notes = testNotes
        )

        assertTrue(result is ListVal)
        assertTrue((result as ListVal).isEmpty())
    }

    // endregion

    // region Find without path filter

    @Test
    fun `find without arguments returns all notes`() {
        val result = execute("[find()]", notes = testNotes)

        assertTrue(result is ListVal)
        val list = result as ListVal
        assertEquals(5, list.size)
    }

    // endregion

    // region Find excludes soft-deleted notes

    @Test
    fun `find excludes soft-deleted notes`() {
        val notes = listOf(
            Note(id = "active1", userId = "user1", path = "a", content = "Active Note", createdAt = Timestamp(Date())),
            Note(id = "deleted1", userId = "user1", path = "b", content = "Deleted Note", state = "deleted", createdAt = Timestamp(Date())),
            Note(id = "active2", userId = "user1", path = "c", content = "Another Active", createdAt = Timestamp(Date()))
        )
        val result = execute("[find()]", notes = notes)

        assertTrue(result is ListVal)
        val list = result as ListVal
        assertEquals(2, list.size)

        val ids = list.items.map { (it as NoteVal).note.id }.toSet()
        assertTrue(ids.contains("active1"))
        assertTrue(ids.contains("active2"))
        assertFalse(ids.contains("deleted1"))
    }

    @Test
    fun `find excludes soft-deleted notes even when matching by name pattern`() {
        val notes = listOf(
            Note(id = "n1", userId = "user1", path = "a", content = "examples of things", createdAt = Timestamp(Date())),
            Note(id = "n2", userId = "user1", path = "b", content = "examples deleted", state = "deleted", createdAt = Timestamp(Date()))
        )
        val result = execute(
            "[find(name: pattern(\"examples\" any*any))]",
            notes = notes
        )

        assertTrue(result is ListVal)
        val list = result as ListVal
        assertEquals(1, list.size)
        assertEquals("n1", (list[0] as NoteVal).note.id)
    }

    // endregion

    // region Find with name filter

    @Test
    fun `find with exact name returns matching note`() {
        val result = execute("[find(name: \"Task inbox\")]", notes = testNotes)

        assertTrue(result is ListVal)
        val list = result as ListVal
        assertEquals(1, list.size)

        val noteVal = list[0] as NoteVal
        assertEquals("note4", noteVal.note.id)
    }

    @Test
    fun `find with exact name returns empty when no match`() {
        val result = execute("[find(name: \"Nonexistent note\")]", notes = testNotes)

        assertTrue(result is ListVal)
        assertTrue((result as ListVal).isEmpty())
    }

    @Test
    fun `find with name pattern returns matching notes`() {
        // Pattern: "Journal" any*(0..) matches all notes starting with "Journal"
        val result = execute(
            "[find(name: pattern(\"Journal\" any*(0..)))]",
            notes = testNotes
        )

        assertTrue(result is ListVal)
        val list = result as ListVal
        assertEquals(3, list.size)

        val names = list.items.map { (it as NoteVal).note.content.lines().first() }.toSet()
        assertTrue(names.contains("Journal entry for Jan 15"))
        assertTrue(names.contains("Journal entry for Jan 16"))
        assertTrue(names.contains("Journal entry for Jan 17"))
    }

    @Test
    fun `find with name pattern returns empty when no match`() {
        val result = execute(
            "[find(name: pattern(\"Meeting\" any*(0..)))]",
            notes = testNotes
        )

        assertTrue(result is ListVal)
        assertTrue((result as ListVal).isEmpty())
    }

    @Test
    fun `find with simple literal pattern returns empty when no match`() {
        // This is the user's exact case - pattern("second") should match nothing
        val result = execute(
            "[find(name:pattern(\"second\"))]",
            notes = testNotes
        )

        assertTrue(result is ListVal)
        assertTrue("Expected empty list but got ${(result as ListVal).size} items", (result as ListVal).isEmpty())
    }


    // endregion

    // region Find with combined filters

    @Test
    fun `find with both path and name filters`() {
        // Only notes with path matching date pattern AND name starting with "Journal"
        val result = execute(
            "[find(path: pattern(digit*4 \"-\" digit*2 \"-\" digit*2), name: pattern(\"Journal\" any*(0..)))]",
            notes = testNotes
        )

        assertTrue(result is ListVal)
        val list = result as ListVal
        // note1 and note2 have date paths and Journal names
        // note3 has "journal/" prefix path (doesn't match date pattern)
        assertEquals(2, list.size)

        val ids = list.items.map { (it as NoteVal).note.id }.toSet()
        assertTrue(ids.contains("note1"))
        assertTrue(ids.contains("note2"))
    }

    @Test
    fun `find with path and name where only path matches returns empty`() {
        val result = execute(
            "[find(path: \"2026-01-15\", name: \"Task inbox\")]",
            notes = testNotes
        )

        assertTrue(result is ListVal)
        assertTrue((result as ListVal).isEmpty())
    }

    // endregion

    // region NoteVal display string

    @Test
    fun `NoteVal displays path when set`() {
        val note = Note(id = "1", path = "my/path", content = "Content")
        val noteVal = NoteVal(note)

        assertEquals("my/path", noteVal.toDisplayString())
    }

    @Test
    fun `NoteVal displays first line of content when path empty`() {
        val note = Note(id = "1", path = "", content = "First line\nSecond line")
        val noteVal = NoteVal(note)

        assertEquals("First line", noteVal.toDisplayString())
    }

    @Test
    fun `NoteVal displays id when path and content empty`() {
        val note = Note(id = "note-id-123", path = "", content = "")
        val noteVal = NoteVal(note)

        assertEquals("note-id-123", noteVal.toDisplayString())
    }

    // endregion

    // region ListVal display string

    @Test
    fun `ListVal displays empty brackets for empty list`() {
        val list = ListVal(emptyList())

        assertEquals("[]", list.toDisplayString())
    }

    @Test
    fun `ListVal displays items comma-separated`() {
        val note1 = Note(id = "1", path = "path1", content = "")
        val note2 = Note(id = "2", path = "path2", content = "")
        val list = ListVal(listOf(NoteVal(note1), NoteVal(note2)))

        assertEquals("[path1, path2]", list.toDisplayString())
    }

    // endregion

    // region Serialization

    @Test
    fun `NoteVal serializes and deserializes`() {
        val note = Note(
            id = "note-123",
            userId = "user-456",
            path = "journal/2026-01-25",
            content = "Test content"
        )
        val original = NoteVal(note)

        val serialized = original.serialize()
        val deserialized = DslValue.deserialize(serialized)

        assertTrue(deserialized is NoteVal)
        val restored = deserialized as NoteVal
        assertEquals("note-123", restored.note.id)
        assertEquals("user-456", restored.note.userId)
        assertEquals("journal/2026-01-25", restored.note.path)
        assertEquals("Test content", restored.note.content)
    }

    @Test
    fun `ListVal serializes and deserializes`() {
        val note1 = Note(id = "1", path = "path1", content = "Content 1")
        val note2 = Note(id = "2", path = "path2", content = "Content 2")
        val original = ListVal(listOf(NoteVal(note1), NoteVal(note2)))

        val serialized = original.serialize()
        val deserialized = DslValue.deserialize(serialized)

        assertTrue(deserialized is ListVal)
        val restored = deserialized as ListVal
        assertEquals(2, restored.size)

        val restoredNote1 = restored[0] as NoteVal
        val restoredNote2 = restored[1] as NoteVal
        assertEquals("path1", restoredNote1.note.path)
        assertEquals("path2", restoredNote2.note.path)
    }

    @Test
    fun `empty ListVal serializes and deserializes`() {
        val original = ListVal(emptyList())

        val serialized = original.serialize()
        val deserialized = DslValue.deserialize(serialized)

        assertTrue(deserialized is ListVal)
        assertTrue((deserialized as ListVal).isEmpty())
    }

    // endregion

    // region find is static

    @Test
    fun `find is classified as static`() {
        assertFalse(org.alkaline.taskbrain.dsl.runtime.BuiltinRegistry.isDynamic("find"))
    }

    // endregion

    // region view - Basic functionality (Milestone 10)

    @Test
    fun `view with empty list returns empty view`() {
        val result = execute("[view(find(path: \"nonexistent\"))]", notes = testNotes)

        assertTrue(result is ViewVal)
        assertTrue((result as ViewVal).isEmpty())
    }

    @Test
    fun `view with single note returns view with that note`() {
        val result = execute("[view(find(path: \"2026-01-15\"))]", notes = testNotes)

        assertTrue(result is ViewVal)
        val view = result as ViewVal
        assertEquals(1, view.size)
        assertEquals("note1", view.notes.first().id)
    }

    @Test
    fun `view with multiple notes returns view with all notes`() {
        val result = execute(
            "[view(find(path: pattern(digit*4 \"-\" digit*2 \"-\" digit*2)))]",
            notes = testNotes
        )

        assertTrue(result is ViewVal)
        val view = result as ViewVal
        assertEquals(2, view.size)

        val paths = view.notes.map { it.path }.toSet()
        assertTrue(paths.contains("2026-01-15"))
        assertTrue(paths.contains("2026-01-16"))
    }

    @Test
    fun `view with sorted list preserves order`() {
        val result = execute(
            "[view(sort(find(path: pattern(digit*4 \"-\" digit*2 \"-\" digit*2)), order: descending))]",
            notes = testNotes
        )

        assertTrue(result is ViewVal)
        val view = result as ViewVal
        assertEquals(2, view.size)

        // Sorted descending by path
        assertEquals("2026-01-16", view.notes[0].path)
        assertEquals("2026-01-15", view.notes[1].path)
    }

    // endregion

    // region view - Display string (Milestone 10)

    @Test
    fun `ViewVal displays empty view message for empty list`() {
        val view = ViewVal(emptyList())

        assertEquals("[empty view]", view.toDisplayString())
    }

    @Test
    fun `ViewVal displays single note content`() {
        val note = Note(id = "1", path = "test", content = "Note content here")
        val view = ViewVal(listOf(note))

        assertEquals("Note content here", view.toDisplayString())
    }

    @Test
    fun `ViewVal displays multiple notes with dividers`() {
        val note1 = Note(id = "1", path = "p1", content = "First note")
        val note2 = Note(id = "2", path = "p2", content = "Second note")
        val view = ViewVal(listOf(note1, note2))

        assertEquals("First note\n---\nSecond note", view.toDisplayString())
    }

    // endregion

    // region view - Serialization (Milestone 10)

    @Test
    fun `ViewVal serializes and deserializes`() {
        val note1 = Note(id = "note1", userId = "user1", path = "path1", content = "Content 1")
        val note2 = Note(id = "note2", userId = "user1", path = "path2", content = "Content 2")
        val original = ViewVal(listOf(note1, note2))

        val serialized = original.serialize()
        val deserialized = DslValue.deserialize(serialized)

        assertTrue(deserialized is ViewVal)
        val restored = deserialized as ViewVal
        assertEquals(2, restored.size)
        assertEquals("path1", restored.notes[0].path)
        assertEquals("path2", restored.notes[1].path)
    }

    @Test
    fun `empty ViewVal serializes and deserializes`() {
        val original = ViewVal(emptyList())

        val serialized = original.serialize()
        val deserialized = DslValue.deserialize(serialized)

        assertTrue(deserialized is ViewVal)
        assertTrue((deserialized as ViewVal).isEmpty())
    }

    // endregion

    // region view - Error handling (Milestone 10)

    @Test
    fun `view with non-list argument throws error`() {
        try {
            execute("[view(42)]")
            fail("Expected ExecutionException")
        } catch (e: ExecutionException) {
            assertTrue(e.message?.contains("must be a list") == true)
        }
    }

    @Test
    fun `view with no arguments throws error`() {
        try {
            execute("[view()]")
            fail("Expected ExecutionException")
        } catch (e: ExecutionException) {
            assertTrue(e.message?.contains("requires a list") == true)
        }
    }

    @Test
    fun `view with list containing non-notes throws error`() {
        try {
            execute("[view(list(1, 2, 3))]")
            fail("Expected ExecutionException")
        } catch (e: ExecutionException) {
            assertTrue(e.message?.contains("must be a note") == true)
        }
    }

    // endregion

    // region view - is classified as static (Milestone 10)

    @Test
    fun `view is classified as static`() {
        assertFalse(org.alkaline.taskbrain.dsl.runtime.BuiltinRegistry.isDynamic("view"))
    }

    // endregion

    // region view - Circular dependency detection (Milestone 10)

    @Test
    fun `view detects circular dependency`() {
        // Create a note that would be viewed
        val noteToView = Note(
            id = "note-to-view",
            userId = "user1",
            path = "target",
            content = "Target note content"
        )
        val notes = listOf(noteToView)

        // Create environment with the note already in view stack
        val env = Environment.withNotes(notes).pushViewStack("note-to-view")

        try {
            val tokens = Lexer("[view(find(path: \"target\"))]").tokenize()
            val directive = Parser(tokens, "[view(find(path: \"target\"))]").parseDirective()
            executor.execute(directive, env)
            fail("Expected ExecutionException for circular dependency")
        } catch (e: ExecutionException) {
            assertTrue(e.message?.contains("Circular view dependency") == true)
        }
    }

    @Test
    fun `view with empty view stack does not throw error`() {
        val result = execute("[view(find(path: \"2026-01-15\"))]", notes = testNotes)

        // Should succeed without circular dependency error
        assertTrue(result is ViewVal)
    }

    // endregion

    // region view - Directive rendering (Milestone 10)

    @Test
    fun `view evaluates directives in viewed note content`() {
        // Create a note with a directive in its content
        val noteWithDirective = Note(
            id = "note-with-directive",
            userId = "user1",
            path = "test-note",
            content = "Result is [add(1, 2)]"
        )
        val notes = listOf(noteWithDirective)

        val result = execute("[view(find(path: \"test-note\"))]", notes = notes)

        assertTrue(result is ViewVal)
        val view = result as ViewVal
        assertEquals(1, view.size)

        // The displayed content should have the directive evaluated
        val displayContent = view.toDisplayString()
        assertEquals("Result is 3", displayContent)
    }

    @Test
    fun `view preserves content without directives`() {
        val noteWithoutDirective = Note(
            id = "note1",
            userId = "user1",
            path = "plain-note",
            content = "Just plain text here"
        )
        val notes = listOf(noteWithoutDirective)

        val result = execute("[view(find(path: \"plain-note\"))]", notes = notes)

        assertTrue(result is ViewVal)
        val view = result as ViewVal
        assertEquals("Just plain text here", view.toDisplayString())
    }

    @Test
    fun `view evaluates multiple directives in content`() {
        val noteWithMultipleDirectives = Note(
            id = "note1",
            userId = "user1",
            path = "multi-directive",
            content = "[add(1, 1)] and [add(2, 2)]"
        )
        val notes = listOf(noteWithMultipleDirectives)

        val result = execute("[view(find(path: \"multi-directive\"))]", notes = notes)

        assertTrue(result is ViewVal)
        val view = result as ViewVal
        assertEquals("2 and 4", view.toDisplayString())
    }

    @Test
    fun `view keeps directive source on error`() {
        val noteWithBadDirective = Note(
            id = "note1",
            userId = "user1",
            path = "bad-directive",
            content = "Error: [unknown_function()]"
        )
        val notes = listOf(noteWithBadDirective)

        val result = execute("[view(find(path: \"bad-directive\"))]", notes = notes)

        assertTrue(result is ViewVal)
        val view = result as ViewVal
        // On error, keep the original source text
        assertEquals("Error: [unknown_function()]", view.toDisplayString())
    }

    @Test
    fun `view stores rendered content separately from raw notes`() {
        val noteWithDirective = Note(
            id = "note1",
            userId = "user1",
            path = "directive-note",
            content = "Value: [42]"
        )
        val notes = listOf(noteWithDirective)

        val result = execute("[view(find(path: \"directive-note\"))]", notes = notes)

        assertTrue(result is ViewVal)
        val view = result as ViewVal

        // Raw note content should be unchanged
        assertEquals("Value: [42]", view.notes.first().content)

        // Rendered content should have directive evaluated
        assertEquals("Value: 42", view.toDisplayString())
    }

    // endregion
}
