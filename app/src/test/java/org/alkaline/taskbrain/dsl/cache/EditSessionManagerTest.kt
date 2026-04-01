package org.alkaline.taskbrain.dsl.cache

import org.alkaline.taskbrain.data.Note
import org.alkaline.taskbrain.dsl.runtime.values.NumberVal
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for EditSessionManager and inline editing support.
 * Phase 8: Inline editing support.
 */
class EditSessionManagerTest {

    private lateinit var cacheManager: DirectiveCacheManager
    private lateinit var editManager: EditSessionManager

    @Before
    fun setUp() {
        cacheManager = DirectiveCacheManager()
        editManager = EditSessionManager(cacheManager)
    }

    // region EditContext Tests

    @Test
    fun `EditContext isOriginatingNote returns true for matching note`() {
        val context = EditContext(editedNoteId = "B", originatingNoteId = "A")

        assertTrue(context.isOriginatingNote("A"))
        assertFalse(context.isOriginatingNote("B"))
        assertFalse(context.isOriginatingNote("C"))
    }

    @Test
    fun `EditContext isEditedNote returns true for matching note`() {
        val context = EditContext(editedNoteId = "B", originatingNoteId = "A")

        assertTrue(context.isEditedNote("B"))
        assertFalse(context.isEditedNote("A"))
    }

    @Test
    fun `EditContext isExpired returns false for fresh context`() {
        val context = EditContext(editedNoteId = "B", originatingNoteId = "A")

        assertFalse(context.isExpired())
    }

    @Test
    fun `EditContext isExpired returns true for old context`() {
        val oldTime = System.currentTimeMillis() - (10 * 60 * 1000)  // 10 minutes ago
        val context = EditContext(
            editedNoteId = "B",
            originatingNoteId = "A",
            editStartTime = oldTime
        )

        assertTrue(context.isExpired())
    }

    @Test
    fun `EditContext isExpired respects custom duration`() {
        val recentTime = System.currentTimeMillis() - 1000  // 1 second ago
        val context = EditContext(
            editedNoteId = "B",
            originatingNoteId = "A",
            editStartTime = recentTime
        )

        assertFalse(context.isExpired(5000))  // 5 seconds - not expired
        assertTrue(context.isExpired(500))    // 0.5 seconds - expired
    }

    // endregion

    // region EditSessionManager Basic Tests

    @Test
    fun `initially no edit session is active`() {
        assertFalse(editManager.isEditSessionActive())
        assertNull(editManager.getEditContext())
    }

    @Test
    fun `startEditSession activates session`() {
        editManager.startEditSession(editedNoteId = "B", originatingNoteId = "A")

        assertTrue(editManager.isEditSessionActive())
        assertNotNull(editManager.getEditContext())
        assertEquals("A", editManager.getEditContext()?.originatingNoteId)
        assertEquals("B", editManager.getEditContext()?.editedNoteId)
    }

    @Test
    fun `endEditSession deactivates session`() {
        editManager.startEditSession(editedNoteId = "B", originatingNoteId = "A")
        editManager.endEditSession()

        assertFalse(editManager.isEditSessionActive())
        assertNull(editManager.getEditContext())
    }

    @Test
    fun `startEditSession ends existing session first`() {
        editManager.startEditSession(editedNoteId = "B", originatingNoteId = "A")
        editManager.startEditSession(editedNoteId = "D", originatingNoteId = "C")

        assertEquals("C", editManager.getEditContext()?.originatingNoteId)
        assertEquals("D", editManager.getEditContext()?.editedNoteId)
    }

    @Test
    fun `startEditSession flushes pending invalidations from old session`() {
        // Phase 4: Verify pending invalidations are flushed when switching sessions

        // Put something in cache for note A
        val result = CachedDirectiveResult.success(
            result = NumberVal(42.0),
            dependencies = DirectiveDependencies.EMPTY
        )
        cacheManager.put("hash1", "A", result)

        // Start first session and queue an invalidation
        editManager.startEditSession(editedNoteId = "B", originatingNoteId = "A")
        editManager.requestInvalidation("A", InvalidationReason.CONTENT_CHANGED)
        assertEquals(1, editManager.pendingInvalidationCount())

        // Cache should still exist (invalidation is queued, not applied)
        assertNotNull(cacheManager.get("hash1", "A"))

        // Start new session - should flush old session's pending invalidations
        editManager.startEditSession(editedNoteId = "F", originatingNoteId = "E")

        // Old session's pending invalidations should have been applied
        assertEquals(0, editManager.pendingInvalidationCount())
        assertNull(cacheManager.get("hash1", "A"))

        // New session should be active
        assertEquals("E", editManager.getEditContext()?.originatingNoteId)
    }

    @Test
    fun `abortEditSession clears session without applying invalidations`() {
        editManager.startEditSession(editedNoteId = "B", originatingNoteId = "A")
        editManager.requestInvalidation("A", InvalidationReason.CONTENT_CHANGED)
        editManager.abortEditSession()

        assertFalse(editManager.isEditSessionActive())
        assertEquals(0, editManager.pendingInvalidationCount())
    }

    // endregion

    // region Suppression Tests

    @Test
    fun `shouldSuppressInvalidation returns false when no session active`() {
        assertFalse(editManager.shouldSuppressInvalidation("A"))
    }

    @Test
    fun `shouldSuppressInvalidation returns true for originating note`() {
        editManager.startEditSession(editedNoteId = "B", originatingNoteId = "A")

        assertTrue(editManager.shouldSuppressInvalidation("A"))
    }

    @Test
    fun `shouldSuppressInvalidation returns false for non-originating note`() {
        editManager.startEditSession(editedNoteId = "B", originatingNoteId = "A")

        assertFalse(editManager.shouldSuppressInvalidation("B"))
        assertFalse(editManager.shouldSuppressInvalidation("C"))
    }

    @Test
    fun `shouldSuppressInvalidation returns false for expired session`() {
        val oldTime = System.currentTimeMillis() - (10 * 60 * 1000)
        val oldContext = EditContext(
            editedNoteId = "B",
            originatingNoteId = "A",
            editStartTime = oldTime
        )

        // Use reflection to set the old context
        val field = EditSessionManager::class.java.getDeclaredField("activeEditContext")
        field.isAccessible = true
        field.set(editManager, oldContext)

        assertFalse(editManager.shouldSuppressInvalidation("A"))
    }

    // endregion

    // region Invalidation Queueing Tests

    @Test
    fun `requestInvalidation queues for originating note`() {
        editManager.startEditSession(editedNoteId = "B", originatingNoteId = "A")

        val applied = editManager.requestInvalidation("A", InvalidationReason.CONTENT_CHANGED)

        assertFalse(applied)
        assertEquals(1, editManager.pendingInvalidationCount())
    }

    @Test
    fun `requestInvalidation applies immediately for non-originating note`() {
        editManager.startEditSession(editedNoteId = "B", originatingNoteId = "A")

        val applied = editManager.requestInvalidation("C", InvalidationReason.CONTENT_CHANGED)

        assertTrue(applied)
        assertEquals(0, editManager.pendingInvalidationCount())
    }

    @Test
    fun `requestInvalidation applies immediately when no session active`() {
        val applied = editManager.requestInvalidation("A", InvalidationReason.CONTENT_CHANGED)

        assertTrue(applied)
    }

    @Test
    fun `multiple invalidations are queued`() {
        editManager.startEditSession(editedNoteId = "B", originatingNoteId = "A")

        editManager.requestInvalidation("A", InvalidationReason.CONTENT_CHANGED)
        editManager.requestInvalidation("A", InvalidationReason.METADATA_CHANGED)

        assertEquals(2, editManager.pendingInvalidationCount())
    }

    @Test
    fun `getPendingInvalidations returns copy of queue`() {
        editManager.startEditSession(editedNoteId = "B", originatingNoteId = "A")
        editManager.requestInvalidation("A", InvalidationReason.CONTENT_CHANGED)

        val pending = editManager.getPendingInvalidations()

        assertEquals(1, pending.size)
        assertEquals("A", pending[0].noteId)
        assertEquals(InvalidationReason.CONTENT_CHANGED, pending[0].reason)
    }

    // endregion

    // region End Session Tests

    @Test
    fun `endEditSession applies all pending invalidations`() {
        // Put something in cache
        val result = CachedDirectiveResult.success(
            result = NumberVal(42.0),
            dependencies = DirectiveDependencies.EMPTY
        )
        cacheManager.put("hash1", "A", result)

        editManager.startEditSession(editedNoteId = "B", originatingNoteId = "A")
        editManager.requestInvalidation("A", InvalidationReason.CONTENT_CHANGED)

        // Before end - cache should still be present
        assertNotNull(cacheManager.get("hash1", "A"))

        editManager.endEditSession()

        // After end - cache should be cleared
        assertNull(cacheManager.get("hash1", "A"))
        assertEquals(0, editManager.pendingInvalidationCount())
    }

    @Test
    fun `endEditSession notifies listeners`() {
        var listenerCalled = false
        editManager.addSessionEndListener { listenerCalled = true }

        editManager.startEditSession(editedNoteId = "B", originatingNoteId = "A")
        editManager.endEditSession()

        assertTrue(listenerCalled)
    }

    @Test
    fun `multiple listeners are notified`() {
        var listener1Called = false
        var listener2Called = false
        editManager.addSessionEndListener { listener1Called = true }
        editManager.addSessionEndListener { listener2Called = true }

        editManager.startEditSession(editedNoteId = "B", originatingNoteId = "A")
        editManager.endEditSession()

        assertTrue(listener1Called)
        assertTrue(listener2Called)
    }

    @Test
    fun `removeSessionEndListener prevents notification`() {
        var listenerCalled = false
        val listener = { listenerCalled = true }
        editManager.addSessionEndListener(listener)
        editManager.removeSessionEndListener(listener)

        editManager.startEditSession(editedNoteId = "B", originatingNoteId = "A")
        editManager.endEditSession()

        assertFalse(listenerCalled)
    }

    @Test
    fun `clearListeners removes all listeners`() {
        var listener1Called = false
        var listener2Called = false
        editManager.addSessionEndListener { listener1Called = true }
        editManager.addSessionEndListener { listener2Called = true }
        editManager.clearListeners()

        editManager.startEditSession(editedNoteId = "B", originatingNoteId = "A")
        editManager.endEditSession()

        assertFalse(listener1Called)
        assertFalse(listener2Called)
    }

    // endregion

    // region Expiration Tests

    @Test
    fun `checkAndHandleExpiredSession returns false for fresh session`() {
        editManager.startEditSession(editedNoteId = "B", originatingNoteId = "A")

        val expired = editManager.checkAndHandleExpiredSession()

        assertFalse(expired)
        assertTrue(editManager.isEditSessionActive())
    }

    @Test
    fun `checkAndHandleExpiredSession returns false when no session`() {
        val expired = editManager.checkAndHandleExpiredSession()

        assertFalse(expired)
    }

    @Test
    fun `checkAndHandleExpiredSession ends expired session`() {
        val oldTime = System.currentTimeMillis() - (10 * 60 * 1000)
        val oldContext = EditContext(
            editedNoteId = "B",
            originatingNoteId = "A",
            editStartTime = oldTime
        )

        val field = EditSessionManager::class.java.getDeclaredField("activeEditContext")
        field.isAccessible = true
        field.set(editManager, oldContext)

        val expired = editManager.checkAndHandleExpiredSession()

        assertTrue(expired)
        assertFalse(editManager.isEditSessionActive())
    }

    // endregion

    // region EditAwareCacheManager Tests

    @Test
    fun `EditAwareCacheManager returns cached result when suppressed`() {
        val editAwareManager = EditAwareCacheManager(cacheManager, editManager)
        val testNotes = listOf(
            Note(id = "A", content = "Note A", path = "test/A")
        )

        // Cache a result
        val result = CachedDirectiveResult.success(
            result = NumberVal(42.0),
            dependencies = DirectiveDependencies.EMPTY.copy(dependsOnNoteExistence = true),
            metadataHashes = MetadataHashes(existenceHash = "old-hash")  // Stale hash
        )
        cacheManager.put("hash1", "A", result)

        // Start edit session
        editManager.startEditSession(editedNoteId = "B", originatingNoteId = "A")

        // Should return cached result even though it's stale (suppressed)
        val retrieved = editAwareManager.getIfValidOrSuppressed(
            "hash1", "A",
            testNotes, testNotes[0]
        )

        assertNotNull(retrieved)
        assertEquals(42.0, (retrieved?.result as NumberVal).value, 0.001)
    }

    @Test
    fun `EditAwareCacheManager returns null for stale result when not suppressed`() {
        val editAwareManager = EditAwareCacheManager(cacheManager, editManager)
        val testNotes = listOf(
            Note(id = "A", content = "Note A", path = "test/A")
        )

        // Cache a stale result
        val result = CachedDirectiveResult.success(
            result = NumberVal(42.0),
            dependencies = DirectiveDependencies.EMPTY.copy(dependsOnNoteExistence = true),
            metadataHashes = MetadataHashes(existenceHash = "old-hash")
        )
        cacheManager.put("hash1", "A", result)

        // No edit session - should do normal staleness check
        val retrieved = editAwareManager.getIfValidOrSuppressed(
            "hash1", "A",
            testNotes, testNotes[0]
        )

        assertNull(retrieved)  // Stale
    }

    @Test
    fun `EditAwareCacheManager invalidateForChangedNotes queues for originating`() {
        val editAwareManager = EditAwareCacheManager(cacheManager, editManager)

        editManager.startEditSession(editedNoteId = "B", originatingNoteId = "A")
        editAwareManager.invalidateForChangedNotes(setOf("A", "C"))

        // A should be queued (originating), C should be applied
        assertEquals(1, editManager.pendingInvalidationCount())
    }

    // endregion

    // region Integration Scenarios

    @Test
    fun `scenario - edit Note B from Note A view`() {
        val editAwareManager = EditAwareCacheManager(cacheManager, editManager)
        val testNotes = listOf(
            Note(id = "A", content = "Dashboard", path = "dashboard"),
            Note(id = "B", content = "Buy groceries", path = "inbox/todo1")
        )

        // Cache Note A's view result
        val viewResult = CachedDirectiveResult.success(
            result = NumberVal(1.0),
            dependencies = DirectiveDependencies.EMPTY.copy(
                firstLineNotes = setOf("B")
            ),
            noteContentHashes = mapOf("B" to ContentHashes(firstLineHash = "old-hash"))
        )
        cacheManager.put("view-hash", "A", viewResult)

        // User starts editing Note B from Note A's view
        editManager.startEditSession(editedNoteId = "B", originatingNoteId = "A")

        // Note B changes - request invalidation for Note A
        editAwareManager.invalidateForChangedNotes(setOf("A"))

        // Note A's cache should NOT be invalidated yet (suppressed)
        assertNotNull(cacheManager.get("view-hash", "A"))

        // Edit session ends
        editManager.endEditSession()

        // Now Note A's cache should be cleared
        assertNull(cacheManager.get("view-hash", "A"))
    }

    @Test
    fun `scenario - external change during edit is queued`() {
        // Setup
        editManager.startEditSession(editedNoteId = "B", originatingNoteId = "A")

        // External change to Note A (e.g., another note in the view changed)
        editManager.requestInvalidation("A", InvalidationReason.CONTENT_CHANGED)

        // Verify queued
        assertEquals(1, editManager.pendingInvalidationCount())
        val pending = editManager.getPendingInvalidations()
        assertEquals(InvalidationReason.CONTENT_CHANGED, pending[0].reason)

        // End session - invalidation applied
        editManager.endEditSession()
        assertEquals(0, editManager.pendingInvalidationCount())
    }

    // endregion
}
