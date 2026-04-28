package org.alkaline.taskbrain.data

import com.google.firebase.Timestamp
import org.junit.Assert.*
import org.junit.Test
import java.util.Date

class NoteFilteringUtilsTest {

    private fun note(
        id: String,
        parentNoteId: String? = null,
        state: String? = null,
        updatedAt: Timestamp? = Timestamp(Date())
    ) = Note(id = id, parentNoteId = parentNoteId, state = state, updatedAt = updatedAt)

    @Test
    fun `filterTopLevelNotes excludes child notes`() {
        val notes = listOf(
            note("note_1"),
            note("note_2", parentNoteId = "parent_1"),
            note("note_3")
        )

        val filtered = NoteFilteringUtils.filterTopLevelNotes(notes)

        assertEquals(listOf("note_1", "note_3"), filtered.map { it.id })
    }

    @Test
    fun `filterTopLevelNotes excludes deleted notes`() {
        val notes = listOf(
            note("note_1"),
            note("note_2", state = "deleted"),
            note("note_3")
        )

        val filtered = NoteFilteringUtils.filterTopLevelNotes(notes)

        assertEquals(listOf("note_1", "note_3"), filtered.map { it.id })
    }

    @Test
    fun `filterTopLevelNotes excludes both children and deleted`() {
        val notes = listOf(
            note("note_1"),
            note("note_2", parentNoteId = "parent_1"),
            note("note_3", state = "deleted"),
            note("note_4")
        )

        val filtered = NoteFilteringUtils.filterTopLevelNotes(notes)

        assertEquals(listOf("note_1", "note_4"), filtered.map { it.id })
    }

    @Test
    fun `filterTopLevelNotes with empty list returns empty`() {
        assertTrue(NoteFilteringUtils.filterTopLevelNotes(emptyList()).isEmpty())
    }

    @Test
    fun `sortByUpdatedAtDescending sorts most recent first`() {
        val now = Date()
        val notes = listOf(
            note("oldest", updatedAt = Timestamp(Date(now.time - 10000))),
            note("middle", updatedAt = Timestamp(Date(now.time - 5000))),
            note("newest", updatedAt = Timestamp(now))
        )

        val sorted = NoteFilteringUtils.sortByUpdatedAtDescending(notes)

        assertEquals(listOf("newest", "middle", "oldest"), sorted.map { it.id })
    }

    @Test
    fun `sortByUpdatedAtDescending handles null timestamps`() {
        val notes = listOf(
            note("null_1", updatedAt = null),
            note("has_time", updatedAt = Timestamp(Date())),
            note("null_2", updatedAt = null)
        )

        val sorted = NoteFilteringUtils.sortByUpdatedAtDescending(notes)

        assertEquals("has_time", sorted[0].id)
    }

    @Test
    fun `filterAndSortNotes filters then sorts`() {
        val now = Date()
        val notes = listOf(
            note("oldest", updatedAt = Timestamp(Date(now.time - 10000))),
            note("child", parentNoteId = "parent", updatedAt = Timestamp(now)),
            note("deleted", state = "deleted", updatedAt = Timestamp(now)),
            note("newest", updatedAt = Timestamp(now)),
            note("middle", updatedAt = Timestamp(Date(now.time - 5000)))
        )

        val result = NoteFilteringUtils.filterAndSortNotes(notes)

        assertEquals(listOf("newest", "middle", "oldest"), result.map { it.id })
    }

    @Test
    fun `filterAndSortNotes returns empty when all filtered`() {
        val notes = listOf(
            note("child", parentNoteId = "parent_1"),
            note("deleted", state = "deleted")
        )

        assertTrue(NoteFilteringUtils.filterAndSortNotes(notes).isEmpty())
    }

    @Test
    fun `filterAndSortDeletedNotes includes deleted top-level notes`() {
        val now = Date()
        val notes = listOf(
            note("live", updatedAt = Timestamp(now)),
            note("gone", state = "deleted", updatedAt = Timestamp(now))
        )

        val filtered = NoteFilteringUtils.filterAndSortDeletedNotes(notes)

        assertEquals(listOf("gone"), filtered.map { it.id })
    }

    @Test
    fun `filterAndSortDeletedNotes excludes deleted child lines`() {
        // Removed child lines keep parentNoteId on soft-delete so the deleted-section
        // view doesn't surface them — only deleted parent notes should appear.
        val notes = listOf(
            note("parent_gone", state = "deleted"),
            note("child_gone", state = "deleted", parentNoteId = "some_parent")
        )

        val filtered = NoteFilteringUtils.filterAndSortDeletedNotes(notes)

        assertEquals(listOf("parent_gone"), filtered.map { it.id })
    }

    @Test
    fun `filterAndSortDeletedNotes sorts most recent first`() {
        val now = Date()
        val notes = listOf(
            note("older", state = "deleted", updatedAt = Timestamp(Date(now.time - 5000))),
            note("newer", state = "deleted", updatedAt = Timestamp(now))
        )

        val sorted = NoteFilteringUtils.filterAndSortDeletedNotes(notes)

        assertEquals(listOf("newer", "older"), sorted.map { it.id })
    }
}
