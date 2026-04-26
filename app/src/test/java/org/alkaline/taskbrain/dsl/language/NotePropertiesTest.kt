package org.alkaline.taskbrain.dsl.language

import com.google.firebase.Timestamp
import org.alkaline.taskbrain.data.Note
import org.alkaline.taskbrain.dsl.runtime.values.DateTimeVal
import org.alkaline.taskbrain.dsl.runtime.values.DslValue
import org.alkaline.taskbrain.dsl.runtime.Environment
import org.alkaline.taskbrain.dsl.runtime.ExecutionException
import org.alkaline.taskbrain.dsl.runtime.Executor
import org.alkaline.taskbrain.dsl.runtime.values.ListVal
import org.alkaline.taskbrain.dsl.runtime.values.NoteVal
import org.alkaline.taskbrain.dsl.runtime.values.StringVal
import org.alkaline.taskbrain.dsl.runtime.values.UndefinedVal
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.ZoneId
import java.util.Date

/**
 * Tests for note property access (Milestone 6).
 *
 * Tests the [.] syntax for current note reference and [.prop] for property access.
 */
class NotePropertiesTest {

    private lateinit var executor: Executor

    @Before
    fun setUp() {
        executor = Executor()
    }

    private fun execute(source: String, currentNote: Note? = null, notes: List<Note>? = null): DslValue {
        val tokens = Lexer(source).tokenize()
        val directive = Parser(tokens, source).parseDirective()
        val env = when {
            notes != null && currentNote != null ->
                Environment.withNotesAndCurrentNote(notes, currentNote)
            notes != null ->
                Environment.withNotes(notes)
            currentNote != null ->
                Environment.withCurrentNote(currentNote)
            else ->
                Environment()
        }
        return executor.execute(directive, env)
    }

    private fun parse(source: String): Directive {
        val tokens = Lexer(source).tokenize()
        return Parser(tokens, source).parseDirective()
    }

    // region Sample notes for testing

    private val testNote = Note(
        id = "test-note-id",
        userId = "user-123",
        path = "journal/2026-01-25",
        content = "My Note Title\nSecond line\nThird line",
        createdAt = Timestamp(Date(1737820800000)), // 2026-01-25T12:00:00Z
        updatedAt = Timestamp(Date(1737907200000)), // 2026-01-26T12:00:00Z
    )

    private val noteWithoutDates = Note(
        id = "no-dates-id",
        userId = "user-123",
        path = "tasks/inbox",
        content = "Inbox"
    )

    // Hierarchy test notes: root -> parent -> child -> grandchild
    private val rootNote = Note(
        id = "root-id",
        userId = "user-123",
        parentNoteId = null,
        path = "root",
        content = "Root Note"
    )

    private val parentNote = Note(
        id = "parent-id",
        userId = "user-123",
        parentNoteId = "root-id",
        path = "parent",
        content = "Parent Note"
    )

    private val childNote = Note(
        id = "child-id",
        userId = "user-123",
        parentNoteId = "parent-id",
        path = "child",
        content = "Child Note"
    )

    private val grandchildNote = Note(
        id = "grandchild-id",
        userId = "user-123",
        parentNoteId = "child-id",
        path = "grandchild",
        content = "Grandchild Note"
    )

    private val hierarchyNotes = listOf(rootNote, parentNote, childNote, grandchildNote)

    // endregion

    // region Lexer DOT token

    @Test
    fun `lexer tokenizes single dot`() {
        val tokens = Lexer("[.]").tokenize()

        assertEquals(4, tokens.size)
        assertEquals(TokenType.LBRACKET, tokens[0].type)
        assertEquals(TokenType.DOT, tokens[1].type)
        assertEquals(TokenType.RBRACKET, tokens[2].type)
        assertEquals(TokenType.EOF, tokens[3].type)
    }

    @Test
    fun `lexer tokenizes dot with property`() {
        val tokens = Lexer("[.path]").tokenize()

        assertEquals(5, tokens.size)
        assertEquals(TokenType.LBRACKET, tokens[0].type)
        assertEquals(TokenType.DOT, tokens[1].type)
        assertEquals(TokenType.IDENTIFIER, tokens[2].type)
        assertEquals("path", tokens[2].literal)
        assertEquals(TokenType.RBRACKET, tokens[3].type)
        assertEquals(TokenType.EOF, tokens[4].type)
    }

    @Test
    fun `lexer still tokenizes double dot for ranges`() {
        val tokens = Lexer("[pattern(digit*(0..))]").tokenize()

        val dotdotToken = tokens.find { it.type == TokenType.DOTDOT }
        assertNotNull("Should have DOTDOT token", dotdotToken)
    }

    // endregion

    // region Parser CurrentNoteRef

    @Test
    fun `parser parses standalone dot as CurrentNoteRef`() {
        val directive = parse("[.]")

        assertTrue(directive.expression is CurrentNoteRef)
    }

    @Test
    fun `parser parses dot with property as PropertyAccess`() {
        val directive = parse("[.path]")

        assertTrue(directive.expression is PropertyAccess)
        val propAccess = directive.expression as PropertyAccess
        assertTrue(propAccess.target is CurrentNoteRef)
        assertEquals("path", propAccess.property)
    }

    @Test
    fun `parser parses chained property access`() {
        // This would be something like find(...).path in the future
        // For now, test that expression.property works
        val directive = parse("[find().path]")

        assertTrue(directive.expression is PropertyAccess)
        val propAccess = directive.expression as PropertyAccess
        assertTrue(propAccess.target is CallExpr)
        assertEquals("path", propAccess.property)
    }

    // endregion

    // region Executor CurrentNoteRef

    @Test
    fun `current note ref returns NoteVal when note in environment`() {
        val result = execute("[.]", currentNote = testNote)

        assertTrue(result is NoteVal)
        assertEquals(testNote.id, (result as NoteVal).note.id)
    }

    @Test
    fun `current note ref throws when no note in environment`() {
        val exception = assertThrows(ExecutionException::class.java) {
            execute("[.]")
        }
        assertTrue(exception.message!!.contains("No current note"))
    }

    // endregion

    // region Property Access - String properties

    @Test
    fun `dot path returns note path`() {
        val result = execute("[.path]", currentNote = testNote)

        assertTrue(result is StringVal)
        assertEquals("journal/2026-01-25", (result as StringVal).value)
    }

    @Test
    fun `dot id returns note id`() {
        val result = execute("[.id]", currentNote = testNote)

        assertTrue(result is StringVal)
        assertEquals("test-note-id", (result as StringVal).value)
    }

    @Test
    fun `dot name returns first line of content`() {
        val result = execute("[.name]", currentNote = testNote)

        assertTrue(result is StringVal)
        assertEquals("My Note Title", (result as StringVal).value)
    }

    @Test
    fun `dot name returns empty string when content empty`() {
        val emptyNote = Note(id = "1", content = "")
        val result = execute("[.name]", currentNote = emptyNote)

        assertTrue(result is StringVal)
        assertEquals("", (result as StringVal).value)
    }

    // endregion

    // region Property Access - Date properties

    @Test
    fun `dot created returns creation datetime`() {
        val result = execute("[.created]", currentNote = testNote)

        assertTrue(result is DateTimeVal)
        val expectedDateTime = testNote.createdAt!!.toDate().toInstant()
            .atZone(ZoneId.systemDefault()).toLocalDateTime()
        assertEquals(expectedDateTime, (result as DateTimeVal).value)
    }

    @Test
    fun `dot modified returns updated datetime`() {
        val result = execute("[.modified]", currentNote = testNote)

        assertTrue(result is DateTimeVal)
        val expectedDateTime = testNote.updatedAt!!.toDate().toInstant()
            .atZone(ZoneId.systemDefault()).toLocalDateTime()
        assertEquals(expectedDateTime, (result as DateTimeVal).value)
    }

    @Test
    fun `dot created throws when no created date`() {
        val exception = assertThrows(ExecutionException::class.java) {
            execute("[.created]", currentNote = noteWithoutDates)
        }
        assertTrue(exception.message!!.contains("no created date"))
    }

    @Test
    fun `dot modified throws when no modified date`() {
        val exception = assertThrows(ExecutionException::class.java) {
            execute("[.modified]", currentNote = noteWithoutDates)
        }
        assertTrue(exception.message!!.contains("no modified date"))
    }

    // endregion

    // region Unknown property

    @Test
    fun `unknown property throws ExecutionException`() {
        val exception = assertThrows(ExecutionException::class.java) {
            execute("[.unknownProp]", currentNote = testNote)
        }
        assertTrue(exception.message!!.contains("Unknown property"))
        assertTrue(exception.message!!.contains("unknownProp"))
    }

    // endregion

    // region Property access on find results

    @Test
    fun `property access on find result`() {
        val notes = listOf(
            Note(id = "1", path = "journal/2026-01-15", content = "Entry 1"),
            Note(id = "2", path = "journal/2026-01-16", content = "Entry 2")
        )

        // Find returns a list, so we'd need first() to get a single note
        // For now, test that property access on a NoteVal works
        val result = execute("[find(path: \"journal/2026-01-15\")]", notes = notes)

        assertTrue(result is ListVal)
        val list = result as ListVal
        assertEquals(1, list.size)

        // Get the note and verify we can access its properties
        val noteVal = list[0] as NoteVal
        assertEquals("journal/2026-01-15", noteVal.getProperty("path", Environment.withNotes(notes)).toDisplayString())
    }

    // endregion

    // region Property access doesn't work on non-notes

    @Test
    fun `property access on string throws`() {
        val exception = assertThrows(ExecutionException::class.java) {
            // qt returns a StringVal, not a NoteVal
            execute("[qt.path]")
        }
        assertTrue(exception.message!!.contains("Cannot access property"))
        assertTrue(exception.message!!.contains("string"))
    }

    @Test
    fun `property access on number throws`() {
        val exception = assertThrows(ExecutionException::class.java) {
            // Create an expression that tries to access property on a number
            // This requires special setup since we can't write [42.path] directly
            val tokens = Lexer("[add(1, 2).path]").tokenize()
            val directive = Parser(tokens, "[add(1, 2).path]").parseDirective()
            executor.execute(directive)
        }
        assertTrue(exception.message!!.contains("Cannot access property"))
        assertTrue(exception.message!!.contains("number"))
    }

    // endregion

    // region Display strings

    @Test
    fun `path property displays correctly`() {
        val result = execute("[.path]", currentNote = testNote)
        assertEquals("journal/2026-01-25", result.toDisplayString())
    }

    @Test
    fun `created property displays as datetime`() {
        val result = execute("[.created]", currentNote = testNote)
        // The datetime should be displayed as "yyyy-MM-dd, HH:mm:ss"
        assertTrue(result.toDisplayString().matches(Regex("\\d{4}-\\d{2}-\\d{2}, \\d{2}:\\d{2}:\\d{2}")))
    }

    // endregion

    // region Hierarchy navigation - .up property

    @Test
    fun `dot up returns parent note`() {
        val result = execute("[.up]", currentNote = childNote, notes = hierarchyNotes)

        assertTrue(result is NoteVal)
        assertEquals("parent-id", (result as NoteVal).note.id)
    }

    @Test
    fun `dot up returns undefined when no parent`() {
        val result = execute("[.up]", currentNote = rootNote, notes = hierarchyNotes)

        assertTrue(result is UndefinedVal)
    }

    @Test
    fun `dot up dot path returns parent path`() {
        val result = execute("[.up.path]", currentNote = childNote, notes = hierarchyNotes)

        assertTrue(result is StringVal)
        assertEquals("parent", (result as StringVal).value)
    }

    @Test
    fun `dot up chained returns grandparent`() {
        val result = execute("[.up.up]", currentNote = grandchildNote, notes = hierarchyNotes)

        assertTrue(result is NoteVal)
        assertEquals("parent-id", (result as NoteVal).note.id)
    }

    // endregion

    // region Hierarchy navigation - .up() method

    @Test
    fun `dot up parentheses returns parent note`() {
        val result = execute("[.up()]", currentNote = childNote, notes = hierarchyNotes)

        assertTrue(result is NoteVal)
        assertEquals("parent-id", (result as NoteVal).note.id)
    }

    @Test
    fun `dot up 0 returns current note`() {
        val result = execute("[.up(0)]", currentNote = childNote, notes = hierarchyNotes)

        assertTrue(result is NoteVal)
        assertEquals("child-id", (result as NoteVal).note.id)
    }

    @Test
    fun `dot up 1 returns parent note`() {
        val result = execute("[.up(1)]", currentNote = childNote, notes = hierarchyNotes)

        assertTrue(result is NoteVal)
        assertEquals("parent-id", (result as NoteVal).note.id)
    }

    @Test
    fun `dot up 2 returns grandparent note`() {
        val result = execute("[.up(2)]", currentNote = grandchildNote, notes = hierarchyNotes)

        assertTrue(result is NoteVal)
        assertEquals("parent-id", (result as NoteVal).note.id)
    }

    @Test
    fun `dot up 3 returns great-grandparent note`() {
        val result = execute("[.up(3)]", currentNote = grandchildNote, notes = hierarchyNotes)

        assertTrue(result is NoteVal)
        assertEquals("root-id", (result as NoteVal).note.id)
    }

    @Test
    fun `dot up exceeding hierarchy returns undefined`() {
        val result = execute("[.up(4)]", currentNote = grandchildNote, notes = hierarchyNotes)

        assertTrue(result is UndefinedVal)
    }

    @Test
    fun `dot up with variable levels`() {
        val result = execute("[n: 2; .up(n)]", currentNote = grandchildNote, notes = hierarchyNotes)

        assertTrue(result is NoteVal)
        assertEquals("parent-id", (result as NoteVal).note.id)
    }

    // endregion

    // region Hierarchy navigation - .root property

    @Test
    fun `dot root returns root ancestor`() {
        val result = execute("[.root]", currentNote = grandchildNote, notes = hierarchyNotes)

        assertTrue(result is NoteVal)
        assertEquals("root-id", (result as NoteVal).note.id)
    }

    @Test
    fun `dot root on root note returns itself`() {
        val result = execute("[.root]", currentNote = rootNote, notes = hierarchyNotes)

        assertTrue(result is NoteVal)
        assertEquals("root-id", (result as NoteVal).note.id)
    }

    @Test
    fun `dot root dot path returns root path`() {
        val result = execute("[.root.path]", currentNote = grandchildNote, notes = hierarchyNotes)

        assertTrue(result is StringVal)
        assertEquals("root", (result as StringVal).value)
    }

    // endregion
}
