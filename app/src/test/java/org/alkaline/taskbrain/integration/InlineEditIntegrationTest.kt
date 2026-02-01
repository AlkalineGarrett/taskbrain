package org.alkaline.taskbrain.integration

import android.app.Application
import android.content.SharedPreferences
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.Observer
import com.google.firebase.Timestamp
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.*
import org.alkaline.taskbrain.data.AlarmRepository
import org.alkaline.taskbrain.data.Note
import org.alkaline.taskbrain.data.NoteLine
import org.alkaline.taskbrain.data.NoteRepository
import org.alkaline.taskbrain.data.PrompterAgent
import org.alkaline.taskbrain.dsl.directives.DirectiveResult
import org.alkaline.taskbrain.dsl.directives.DirectiveResultRepository
import org.alkaline.taskbrain.service.AlarmScheduler
import org.alkaline.taskbrain.testutil.SlowTest
import org.alkaline.taskbrain.ui.currentnote.CurrentNoteViewModel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import java.util.Date
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Integration test that replicates the inline edit stale content bug.
 *
 * THE BUG:
 * When user taps out to save an inline edit, the UI shows stale content for ~1.5 seconds
 * because directiveResults isn't updated until forceRefreshAllDirectives completes.
 *
 * THE FIX (applied at UI layer in DirectiveAwareLineInput.kt):
 * During the transitional state (isEditing=false but session still active), the UI now
 * uses session.currentContent instead of the stale displayContent from directiveResults.
 * This ensures the user sees their edits immediately, even though directive rendering
 * may briefly show raw directive text (like [add(1,1)] instead of 2).
 *
 * This test verifies that the ViewModel keeps the session active until refresh completes,
 * which is a prerequisite for the UI fix to work.
 *
 * From actual logs (log-inline-edit-details.txt):
 * - 16:03:44.171: Focus lost, editingNoteIndex=null set (triggers UI recompose)
 * - 16:03:44.176: UI shows OLD content (displayContent from stale directiveResults)
 * - 16:03:44.891: Save completes, session still active
 * - 16:03:45.759: forceRefreshAllDirectives completes, directiveResults updated
 * - 16:03:45.767: UI finally shows NEW content
 * Gap: ~1.6 seconds of stale content visibility (now fixed)
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Category(SlowTest::class)
class InlineEditIntegrationTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    // Mocked external dependencies
    private lateinit var mockRepository: NoteRepository
    private lateinit var mockAlarmRepository: AlarmRepository
    private lateinit var mockAlarmScheduler: AlarmScheduler
    private lateinit var mockApplication: Application
    private lateinit var mockSharedPreferences: SharedPreferences
    private lateinit var mockAgent: PrompterAgent
    private lateinit var mockDirectiveResultRepository: DirectiveResultRepository

    // Real ViewModel under test
    private lateinit var viewModel: CurrentNoteViewModel

    // Test data - matching the actual log data
    private val hostNoteId = "YJtk8v5GusAS8047xp9x"  // "View test" note
    private val viewedNoteId = "OljEZ1ZWdM3tacKeLUlH"  // "examples" note

    private val oldContent = """examples
Poofgttt,,, and!! Works?
[add(1,6075)]
[once[datetime]]
[once[.root.name: "examples"]]
[find()]"""

    private val newContent = """examples
Poofgttt,,, and!! Works? Editing a line
[add(1,6075)]
[once[datetime]]
[once[.root.name: "examples"]]
[find()]"""

    private val hostNoteContent = """View test
•

[view(find(name:"examples"))]

"""

    // Timing from actual logs
    private val saveDelayMs = 1300L  // 13:57:42.980 to 13:57:44.324
    private val loadDelayMs = 700L   // 13:57:44.326 to 13:57:45.028

    // Track directiveResults changes
    private val directiveResultsHistory = CopyOnWriteArrayList<Pair<Long, Map<String, DirectiveResult>>>()

    private fun createNote(id: String, content: String) = Note(
        id = id,
        userId = "test-user",
        content = content,
        path = "test",
        createdAt = Timestamp(Date())
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        // Create mocks for external dependencies
        mockApplication = mockk(relaxed = true)
        mockAlarmRepository = mockk(relaxed = true)
        mockAlarmScheduler = mockk(relaxed = true)

        // Mock SharedPreferences
        mockSharedPreferences = mockk(relaxed = true)
        val mockEditor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { mockSharedPreferences.edit() } returns mockEditor
        every { mockEditor.putString(any(), any()) } returns mockEditor
        every { mockEditor.apply() } just Runs
        every { mockSharedPreferences.getString(any(), any()) } returns null
        every { mockApplication.getSharedPreferences(any(), any()) } returns mockSharedPreferences

        // Create mock repository with realistic timing
        mockRepository = mockk(relaxed = true)

        // Mock PrompterAgent and DirectiveResultRepository (Firebase-dependent)
        mockAgent = mockk(relaxed = true)
        mockDirectiveResultRepository = mockk(relaxed = true)
        coEvery { mockDirectiveResultRepository.getResults(any()) } returns Result.success(emptyMap())

        directiveResultsHistory.clear()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    /**
     * Verifies the ViewModel-level prerequisite for the UI fix:
     * The session must stay active until forceRefreshAllDirectives completes.
     *
     * The full fix has two parts:
     * 1. ViewModel: Session stays active until refresh completes (verified here)
     * 2. UI (DirectiveAwareLineInput.kt): During transitional state (isEditing=false,
     *    session active), use session.currentContent instead of stale displayContent
     *
     * This test ensures part 1 is working correctly.
     */
    @Test
    fun `session stays active until forceRefreshAllDirectives completes`() = runTest {
        // Setup: Mock repository with realistic timing
        val oldNotes = listOf(
            createNote(hostNoteId, hostNoteContent),
            createNote(viewedNoteId, oldContent)
        )
        val newNotes = listOf(
            createNote(hostNoteId, hostNoteContent),
            createNote(viewedNoteId, newContent)  // Updated content after save
        )

        // Track if save has happened
        var saveCompleted = false

        // Mock loadNotesWithFullContent - returns OLD content before save, NEW content after
        coEvery { mockRepository.loadNotesWithFullContent() } coAnswers {
            delay(loadDelayMs)  // Simulate Firestore latency
            if (saveCompleted) {
                println("loadNotesWithFullContent: returning NEW content (after save)")
                Result.success(newNotes)
            } else {
                println("loadNotesWithFullContent: returning OLD content (before save)")
                Result.success(oldNotes)
            }
        }

        // Mock saveNoteWithFullContent - takes time like real Firestore
        coEvery {
            mockRepository.saveNoteWithFullContent(viewedNoteId, newContent)
        } coAnswers {
            delay(saveDelayMs)  // Simulate Firestore save latency
            saveCompleted = true
            println("saveNoteWithFullContent: save completed, future loads will return NEW content")
            Result.success(Unit)
        }

        // Mock loadNoteWithChildren - returns the host note content as NoteLines
        val hostNoteLines = hostNoteContent.lines().mapIndexed { idx, line ->
            NoteLine(content = line, noteId = if (idx == 0) hostNoteId else null)
        }
        coEvery { mockRepository.loadNoteWithChildren(hostNoteId) } coAnswers {
            delay(loadDelayMs)
            Result.success(hostNoteLines)
        }

        // Mock other repository methods
        coEvery { mockRepository.loadNoteById(any()) } returns Result.success(null)
        coEvery { mockRepository.isNoteDeleted(any()) } returns Result.success(false)
        coEvery { mockRepository.updateLastAccessed(any()) } returns Result.success(Unit)

        // Create ViewModel with mocked dependencies
        viewModel = CurrentNoteViewModel(
            application = mockApplication,
            repository = mockRepository,
            alarmRepository = mockAlarmRepository,
            alarmScheduler = mockAlarmScheduler,
            sharedPreferences = mockSharedPreferences,
            agent = mockAgent,
            directiveResultRepository = mockDirectiveResultRepository,
            noteOperationsProvider = { null }  // No mutations in tests
        )

        // Observe directiveResults to track changes
        var lastResultsBeforeSessionEnd: Map<String, DirectiveResult>? = null
        var sessionEnded = false

        viewModel.directiveResults.observeForever { results ->
            val timestamp = System.currentTimeMillis()
            directiveResultsHistory.add(timestamp to results)
            println("directiveResults changed at $timestamp: ${results.size} entries")
            results.forEach { (key, result) ->
                val content = result.toValue()?.toDisplayString()?.take(60)
                println("  [$key]: '$content...'")
            }
        }

        // USER ACTION 1: Load the host note (contains the view directive)
        // This triggers executeDirectivesLive which populates _directiveResults with OLD content
        viewModel.loadContent(hostNoteId)
        advanceUntilIdle()

        println("\n=== After loading note ===")
        val initialResults = viewModel.directiveResults.value
        println("Initial directiveResults: ${initialResults?.size} entries")

        // USER ACTION 2: Start inline edit session (user taps on view to edit)
        viewModel.startInlineEditSession(viewedNoteId)
        println("\n=== Started inline edit session ===")

        // USER ACTION 3: Save inline edit (user taps out)
        // This is where the bug occurs
        println("\n=== Calling saveInlineNoteContent (simulates tap out) ===")

        var saveSucceeded = false
        var resultsAtSaveCallback: Map<String, DirectiveResult>? = null
        var resultsAtRefreshComplete: Map<String, DirectiveResult>? = null
        var sessionActiveAtOnSuccess = false  // Track if session was still active at onSuccess

        viewModel.saveInlineNoteContent(
            noteId = viewedNoteId,
            newContent = newContent,
            onSuccess = {
                saveSucceeded = true
                // Capture what directiveResults looks like when onSuccess is called
                resultsAtSaveCallback = viewModel.directiveResults.value?.toMap()
                println("onSuccess callback: directiveResults has ${resultsAtSaveCallback?.size} entries")
                resultsAtSaveCallback?.forEach { (key, result) ->
                    val content = result.toValue()?.toDisplayString()?.take(60)
                    println("  AT onSuccess [$key]: '$content...'")
                }

                // Check if session is still active (should be with the fix)
                sessionActiveAtOnSuccess = viewModel.isInlineEditSessionActive()
                println("Session active at onSuccess: $sessionActiveAtOnSuccess")

                // USER ACTION 4: Call forceRefreshAllDirectives with callback
                // (like CurrentNoteScreen now does with the fix)
                viewModel.forceRefreshAllDirectives(hostNoteContent) {
                    // This callback is called AFTER results are updated
                    resultsAtRefreshComplete = viewModel.directiveResults.value?.toMap()
                    println("forceRefreshAllDirectives callback: results has ${resultsAtRefreshComplete?.size} entries")
                    resultsAtRefreshComplete?.forEach { (key, result) ->
                        val content = result.toValue()?.toDisplayString()?.take(60)
                        println("  AT REFRESH COMPLETE [$key]: '$content...'")
                    }

                    // Now end the session (like the fixed code does)
                    viewModel.endInlineEditSession()
                    println("Session ended in refresh callback")
                }
            }
        )

        // Let everything complete
        advanceUntilIdle()

        println("\n=== After all operations complete ===")
        val finalResults = viewModel.directiveResults.value
        println("Final directiveResults: ${finalResults?.size} entries")
        finalResults?.forEach { (key, result) ->
            val content = result.toValue()?.toDisplayString()?.take(60)
            println("  FINAL [$key]: '$content...'")
        }

        // Verify save succeeded
        assertTrue("Save should have succeeded", saveSucceeded)
        coVerify { mockRepository.saveNoteWithFullContent(viewedNoteId, newContent) }

        // VERIFY THE FIX:
        // The key invariant: session must stay active until AFTER directiveResults is updated
        // With the bug: session ends at saveInlineNoteContent, before refresh completes → UI re-renders with stale content
        // With the fix: session ends in forceRefreshAllDirectives callback, after refresh completes → UI re-renders with fresh content
        println("\n=== Fix Verification ===")

        // Get content at different points
        val contentAtOnSuccess = resultsAtSaveCallback?.values?.firstOrNull()
            ?.toValue()?.toDisplayString() ?: ""
        val contentAtRefresh = resultsAtRefreshComplete?.values?.firstOrNull()
            ?.toValue()?.toDisplayString() ?: ""

        println("Content at onSuccess: '${contentAtOnSuccess.take(80)}...'")
        println("Content at refresh complete: '${contentAtRefresh.take(80)}...'")

        val hasFreshContentAtOnSuccess = contentAtOnSuccess.contains("Editing a line")
        val hasFreshContentAtRefresh = contentAtRefresh.contains("Editing a line")

        println("Fresh content at onSuccess: $hasFreshContentAtOnSuccess")
        println("Fresh content at refresh: $hasFreshContentAtRefresh")

        // Check results at forceRefreshAllDirectives callback
        if (resultsAtRefreshComplete != null && resultsAtRefreshComplete!!.isNotEmpty()) {
            // THE KEY FIX VERIFICATION:

            // 1. Session MUST still be active at onSuccess time
            // This is the ViewModel-level prerequisite for the UI fix.
            // The UI fix (in DirectiveAwareLineInput.kt) checks if session is active to decide
            // whether to use session.currentContent or displayContent from directiveResults.
            // If session ended before onSuccess, the UI would have no way to get fresh content.
            assertTrue(
                "Session must stay active at onSuccess time. " +
                "This is required for the UI to use session.currentContent during the transitional state. " +
                "Check: endInlineEditSession() should only be called in forceRefreshAllDirectives callback.",
                sessionActiveAtOnSuccess
            )

            // 2. Content at refresh callback should be fresh (this is when session ends)
            assertTrue(
                "Content at forceRefreshAllDirectives callback should be fresh",
                hasFreshContentAtRefresh
            )

            // Note: _directiveResults being stale at onSuccess is EXPECTED and OK.
            // The UI fix doesn't require _directiveResults to be fresh at onSuccess.
            // Instead, the UI uses session.currentContent during the transitional state.
            // When session finally ends (in refresh callback), _directiveResults IS fresh,
            // and the UI switches back to using displayContent from directiveResults.

            println("PREREQUISITE VERIFIED: Session was active at onSuccess, enabling UI to use session content")
        } else {
            fail("resultsAtRefreshComplete should not be empty")
        }
    }
}
