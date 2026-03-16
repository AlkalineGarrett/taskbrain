package org.alkaline.taskbrain.data

import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class NoteRepositoryTest {

    private lateinit var mockFirestore: FirebaseFirestore
    private lateinit var mockAuth: FirebaseAuth
    private lateinit var mockCollection: CollectionReference
    private lateinit var repository: NoteRepository

    @Before
    fun setUp() {
        mockFirestore = mockk(relaxed = true)
        mockAuth = mockk(relaxed = true)
        mockCollection = mockk(relaxed = true)

        every { mockAuth.currentUser } returns mockk { every { uid } returns USER_ID }
        every { mockFirestore.collection("notes") } returns mockCollection

        // Default: no tree-format descendants (old format tests)
        mockEmptyTreeQuery()

        repository = NoteRepository(mockFirestore, mockAuth)
    }

    private fun signOut() {
        every { mockAuth.currentUser } returns null
    }

    private fun mockDocument(noteId: String, note: Note?): DocumentReference {
        val ref = mockk<DocumentReference>()
        val snapshot = mockk<DocumentSnapshot>()
        every { mockCollection.document(noteId) } returns ref
        every { ref.get() } returns Tasks.forResult(snapshot)
        every { snapshot.exists() } returns (note != null)
        every { snapshot.toObject(Note::class.java) } returns note
        every { snapshot.get("containedNotes") } returns (note?.containedNotes ?: emptyList<String>())
        return ref
    }

    /** Mock the rootNoteId tree query to return empty (no tree-format descendants). */
    private fun mockEmptyTreeQuery() {
        val emptySnapshot = mockk<QuerySnapshot> {
            every { documents } returns emptyList()
            every { isEmpty } returns true
            every { iterator() } returns mutableListOf<QueryDocumentSnapshot>().iterator()
        }

        val rootQuery = mockk<Query> {
            every { whereEqualTo("userId", any()) } returns mockk<Query> {
                every { get() } returns Tasks.forResult(emptySnapshot)
            }
        }
        every { mockCollection.whereEqualTo("rootNoteId", any()) } returns rootQuery
    }

    /** Mock transaction that just executes the function. */
    private fun mockSimpleTransaction() {
        every { mockFirestore.runTransaction<Map<Int, String>>(any()) } answers {
            val function = firstArg<Transaction.Function<Map<Int, String>>>()
            val transaction = mockk<Transaction>(relaxed = true)
            Tasks.forResult(function.apply(transaction))
        }
    }

    /** Mock batch operations. */
    private fun mockBatch(): WriteBatch {
        val batch = mockk<WriteBatch>(relaxed = true) {
            every { commit() } returns Tasks.forResult(null)
        }
        every { mockFirestore.batch() } returns batch
        return batch
    }

    // region Auth Tests

    @Test
    fun `all methods should fail when user is not signed in`() = runTest {
        signOut()

        val results = listOf(
            repository.loadNoteWithChildren("note_1"),
            repository.saveNoteWithChildren("note_1", listOf(NoteLine("Content", "note_1"))),
            repository.loadUserNotes(),
            repository.createNote(),
            repository.createMultiLineNote("Line 1\nLine 2")
        )

        results.forEach { result ->
            assertTrue(result.isFailure)
            assertEquals("User not signed in", result.exceptionOrNull()?.message)
        }
    }

    // endregion

    // region Load Tests

    @Test
    fun `loadNoteWithChildren returns empty line when document does not exist`() = runTest {
        mockDocument("note_1", null)

        val lines = repository.loadNoteWithChildren("note_1").getOrThrow()

        assertEquals(listOf(NoteLine("", "note_1")), lines)
    }

    @Test
    fun `loadNoteWithChildren does not add trailing empty line when note is single empty line`() = runTest {
        mockDocument("note_1", Note(content = "", containedNotes = emptyList()))

        val lines = repository.loadNoteWithChildren("note_1").getOrThrow()

        assertEquals(1, lines.size)
        assertEquals("", lines[0].content)
        assertEquals("note_1", lines[0].noteId)
    }

    @Test
    fun `loadNoteWithChildren returns parent content with trailing empty line`() = runTest {
        mockDocument("note_1", Note(content = "Parent content", containedNotes = emptyList()))

        val lines = repository.loadNoteWithChildren("note_1").getOrThrow()

        assertEquals(2, lines.size)
        assertEquals("Parent content", lines[0].content)
        assertEquals("note_1", lines[0].noteId)
        assertEquals("", lines[1].content)
        assertNull(lines[1].noteId)
    }

    @Test
    fun `loadNoteWithChildren returns parent and children in order - old format`() = runTest {
        mockDocument("parent", Note(content = "Parent", containedNotes = listOf("child_1", "child_2")))
        mockDocument("child_1", Note(content = "Child 1"))
        mockDocument("child_2", Note(content = "Child 2"))

        val lines = repository.loadNoteWithChildren("parent").getOrThrow()

        assertEquals(
            listOf(
                NoteLine("Parent", "parent"),
                NoteLine("Child 1", "child_1"),
                NoteLine("Child 2", "child_2"),
                NoteLine("", null)
            ),
            lines
        )
    }

    @Test
    fun `loadNoteWithChildren treats empty child IDs as spacers - old format`() = runTest {
        mockDocument("parent", Note(content = "Parent", containedNotes = listOf("", "child_1", "")))
        mockDocument("child_1", Note(content = "Child"))

        val lines = repository.loadNoteWithChildren("parent").getOrThrow()

        assertEquals(5, lines.size)
        assertEquals("", lines[1].content)
        assertNull(lines[1].noteId)
        assertEquals("Child", lines[2].content)
        assertEquals("", lines[3].content)
        assertEquals("", lines[4].content)
        assertNull(lines[4].noteId)
    }

    @Test
    fun `loadUserNotes filters out children and deleted notes`() = runTest {
        val mockQuery = mockk<Query>()
        val docs = listOf(
            mockk<QueryDocumentSnapshot> {
                every { id } returns "note_1"
                every { toObject(Note::class.java) } returns Note(id = "note_1")
            },
            mockk<QueryDocumentSnapshot> {
                every { id } returns "note_2"
                every { toObject(Note::class.java) } returns Note(id = "note_2", state = "deleted")
            },
            mockk<QueryDocumentSnapshot> {
                every { id } returns "note_3"
                every { toObject(Note::class.java) } returns Note(id = "note_3", parentNoteId = "parent")
            }
        )
        every { mockCollection.whereEqualTo("userId", USER_ID) } returns mockQuery
        every { mockQuery.get() } returns Tasks.forResult(mockk {
            every { iterator() } returns docs.toMutableList().iterator()
        })

        val notes = repository.loadUserNotes().getOrThrow()

        assertEquals(1, notes.size)
        assertEquals("note_1", notes[0].id)
    }

    @Test
    fun `loadNotesWithFullContent reconstructs content from children - old format`() = runTest {
        val mockQuery = mockk<Query>()
        // Include parent AND children in the query result (all user notes)
        val docs = listOf(
            mockk<QueryDocumentSnapshot> {
                every { id } returns "parent_note"
                every { toObject(Note::class.java) } returns Note(
                    id = "parent_note",
                    content = "First line",
                    containedNotes = listOf("child_1", "child_2")
                )
            },
            mockk<QueryDocumentSnapshot> {
                every { id } returns "child_1"
                every { toObject(Note::class.java) } returns Note(
                    id = "child_1",
                    content = "Second line",
                    parentNoteId = "parent_note"
                )
            },
            mockk<QueryDocumentSnapshot> {
                every { id } returns "child_2"
                every { toObject(Note::class.java) } returns Note(
                    id = "child_2",
                    content = "Third line",
                    parentNoteId = "parent_note"
                )
            }
        )
        every { mockCollection.whereEqualTo("userId", USER_ID) } returns mockQuery
        every { mockQuery.get() } returns Tasks.forResult(mockk {
            every { iterator() } returns docs.toMutableList().iterator()
        })

        val notes = repository.loadNotesWithFullContent().getOrThrow()

        assertEquals(1, notes.size)
        assertEquals("parent_note", notes[0].id)
        assertEquals("First line\nSecond line\nThird line", notes[0].content)
    }

    @Test
    fun `loadNotesWithFullContent returns notes without children as-is`() = runTest {
        val mockQuery = mockk<Query>()
        val docs = listOf(
            mockk<QueryDocumentSnapshot> {
                every { id } returns "simple_note"
                every { toObject(Note::class.java) } returns Note(
                    id = "simple_note",
                    content = "Single line content",
                    containedNotes = emptyList()
                )
            }
        )
        every { mockCollection.whereEqualTo("userId", USER_ID) } returns mockQuery
        every { mockQuery.get() } returns Tasks.forResult(mockk {
            every { iterator() } returns docs.toMutableList().iterator()
        })

        val notes = repository.loadNotesWithFullContent().getOrThrow()

        assertEquals(1, notes.size)
        assertEquals("simple_note", notes[0].id)
        assertEquals("Single line content", notes[0].content)
    }

    // endregion

    // region Save Tests

    @Test
    fun `saveNoteWithChildren saves parent content`() = runTest {
        mockDocument("note_1", null)
        mockSimpleTransaction()

        val result = repository.saveNoteWithChildren("note_1", listOf(NoteLine("Content", "note_1")))

        assertTrue(result.isSuccess)
        verify { mockFirestore.runTransaction<Map<Int, String>>(any()) }
    }

    @Test
    fun `saveNoteWithChildren creates new child notes and returns their IDs`() = runTest {
        mockDocument("note_1", null)
        val childRef = mockk<DocumentReference> { every { id } returns "new_child" }
        every { mockCollection.document() } returns childRef
        mockSimpleTransaction()

        val result = repository.saveNoteWithChildren(
            "note_1",
            listOf(NoteLine("Parent", "note_1"), NoteLine("\tNew child", null))
        ).getOrThrow()

        assertEquals("new_child", result[1])
    }

    @Test
    fun `saveNoteWithChildren updates existing child notes`() = runTest {
        mockDocument("note_1", Note(containedNotes = listOf("child_1")))
        mockDocument("child_1", null)
        mockSimpleTransaction()

        val result = repository.saveNoteWithChildren(
            "note_1",
            listOf(NoteLine("Parent", "note_1"), NoteLine("\tUpdated", "child_1"))
        )

        assertTrue(result.isSuccess)
    }

    @Test
    fun `saveNoteWithChildren soft-deletes removed children`() = runTest {
        mockDocument("note_1", Note(containedNotes = listOf("old_child")))
        mockDocument("old_child", null)
        mockSimpleTransaction()

        val result = repository.saveNoteWithChildren(
            "note_1",
            listOf(NoteLine("Parent only", "note_1"))
        )

        assertTrue(result.isSuccess)
    }

    @Test
    fun `saveNoteWithChildren drops trailing empty lines`() = runTest {
        mockDocument("note_1", null)
        val childRef = mockk<DocumentReference> { every { id } returns "child_1" }
        every { mockCollection.document() } returns childRef
        mockSimpleTransaction()

        val result = repository.saveNoteWithChildren(
            "note_1",
            listOf(
                NoteLine("Parent", "note_1"),
                NoteLine("\tChild content", null),
                NoteLine("", null)  // Trailing empty line should be dropped
            )
        ).getOrThrow()

        assertEquals(1, result.size)
        assertEquals("child_1", result[1])
    }

    @Test
    fun `saveNoteWithChildren drops multiple trailing empty lines`() = runTest {
        mockDocument("note_1", null)
        val childRef = mockk<DocumentReference> { every { id } returns "child_1" }
        every { mockCollection.document() } returns childRef
        mockSimpleTransaction()

        val result = repository.saveNoteWithChildren(
            "note_1",
            listOf(
                NoteLine("Parent", "note_1"),
                NoteLine("\tChild", null),
                NoteLine("", null),
                NoteLine("", null),
                NoteLine("", null)
            )
        ).getOrThrow()

        assertEquals(1, result.size)
        assertEquals("child_1", result[1])
    }

    @Test
    fun `saveNoteWithChildren preserves empty lines in the middle`() = runTest {
        mockDocument("note_1", null)
        val childRef = mockk<DocumentReference> { every { id } returns "child_1" }
        every { mockCollection.document() } returns childRef
        mockSimpleTransaction()

        val result = repository.saveNoteWithChildren(
            "note_1",
            listOf(
                NoteLine("Parent", "note_1"),
                NoteLine("\t", null),  // Spacer in middle — preserved
                NoteLine("\tChild", null),
                NoteLine("", null)   // Trailing empty — dropped
            )
        ).getOrThrow()

        assertEquals(1, result.size)
        assertEquals("child_1", result[2])  // Index 2 because of the spacer
    }

    @Test
    fun `saveNoteWithChildren treats whitespace-only lines as content`() = runTest {
        mockDocument("note_1", null)
        val childRef = mockk<DocumentReference> { every { id } returns "new_child" }
        every { mockCollection.document() } returns childRef
        mockSimpleTransaction()

        val result = repository.saveNoteWithChildren(
            "note_1",
            listOf(
                NoteLine("Parent", "note_1"),
                NoteLine("\t   ", null)  // Tab + whitespace — whitespace is content
            )
        ).getOrThrow()

        assertEquals("new_child", result[1])
    }

    // endregion

    // region Create Tests

    @Test
    fun `createNote returns new note ID`() = runTest {
        val newRef = mockk<DocumentReference> { every { id } returns "new_note_id" }
        every { mockCollection.add(any<Map<String, Any?>>()) } returns Tasks.forResult(newRef)

        val noteId = repository.createNote().getOrThrow()

        assertEquals("new_note_id", noteId)
    }

    @Test
    fun `createMultiLineNote creates parent with children`() = runTest {
        val refs = listOf("parent_id", "child_1", "child_2").map { id ->
            mockk<DocumentReference> { every { this@mockk.id } returns id }
        }
        every { mockCollection.document() } returnsMany refs
        val batch = mockBatch()

        val parentId = repository.createMultiLineNote("Line 1\nLine 2\nLine 3").getOrThrow()

        assertEquals("parent_id", parentId)
        verify(exactly = 3) { batch.set(any(), any<Map<String, Any?>>()) }
    }

    @Test
    fun `createMultiLineNote treats blank lines as spacers`() = runTest {
        val refs = listOf("parent_id", "child_id").map { id ->
            mockk<DocumentReference> { every { this@mockk.id } returns id }
        }
        every { mockCollection.document() } returnsMany refs
        val batch = mockBatch()

        repository.createMultiLineNote("Line 1\n\nLine 3").getOrThrow()

        verify(exactly = 2) { batch.set(any(), any<Map<String, Any?>>()) }
    }

    @Test
    fun `createMultiLineNote treats whitespace-only lines as content, not spacers`() = runTest {
        val refs = listOf("parent_id", "child_1", "child_2").map { id ->
            mockk<DocumentReference> { every { this@mockk.id } returns id }
        }
        every { mockCollection.document() } returnsMany refs
        val batch = mockBatch()

        repository.createMultiLineNote("Line 1\n   \nLine 3").getOrThrow()

        verify(exactly = 3) { batch.set(any(), any<Map<String, Any?>>()) }
    }

    // endregion

    // region Tree-Format Load Tests

    /**
     * Helper to mock the rootNoteId tree query to return actual descendants.
     * Call AFTER mockEmptyTreeQuery to override the default.
     */
    private fun mockTreeQuery(rootId: String, descendants: List<Pair<String, Note>>) {
        val docSnapshots = descendants.map { (id, note) ->
            mockk<QueryDocumentSnapshot> {
                every { this@mockk.id } returns id
                every { toObject(Note::class.java) } returns note
            }
        }
        val querySnapshot = mockk<QuerySnapshot> {
            every { documents } returns docSnapshots.map { it as DocumentSnapshot }
            every { isEmpty } returns docSnapshots.isEmpty()
            every { iterator() } returns docSnapshots.toMutableList().iterator()
        }
        val rootQuery = mockk<Query> {
            every { whereEqualTo("userId", any()) } returns mockk<Query> {
                every { get() } returns Tasks.forResult(querySnapshot)
            }
        }
        every { mockCollection.whereEqualTo("rootNoteId", rootId) } returns rootQuery
    }

    @Test
    fun `loadNoteWithChildren loads flat children via tree query - new format`() = runTest {
        mockDocument("root", Note(
            id = "root",
            content = "Root",
            containedNotes = listOf("c1", "c2")
        ))
        mockTreeQuery("root", listOf(
            "c1" to Note(id = "c1", content = "Child 1", containedNotes = emptyList(), rootNoteId = "root"),
            "c2" to Note(id = "c2", content = "Child 2", containedNotes = emptyList(), rootNoteId = "root"),
        ))

        val lines = repository.loadNoteWithChildren("root").getOrThrow()

        assertEquals(4, lines.size)
        assertEquals(NoteLine("Root", "root"), lines[0])
        assertEquals(NoteLine("Child 1", "c1"), lines[1])
        assertEquals(NoteLine("Child 2", "c2"), lines[2])
        assertEquals(NoteLine("", null), lines[3])
    }

    @Test
    fun `loadNoteWithChildren loads nested tree - new format`() = runTest {
        mockDocument("root", Note(
            id = "root",
            content = "Root",
            containedNotes = listOf("a")
        ))
        mockTreeQuery("root", listOf(
            "a" to Note(id = "a", content = "A", containedNotes = listOf("b"), rootNoteId = "root"),
            "b" to Note(id = "b", content = "B", containedNotes = emptyList(), rootNoteId = "root"),
        ))

        val lines = repository.loadNoteWithChildren("root").getOrThrow()

        assertEquals(4, lines.size)
        assertEquals(NoteLine("Root", "root"), lines[0])
        assertEquals(NoteLine("A", "a"), lines[1])
        assertEquals(NoteLine("\tB", "b"), lines[2])
        assertEquals(NoteLine("", null), lines[3])
    }

    @Test
    fun `loadNoteWithChildren filters deleted descendants in tree query`() = runTest {
        mockDocument("root", Note(
            id = "root",
            content = "Root",
            containedNotes = listOf("c1", "c2")
        ))
        mockTreeQuery("root", listOf(
            "c1" to Note(id = "c1", content = "Alive", containedNotes = emptyList(), rootNoteId = "root"),
            "c2" to Note(id = "c2", content = "Dead", containedNotes = emptyList(), rootNoteId = "root", state = "deleted"),
        ))

        val lines = repository.loadNoteWithChildren("root").getOrThrow()

        // Only c1 should appear (c2 is deleted)
        assertEquals(3, lines.size)
        assertEquals(NoteLine("Root", "root"), lines[0])
        assertEquals(NoteLine("Alive", "c1"), lines[1])
        assertEquals(NoteLine("", null), lines[2])
    }

    @Test
    fun `loadNotesWithFullContent reconstructs content from tree descendants - new format`() = runTest {
        val mockQuery = mockk<Query>()
        val docs = listOf(
            mockk<QueryDocumentSnapshot> {
                every { id } returns "root"
                every { toObject(Note::class.java) } returns Note(
                    id = "root",
                    content = "Root",
                    containedNotes = listOf("a")
                )
            },
            mockk<QueryDocumentSnapshot> {
                every { id } returns "a"
                every { toObject(Note::class.java) } returns Note(
                    id = "a",
                    content = "A",
                    containedNotes = listOf("b"),
                    parentNoteId = "root",
                    rootNoteId = "root"
                )
            },
            mockk<QueryDocumentSnapshot> {
                every { id } returns "b"
                every { toObject(Note::class.java) } returns Note(
                    id = "b",
                    content = "B",
                    containedNotes = emptyList(),
                    parentNoteId = "a",
                    rootNoteId = "root"
                )
            }
        )
        every { mockCollection.whereEqualTo("userId", USER_ID) } returns mockQuery
        every { mockQuery.get() } returns Tasks.forResult(mockk {
            every { iterator() } returns docs.toMutableList().iterator()
        })

        val notes = repository.loadNotesWithFullContent().getOrThrow()

        assertEquals(1, notes.size)
        assertEquals("root", notes[0].id)
        assertEquals("Root\nA\n\tB", notes[0].content)
    }

    // endregion

    // region Nested Save Tests

    @Test
    fun `saveNoteWithChildren saves nested tree structure`() = runTest {
        mockDocument("root", null)
        val refs = listOf("child_a", "child_b").map { id ->
            mockk<DocumentReference> { every { this@mockk.id } returns id }
        }
        var refIndex = 0
        every { mockCollection.document() } answers { refs[refIndex++] }
        mockSimpleTransaction()

        val result = repository.saveNoteWithChildren(
            "root",
            listOf(
                NoteLine("Root", "root"),
                NoteLine("\tA", null),
                NoteLine("\t\tB", null),
            )
        ).getOrThrow()

        assertEquals("child_a", result[1])
        assertEquals("child_b", result[2])
    }

    @Test
    fun `saveNoteWithChildren returns empty map for empty lines`() = runTest {
        val result = repository.saveNoteWithChildren("note_1", emptyList()).getOrThrow()

        assertEquals(emptyMap<Int, String>(), result)
    }

    // endregion

    // region Delete/Restore Tests

    @Test
    fun `softDeleteNote deletes root and new-format descendants`() = runTest {
        mockTreeQuery("note_1", listOf(
            "child_1" to Note(id = "child_1", rootNoteId = "note_1"),
            "child_2" to Note(id = "child_2", rootNoteId = "note_1"),
        ))
        val batch = mockBatch()

        repository.softDeleteNote("note_1").getOrThrow()

        // Should update 3 docs: root + 2 children
        verify(exactly = 3) { batch.update(any(), any<Map<String, Any?>>()) }
    }

    @Test
    fun `softDeleteNote deletes root and old-format children`() = runTest {
        // Empty tree query (no new-format descendants)
        mockEmptyTreeQuery()
        mockDocument("note_1", Note(containedNotes = listOf("old_1", "", "old_2")))
        val batch = mockBatch()

        repository.softDeleteNote("note_1").getOrThrow()

        // Should update 3 docs: root + 2 non-empty children (empty string is spacer)
        verify(exactly = 3) { batch.update(any(), any<Map<String, Any?>>()) }
    }

    @Test
    fun `softDeleteNote deletes only root when no descendants`() = runTest {
        mockEmptyTreeQuery()
        mockDocument("note_1", Note(containedNotes = emptyList()))
        val batch = mockBatch()

        repository.softDeleteNote("note_1").getOrThrow()

        verify(exactly = 1) { batch.update(any(), any<Map<String, Any?>>()) }
    }

    @Test
    fun `undeleteNote restores root and new-format descendants`() = runTest {
        mockTreeQuery("note_1", listOf(
            "child_1" to Note(id = "child_1", rootNoteId = "note_1", state = "deleted"),
            "child_2" to Note(id = "child_2", rootNoteId = "note_1", state = "deleted"),
        ))
        val batch = mockBatch()

        repository.undeleteNote("note_1").getOrThrow()

        verify(exactly = 3) { batch.update(any(), any<Map<String, Any?>>()) }
    }

    @Test
    fun `undeleteNote restores root and old-format children`() = runTest {
        mockEmptyTreeQuery()
        mockDocument("note_1", Note(containedNotes = listOf("old_1", "old_2")))
        val batch = mockBatch()

        repository.undeleteNote("note_1").getOrThrow()

        verify(exactly = 3) { batch.update(any(), any<Map<String, Any?>>()) }
    }

    // endregion

    companion object {
        private const val USER_ID = "test_user_id"
    }
}
