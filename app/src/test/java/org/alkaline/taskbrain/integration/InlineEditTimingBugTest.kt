package org.alkaline.taskbrain.integration

import org.alkaline.taskbrain.dsl.cache.DirectiveCacheManager
import org.alkaline.taskbrain.dsl.cache.EditSessionManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * This test replicates the ACTUAL bug in the inline edit flow.
 *
 * ## The Bug
 *
 * When user taps out of inline edit:
 *
 * ```
 * saveInlineNoteContent() {
 *   save to Firestore
 *   cachedNotes = null
 *   clear caches
 *   endInlineEditSession()  ← SESSION ENDS HERE, UI CAN RECOMPOSE
 *   onSuccess?.invoke()     ← This calls forceRefreshAllDirectives
 * }
 *
 * // In CurrentNoteScreen onSuccess callback:
 * forceRefreshAllDirectives(userContent)  ← ASYNC! Launches coroutine
 *   // ... eventually updates _directiveResults
 * ```
 *
 * The problem: `endInlineEditSession()` is called BEFORE `forceRefreshAllDirectives`
 * even STARTS. Since forceRefreshAllDirectives is async, there's a window where:
 * - Session has ended (UI can recompose)
 * - _directiveResults still has OLD content
 * - User sees stale content briefly
 *
 * ## What This Test Verifies
 *
 * This test simulates the exact sequence and shows that with the current code,
 * the session ends before the results are updated.
 */
class InlineEditTimingBugTest {

    private lateinit var cacheManager: DirectiveCacheManager
    private lateinit var sessionManager: EditSessionManager

    // Simulated state
    private var directiveResults: Map<String, String> = emptyMap()
    private var cachedNotes: List<String>? = null

    // Track the order of operations
    private val operationLog = CopyOnWriteArrayList<String>()

    // Track what directiveResults contained when session ended
    private var directiveResultsAtSessionEnd: Map<String, String>? = null

    @Before
    fun setUp() {
        cacheManager = DirectiveCacheManager()
        sessionManager = EditSessionManager(cacheManager)
        operationLog.clear()
        directiveResultsAtSessionEnd = null

        // Listen for session end
        sessionManager.addSessionEndListener {
            operationLog.add("SESSION_ENDED")
            // Capture what directiveResults contains at this moment
            directiveResultsAtSessionEnd = directiveResults.toMap()
        }
    }

    /**
     * This test replicates the bug: session ends while directiveResults still has old content.
     */
    @Test
    fun `BUG REPLICATION - session ends before directiveResults updated`() {
        val oldContent = "Old content from before edit"
        val newContent = "New content after edit"
        val noteId = "note-123"
        val hostNoteId = "host-456"

        // Initial state: directiveResults has old content
        directiveResults = mapOf("view-directive" to oldContent)
        cachedNotes = listOf(oldContent)
        operationLog.add("INITIAL_STATE: directiveResults has old content")

        // Simulate: user starts editing
        sessionManager.startEditSession(noteId, hostNoteId)
        operationLog.add("EDIT_SESSION_STARTED")

        // Simulate: user taps out, triggering saveInlineNoteContent
        // This replicates the CURRENT (buggy) code in saveInlineNoteContent:

        // Step 1: Save succeeds (simulated)
        operationLog.add("SAVE_TO_FIRESTORE_SUCCESS")

        // Step 2: cachedNotes = null
        cachedNotes = null
        operationLog.add("CACHED_NOTES_CLEARED")

        // Step 3: Clear caches
        cacheManager.clearAll()
        operationLog.add("DIRECTIVE_CACHE_CLEARED")

        // Step 4: endInlineEditSession() - THIS IS THE BUG LOCATION
        // In the real code, this is called BEFORE forceRefreshAllDirectives
        sessionManager.endEditSession()
        // The listener will log SESSION_ENDED and capture directiveResultsAtSessionEnd

        // Step 5: onSuccess callback runs, which calls forceRefreshAllDirectives
        operationLog.add("ON_SUCCESS_CALLBACK_STARTS")

        // Step 6: forceRefreshAllDirectives is ASYNC - it would launch a coroutine
        // But in this test we simulate it synchronously to show the timing issue
        operationLog.add("FORCE_REFRESH_STARTS_ASYNC")

        // ... async work happens ...
        // Eventually directiveResults is updated
        directiveResults = mapOf("view-directive" to newContent)
        operationLog.add("DIRECTIVE_RESULTS_UPDATED")

        // Print the operation log
        println("=== Operation Log ===")
        operationLog.forEachIndexed { idx, op -> println("$idx: $op") }

        println("\n=== Bug Verification ===")
        println("directiveResultsAtSessionEnd = $directiveResultsAtSessionEnd")
        println("final directiveResults = $directiveResults")

        // THE BUG: At the moment the session ended, directiveResults still had OLD content
        assertNotNull("Should have captured directiveResults at session end", directiveResultsAtSessionEnd)
        assertEquals(
            "BUG CONFIRMED: When session ended, directiveResults still had OLD content",
            oldContent,
            directiveResultsAtSessionEnd!!["view-directive"]
        )

        // Verify the order: SESSION_ENDED comes before DIRECTIVE_RESULTS_UPDATED
        val sessionEndIndex = operationLog.indexOf("SESSION_ENDED")
        val resultsUpdatedIndex = operationLog.indexOf("DIRECTIVE_RESULTS_UPDATED")
        assertTrue(
            "BUG CONFIRMED: Session ended (idx=$sessionEndIndex) before results updated (idx=$resultsUpdatedIndex)",
            sessionEndIndex < resultsUpdatedIndex
        )
    }

    /**
     * This test shows what the CORRECT behavior should look like.
     */
    @Test
    fun `CORRECT BEHAVIOR - session ends after directiveResults updated`() {
        val oldContent = "Old content from before edit"
        val newContent = "New content after edit"
        val noteId = "note-123"
        val hostNoteId = "host-456"

        // Initial state: directiveResults has old content
        directiveResults = mapOf("view-directive" to oldContent)
        cachedNotes = listOf(oldContent)
        operationLog.add("INITIAL_STATE: directiveResults has old content")

        // Simulate: user starts editing
        sessionManager.startEditSession(noteId, hostNoteId)
        operationLog.add("EDIT_SESSION_STARTED")

        // Simulate: user taps out, triggering saveInlineNoteContent
        // This shows the CORRECT sequence:

        // Step 1: Save succeeds (simulated)
        operationLog.add("SAVE_TO_FIRESTORE_SUCCESS")

        // Step 2: cachedNotes = null
        cachedNotes = null
        operationLog.add("CACHED_NOTES_CLEARED")

        // Step 3: Clear caches
        cacheManager.clearAll()
        operationLog.add("DIRECTIVE_CACHE_CLEARED")

        // Step 4: onSuccess callback runs, which calls forceRefreshAllDirectives
        // BUT we wait for it to complete before ending session
        operationLog.add("ON_SUCCESS_CALLBACK_STARTS")
        operationLog.add("FORCE_REFRESH_STARTS")

        // ... refresh work happens ...
        // directiveResults is updated FIRST
        directiveResults = mapOf("view-directive" to newContent)
        operationLog.add("DIRECTIVE_RESULTS_UPDATED")

        // Step 5: ONLY NOW do we end the session
        sessionManager.endEditSession()
        // The listener will log SESSION_ENDED and capture directiveResultsAtSessionEnd

        // Print the operation log
        println("=== Operation Log ===")
        operationLog.forEachIndexed { idx, op -> println("$idx: $op") }

        println("\n=== Correct Behavior Verification ===")
        println("directiveResultsAtSessionEnd = $directiveResultsAtSessionEnd")
        println("final directiveResults = $directiveResults")

        // CORRECT: At the moment the session ended, directiveResults had NEW content
        assertNotNull("Should have captured directiveResults at session end", directiveResultsAtSessionEnd)
        assertEquals(
            "CORRECT: When session ended, directiveResults had NEW content",
            newContent,
            directiveResultsAtSessionEnd!!["view-directive"]
        )

        // Verify the order: DIRECTIVE_RESULTS_UPDATED comes before SESSION_ENDED
        val sessionEndIndex = operationLog.indexOf("SESSION_ENDED")
        val resultsUpdatedIndex = operationLog.indexOf("DIRECTIVE_RESULTS_UPDATED")
        assertTrue(
            "CORRECT: Results updated (idx=$resultsUpdatedIndex) before session ended (idx=$sessionEndIndex)",
            resultsUpdatedIndex < sessionEndIndex
        )
    }
}
