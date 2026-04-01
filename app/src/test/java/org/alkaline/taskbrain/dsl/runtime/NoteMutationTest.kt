package org.alkaline.taskbrain.dsl.runtime

import com.google.firebase.Timestamp
import org.alkaline.taskbrain.dsl.runtime.values.DslValue
import org.alkaline.taskbrain.dsl.runtime.values.ListVal
import org.alkaline.taskbrain.dsl.runtime.values.NoteVal
import org.alkaline.taskbrain.dsl.runtime.values.NumberVal
import org.alkaline.taskbrain.data.Note
import org.alkaline.taskbrain.dsl.language.Lexer
import org.alkaline.taskbrain.dsl.language.Parser
import org.alkaline.taskbrain.testutil.MockNoteOperations
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Date

/**
 * Tests for Note Mutation functionality.
 *
 * Tests cover:
 * - Variable assignment: [x: 5]
 * - Statement separation: [x: 5; y: 10; add(x, y)]
 * - Property assignment: [.path: "value"]
 * - Method calls: [.append("text")]
 * - new() function
 * - maybe_new() function
 */
class NoteMutationTest {

    private lateinit var executor: Executor
    private lateinit var mockOps: MockNoteOperations

    @Before
    fun setUp() {
        executor = Executor()
        mockOps = MockNoteOperations()
    }

    private fun execute(
        source: String,
        notes: List<Note>? = null,
        currentNote: Note? = null,
        noteOps: NoteOperations? = mockOps
    ): DslValue {
        val tokens = Lexer(source).tokenize()
        val directive = Parser(tokens, source).parseDirective()
        val env = Environment(NoteContext(
            notes = notes,
            currentNote = currentNote,
            noteOperations = noteOps
        ))
        return executor.execute(directive, env)
    }

    // region Sample notes

    private val testNote = Note(
        id = "test-note-1",
        userId = "user1",
        path = "journal/2026-01-25",
        content = "Test content",
        createdAt = Timestamp(Date())
    )

    // endregion

    // region Variable assignment tests

    @Test
    fun `variable assignment returns assigned value`() {
        val result = execute("[x: 5]")

        assertTrue(result is NumberVal)
        assertEquals(5.0, (result as NumberVal).value, 0.0)
    }

    @Test
    fun `variable can be referenced after assignment`() {
        val result = execute("[x: 42; x]")

        assertTrue(result is NumberVal)
        assertEquals(42.0, (result as NumberVal).value, 0.0)
    }

    @Test
    fun `multiple variables can be defined and used`() {
        val result = execute("[x: 10; y: 20; add(x, y)]")

        assertTrue(result is NumberVal)
        assertEquals(30.0, (result as NumberVal).value, 0.0)
    }

    @Test
    fun `undefined variable throws error`() {
        try {
            execute("[undefinedVar]")
            fail("Expected ExecutionException")
        } catch (e: ExecutionException) {
            // Error message says "Unknown function or variable" when not found
            assertTrue(e.message?.contains("Unknown function or variable") == true)
        }
    }

    // endregion

    // region Statement separation tests

    @Test
    fun `semicolon separates statements`() {
        val result = execute("[1; 2; 3]")

        // Returns value of last statement
        assertTrue(result is NumberVal)
        assertEquals(3.0, (result as NumberVal).value, 0.0)
    }

    @Test
    fun `statements execute in order`() {
        val result = execute("[a: 1; a: add(a, 1); a: add(a, 1); a]")

        assertTrue(result is NumberVal)
        assertEquals(3.0, (result as NumberVal).value, 0.0)
    }

    @Test
    fun `single statement without semicolon works`() {
        val result = execute("[add(1, 2)]")

        assertTrue(result is NumberVal)
        assertEquals(3.0, (result as NumberVal).value, 0.0)
    }

    // endregion

    // region Property assignment tests

    @Test
    fun `assign path to current note`() {
        mockOps.addNote(testNote)

        execute("[.path: \"new/path\"]", currentNote = testNote)

        assertEquals(1, mockOps.updatedPaths.size)
        assertEquals(testNote.id to "new/path", mockOps.updatedPaths[0])
    }

    @Test
    fun `assign name to current note`() {
        mockOps.addNote(testNote)

        execute("[.name: \"New Title\"]", currentNote = testNote)

        assertEquals(1, mockOps.updatedContents.size)
        assertEquals(testNote.id, mockOps.updatedContents[0].first)
        assertEquals("New Title", mockOps.updatedContents[0].second)
    }

    @Test
    fun `assign name preserves rest of content`() {
        val noteWithMultipleLines = testNote.copy(content = "Old Title\nLine 2\nLine 3")
        mockOps.addNote(noteWithMultipleLines)

        execute("[.name: \"New Title\"]", currentNote = noteWithMultipleLines)

        assertEquals(1, mockOps.updatedContents.size)
        assertEquals("New Title\nLine 2\nLine 3", mockOps.updatedContents[0].second)
    }

    @Test
    fun `cannot assign to read-only property`() {
        mockOps.addNote(testNote)

        try {
            execute("[.id: \"new-id\"]", currentNote = testNote)
            fail("Expected ExecutionException")
        } catch (e: ExecutionException) {
            assertTrue(e.message?.contains("read-only") == true)
        }
    }

    @Test
    fun `property assignment without note operations throws error`() {
        try {
            execute("[.path: \"new/path\"]", currentNote = testNote, noteOps = null)
            fail("Expected ExecutionException")
        } catch (e: ExecutionException) {
            assertTrue(e.message?.contains("note operations not available") == true)
        }
    }

    // endregion

    // region Method call tests

    @Test
    fun `append method adds text to note`() {
        mockOps.addNote(testNote)

        val result = execute("[.append(\"New line\")]", currentNote = testNote)

        assertTrue(result is NoteVal)
        assertEquals(1, mockOps.appendedTexts.size)
        assertEquals(testNote.id to "New line", mockOps.appendedTexts[0])
    }

    @Test
    fun `append returns updated note for chaining`() {
        mockOps.addNote(testNote)

        val result = execute("[.append(\"Line 1\"); .append(\"Line 2\")]", currentNote = testNote)

        assertTrue(result is NoteVal)
        assertEquals(2, mockOps.appendedTexts.size)
    }

    @Test
    fun `append with variable text`() {
        mockOps.addNote(testNote)

        execute("[msg: \"Hello\"; .append(msg)]", currentNote = testNote)

        assertEquals(1, mockOps.appendedTexts.size)
        assertEquals("Hello", mockOps.appendedTexts[0].second)
    }

    @Test
    fun `append without note operations throws error`() {
        try {
            execute("[.append(\"text\")]", currentNote = testNote, noteOps = null)
            fail("Expected ExecutionException")
        } catch (e: ExecutionException) {
            assertTrue(e.message?.contains("note operations not available") == true)
        }
    }

    @Test
    fun `unknown method throws error`() {
        mockOps.addNote(testNote)

        try {
            execute("[.unknownMethod()]", currentNote = testNote)
            fail("Expected ExecutionException")
        } catch (e: ExecutionException) {
            assertTrue(e.message?.contains("Unknown method") == true)
        }
    }

    // endregion

    // region new() function tests

    @Test
    fun `new creates note at path`() {
        val result = execute("[new(path: \"test/path\", content: \"Hello\")]")

        assertTrue(result is NoteVal)
        assertEquals(1, mockOps.createdNotes.size)
        assertEquals("test/path", mockOps.createdNotes[0].path)
        assertEquals("Hello", mockOps.createdNotes[0].content)
    }

    @Test
    fun `new with empty content`() {
        val result = execute("[new(path: \"test/path\")]")

        assertTrue(result is NoteVal)
        assertEquals(1, mockOps.createdNotes.size)
        assertEquals("", mockOps.createdNotes[0].content)
    }

    @Test
    fun `new throws error when path exists`() {
        mockOps.addNote(Note(id = "existing", path = "test/path", content = ""))

        try {
            execute("[new(path: \"test/path\")]")
            fail("Expected ExecutionException")
        } catch (e: ExecutionException) {
            assertTrue(e.message?.contains("already exists") == true)
        }
    }

    @Test
    fun `new requires path argument`() {
        try {
            execute("[new(content: \"Hello\")]")
            fail("Expected ExecutionException")
        } catch (e: ExecutionException) {
            assertTrue(e.message?.contains("requires a 'path' argument") == true)
        }
    }

    @Test
    fun `new without note operations throws error`() {
        try {
            execute("[new(path: \"test/path\")]", noteOps = null)
            fail("Expected ExecutionException")
        } catch (e: ExecutionException) {
            assertTrue(e.message?.contains("requires note operations") == true)
        }
    }

    // endregion

    // region maybe_new() function tests

    @Test
    fun `maybe_new returns existing note`() {
        val existingNote = Note(id = "existing", userId = "user1", path = "test/path", content = "Existing content")
        mockOps.addNote(existingNote)

        val result = execute("[maybe_new(path: \"test/path\", maybe_content: \"New content\")]")

        assertTrue(result is NoteVal)
        assertEquals("existing", (result as NoteVal).note.id)
        assertEquals(0, mockOps.createdNotes.size)  // Should not create
    }

    @Test
    fun `maybe_new creates note when not exists`() {
        val result = execute("[maybe_new(path: \"test/path\", maybe_content: \"New content\")]")

        assertTrue(result is NoteVal)
        assertEquals(1, mockOps.createdNotes.size)
        assertEquals("test/path", mockOps.createdNotes[0].path)
        assertEquals("New content", mockOps.createdNotes[0].content)
    }

    @Test
    fun `maybe_new without maybe_content creates empty note`() {
        val result = execute("[maybe_new(path: \"test/path\")]")

        assertTrue(result is NoteVal)
        assertEquals(1, mockOps.createdNotes.size)
        assertEquals("", mockOps.createdNotes[0].content)
    }

    @Test
    fun `maybe_new requires path argument`() {
        try {
            execute("[maybe_new(maybe_content: \"Hello\")]")
            fail("Expected ExecutionException")
        } catch (e: ExecutionException) {
            assertTrue(e.message?.contains("requires a 'path' argument") == true)
        }
    }

    @Test
    fun `maybe_new is idempotent`() {
        execute("[maybe_new(path: \"test/path\", maybe_content: \"First call\")]")
        execute("[maybe_new(path: \"test/path\", maybe_content: \"Second call\")]")

        assertEquals(1, mockOps.createdNotes.size)
        assertEquals("First call", mockOps.createdNotes[0].content)
    }

    // endregion

    // region Combined functionality tests

    @Test
    fun `complex workflow with variables and mutations`() {
        mockOps.addNote(testNote)

        // Use a simpler workflow that doesn't depend on string() function
        val result = execute(
            "[msg: \"Test message\"; .append(msg)]",
            currentNote = testNote
        )

        assertTrue(result is NoteVal)
        assertEquals(1, mockOps.appendedTexts.size)
        assertEquals("Test message", mockOps.appendedTexts[0].second)
    }

    @Test
    fun `find notes and access properties`() {
        val notes = listOf(
            Note(id = "n1", userId = "u1", path = "2026-01-25", content = "Journal 1"),
            Note(id = "n2", userId = "u1", path = "2026-01-26", content = "Journal 2")
        )

        val result = execute(
            "[find(path: \"2026-01-25\")]",
            notes = notes
        )

        assertTrue(result is ListVal)
        assertEquals(1, (result as ListVal).size)
    }

    // endregion
}
