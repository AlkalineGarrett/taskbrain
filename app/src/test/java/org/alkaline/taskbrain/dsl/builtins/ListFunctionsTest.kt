package org.alkaline.taskbrain.dsl.builtins

import com.google.firebase.Timestamp
import org.alkaline.taskbrain.data.Note
import org.alkaline.taskbrain.dsl.language.Lexer
import org.alkaline.taskbrain.dsl.language.Parser
import org.alkaline.taskbrain.dsl.runtime.values.BooleanVal
import org.alkaline.taskbrain.dsl.runtime.values.DateTimeVal
import org.alkaline.taskbrain.dsl.runtime.values.DateVal
import org.alkaline.taskbrain.dsl.runtime.values.DslValue
import org.alkaline.taskbrain.dsl.runtime.Environment
import org.alkaline.taskbrain.dsl.runtime.ExecutionException
import org.alkaline.taskbrain.dsl.runtime.Executor
import org.alkaline.taskbrain.dsl.runtime.values.ListVal
import org.alkaline.taskbrain.dsl.runtime.values.NoteVal
import org.alkaline.taskbrain.dsl.runtime.values.NumberVal
import org.alkaline.taskbrain.dsl.runtime.values.StringVal
import org.alkaline.taskbrain.dsl.runtime.values.TimeVal
import org.alkaline.taskbrain.dsl.runtime.values.UndefinedVal
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Date

/**
 * Tests for ListFunctions (list, sort, first).
 *
 * Milestone 9.
 */
class ListFunctionsTest {

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

    // region Test notes

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
            path = "2026-01-10",
            content = "Journal entry for Jan 10",
            createdAt = Timestamp(Date())
        ),
        Note(
            id = "note4",
            userId = "user1",
            path = "tasks/inbox",
            content = "Task inbox",
            createdAt = Timestamp(Date())
        )
    )

    // endregion

    // region sort - Basic functionality

    @Test
    fun `sort empty list returns empty list`() {
        // Use find with a pattern that won't match anything
        val result = execute("[sort(find(path: \"nonexistent\"))]", notes = testNotes)

        assertTrue(result is ListVal)
        assertTrue((result as ListVal).isEmpty())
    }

    @Test
    fun `sort single item list returns same list`() {
        val result = execute("[sort(find(path: \"tasks/inbox\"))]", notes = testNotes)

        assertTrue(result is ListVal)
        val list = result as ListVal
        assertEquals(1, list.size)
        assertEquals("tasks/inbox", (list[0] as NoteVal).note.path)
    }

    @Test
    fun `sort notes by default sorts by path`() {
        val result = execute("[sort(find())]", notes = testNotes)

        assertTrue(result is ListVal)
        val list = result as ListVal
        assertEquals(4, list.size)

        // Sorted alphabetically by path
        val paths = list.items.map { (it as NoteVal).note.path }
        assertEquals(listOf("2026-01-10", "2026-01-15", "2026-01-16", "tasks/inbox"), paths)
    }

    // endregion

    // region sort - with key lambda

    @Test
    fun `sort with key lambda extracts sort key`() {
        // Sort by path using key lambda (should give same result as default for notes)
        val result = execute("[sort(find(), key: [i.path])]", notes = testNotes)

        assertTrue(result is ListVal)
        val list = result as ListVal
        assertEquals(4, list.size)

        val paths = list.items.map { (it as NoteVal).note.path }
        assertEquals(listOf("2026-01-10", "2026-01-15", "2026-01-16", "tasks/inbox"), paths)
    }

    @Test
    fun `sort with key lambda sorting by name`() {
        val result = execute("[sort(find(), key: [i.name])]", notes = testNotes)

        assertTrue(result is ListVal)
        val list = result as ListVal
        assertEquals(4, list.size)

        // Names: "Journal entry for Jan 10", "Journal entry for Jan 15", "Journal entry for Jan 16", "Task inbox"
        val names = list.items.map { (it as NoteVal).note.content.lines().first() }
        assertEquals(
            listOf(
                "Journal entry for Jan 10",
                "Journal entry for Jan 15",
                "Journal entry for Jan 16",
                "Task inbox"
            ),
            names
        )
    }

    // endregion

    // region sort - order parameter

    @Test
    fun `sort with order desc reverses result`() {
        val result = execute("[sort(find(), order: descending)]", notes = testNotes)

        assertTrue(result is ListVal)
        val list = result as ListVal
        assertEquals(4, list.size)

        // Reverse alphabetical order by path
        val paths = list.items.map { (it as NoteVal).note.path }
        assertEquals(listOf("tasks/inbox", "2026-01-16", "2026-01-15", "2026-01-10"), paths)
    }

    @Test
    fun `sort with order asc is default behavior`() {
        val result = execute("[sort(find(), order: ascending)]", notes = testNotes)

        assertTrue(result is ListVal)
        val list = result as ListVal
        assertEquals(4, list.size)

        val paths = list.items.map { (it as NoteVal).note.path }
        assertEquals(listOf("2026-01-10", "2026-01-15", "2026-01-16", "tasks/inbox"), paths)
    }

    @Test
    fun `sort with key lambda and order desc`() {
        val result = execute("[sort(find(), key: [i.path], order: descending)]", notes = testNotes)

        assertTrue(result is ListVal)
        val list = result as ListVal
        assertEquals(4, list.size)

        val paths = list.items.map { (it as NoteVal).note.path }
        assertEquals(listOf("tasks/inbox", "2026-01-16", "2026-01-15", "2026-01-10"), paths)
    }

    // endregion

    // region sort - Value type comparison

    @Test
    fun `compareValues sorts numbers numerically`() {
        // Test the internal comparison function directly
        val a = NumberVal(10.0)
        val b = NumberVal(2.0)
        val c = NumberVal(10.0)

        assertTrue(ListFunctions.compareValues(a, b) > 0)  // 10 > 2
        assertTrue(ListFunctions.compareValues(b, a) < 0)  // 2 < 10
        assertEquals(0, ListFunctions.compareValues(a, c)) // 10 == 10
    }

    @Test
    fun `compareValues sorts strings lexicographically`() {
        val a = StringVal("apple")
        val b = StringVal("banana")
        val c = StringVal("apple")

        assertTrue(ListFunctions.compareValues(a, b) < 0)  // "apple" < "banana"
        assertTrue(ListFunctions.compareValues(b, a) > 0)  // "banana" > "apple"
        assertEquals(0, ListFunctions.compareValues(a, c)) // "apple" == "apple"
    }

    @Test
    fun `compareValues sorts dates chronologically`() {
        val a = DateVal(LocalDate.of(2026, 1, 15))
        val b = DateVal(LocalDate.of(2026, 1, 10))
        val c = DateVal(LocalDate.of(2026, 1, 15))

        assertTrue(ListFunctions.compareValues(a, b) > 0)  // Jan 15 > Jan 10
        assertTrue(ListFunctions.compareValues(b, a) < 0)  // Jan 10 < Jan 15
        assertEquals(0, ListFunctions.compareValues(a, c)) // Jan 15 == Jan 15
    }

    @Test
    fun `compareValues sorts times chronologically`() {
        val a = TimeVal(LocalTime.of(14, 30))
        val b = TimeVal(LocalTime.of(9, 0))
        val c = TimeVal(LocalTime.of(14, 30))

        assertTrue(ListFunctions.compareValues(a, b) > 0)  // 14:30 > 9:00
        assertTrue(ListFunctions.compareValues(b, a) < 0)  // 9:00 < 14:30
        assertEquals(0, ListFunctions.compareValues(a, c)) // 14:30 == 14:30
    }

    @Test
    fun `compareValues sorts datetimes chronologically`() {
        val a = DateTimeVal(LocalDateTime.of(2026, 1, 15, 14, 30))
        val b = DateTimeVal(LocalDateTime.of(2026, 1, 15, 9, 0))
        val c = DateTimeVal(LocalDateTime.of(2026, 1, 15, 14, 30))

        assertTrue(ListFunctions.compareValues(a, b) > 0)
        assertTrue(ListFunctions.compareValues(b, a) < 0)
        assertEquals(0, ListFunctions.compareValues(a, c))
    }

    @Test
    fun `compareValues sorts booleans false before true`() {
        val a = BooleanVal(true)
        val b = BooleanVal(false)
        val c = BooleanVal(true)

        assertTrue(ListFunctions.compareValues(a, b) > 0)  // true > false
        assertTrue(ListFunctions.compareValues(b, a) < 0)  // false < true
        assertEquals(0, ListFunctions.compareValues(a, c)) // true == true
    }

    @Test
    fun `compareValues sorts undefined first`() {
        val undef = UndefinedVal
        val num = NumberVal(5.0)
        val str = StringVal("hello")

        assertTrue(ListFunctions.compareValues(undef, num) < 0)  // undefined < number
        assertTrue(ListFunctions.compareValues(undef, str) < 0)  // undefined < string
        assertTrue(ListFunctions.compareValues(num, undef) > 0)  // number > undefined
        assertEquals(0, ListFunctions.compareValues(undef, undef)) // undefined == undefined
    }

    @Test
    fun `compareValues different types use type precedence`() {
        val bool = BooleanVal(true)
        val num = NumberVal(1.0)
        val str = StringVal("a")

        // Boolean < Number < String (by type precedence)
        assertTrue(ListFunctions.compareValues(bool, num) < 0)
        assertTrue(ListFunctions.compareValues(num, str) < 0)
        assertTrue(ListFunctions.compareValues(bool, str) < 0)
    }

    // endregion

    // region sort - Error handling

    @Test
    fun `sort with non-list argument throws error`() {
        try {
            execute("[sort(42)]")
            fail("Expected ExecutionException")
        } catch (e: ExecutionException) {
            assertTrue(e.message?.contains("must be a list") == true)
        }
    }

    @Test
    fun `sort with invalid order throws error`() {
        try {
            execute("[sort(find(), order: \"invalid\")]", notes = testNotes)
            fail("Expected ExecutionException")
        } catch (e: ExecutionException) {
            assertTrue(e.message?.contains("must be 'ascending' or 'descending'") == true)
        }
    }

    @Test
    fun `sort with non-string order throws error`() {
        try {
            execute("[sort(find(), order: 42)]", notes = testNotes)
            fail("Expected ExecutionException")
        } catch (e: ExecutionException) {
            assertTrue(e.message?.contains("must be 'ascending' or 'descending'") == true)
        }
    }

    @Test
    fun `sort with no arguments throws error`() {
        try {
            execute("[sort()]", notes = testNotes)
            fail("Expected ExecutionException")
        } catch (e: ExecutionException) {
            assertTrue(e.message?.contains("requires exactly 1 positional argument") == true)
        }
    }

    @Test
    fun `sort with too many positional arguments throws error`() {
        try {
            execute("[sort(find(), find())]", notes = testNotes)
            fail("Expected ExecutionException")
        } catch (e: ExecutionException) {
            assertTrue(e.message?.contains("requires exactly 1 positional argument") == true)
        }
    }

    // endregion

    // region sort - is classified as static

    @Test
    fun `sort is classified as static`() {
        assertFalse(org.alkaline.taskbrain.dsl.runtime.BuiltinRegistry.isDynamic("sort"))
    }

    // endregion

    // region first - Basic functionality

    @Test
    fun `first on non-empty list returns first item`() {
        val result = execute("[first(sort(find()))]", notes = testNotes)

        assertTrue(result is NoteVal)
        assertEquals("2026-01-10", (result as NoteVal).note.path)
    }

    @Test
    fun `first on empty list returns undefined`() {
        val result = execute("[first(find(path: \"nonexistent\"))]", notes = testNotes)

        assertTrue(result is UndefinedVal)
    }

    @Test
    fun `first with sort descending returns last item`() {
        val result = execute("[first(sort(find(), order: descending))]", notes = testNotes)

        assertTrue(result is NoteVal)
        assertEquals("tasks/inbox", (result as NoteVal).note.path)
    }

    // endregion

    // region first - Error handling

    @Test
    fun `first with non-list argument throws error`() {
        try {
            execute("[first(42)]")
            fail("Expected ExecutionException")
        } catch (e: ExecutionException) {
            assertTrue(e.message?.contains("must be a list") == true)
        }
    }

    @Test
    fun `first with no arguments throws error`() {
        try {
            execute("[first()]")
            fail("Expected ExecutionException")
        } catch (e: ExecutionException) {
            assertTrue(e.message?.contains("requires exactly 1 argument") == true)
        }
    }

    // endregion

    // region first - is classified as static

    @Test
    fun `first is classified as static`() {
        assertFalse(org.alkaline.taskbrain.dsl.runtime.BuiltinRegistry.isDynamic("first"))
    }

    // endregion

    // region Integration tests - sort with find and lambda

    @Test
    fun `full example - sort find by path descending`() {
        // The target example from the spec (simplified without parse_date)
        val result = execute(
            "[sort(find(), key: [i.path], order: descending)]",
            notes = testNotes
        )

        assertTrue(result is ListVal)
        val list = result as ListVal
        assertEquals(4, list.size)

        val paths = list.items.map { (it as NoteVal).note.path }
        assertEquals(listOf("tasks/inbox", "2026-01-16", "2026-01-15", "2026-01-10"), paths)
    }

    @Test
    fun `first combined with sort gets most recent`() {
        val result = execute(
            "[first(sort(find(), key: [i.path], order: descending))]",
            notes = testNotes
        )

        // "tasks/inbox" comes last alphabetically/descending
        assertTrue(result is NoteVal)
        assertEquals("tasks/inbox", (result as NoteVal).note.path)
    }

    // endregion

    // region list - Basic functionality

    @Test
    fun `list with no arguments creates empty list`() {
        val result = execute("[list()]")

        assertTrue(result is ListVal)
        assertTrue((result as ListVal).isEmpty())
    }

    @Test
    fun `list with numbers creates list of numbers`() {
        val result = execute("[list(1, 2, 3)]")

        assertTrue(result is ListVal)
        val list = result as ListVal
        assertEquals(3, list.size)
        assertEquals(1.0, (list[0] as NumberVal).value, 0.001)
        assertEquals(2.0, (list[1] as NumberVal).value, 0.001)
        assertEquals(3.0, (list[2] as NumberVal).value, 0.001)
    }

    @Test
    fun `list with strings creates list of strings`() {
        val result = execute("[list(\"a\", \"b\", \"c\")]")

        assertTrue(result is ListVal)
        val list = result as ListVal
        assertEquals(3, list.size)
        assertEquals("a", (list[0] as StringVal).value)
        assertEquals("b", (list[1] as StringVal).value)
        assertEquals("c", (list[2] as StringVal).value)
    }

    @Test
    fun `list with mixed types creates mixed list`() {
        val result = execute("[list(1, \"hello\", 3)]")

        assertTrue(result is ListVal)
        val list = result as ListVal
        assertEquals(3, list.size)
        assertTrue(list[0] is NumberVal)
        assertTrue(list[1] is StringVal)
        assertTrue(list[2] is NumberVal)
    }

    @Test
    fun `list with single item`() {
        val result = execute("[list(42)]")

        assertTrue(result is ListVal)
        val list = result as ListVal
        assertEquals(1, list.size)
        assertEquals(42.0, (list[0] as NumberVal).value, 0.001)
    }

    @Test
    fun `list is classified as static`() {
        assertFalse(org.alkaline.taskbrain.dsl.runtime.BuiltinRegistry.isDynamic("list"))
    }

    // endregion

    // region ascending/descending constants

    @Test
    fun `ascending constant returns string`() {
        val result = execute("[ascending]")

        assertTrue(result is StringVal)
        assertEquals("ascending", (result as StringVal).value)
    }

    @Test
    fun `descending constant returns string`() {
        val result = execute("[descending]")

        assertTrue(result is StringVal)
        assertEquals("descending", (result as StringVal).value)
    }

    @Test
    fun `ascending is classified as static`() {
        assertFalse(org.alkaline.taskbrain.dsl.runtime.BuiltinRegistry.isDynamic("ascending"))
    }

    @Test
    fun `descending is classified as static`() {
        assertFalse(org.alkaline.taskbrain.dsl.runtime.BuiltinRegistry.isDynamic("descending"))
    }

    // endregion

    // region sort with list() - Integration tests

    @Test
    fun `sort list of numbers ascending`() {
        val result = execute("[sort(list(3, 1, 4, 1, 5, 9, 2, 6))]")

        assertTrue(result is ListVal)
        val list = result as ListVal
        val values = list.items.map { (it as NumberVal).value }
        assertEquals(listOf(1.0, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 9.0), values)
    }

    @Test
    fun `sort list of numbers descending`() {
        val result = execute("[sort(list(3, 1, 4, 1, 5), order: descending)]")

        assertTrue(result is ListVal)
        val list = result as ListVal
        val values = list.items.map { (it as NumberVal).value }
        assertEquals(listOf(5.0, 4.0, 3.0, 1.0, 1.0), values)
    }

    @Test
    fun `sort list of strings`() {
        val result = execute("[sort(list(\"banana\", \"apple\", \"cherry\"))]")

        assertTrue(result is ListVal)
        val list = result as ListVal
        val values = list.items.map { (it as StringVal).value }
        assertEquals(listOf("apple", "banana", "cherry"), values)
    }

    @Test
    fun `first of sorted list`() {
        val result = execute("[first(sort(list(3, 1, 4, 1, 5)))]")

        assertTrue(result is NumberVal)
        assertEquals(1.0, (result as NumberVal).value, 0.001)
    }

    @Test
    fun `first of sorted list descending gets max`() {
        val result = execute("[first(sort(list(3, 1, 4, 1, 5), order: descending))]")

        assertTrue(result is NumberVal)
        assertEquals(5.0, (result as NumberVal).value, 0.001)
    }

    // endregion
}
