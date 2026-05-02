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
        every { NoteStore.getRawNoteById(any()) } returns null
        every { NoteStore.raiseWarning(any()) } just Runs
        // Save/delete ops require NoteStore to be loaded; default to loaded for
        // tests that aren't exercising the load guard. Tests for the guard
        // itself override this.
        every { NoteStore.isLoaded() } returns true

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
            repository.saveNoteWithChildren("note_1", listOf(NoteLine("Content", "note_1")), extraOpsBuilder = null, localBase = null),
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
    fun `loadNoteWithChildren prefers NoteStore when loaded and has the note`() = runTest {
        val storeNote = Note(
            id = "note_1",
            content = "Cached parent",
            containedNotes = emptyList(),
            showCompleted = false,
        )
        every { NoteStore.getRawNoteById("note_1") } returns storeNote
        every { NoteStore.getNoteLinesById("note_1") } returns listOf(
            NoteLine("Cached parent", "note_1"),
            NoteLine("Cached child", "child_1"),
        )

        val result = repository.loadNoteWithChildren("note_1").getOrThrow()

        assertEquals(2, result.lines.size)
        assertEquals("Cached parent", result.lines[0].content)
        assertEquals("Cached child", result.lines[1].content)
        assertFalse(result.isDeleted)
        assertFalse(result.showCompleted)
        // No Firestore document fetch should have been issued
        verify(exactly = 0) { mockCollection.document("note_1") }
    }

    @Test
    fun `loadNoteWithChildren falls back to Firestore when NoteStore not loaded`() = runTest {
        every { NoteStore.isLoaded() } returns false
        mockDocument("note_1", Note(content = "From Firestore", containedNotes = emptyList()))

        val lines = repository.loadNoteWithChildren("note_1").getOrThrow().lines

        assertEquals(1, lines.size)
        assertEquals("From Firestore", lines[0].content)
    }

    @Test
    fun `loadNoteWithChildren falls back to Firestore when NoteStore loaded but missing the note`() = runTest {
        every { NoteStore.getRawNoteById("note_1") } returns null
        mockDocument("note_1", Note(content = "From Firestore", containedNotes = emptyList()))

        val lines = repository.loadNoteWithChildren("note_1").getOrThrow().lines

        assertEquals(1, lines.size)
        assertEquals("From Firestore", lines[0].content)
    }

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

        val result = repository.saveNoteWithChildren("note_1", listOf(NoteLine("Content", "note_1")), extraOpsBuilder = null, localBase = null)

        assertTrue(result.isSuccess)
        verify { batch.set(any(), any<Map<String, Any?>>(), any<SetOptions>()) }
    }

    @Test
    fun `saveNoteWithChildren stamps lastWriterOpId and version on every write`() = runTest {
        // Echo-suppression contract: every doc the save writes carries the
        // same clientOpId so the listener can drop the server echo, plus a
        // version bumped from the in-memory note's version (or 1 for new docs).
        val rootNote = Note(id = "note_1", content = "Old", containedNotes = listOf("c1"), version = 7L)
        val existingChild = Note(
            id = "c1", content = "old child",
            parentNoteId = "note_1", rootNoteId = "note_1", version = 3L,
        )
        every { NoteStore.getNoteById("note_1") } returns rootNote
        every { NoteStore.getRawNoteById("note_1") } returns rootNote
        every { NoteStore.getRawNoteById("c1") } returns existingChild
        every { NoteStore.getDescendantIds("note_1") } returns setOf("c1")
        mockDocument("note_1", rootNote)
        mockDocument("c1", existingChild)
        val newChildRef = mockk<DocumentReference> { every { id } returns "new_id" }
        every { mockCollection.document() } returns newChildRef
        val batch = mockBatch()
        val captured = mutableListOf<Map<String, Any?>>()
        every { batch.set(any(), capture(captured), any<SetOptions>()) } returns batch
        every { batch.set(any(), capture(captured)) } returns batch

        repository.saveNoteWithChildren(
            "note_1",
            listOf(
                NoteLine("New root content", "note_1"),
                NoteLine("\tedited", "c1"),
                NoteLine("\tbrand new", null),
            ),
            extraOpsBuilder = null, localBase = null,
        ).getOrThrow()

        // Every write must carry a non-null lastWriterOpId, all the same.
        val opIds = captured.map { it["lastWriterOpId"] }
        assertTrue("expected non-empty opIds, got $opIds", opIds.isNotEmpty())
        assertTrue("opIds must be non-null: $opIds", opIds.all { it is String && (it as String).isNotEmpty() })
        assertTrue("opIds must all match: $opIds", opIds.toSet().size == 1)

        // Version bumps: root 7→8, edited child 3→4, fresh child=1.
        val rootWrite = captured.first { it["content"] == "New root content" }
        assertEquals(8L, rootWrite["version"])
        val editedWrite = captured.first { it["content"] == "edited" }
        assertEquals(4L, editedWrite["version"])
        val freshWrite = captured.first { it["content"] == "brand new" }
        assertEquals(1L, freshWrite["version"])
    }

    @Test
    fun `saveNoteWithChildren creates new child notes and returns their IDs`() = runTest {
        mockDocument("note_1", null)
        val childRef = mockk<DocumentReference> { every { id } returns "new_child" }
        every { mockCollection.document() } returns childRef
        mockBatch()

        val result = repository.saveNoteWithChildren(
            "note_1",
            listOf(NoteLine("Parent", "note_1"), NoteLine("\tNew child", null)),
            extraOpsBuilder = null, localBase = null,
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
            extraOpsBuilder = null, localBase = null,
        ).getOrThrow()

        // Warning must reach the user via NoteStore.raiseWarning (which the VM
        // observes and routes to the save-warning dialog).
        verify(atLeast = 1) { NoteStore.raiseWarning(match { it.contains("line ID") }) }
    }

    @Test
    fun `saveNoteWithChildren does not alias sentinel to an existing line with identical content`() = runTest {
        // A sentinel marks a brand-new line (typed/pasted/split). Even when its
        // content happens to match an existing sibling under the same parent,
        // the save must allocate a FRESH doc — never alias to the existing id.
        // Identity is preserved through proper line tracking, not through
        // content matching at save time. (Cut/paste identity preservation is
        // handled separately via the cut-delete state in Phase 8.)
        mockDocument("note_1", Note(id = "note_1", containedNotes = listOf("c1")))
        mockDocument("c1", null)
        val existingChild = Note(id = "c1", content = "• Item A", parentNoteId = "note_1", rootNoteId = "note_1")
        every { NoteStore.getDescendantIds("note_1") } returns setOf("c1")
        every { NoteStore.getRawNoteById("c1") } returns existingChild
        every { NoteStore.getLiveDescendantsByParent("note_1") } returns
            mapOf("note_1" to ArrayDeque(listOf(existingChild)))
        val freshRef = mockk<DocumentReference> { every { id } returns "fresh_id" }
        every { mockCollection.document() } returns freshRef
        mockBatch()

        val sentinel = NoteIdSentinel.new(NoteIdSentinel.Origin.PASTE)
        val assigned = repository.saveNoteWithChildren(
            "note_1",
            listOf(NoteLine("Parent", "note_1"), NoteLine("• Item A", sentinel)),
            extraOpsBuilder = null, localBase = null,
        ).getOrThrow()

        assertEquals("sentinel allocates a fresh doc, never aliases to c1", "fresh_id", assigned[1])
        // No null id was ever present, so no user-facing warning is raised.
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
            listOf(NoteLine("Parent", "note_1"), NoteLine("\tPasted line", sentinel)),
            extraOpsBuilder = null, localBase = null,
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
            listOf(NoteLine("Parent", "note_1"), NoteLine("\tUpdated", "child_1")),
            extraOpsBuilder = null, localBase = null,
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
            listOf(NoteLine("Parent only", "note_1")),
            extraOpsBuilder = null, localBase = null,
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
            ),
            extraOpsBuilder = null, localBase = null,
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
            ),
            extraOpsBuilder = null, localBase = null,
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
            ),
            extraOpsBuilder = null, localBase = null,
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
            ),
            extraOpsBuilder = null, localBase = null,
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
            ),
            extraOpsBuilder = null, localBase = null,
        ).getOrThrow()

        assertEquals("new_child", result[1])
    }

    // region Save diff/skip Tests
    //
    // Verifies saveNoteWithChildren elides merge writes for lines whose
    // computed payload matches the in-memory NoteStore copy.

    /**
     * Wires both the Firestore doc-ref mocks and the NoteStore lookups the
     * save path consults: [rootNote] is returned for the root id and each
     * child id resolves to its [Note] in NoteStore. Pass the same shape the
     * production save would see in steady state.
     */
    private fun setupStore(rootNote: Note, vararg children: Note) {
        mockDocument(rootNote.id, rootNote)
        children.forEach { mockDocument(it.id, null) }
        every { NoteStore.getDescendantIds(rootNote.id) } returns children.map { it.id }.toSet()
        every { NoteStore.getRawNoteById(rootNote.id) } returns rootNote
        children.forEach { child ->
            every { NoteStore.getRawNoteById(child.id) } returns child
        }
    }

    @Test
    fun `saveNoteWithChildren skips all merge writes when nothing changed`() = runTest {
        val rootNote = Note(id = "note_1", content = "Parent", containedNotes = listOf("c1"))
        val unchangedChild = Note(
            id = "c1",
            content = "Untouched",
            parentNoteId = "note_1",
            rootNoteId = "note_1",
        )
        setupStore(rootNote, unchangedChild)
        val batch = mockBatch()

        val result = repository.saveNoteWithChildren(
            "note_1",
            listOf(NoteLine("Parent", "note_1"), NoteLine("\tUntouched", "c1")),
            extraOpsBuilder = null, localBase = null,
        )

        assertTrue(result.isSuccess)
        verify(exactly = 0) { batch.set(any(), any<Map<String, Any?>>(), any<SetOptions>()) }
    }

    @Test
    fun `saveNoteWithChildren writes only the changed child when one of many is edited`() = runTest {
        val rootNote = Note(id = "note_1", content = "Parent", containedNotes = listOf("c1", "c2", "c3"))
        val c1 = Note(id = "c1", content = "First", parentNoteId = "note_1", rootNoteId = "note_1")
        val c2 = Note(id = "c2", content = "Second", parentNoteId = "note_1", rootNoteId = "note_1")
        val c3 = Note(id = "c3", content = "Third", parentNoteId = "note_1", rootNoteId = "note_1")
        setupStore(rootNote, c1, c2, c3)
        val batch = mockBatch()

        repository.saveNoteWithChildren(
            "note_1",
            listOf(
                NoteLine("Parent", "note_1"),
                NoteLine("\tFirst", "c1"),
                NoteLine("\tSecond EDITED", "c2"),
                NoteLine("\tThird", "c3"),
            ),
            extraOpsBuilder = null, localBase = null,
        ).getOrThrow()

        // Root: containedNotes still [c1,c2,c3] → skip. c1, c3: unchanged →
        // skip. c2: content differs → exactly one merge write.
        verify(exactly = 1) { batch.set(any(), any<Map<String, Any?>>(), any<SetOptions>()) }
    }

    @Test
    fun `saveNoteWithChildren writes root when content changes but skips unchanged child`() = runTest {
        val rootNote = Note(id = "note_1", content = "Old parent", containedNotes = listOf("c1"))
        val c1 = Note(id = "c1", content = "Untouched", parentNoteId = "note_1", rootNoteId = "note_1")
        setupStore(rootNote, c1)
        val batch = mockBatch()

        repository.saveNoteWithChildren(
            "note_1",
            listOf(NoteLine("New parent", "note_1"), NoteLine("\tUntouched", "c1")),
            extraOpsBuilder = null, localBase = null,
        ).getOrThrow()

        verify(exactly = 1) { batch.set(any(), any<Map<String, Any?>>(), any<SetOptions>()) }
    }

    @Test
    fun `saveNoteWithChildren writes root when containedNotes order changes`() = runTest {
        // Reordering siblings flips the parent's containedNotes — root must
        // write even when neither child's content changed.
        val rootNote = Note(id = "note_1", content = "Parent", containedNotes = listOf("a", "b"))
        val a = Note(id = "a", content = "A", parentNoteId = "note_1", rootNoteId = "note_1")
        val b = Note(id = "b", content = "B", parentNoteId = "note_1", rootNoteId = "note_1")
        setupStore(rootNote, a, b)
        val batch = mockBatch()

        repository.saveNoteWithChildren(
            "note_1",
            listOf(
                NoteLine("Parent", "note_1"),
                NoteLine("\tB", "b"),
                NoteLine("\tA", "a"),
            ),
            extraOpsBuilder = null, localBase = null,
        ).getOrThrow()

        // Root: containedNotes flipped → write. a, b: unchanged → skip.
        verify(exactly = 1) { batch.set(any(), any<Map<String, Any?>>(), any<SetOptions>()) }
    }

    @Test
    fun `saveNoteWithChildren writes parent and child when child is reparented`() = runTest {
        // Indenting B under A flips the root's containedNotes (drops B), A's
        // containedNotes (gains B), and B's parentNoteId. All three must
        // write; nothing else exists to skip.
        val rootNote = Note(id = "note_1", content = "Parent", containedNotes = listOf("a", "b"))
        val a = Note(id = "a", content = "A", parentNoteId = "note_1", rootNoteId = "note_1")
        val b = Note(id = "b", content = "B", parentNoteId = "note_1", rootNoteId = "note_1")
        setupStore(rootNote, a, b)
        val batch = mockBatch()

        repository.saveNoteWithChildren(
            "note_1",
            listOf(
                NoteLine("Parent", "note_1"),
                NoteLine("\tA", "a"),
                NoteLine("\t\tB", "b"),
            ),
            extraOpsBuilder = null, localBase = null,
        ).getOrThrow()

        verify(exactly = 3) { batch.set(any(), any<Map<String, Any?>>(), any<SetOptions>()) }
    }

    @Test
    fun `saveNoteWithChildren writes child whose existing state is non-null`() = runTest {
        // A child whose existing doc carries state (e.g., a soft-deleted line
        // being restored) must always be written so the merge can reset state
        // to null. Without this guard the doc would stay marked deleted.
        val rootNote = Note(id = "note_1", content = "Parent", containedNotes = listOf("c1"))
        val deletedChild = Note(
            id = "c1", content = "Same", parentNoteId = "note_1", rootNoteId = "note_1",
            state = "deleted",
        )
        setupStore(rootNote, deletedChild)
        val batch = mockBatch()

        repository.saveNoteWithChildren(
            "note_1",
            listOf(NoteLine("Parent", "note_1"), NoteLine("\tSame", "c1")),
            extraOpsBuilder = null, localBase = null,
        ).getOrThrow()

        verify(exactly = 1) { batch.set(any(), any<Map<String, Any?>>(), any<SetOptions>()) }
    }

    @Test
    fun `saveNoteWithChildren writes child whose existing parentNoteId differs`() = runTest {
        // Existing doc claims a different parent than the editor places it
        // under — must write to reconcile.
        val rootNote = Note(id = "note_1", content = "Parent", containedNotes = listOf("c1"))
        val orphan = Note(
            id = "c1", content = "Same", parentNoteId = "stranger", rootNoteId = "note_1",
        )
        setupStore(rootNote, orphan)
        val batch = mockBatch()

        repository.saveNoteWithChildren(
            "note_1",
            listOf(NoteLine("Parent", "note_1"), NoteLine("\tSame", "c1")),
            extraOpsBuilder = null, localBase = null,
        ).getOrThrow()

        verify(exactly = 1) { batch.set(any(), any<Map<String, Any?>>(), any<SetOptions>()) }
    }

    @Test
    fun `saveNoteWithChildren soft-deletes removed child without clearing parent refs`() = runTest {
        // The deleted-section view distinguishes deleted parent notes (no
        // parentNoteId) from removed child lines (parentNoteId set). Clearing
        // those fields here would make the child indistinguishable from a
        // top-level deletion and surface it incorrectly.
        val rootNote = Note(id = "note_1", content = "Parent", containedNotes = listOf("c1"))
        val child = Note(
            id = "c1", content = "Doomed", parentNoteId = "note_1", rootNoteId = "note_1",
        )
        setupStore(rootNote, child)
        val batch = mockBatch()
        val childRef = mockCollection.document("c1")

        repository.saveNoteWithChildren(
            "note_1",
            listOf(NoteLine("Parent", "note_1")),
            extraOpsBuilder = null, localBase = null,
        ).getOrThrow()

        val deletePayload = slot<Map<String, Any?>>()
        verify { batch.set(childRef, capture(deletePayload), any<SetOptions>()) }
        assertEquals("deleted", deletePayload.captured["state"])
        assertFalse(
            "parentNoteId must not be cleared on soft-delete",
            deletePayload.captured.containsKey("parentNoteId"),
        )
        assertFalse(
            "rootNoteId must not be cleared on soft-delete",
            deletePayload.captured.containsKey("rootNoteId"),
        )
    }

    // endregion

    // endregion

    // region 3-way containedNotes merge (Phase 4)

    @Test
    fun `saveNoteWithChildren integrates concurrent root-level addition into containedNotes`() = runTest {
        // Editor saw [c1] (localBase). Other client added c2 since. We add c3.
        // Save lands [c1, c3, c2] — local then remote-only at end.
        val root = Note(id = "note_1", containedNotes = listOf("c1", "c2"))
        val c1 = Note(id = "c1", content = "first", parentNoteId = "note_1", rootNoteId = "note_1")
        val c2 = Note(id = "c2", content = "concurrent", parentNoteId = "note_1", rootNoteId = "note_1")
        every { NoteStore.getNoteById("note_1") } returns root
        every { NoteStore.getRawNoteById("note_1") } returns root
        every { NoteStore.getRawNoteById("c1") } returns c1
        every { NoteStore.getRawNoteById("c2") } returns c2
        every { NoteStore.getDescendantIds("note_1") } returns setOf("c1", "c2")
        mockDocument("note_1", root)
        mockDocument("c1", c1)
        mockDocument("c2", c2)
        val newRef = mockk<DocumentReference> { every { id } returns "c3" }
        every { mockCollection.document() } returns newRef
        val batch = mockBatch()
        val captured = mutableListOf<Map<String, Any?>>()
        every { batch.set(any(), capture(captured), any<SetOptions>()) } returns batch
        every { batch.set(any(), capture(captured)) } returns batch

        repository.saveNoteWithChildren(
            "note_1",
            listOf(
                NoteLine("parent", "note_1"),
                NoteLine("\tfirst", "c1"),
                NoteLine("\tour-add", null),
            ),
            extraOpsBuilder = null,
            localBase = listOf("c1"),
        ).getOrThrow()

        val rootWrite = captured.first { it["content"] == "parent" }
        assertEquals(listOf("c1", "c3", "c2"), rootWrite["containedNotes"])
        assertEquals(listOf("c1"), rootWrite["containedNotesBase"])
    }

    @Test
    fun `saveNoteWithChildren does not soft-delete a concurrently-added subtree`() = runTest {
        // Other client added c2 + c2a since localBase=[c1]. Our save must not
        // soft-delete them just because they aren't in our trackedLines.
        val root = Note(id = "note_1", containedNotes = listOf("c1", "c2"))
        val c1 = Note(id = "c1", content = "first", parentNoteId = "note_1", rootNoteId = "note_1")
        val c2 = Note(
            id = "c2", content = "concurrent root",
            parentNoteId = "note_1", rootNoteId = "note_1",
            containedNotes = listOf("c2a"),
        )
        val c2a = Note(
            id = "c2a", content = "concurrent child",
            parentNoteId = "c2", rootNoteId = "note_1",
        )
        every { NoteStore.getNoteById("note_1") } returns root
        every { NoteStore.getRawNoteById("note_1") } returns root
        every { NoteStore.getRawNoteById("c1") } returns c1
        every { NoteStore.getRawNoteById("c2") } returns c2
        every { NoteStore.getRawNoteById("c2a") } returns c2a
        every { NoteStore.getDescendantIds("note_1") } returns setOf("c1", "c2", "c2a")
        mockDocument("note_1", root)
        mockDocument("c1", c1)
        mockDocument("c2", c2)
        mockDocument("c2a", c2a)
        val batch = mockBatch()
        val captured = mutableListOf<Map<String, Any?>>()
        every { batch.set(any(), capture(captured), any<SetOptions>()) } returns batch
        every { batch.set(any(), capture(captured)) } returns batch

        repository.saveNoteWithChildren(
            "note_1",
            listOf(NoteLine("parent", "note_1"), NoteLine("\tfirst", "c1")),
            extraOpsBuilder = null,
            localBase = listOf("c1"),
        ).getOrThrow()

        val deletedContents = captured.filter { it["state"] == "deleted" }
        // Soft-delete writes don't carry "content"; they only carry state +
        // updatedAt + lastWriterOpId + version. There should be ZERO of them.
        assertEquals("expected no soft-deletes, got: $deletedContents", 0, deletedContents.size)
    }

    @Test
    fun `saveNoteWithChildren respects a concurrent remote removal`() = runTest {
        // Editor saw [c1, c2]. Other client removed c1 (remote = [c2]).
        // Our save lands [c2] — remote removal honored even though local
        // still passed c1 in trackedLines.
        val root = Note(id = "note_1", containedNotes = listOf("c2"))
        val c2 = Note(id = "c2", content = "kept", parentNoteId = "note_1", rootNoteId = "note_1")
        every { NoteStore.getNoteById("note_1") } returns root
        every { NoteStore.getRawNoteById("note_1") } returns root
        every { NoteStore.getRawNoteById("c2") } returns c2
        every { NoteStore.getDescendantIds("note_1") } returns setOf("c2")
        mockDocument("note_1", root)
        mockDocument("c2", c2)
        val newRef = mockk<DocumentReference> { every { id } returns "stale_c1" }
        every { mockCollection.document() } returns newRef
        val batch = mockBatch()
        val captured = mutableListOf<Map<String, Any?>>()
        every { batch.set(any(), capture(captured), any<SetOptions>()) } returns batch
        every { batch.set(any(), capture(captured)) } returns batch

        repository.saveNoteWithChildren(
            "note_1",
            listOf(
                NoteLine("parent", "note_1"),
                NoteLine("\tc1-stale", "c1"),
                NoteLine("\tkept", "c2"),
            ),
            extraOpsBuilder = null,
            localBase = listOf("c1", "c2"),
        ).getOrThrow()

        val rootWrite = captured.first { it["content"] == "parent" }
        assertEquals(listOf("c2"), rootWrite["containedNotes"])
    }

    // endregion

    // region Multi-note atomic batched save (Phase 2)

    @Test
    fun `saveMultipleNotes returns empty result for empty input without committing`() = runTest {
        val batch = mockBatch()

        val result = repository.saveMultipleNotes(emptyList()).getOrThrow()

        assertTrue(result.isEmpty())
        verify(exactly = 0) { batch.commit() }
    }

    @Test
    fun `saveMultipleNotes combines writes from multiple notes into a single batch commit`() = runTest {
        val r1 = Note(id = "r1", content = "Old r1", containedNotes = listOf("c1"))
        val c1 = Note(id = "c1", content = "a", parentNoteId = "r1", rootNoteId = "r1")
        val r2 = Note(id = "r2", content = "Old r2", containedNotes = listOf("c2"))
        val c2 = Note(id = "c2", content = "b", parentNoteId = "r2", rootNoteId = "r2")
        every { NoteStore.getRawNoteById("r1") } returns r1
        every { NoteStore.getRawNoteById("c1") } returns c1
        every { NoteStore.getRawNoteById("r2") } returns r2
        every { NoteStore.getRawNoteById("c2") } returns c2
        every { NoteStore.getNoteById("r1") } returns r1
        every { NoteStore.getNoteById("r2") } returns r2
        every { NoteStore.getDescendantIds("r1") } returns setOf("c1")
        every { NoteStore.getDescendantIds("r2") } returns setOf("c2")
        mockDocument("r1", r1)
        mockDocument("r2", r2)
        mockDocument("c1", c1)
        mockDocument("c2", c2)
        val batch = mockBatch()

        val result = repository.saveMultipleNotes(
            listOf(
                NoteRepository.SaveItem("r1", listOf(NoteLine("New r1", "r1"), NoteLine("\ta", "c1")), null),
                NoteRepository.SaveItem("r2", listOf(NoteLine("New r2", "r2"), NoteLine("\tb", "c2")), null),
            ),
        ).getOrThrow()

        assertEquals(2, result.size)
        assertTrue(result.containsKey("r1"))
        assertTrue(result.containsKey("r2"))
        // One commit covers both notes' rewrites.
        verify(exactly = 1) { batch.commit() }
    }

    @Test
    fun `saveMultipleNotes rolls back all notes when one item trips the content-drop guard`() = runTest {
        // r1 has 4 declared children, save sends only 1 → trips guard.
        // r2 is benign. The whole batch must abort before commit.
        val r1 = Note(id = "r1", content = "Old r1", containedNotes = listOf("a", "b", "c", "d"))
        val a = Note(id = "a", content = "A", parentNoteId = "r1", rootNoteId = "r1")
        val b = Note(id = "b", content = "B", parentNoteId = "r1", rootNoteId = "r1")
        val c = Note(id = "c", content = "C", parentNoteId = "r1", rootNoteId = "r1")
        val d = Note(id = "d", content = "D", parentNoteId = "r1", rootNoteId = "r1")
        val r2 = Note(id = "r2", content = "Old r2", containedNotes = emptyList())
        every { NoteStore.getRawNoteById("r1") } returns r1
        every { NoteStore.getNoteById("r1") } returns r1
        every { NoteStore.getRawNoteById("r2") } returns r2
        every { NoteStore.getNoteById("r2") } returns r2
        every { NoteStore.getDescendantIds("r1") } returns setOf("a", "b", "c", "d")
        every { NoteStore.getDescendantIds("r2") } returns emptySet()
        every { NoteStore.getRawNoteById("a") } returns a
        every { NoteStore.getRawNoteById("b") } returns b
        every { NoteStore.getRawNoteById("c") } returns c
        every { NoteStore.getRawNoteById("d") } returns d
        mockDocument("r1", r1)
        mockDocument("r2", r2)
        mockDocument("a", a)
        mockDocument("b", b)
        mockDocument("c", c)
        mockDocument("d", d)
        val batch = mockBatch()

        val result = repository.saveMultipleNotes(
            listOf(
                NoteRepository.SaveItem("r1", listOf(NoteLine("r1", "r1"), NoteLine("\tA", "a")), null),
                NoteRepository.SaveItem("r2", listOf(NoteLine("New r2", "r2")), null),
            ),
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is ContentDropAbortException)
        verify(exactly = 0) { batch.commit() }
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
            ),
            extraOpsBuilder = null, localBase = null,
        ).getOrThrow()

        assertEquals("child_a", result[1])
        assertEquals("child_b", result[2])
    }

    @Test
    fun `saveNoteWithChildren returns empty map for empty lines`() = runTest {
        val result = repository.saveNoteWithChildren("note_1", emptyList(), extraOpsBuilder = null, localBase = null).getOrThrow()

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

        val result = repository.saveNoteWithChildren("root", lines, extraOpsBuilder = null, localBase = null).getOrThrow()

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

    // region NoteStore-loaded invariant guard

    @Test
    fun `saveNoteWithChildren fails with NoteStoreNotLoadedException when NoteStore not loaded`() = runTest {
        every { NoteStore.isLoaded() } returns false
        val result = repository.saveNoteWithChildren(
            "note_1",
            listOf(NoteLine("Hello", "note_1")),
            extraOpsBuilder = null, localBase = null,
        )
        val ex = result.exceptionOrNull()
        assertTrue(ex is NoteStore.NoteStoreNotLoadedException)
        assertEquals("saveNoteWithChildren", (ex as NoteStore.NoteStoreNotLoadedException).operation)
        assertEquals("note_1", ex.noteId)
    }

    @Test
    fun `softDeleteNote fails with NoteStoreNotLoadedException when NoteStore not loaded`() = runTest {
        every { NoteStore.isLoaded() } returns false
        val result = repository.softDeleteNote("note_1")
        val ex = result.exceptionOrNull()
        assertTrue(ex is NoteStore.NoteStoreNotLoadedException)
        assertEquals("softDeleteNote", (ex as NoteStore.NoteStoreNotLoadedException).operation)
    }

    @Test
    fun `undeleteNote fails with NoteStoreNotLoadedException when NoteStore not loaded`() = runTest {
        every { NoteStore.isLoaded() } returns false
        val result = repository.undeleteNote("note_1")
        val ex = result.exceptionOrNull()
        assertTrue(ex is NoteStore.NoteStoreNotLoadedException)
        assertEquals("undeleteNote", (ex as NoteStore.NoteStoreNotLoadedException).operation)
    }

    @Test
    fun `saveNoteWithFullContent fails with NoteStoreNotLoadedException when NoteStore not loaded`() = runTest {
        every { NoteStore.isLoaded() } returns false
        val result = repository.saveNoteWithFullContent("note_1", "Hello")
        val ex = result.exceptionOrNull()
        assertTrue(ex is NoteStore.NoteStoreNotLoadedException)
        assertEquals("saveNoteWithFullContent", (ex as NoteStore.NoteStoreNotLoadedException).operation)
    }

    @Test
    fun `NoteStoreNotLoadedException carries a stack trace pointing at NoteRepository`() = runTest {
        every { NoteStore.isLoaded() } returns false
        val ex = repository.softDeleteNote("note_1").exceptionOrNull()
        assertNotNull(ex)
        val stack = ex!!.stackTraceToString()
        assertTrue(
            "Expected stack to mention NoteRepository, got: $stack",
            stack.contains("NoteRepository"),
        )
    }

    // endregion

    companion object {
        private const val USER_ID = "test_user_id"
    }
}
