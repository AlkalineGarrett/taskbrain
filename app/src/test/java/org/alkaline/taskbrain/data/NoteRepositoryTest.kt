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

        // Default: no descendants
        mockEmptyTreeQuery()
        mockkObject(NoteStore)
        every { NoteStore.getDescendantIds(any()) } returns emptySet()
        every { NoteStore.getAllDescendantIds(any()) } returns emptySet()
        every { NoteStore.getLiveDescendantsByParent(any()) } returns emptyMap()
        every { NoteStore.getNoteById(any()) } returns null
        every { NoteStore.raiseWarning(any()) } just Runs

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

        val lines = repository.loadNoteWithChildren("note_1").getOrThrow().lines

        assertEquals(listOf(NoteLine("", "note_1")), lines)
    }

    @Test
    fun `loadNoteWithChildren does not add trailing empty line when note is single empty line`() = runTest {
        mockDocument("note_1", Note(content = "", containedNotes = emptyList()))

        val lines = repository.loadNoteWithChildren("note_1").getOrThrow().lines

        assertEquals(1, lines.size)
        assertEquals("", lines[0].content)
        assertEquals("note_1", lines[0].noteId)
    }

    @Test
    fun `loadNoteWithChildren returns just parent content when no children`() = runTest {
        mockDocument("note_1", Note(content = "Parent content", containedNotes = emptyList()))

        val lines = repository.loadNoteWithChildren("note_1").getOrThrow().lines

        assertEquals(1, lines.size)
        assertEquals("Parent content", lines[0].content)
        assertEquals("note_1", lines[0].noteId)
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
    fun `loadNotesWithFullContent reconstructs content from descendants`() = runTest {
        val mockQuery = mockk<Query>()
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
                    parentNoteId = "parent_note",
                    rootNoteId = "parent_note"
                )
            },
            mockk<QueryDocumentSnapshot> {
                every { id } returns "child_2"
                every { toObject(Note::class.java) } returns Note(
                    id = "child_2",
                    content = "Third line",
                    parentNoteId = "parent_note",
                    rootNoteId = "parent_note"
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
        val batch = mockBatch()

        val result = repository.saveNoteWithChildren("note_1", listOf(NoteLine("Content", "note_1")))

        assertTrue(result.isSuccess)
        verify { batch.set(any(), any<Map<String, Any?>>(), any<SetOptions>()) }
    }

    @Test
    fun `saveNoteWithChildren creates new child notes and returns their IDs`() = runTest {
        mockDocument("note_1", null)
        val childRef = mockk<DocumentReference> { every { id } returns "new_child" }
        every { mockCollection.document() } returns childRef
        mockBatch()

        val result = repository.saveNoteWithChildren(
            "note_1",
            listOf(NoteLine("Parent", "note_1"), NoteLine("\tNew child", null))
        ).getOrThrow()

        assertEquals("new_child", result[1])
    }

    @Test
    fun `saveNoteWithChildren raises user warning when null noteIds were recovered`() = runTest {
        // A bare null noteId reaching save means an upstream editor path wiped
        // a real id. The reconciliation recovers the data from rawNotes, but
        // the user should still see a warning so the underlying bug isn't
        // masked by the silent recovery.
        mockDocument("note_1", Note(id = "note_1", containedNotes = listOf("c1")))
        mockDocument("c1", null)
        val existingChild = Note(id = "c1", content = "• Item A", parentNoteId = "note_1", rootNoteId = "note_1")
        every { NoteStore.getDescendantIds("note_1") } returns setOf("c1")
        every { NoteStore.getRawNoteById("c1") } returns existingChild
        every { NoteStore.getLiveDescendantsByParent("note_1") } returns
            mapOf("note_1" to ArrayDeque(listOf(existingChild)))
        mockBatch()

        repository.saveNoteWithChildren(
            "note_1",
            listOf(NoteLine("Parent", "note_1"), NoteLine("• Item A", null)),
        ).getOrThrow()

        // Warning must reach the user via NoteStore.raiseWarning (which the VM
        // observes and routes to the save-warning dialog).
        verify(atLeast = 1) { NoteStore.raiseWarning(match { it.contains("line ID") }) }
    }

    @Test
    fun `saveNoteWithChildren does NOT raise warning when only sentinel noteIds were recovered`() = runTest {
        // Sentinels are expected placeholders from paste / split / etc. Matching
        // a sentinel to an existing doc via content is normal (cut+paste within
        // the same note) and should be silent — no dialog to the user.
        mockDocument("note_1", Note(id = "note_1", containedNotes = listOf("c1")))
        mockDocument("c1", null)
        val existingChild = Note(id = "c1", content = "• Item A", parentNoteId = "note_1", rootNoteId = "note_1")
        every { NoteStore.getDescendantIds("note_1") } returns setOf("c1")
        every { NoteStore.getRawNoteById("c1") } returns existingChild
        every { NoteStore.getLiveDescendantsByParent("note_1") } returns
            mapOf("note_1" to ArrayDeque(listOf(existingChild)))
        mockBatch()

        val sentinel = NoteIdSentinel.new(NoteIdSentinel.Origin.PASTE)
        repository.saveNoteWithChildren(
            "note_1",
            listOf(NoteLine("Parent", "note_1"), NoteLine("• Item A", sentinel)),
        ).getOrThrow()

        verify(exactly = 0) { NoteStore.raiseWarning(any()) }
    }

    @Test
    fun `saveNoteWithChildren strips sentinel noteIds and allocates fresh docs`() = runTest {
        // Sentinels are placeholders from creation paths (paste, split, etc.).
        // The save must treat them as "allocate fresh", not try to use them
        // as real Firestore doc ids.
        mockDocument("note_1", null)
        val childRef = mockk<DocumentReference> { every { id } returns "fresh_id" }
        every { mockCollection.document() } returns childRef
        val batch = mockBatch()

        val sentinel = NoteIdSentinel.new(NoteIdSentinel.Origin.PASTE)
        val result = repository.saveNoteWithChildren(
            "note_1",
            listOf(NoteLine("Parent", "note_1"), NoteLine("\tPasted line", sentinel))
        ).getOrThrow()

        assertEquals("fresh Firestore id replaces the sentinel", "fresh_id", result[1])

        // Sentinel lines must be written via the CREATE path (non-merge) with
        // userId; otherwise Firestore rejects as PERMISSION_DENIED.
        val createdPayload = slot<Map<String, Any?>>()
        verify { batch.set(childRef, capture(createdPayload)) }
        assertEquals(USER_ID, createdPayload.captured["userId"])
        assertEquals("Pasted line", createdPayload.captured["content"])
        verify(exactly = 0) { batch.set(childRef, any(), any<SetOptions>()) }
    }

    @Test
    fun `saveNoteWithChildren updates existing child notes`() = runTest {
        mockDocument("note_1", Note(containedNotes = listOf("child_1")))
        mockDocument("child_1", null)
        mockBatch()

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
        mockBatch()

        val result = repository.saveNoteWithChildren(
            "note_1",
            listOf(NoteLine("Parent only", "note_1"))
        )

        assertTrue(result.isSuccess)
    }

    @Test
    fun `saveNoteWithChildren persists trailing empty lines as docs`() = runTest {
        mockDocument("note_1", null)
        val refs = listOf("child_1", "trailing").map { id ->
            mockk<DocumentReference> { every { this@mockk.id } returns id }
        }
        every { mockCollection.document() } returnsMany refs
        mockBatch()

        val result = repository.saveNoteWithChildren(
            "note_1",
            listOf(
                NoteLine("Parent", "note_1"),
                NoteLine("\tChild content", null),
                NoteLine("", null),
            )
        ).getOrThrow()

        assertEquals(2, result.size)
        assertEquals("child_1", result[1])
        assertEquals("trailing", result[2])
    }

    @Test
    fun `saveNoteWithChildren recovers null noteIds by parent+content against rawNotes`() = runTest {
        // Reproduces the observed bug where the editor's tracked lines have lost
        // noteIds for items that still exist in Firestore with matching content.
        // Without reconciliation the save would allocate fresh docs and the
        // content-drop guard would abort.
        mockDocument("note_1", Note(id = "note_1", containedNotes = listOf("decided", "undecided")))
        mockDocument("decided", null)
        mockDocument("undecided", null)
        mockDocument("dining", null)
        val decided = Note(id = "decided", content = "• Decided", parentNoteId = "note_1", rootNoteId = "note_1")
        val undecided = Note(id = "undecided", content = "• Undecided", parentNoteId = "note_1", rootNoteId = "note_1")
        val dining = Note(id = "dining", content = "• Dining table", parentNoteId = "decided", rootNoteId = "note_1")
        every { NoteStore.getDescendantIds("note_1") } returns setOf("decided", "undecided", "dining")
        every { NoteStore.getRawNoteById("decided") } returns decided
        every { NoteStore.getRawNoteById("undecided") } returns undecided
        every { NoteStore.getRawNoteById("dining") } returns dining
        every { NoteStore.getLiveDescendantsByParent("note_1") } returns mapOf(
            "note_1" to ArrayDeque(listOf(decided, undecided)),
            "decided" to ArrayDeque(listOf(dining)),
        )
        mockBatch()

        val result = repository.saveNoteWithChildren(
            "note_1",
            listOf(
                NoteLine("Parent", "note_1"),
                NoteLine("• Decided", null),       // was cUa99-style; lost its id
                NoteLine("\t• Dining table", null), // was aE5e-style; lost its id
                NoteLine("• Undecided", null),     // was wvKV-style; lost its id
            )
        )

        // Reconciliation should have matched the three lines against existing
        // descendants — no orphan deletes, save succeeds.
        assertTrue("save should succeed after reconciliation, got $result", result.isSuccess)
    }

    @Test
    fun `saveNoteWithChildren persists multiple trailing empty lines as docs`() = runTest {
        mockDocument("note_1", null)
        val refs = listOf("child_1", "t1", "t2", "t3").map { id ->
            mockk<DocumentReference> { every { this@mockk.id } returns id }
        }
        every { mockCollection.document() } returnsMany refs
        mockBatch()

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

        assertEquals(4, result.size)
        assertEquals("child_1", result[1])
        assertEquals("t1", result[2])
        assertEquals("t2", result[3])
        assertEquals("t3", result[4])
    }

    @Test
    fun `saveNoteWithChildren preserves empty lines in the middle`() = runTest {
        mockDocument("note_1", null)
        // One ref per new line (mid-spacer + child + trailing).
        val refs = listOf("middle", "child_1", "trailing").map { id ->
            mockk<DocumentReference> { every { this@mockk.id } returns id }
        }
        every { mockCollection.document() } returnsMany refs
        mockBatch()

        val result = repository.saveNoteWithChildren(
            "note_1",
            listOf(
                NoteLine("Parent", "note_1"),
                NoteLine("\t", null),
                NoteLine("\tChild", null),
                NoteLine("", null),
            )
        ).getOrThrow()

        assertEquals(3, result.size)
        assertEquals("middle", result[1])
        assertEquals("child_1", result[2])
        assertEquals("trailing", result[3])
    }

    @Test
    fun `saveNoteWithChildren treats whitespace-only lines as content`() = runTest {
        mockDocument("note_1", null)
        val childRef = mockk<DocumentReference> { every { id } returns "new_child" }
        every { mockCollection.document() } returns childRef
        mockBatch()

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
    fun `createMultiLineNote allocates a doc for blank lines`() = runTest {
        val refs = listOf("parent_id", "blank_id", "child_id").map { id ->
            mockk<DocumentReference> { every { this@mockk.id } returns id }
        }
        every { mockCollection.document() } returnsMany refs
        val batch = mockBatch()

        repository.createMultiLineNote("Line 1\n\nLine 3").getOrThrow()

        // parent + blank-line doc + Line 3 child
        verify(exactly = 3) { batch.set(any(), any<Map<String, Any?>>()) }
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
            "c1" to Note(id = "c1", content = "Child 1", containedNotes = emptyList(), parentNoteId = "root", rootNoteId = "root"),
            "c2" to Note(id = "c2", content = "Child 2", containedNotes = emptyList(), parentNoteId = "root", rootNoteId = "root"),
        ))

        val lines = repository.loadNoteWithChildren("root").getOrThrow().lines

        // No more auto-appended trailing empty — the persisted shape is what
        // comes back.
        assertEquals(3, lines.size)
        assertEquals(NoteLine("Root", "root"), lines[0])
        assertEquals(NoteLine("Child 1", "c1"), lines[1])
        assertEquals(NoteLine("Child 2", "c2"), lines[2])
    }

    @Test
    fun `loadNoteWithChildren loads nested tree - new format`() = runTest {
        mockDocument("root", Note(
            id = "root",
            content = "Root",
            containedNotes = listOf("a")
        ))
        mockTreeQuery("root", listOf(
            "a" to Note(id = "a", content = "A", containedNotes = listOf("b"), parentNoteId = "root", rootNoteId = "root"),
            "b" to Note(id = "b", content = "B", containedNotes = emptyList(), parentNoteId = "a", rootNoteId = "root"),
        ))

        val lines = repository.loadNoteWithChildren("root").getOrThrow().lines

        assertEquals(3, lines.size)
        assertEquals(NoteLine("Root", "root"), lines[0])
        assertEquals(NoteLine("A", "a"), lines[1])
        assertEquals(NoteLine("\tB", "b"), lines[2])
    }

    @Test
    fun `loadNoteWithChildren filters deleted descendants in tree query`() = runTest {
        mockDocument("root", Note(
            id = "root",
            content = "Root",
            containedNotes = listOf("c1", "c2")
        ))
        mockTreeQuery("root", listOf(
            "c1" to Note(id = "c1", content = "Alive", containedNotes = emptyList(), parentNoteId = "root", rootNoteId = "root"),
            "c2" to Note(id = "c2", content = "Dead", containedNotes = emptyList(), parentNoteId = "root", rootNoteId = "root", state = "deleted"),
        ))

        val lines = repository.loadNoteWithChildren("root").getOrThrow().lines

        assertEquals(2, lines.size)
        assertEquals(NoteLine("Root", "root"), lines[0])
        assertEquals(NoteLine("Alive", "c1"), lines[1])
    }

    @Test
    fun `loadNoteWithChildren drops orphan refs and appends strays`() = runTest {
        // Partial-sync / corrupted-data scenario: root's containedNotes names a
        // child that the descendants query didn't return, and a different child
        // is linked by parentNoteId but missing from containedNotes.
        mockDocument("root", Note(
            id = "root",
            content = "Root",
            containedNotes = listOf("c1", "missing")
        ))
        mockTreeQuery("root", listOf(
            "c1" to Note(id = "c1", content = "Declared", parentNoteId = "root", rootNoteId = "root"),
            "s" to Note(id = "s", content = "Stray", parentNoteId = "root", rootNoteId = "root"),
        ))

        val lines = repository.loadNoteWithChildren("root").getOrThrow().lines

        assertEquals(listOf("Root", "Declared", "Stray"), lines.map { it.content })
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
        mockBatch()

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

    @Test
    fun `saveNoteWithChildren uses multiple batches for many children`() = runTest {
        mockDocument("root", null)
        // Create 501 child lines (exceeds 500-op batch limit when combined with root write)
        val childRefs = (1..501).map { i ->
            mockk<DocumentReference> { every { id } returns "child_$i" }
        }
        var refIndex = 0
        every { mockCollection.document() } answers { childRefs[refIndex++] }
        val batch = mockBatch()

        val lines = mutableListOf(NoteLine("Root", "root"))
        for (i in 1..501) {
            lines.add(NoteLine("\tChild $i", null))
        }

        val result = repository.saveNoteWithChildren("root", lines).getOrThrow()

        assertEquals(501, result.size)
        // Should commit multiple batches (502 ops: 1 root + 501 children)
        verify(atLeast = 2) { batch.commit() }
    }

    // endregion

    // region Delete/Restore Tests

    @Test
    fun `softDeleteNote deletes root and descendants`() = runTest {
        every { NoteStore.getDescendantIds("note_1") } returns setOf("child_1", "child_2")
        val batch = mockBatch()

        repository.softDeleteNote("note_1").getOrThrow()

        // Should write 3 docs: root + 2 children
        verify(exactly = 3) { batch.set(any(), any<Map<String, Any?>>(), any<SetOptions>()) }
    }

    @Test
    fun `softDeleteNote deletes only root when no descendants`() = runTest {
        every { NoteStore.getDescendantIds("note_1") } returns emptySet()
        val batch = mockBatch()

        repository.softDeleteNote("note_1").getOrThrow()

        verify(exactly = 1) { batch.set(any(), any<Map<String, Any?>>(), any<SetOptions>()) }
    }

    @Test
    fun `undeleteNote restores root and descendants including deleted`() = runTest {
        every { NoteStore.getAllDescendantIds("note_1") } returns setOf("child_1", "child_2")
        val batch = mockBatch()

        repository.undeleteNote("note_1").getOrThrow()

        verify(exactly = 3) { batch.set(any(), any<Map<String, Any?>>(), any<SetOptions>()) }
    }

    // endregion

    companion object {
        private const val USER_ID = "test_user_id"
    }
}
