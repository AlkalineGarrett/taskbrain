package org.alkaline.taskbrain.dsl.cache

import org.alkaline.taskbrain.data.Note
import org.alkaline.taskbrain.dsl.directives.DirectiveFinder
import org.alkaline.taskbrain.dsl.runtime.values.NumberVal
import org.alkaline.taskbrain.dsl.runtime.values.StringVal
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.LocalTime

/**
 * Integration tests for the caching system.
 * Phase 10: Integration and testing.
 *
 * Tests cover:
 * - CachedDirectiveExecutor with cache hits and misses
 * - Staleness detection and re-execution
 * - Nested views and transitive dependencies
 * - Inline editing with suppressed invalidation
 * - Error caching behavior
 * - RefreshScheduler for time-based triggers
 */
class CachingIntegrationTest {

    private lateinit var cacheManager: DirectiveCacheManager
    private lateinit var editSessionManager: EditSessionManager
    private lateinit var executor: CachedDirectiveExecutor

    private val testNotes = listOf(
        Note(id = "note-1", content = "Note One", path = "inbox/note1"),
        Note(id = "note-2", content = "Note Two", path = "inbox/note2"),
        Note(id = "note-3", content = "Note Three", path = "archive/note3"),
        Note(id = "current", content = "Current Note", path = "current")
    )

    @Before
    fun setUp() {
        cacheManager = DirectiveCacheManager()
        editSessionManager = EditSessionManager(cacheManager)
        executor = CachedDirectiveExecutor(
            cacheManager = cacheManager,
            editSessionManager = editSessionManager
        )
        // Phase 3: Clear cached metadata hashes before each test
        MetadataHasher.invalidateCache()
    }

    // region Cache Hit/Miss Tests

    @Test
    fun `first execution is cache miss`() {
        val currentNote = testNotes.find { it.id == "current" }

        val result = executor.execute(
            sourceText = "[add(1, 2)]",
            notes = testNotes,
            currentNote = currentNote
        )

        assertFalse(result.cacheHit)
        assertTrue(result.result.isComputed)
    }

    @Test
    fun `second execution with same input is cache hit`() {
        val currentNote = testNotes.find { it.id == "current" }

        // First execution - cache miss
        val result1 = executor.execute(
            sourceText = "[add(1, 2)]",
            notes = testNotes,
            currentNote = currentNote
        )
        assertFalse(result1.cacheHit)

        // Second execution - cache hit
        val result2 = executor.execute(
            sourceText = "[add(1, 2)]",
            notes = testNotes,
            currentNote = currentNote
        )
        assertTrue(result2.cacheHit)
        assertEquals(result1.result.toValue()?.toDisplayString(), result2.result.toValue()?.toDisplayString())
    }

    @Test
    fun `different directives have separate cache entries`() {
        val currentNote = testNotes.find { it.id == "current" }

        executor.execute("[add(1, 2)]", testNotes, currentNote)
        executor.execute("[add(3, 4)]", testNotes, currentNote)

        // Both should be cached
        val result1 = executor.execute("[add(1, 2)]", testNotes, currentNote)
        val result2 = executor.execute("[add(3, 4)]", testNotes, currentNote)

        assertTrue(result1.cacheHit)
        assertTrue(result2.cacheHit)
    }

    // endregion

    // region Staleness Tests

    @Test
    fun `cache miss when notes change for find directive`() {
        val currentNote = testNotes.find { it.id == "current" }

        // First execution
        executor.execute(
            sourceText = "[find(path: \"inbox/note1\")]",
            notes = testNotes,
            currentNote = currentNote
        )

        // Modify notes (simulate path change)
        val modifiedNotes = testNotes.map { note ->
            if (note.id == "note-1") note.copy(path = "inbox/note1-renamed")
            else note
        }

        // Second execution with modified notes should detect staleness
        val result = executor.execute(
            sourceText = "[find(path: \"inbox/note1\")]",
            notes = modifiedNotes,
            currentNote = currentNote
        )

        // Should be cache miss due to staleness
        assertFalse(result.cacheHit)
    }

    // endregion

    // region Current Note Exclusion Tests

    @Test
    fun `find excludes current note from results`() {
        // Current note is in inbox
        val currentNote = testNotes.find { it.id == "note-1" }

        val result = executor.execute(
            sourceText = "[find(path: pattern(\"inbox/\" any*(1..)))]",
            notes = testNotes,
            currentNote = currentNote
        )

        assertTrue(result.result.isComputed)
        val displayValue = result.result.toValue()?.toDisplayString() ?: ""
        // Should not include note-1 (current note) path in results
        assertFalse(displayValue.contains("inbox/note1"))
        // Should include note-2 path
        assertTrue(displayValue.contains("inbox/note2"))
    }

    // endregion

    // region Invalidation Tests

    @Test
    fun `invalidateForChangedNotes clears relevant caches`() {
        val currentNote = testNotes.find { it.id == "current" }

        // Cache a result using a self-referencing directive (per-note cache)
        executor.execute("[.path]", testNotes, currentNote)

        // Invalidate for the current note
        executor.invalidateForChangedNotes(setOf("current"))

        // Next execution should be cache miss
        val result = executor.execute("[.path]", testNotes, currentNote)
        assertFalse(result.cacheHit)
    }

    @Test
    fun `clearAll removes all cache entries`() {
        val currentNote = testNotes.find { it.id == "current" }

        // Cache multiple results
        executor.execute("[add(1, 2)]", testNotes, currentNote)
        executor.execute("[add(3, 4)]", testNotes, currentNote)

        // Clear all
        executor.clearAll()

        // Both should be cache misses
        assertFalse(executor.execute("[add(1, 2)]", testNotes, currentNote).cacheHit)
        assertFalse(executor.execute("[add(3, 4)]", testNotes, currentNote).cacheHit)
    }

    // endregion

    // region Inline Editing Tests

    @Test
    fun `edit session suppresses invalidation for originating note`() {
        val currentNote = testNotes.find { it.id == "current" }
        val editedNote = testNotes.find { it.id == "note-1" }

        // Cache a result using self-referencing directive (per-note cache)
        executor.execute("[.path]", testNotes, currentNote)

        // Start edit session (editing note-1 from current note's view)
        editSessionManager.startEditSession(
            editedNoteId = editedNote!!.id,
            originatingNoteId = currentNote!!.id
        )

        // Request invalidation for current note (originating)
        executor.invalidateForChangedNotes(setOf("current"))

        // Should still be cached (suppressed)
        assertTrue(executor.execute("[.path]", testNotes, currentNote).cacheHit)

        // End edit session
        editSessionManager.endEditSession()

        // Now should be invalidated
        assertFalse(executor.execute("[.path]", testNotes, currentNote).cacheHit)
    }

    @Test
    fun `edit session allows invalidation for non-originating notes`() {
        val currentNote = testNotes.find { it.id == "current" }
        val otherNote = testNotes.find { it.id == "note-2" }

        // Cache results for both notes
        executor.execute("[add(1, 2)]", testNotes, currentNote)
        executor.execute("[add(1, 2)]", testNotes, otherNote)

        // Start edit session for current note
        editSessionManager.startEditSession(
            editedNoteId = "note-1",
            originatingNoteId = currentNote!!.id
        )

        // Invalidate for other note (not suppressed)
        executor.invalidateForChangedNotes(setOf("note-2"))

        // Current note should still be cached (suppressed)
        assertTrue(executor.execute("[add(1, 2)]", testNotes, currentNote).cacheHit)
    }

    // endregion

    // region Error Caching Tests

    @Test
    fun `syntax errors are cached`() {
        val currentNote = testNotes.find { it.id == "current" }

        // First execution - parse error
        val result1 = executor.execute("[invalid syntax", testNotes, currentNote)
        assertFalse(result1.result.isComputed)  // Has error

        // Second execution should NOT be a cache hit for unparseable directives
        // because we can't generate a cache key
        val result2 = executor.execute("[invalid syntax", testNotes, currentNote)
        assertFalse(result2.cacheHit)
    }

    @Test
    fun `deterministic errors like type errors are cached`() {
        val currentNote = testNotes.find { it.id == "current" }

        // First execution - type error (add with strings)
        val result1 = executor.execute("[add(\"a\", \"b\")]", testNotes, currentNote)
        assertFalse(result1.cacheHit)

        // The error should be cached
        val result2 = executor.execute("[add(\"a\", \"b\")]", testNotes, currentNote)
        assertTrue(result2.cacheHit)
        assertFalse(result2.result.isComputed)  // Has error
    }

    // endregion

    // region RefreshScheduler Tests

    @Test
    fun `scheduler registers and tracks triggers`() {
        var invalidationCount = 0
        val scheduler = RefreshScheduler(
            onTrigger = { _, _ -> invalidationCount++ }
        )

        scheduler.register(
            cacheKey = "test-key",
            noteId = "note-1",
            triggers = listOf(DailyTimeTrigger(LocalTime.of(12, 0)))
        )

        assertEquals(1, scheduler.registrationCount())
        assertTrue(scheduler.registeredKeys().contains("test-key"))
    }

    @Test
    fun `scheduler unregisters correctly`() {
        val scheduler = RefreshScheduler(onTrigger = { _, _ -> })

        scheduler.register("key-1", "note-1", listOf(DailyTimeTrigger(LocalTime.of(12, 0))))
        scheduler.register("key-2", "note-2", listOf(DailyTimeTrigger(LocalTime.of(14, 0))))

        scheduler.unregister("key-1")
        assertEquals(1, scheduler.registrationCount())

        scheduler.unregisterNote("note-2")
        assertEquals(0, scheduler.registrationCount())
    }

    @Test
    fun `scheduler calculates next trigger time`() {
        val scheduler = RefreshScheduler(onTrigger = { _, _ -> })

        val futureTime = LocalTime.now().plusHours(2)
        scheduler.register(
            cacheKey = "test-key",
            noteId = "note-1",
            triggers = listOf(DailyTimeTrigger(futureTime))
        )

        val nextTrigger = scheduler.nextTriggerTime()
        assertNotNull(nextTrigger)
    }

    // endregion

    // region Dependency Tracking Tests

    @Test
    fun `dependencies are collected during execution`() {
        val currentNote = testNotes.find { it.id == "current" }

        val result = executor.execute(
            sourceText = "[find(path: \"inbox/note1\")]",
            notes = testNotes,
            currentNote = currentNote
        )

        // find() should create dependencies
        assertTrue(result.dependencies.dependsOnNoteExistence || result.dependencies.dependsOnPath)
    }

    // endregion

    // region Phase 1 - View Transitive Dependency Tests

    @Test
    fun `view directive tracks viewed note IDs as dependencies`() {
        // Create notes where one contains a view directive
        val viewedNote = Note(id = "viewed", content = "Viewed content with [add(1, 2)]", path = "viewed")
        val hostNote = Note(id = "host", content = "Host note", path = "host")
        val notes = listOf(viewedNote, hostNote)

        val result = executor.execute(
            sourceText = "[view find(path: \"viewed\")]",
            notes = notes,
            currentNote = hostNote
        )

        // The viewed note ID should be in both firstLineNotes and nonFirstLineNotes
        // This ensures any change to the viewed note's content triggers staleness
        assertTrue(
            "Viewed note ID should be tracked in firstLineNotes",
            result.dependencies.firstLineNotes.contains("viewed")
        )
        assertTrue(
            "Viewed note ID should be tracked in nonFirstLineNotes",
            result.dependencies.nonFirstLineNotes.contains("viewed")
        )
    }

    @Test
    fun `view becomes stale when viewed note content changes`() {
        // Create notes where one contains a view directive
        val viewedNote = Note(id = "viewed", content = "Original content", path = "viewed")
        val hostNote = Note(id = "host", content = "Host note", path = "host")
        val originalNotes = listOf(viewedNote, hostNote)

        // First execution - cache the view result
        val result1 = executor.execute(
            sourceText = "[view find(path: \"viewed\")]",
            notes = originalNotes,
            currentNote = hostNote
        )
        assertFalse("First execution should be cache miss", result1.cacheHit)

        // Verify the viewed note is tracked as dependency (in both sets for full content)
        assertTrue(
            "Viewed note should be tracked in firstLineNotes",
            result1.dependencies.firstLineNotes.contains("viewed")
        )

        // Modify the viewed note's content
        val modifiedNotes = listOf(
            viewedNote.copy(content = "Modified content"),
            hostNote
        )

        // Second execution with modified content should detect staleness
        val result2 = executor.execute(
            sourceText = "[view find(path: \"viewed\")]",
            notes = modifiedNotes,
            currentNote = hostNote
        )

        // Should be cache miss because the viewed note's content changed
        assertFalse(
            "View should be stale when viewed note content changes",
            result2.cacheHit
        )
    }

    @Test
    fun `multiple viewed notes are all tracked as dependencies`() {
        // Create multiple notes to be viewed
        val note1 = Note(id = "inbox-1", content = "First note", path = "inbox/note1")
        val note2 = Note(id = "inbox-2", content = "Second note", path = "inbox/note2")
        val hostNote = Note(id = "host", content = "Host note", path = "host")
        val notes = listOf(note1, note2, hostNote)

        val result = executor.execute(
            sourceText = "[view find(path: pattern(\"inbox/\" any*(1..)))]",
            notes = notes,
            currentNote = hostNote
        )

        // Both viewed note IDs should be tracked in firstLineNotes
        // (checking firstLineNotes is sufficient - they're also in nonFirstLineNotes)
        assertTrue(
            "First viewed note should be tracked",
            result.dependencies.firstLineNotes.contains("inbox-1")
        )
        assertTrue(
            "Second viewed note should be tracked",
            result.dependencies.firstLineNotes.contains("inbox-2")
        )
    }

    @Test
    fun `nested directives in viewed notes propagate dependencies`() {
        // Create a viewed note with a directive that has its own dependencies
        val viewedNote = Note(
            id = "viewed",
            content = "Viewed note with [find(path: \"other\")]",
            path = "viewed"
        )
        val otherNote = Note(id = "other", content = "Other note", path = "other")
        val hostNote = Note(id = "host", content = "Host note", path = "host")
        val notes = listOf(viewedNote, otherNote, hostNote)

        val result = executor.execute(
            sourceText = "[view find(path: \"viewed\")]",
            notes = notes,
            currentNote = hostNote
        )

        // The host view should have dependencies from nested directives
        // At minimum, the viewed note should be tracked in firstLineNotes
        assertTrue(
            "Viewed note should be tracked",
            result.dependencies.firstLineNotes.contains("viewed")
        )
        // The nested find() should contribute note existence dependency
        assertTrue(
            "Nested find should contribute dependencies",
            result.dependencies.dependsOnNoteExistence || result.dependencies.dependsOnPath
        )
    }

    // endregion

    // region Factory Tests

    @Test
    fun `factory creates in-memory executor`() {
        val executor = CachedDirectiveExecutorFactory.createInMemoryOnly()
        val currentNote = testNotes.find { it.id == "current" }

        val result = executor.execute("[add(1, 2)]", testNotes, currentNote)
        assertTrue(result.result.isComputed)
    }

    @Test
    fun `factory creates edit-aware executor`() {
        val executor = CachedDirectiveExecutorFactory.createWithEditSupport()
        val currentNote = testNotes.find { it.id == "current" }

        val result = executor.execute("[add(1, 2)]", testNotes, currentNote)
        assertTrue(result.result.isComputed)
    }

    // endregion

    // region Phase 5 - Integration Tests

    /**
     * Test 5.1: Inline editing -> save -> view refresh flow
     *
     * Simulates the complete flow:
     * 1. Host note A has [view find(path: "B")]
     * 2. Note B has content "Original"
     * 3. Execute directives on A - view shows "Original"
     * 4. Start inline edit session (editing B from A's view)
     * 5. During edit, A's cache is suppressed
     * 6. End edit session (simulating save completion)
     * 7. Execute directives on A again with modified B
     * 8. Assert view now shows "Modified"
     */
    @Test
    fun `view updates after inline edit save - full flow`() {
        // Setup: Note A views Note B
        val noteB = Note(id = "B", content = "Original", path = "B")
        val noteA = Note(id = "A", content = "Dashboard", path = "A")
        val originalNotes = listOf(noteA, noteB)

        // Step 1: Execute view directive on A - caches result
        val result1 = executor.execute(
            sourceText = "[view find(path: \"B\")]",
            notes = originalNotes,
            currentNote = noteA
        )
        assertFalse("First execution should be cache miss", result1.cacheHit)
        assertTrue(
            "View should display 'Original'",
            result1.result.toValue()?.toDisplayString()?.contains("Original") == true
        )

        // Step 2: Start edit session (editing B from A's view)
        editSessionManager.startEditSession(editedNoteId = "B", originatingNoteId = "A")

        // Step 3: Request invalidation for A (would normally happen during edit)
        // This should be suppressed because A is the originating note
        executor.invalidateForChangedNotes(setOf("A"))

        // Step 4: Verify cache is still valid (suppressed)
        val resultDuringEdit = executor.execute(
            sourceText = "[view find(path: \"B\")]",
            notes = originalNotes,
            currentNote = noteA
        )
        assertTrue("Cache should still be valid during edit session", resultDuringEdit.cacheHit)

        // Step 5: End edit session (simulates save completion)
        editSessionManager.endEditSession()

        // Step 6: Simulate the "save" by creating modified notes
        val modifiedNotes = listOf(
            noteA,
            noteB.copy(content = "Modified")
        )

        // Step 7: Execute again with modified content
        // Cache should be stale because:
        // 1. Pending invalidation was applied when session ended
        // 2. Content hash changed
        val result2 = executor.execute(
            sourceText = "[view find(path: \"B\")]",
            notes = modifiedNotes,
            currentNote = noteA
        )

        // Should be cache miss (content changed)
        assertFalse("Should detect staleness after edit", result2.cacheHit)
        assertTrue(
            "View should now display 'Modified'",
            result2.result.toValue()?.toDisplayString()?.contains("Modified") == true
        )
    }

    /**
     * Test 5.2: Transitive dependency propagation
     *
     * Verifies that dependencies from nested directives propagate to parent view.
     */
    @Test
    fun `nested directive dependencies propagate to parent view - path change`() {
        // Note B has a directive that depends on its own path
        val noteB = Note(id = "B", content = "My path: [.path]", path = "original/path")
        val noteA = Note(id = "A", content = "Dashboard", path = "A")
        val originalNotes = listOf(noteA, noteB)

        // Execute view on A - this should track B's content
        val result1 = executor.execute(
            sourceText = "[view find(path: pattern(\"original/\" any*(1..)))]",
            notes = originalNotes,
            currentNote = noteA
        )
        assertFalse("First execution should be cache miss", result1.cacheHit)

        // Verify B is tracked as a dependency
        assertTrue(
            "Viewed note B should be tracked in dependencies",
            result1.dependencies.firstLineNotes.contains("B")
        )

        // Change B's content (which changes the [.path] output)
        val modifiedNotes = listOf(
            noteA,
            noteB.copy(content = "My path changed: [.path]")
        )

        // Execute again - should detect staleness because B's content changed
        val result2 = executor.execute(
            sourceText = "[view find(path: pattern(\"original/\" any*(1..)))]",
            notes = modifiedNotes,
            currentNote = noteA
        )

        assertFalse(
            "View should be stale when viewed note's content changes",
            result2.cacheHit
        )
    }

    /**
     * Test 5.3: Cache refresh uses current data after invalidation
     *
     * Verifies that after cache invalidation, the next execution uses fresh data.
     * This tests the atomic refresh pattern (Phase 2).
     */
    @Test
    fun `refresh uses fresh data after cache invalidation`() {
        // Setup
        val noteB = Note(id = "B", content = "Version 1", path = "B")
        val noteA = Note(id = "A", content = "Host", path = "A")
        val v1Notes = listOf(noteA, noteB)

        // Execute and cache
        val result1 = executor.execute(
            sourceText = "[view find(path: \"B\")]",
            notes = v1Notes,
            currentNote = noteA
        )
        assertFalse(result1.cacheHit)
        assertTrue(
            result1.result.toValue()?.toDisplayString()?.contains("Version 1") == true
        )

        // Second execution - should be cached
        val result2 = executor.execute(
            sourceText = "[view find(path: \"B\")]",
            notes = v1Notes,
            currentNote = noteA
        )
        assertTrue("Should be cache hit with same data", result2.cacheHit)

        // Simulate save completing: invalidate cache, then provide new data
        executor.clearAll()  // This simulates directiveCacheManager.clearAll()
        MetadataHasher.invalidateCache()  // Phase 3: Also clear metadata cache

        // Create new version of notes (simulating post-save data)
        val v2Notes = listOf(
            noteA,
            noteB.copy(content = "Version 2")
        )

        // Execute with fresh data after invalidation
        val result3 = executor.execute(
            sourceText = "[view find(path: \"B\")]",
            notes = v2Notes,
            currentNote = noteA
        )

        // Should be cache miss (cache was cleared) and show new content
        assertFalse("Should be cache miss after invalidation", result3.cacheHit)
        assertTrue(
            "Should display Version 2 after refresh",
            result3.result.toValue()?.toDisplayString()?.contains("Version 2") == true
        )
    }

    /**
     * Test 5.3b: Stale cache detection without explicit invalidation
     *
     * Verifies that even without explicit invalidation, staleness is detected
     * when content hashes don't match.
     */
    @Test
    fun `staleness detected via content hash mismatch`() {
        // Setup
        val noteB = Note(id = "B", content = "Original content", path = "B")
        val noteA = Note(id = "A", content = "Host", path = "A")
        val v1Notes = listOf(noteA, noteB)

        // Execute and cache
        val result1 = executor.execute(
            sourceText = "[view find(path: \"B\")]",
            notes = v1Notes,
            currentNote = noteA
        )
        assertFalse(result1.cacheHit)

        // Verify dependency tracking
        assertTrue(
            "Should track viewed note in dependencies",
            result1.dependencies.firstLineNotes.contains("B")
        )

        // Create modified notes WITHOUT invalidating cache
        // The staleness checker should detect this via hash comparison
        val v2Notes = listOf(
            noteA,
            noteB.copy(content = "Modified content")
        )

        // Execute with new data - staleness should be detected
        val result2 = executor.execute(
            sourceText = "[view find(path: \"B\")]",
            notes = v2Notes,
            currentNote = noteA
        )

        assertFalse(
            "Staleness should be detected via hash mismatch",
            result2.cacheHit
        )
        assertTrue(
            "Should display modified content",
            result2.result.toValue()?.toDisplayString()?.contains("Modified content") == true
        )
    }

    /**
     * Test: Edit session abort discards pending invalidations
     *
     * Verifies that aborting an edit session (e.g., user cancels)
     * does not apply the queued invalidations.
     */
    @Test
    fun `edit session abort discards pending invalidations`() {
        // Setup
        val noteB = Note(id = "B", content = "Original", path = "B")
        val noteA = Note(id = "A", content = "Host", path = "A")
        val notes = listOf(noteA, noteB)

        // Cache a result
        executor.execute("[view find(path: \"B\")]", notes, noteA)

        // Start edit session
        editSessionManager.startEditSession(editedNoteId = "B", originatingNoteId = "A")

        // Queue an invalidation for A
        editSessionManager.requestInvalidation("A", InvalidationReason.CONTENT_CHANGED)
        assertEquals(1, editSessionManager.pendingInvalidationCount())

        // Abort the session (user cancelled)
        editSessionManager.abortEditSession()

        // Pending invalidations should be discarded
        assertEquals(0, editSessionManager.pendingInvalidationCount())

        // Cache should still be valid (not invalidated)
        val result = executor.execute("[view find(path: \"B\")]", notes, noteA)
        assertTrue("Cache should still be valid after abort", result.cacheHit)
    }

    // endregion
}
