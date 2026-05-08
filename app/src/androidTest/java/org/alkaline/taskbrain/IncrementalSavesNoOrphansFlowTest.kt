package org.alkaline.taskbrain

import android.util.Log
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.runBlocking
import org.alkaline.taskbrain.EmulatorTestSupport.descendantsByState
import org.alkaline.taskbrain.EmulatorTestSupport.waitAndClickContentDescription
import org.alkaline.taskbrain.EmulatorTestSupport.waitAndClickText
import org.alkaline.taskbrain.EmulatorTestSupport.waitForNoteInStore
import org.alkaline.taskbrain.data.NoteRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On a freshly-loaded note (every line carries a real Firestore id),
 * single-line edits — indent, single-char insert, unindent — must
 * never allocate a fresh child doc or orphan an existing one.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class IncrementalSavesNoOrphansFlowTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    companion object {
        private const val TAG = "IncrementalSaves"
        @JvmStatic
        @BeforeClass
        fun classSetUp() = EmulatorTestSupport.requireEmulatorAndSignIn()
    }

    @Test
    fun singleEditOnEveryLineHasRealId_neverAllocatesFreshDocs() = runBlocking {
        val ts = System.currentTimeMillis()
        val title = "incr-$ts"
        // Unique tags so onAllNodesWithText can disambiguate.
        val a = "alpha$ts"
        val b = "beta$ts"
        val c = "gamma$ts"
        val d = "delta$ts"
        val e = "epsilon$ts"
        val f = "zeta$ts"
        val g = "eta$ts"
        val h = "theta$ts"
        val i = "iota$ts"

        // Tree depths 0-3, content on every line.
        val seedContent = buildString {
            append(title).append('\n')
            append("• ").append(a).append('\n')
            append("\t• ").append(b).append('\n')
            append("\t\t• ").append(c).append('\n')
            append("\t\t\t• ").append(d).append('\n')
            append("• ").append(e).append('\n')
            append("\t• ").append(f).append('\n')
            append("\t\t• ").append(g).append('\n')
            append("• ").append(h).append('\n')
            append("\t• ").append(i)
        }
        val repo = NoteRepository(
            FirebaseFirestore.getInstance(),
            com.google.firebase.auth.FirebaseAuth.getInstance(),
        )
        val rootId = repo.createMultiLineNote(seedContent).getOrThrow()
        composeTestRule.waitForNoteInStore(rootId)

        val (live0, deleted0) = descendantsByState(rootId)
        assertEquals(9, live0.size)
        assertTrue(deleted0.isEmpty())

        val activity = composeTestRule.activity
        val allNotesLabel = activity.getString(R.string.title_note_list)
        val saveLabel = activity.getString(R.string.action_save)
        val savedLabel = activity.getString(R.string.status_saved)
        val indentDesc = activity.getString(R.string.command_indent)
        val unindentDesc = activity.getString(R.string.command_unindent)

        composeTestRule.waitAndClickText(allNotesLabel)
        composeTestRule.waitAndClickText(title)
        composeTestRule.waitUntilAtLeastOneExists(hasText(saveLabel), 10_000)
        composeTestRule.waitUntilAtLeastOneExists(hasText(d, substring = true), 10_000)

        suspend fun assertSaveAllocatedNothing(stepLabel: String, prevLive: Set<String>) {
            composeTestRule.waitAndClickText(saveLabel)
            composeTestRule.waitUntilAtLeastOneExists(hasText(savedLabel), 15_000)

            val (live, deleted) = descendantsByState(rootId)
            val newlyAllocated = live - prevLive
            val noLongerLive = prevLive - live
            Log.i(TAG, "$stepLabel: live=${live.size} added=${newlyAllocated.size} " +
                "dropped=${noLongerLive.size} deleted=${deleted.size}")
            assertTrue(
                "$stepLabel allocated/dropped. Newly: $newlyAllocated. Dropped: $noLongerLive.",
                newlyAllocated.isEmpty() && noLongerLive.isEmpty(),
            )
            assertTrue("$stepLabel orphaned: $deleted.", deleted.isEmpty())
        }

        val ime = InstrumentationRegistry.getInstrumentation()

        composeTestRule.onAllNodesWithText(e, substring = true)[0].performClick()
        composeTestRule.waitForIdle()
        composeTestRule.waitAndClickContentDescription(indentDesc)
        composeTestRule.waitForIdle()
        assertSaveAllocatedNothing("step c (indent $e)", live0)

        composeTestRule.onAllNodesWithText(h, substring = true)[0].performClick()
        composeTestRule.waitForIdle()
        ime.sendStringSync("X")
        composeTestRule.waitForIdle()
        assertSaveAllocatedNothing("step d (add char to $h)", live0)

        composeTestRule.onAllNodesWithText(d, substring = true)[0].performClick()
        composeTestRule.waitForIdle()
        composeTestRule.waitAndClickContentDescription(unindentDesc)
        composeTestRule.waitForIdle()
        assertSaveAllocatedNothing("step e (unindent $d)", live0)
    }
}
