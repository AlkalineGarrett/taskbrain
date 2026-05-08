package org.alkaline.taskbrain

import android.util.Log
import android.view.KeyEvent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.runBlocking
import org.alkaline.taskbrain.EmulatorTestSupport.descendantsByState
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
 * Two saves with one trivial edit between them must not allocate a
 * fresh Firestore doc for any line that already had a real id at the
 * end of save 1, and must not soft-delete any doc save 1 produced.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ConsecutiveSavesNoOrphansFlowTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    companion object {
        private const val TAG = "ConsecutiveSaves"
        @JvmStatic
        @BeforeClass
        fun classSetUp() = EmulatorTestSupport.requireEmulatorAndSignIn()
    }

    @Test
    fun secondSave_doesNotAllocateFreshDocsOrOrphanChildren() = runBlocking {
        val ts = System.currentTimeMillis()
        val title = "consec-$ts"

        val repo = NoteRepository(
            FirebaseFirestore.getInstance(),
            com.google.firebase.auth.FirebaseAuth.getInstance(),
        )
        val rootId = repo.createMultiLineNote(title).getOrThrow()
        composeTestRule.waitForNoteInStore(rootId)

        val activity = composeTestRule.activity
        val allNotesLabel = activity.getString(R.string.title_note_list)
        val saveLabel = activity.getString(R.string.action_save)
        val savedLabel = activity.getString(R.string.status_saved)

        composeTestRule.waitAndClickText(allNotesLabel)
        composeTestRule.waitAndClickText(title)
        composeTestRule.waitUntilAtLeastOneExists(hasText(saveLabel), 10_000)

        // Type Enter + child content. The Enter creates a sentinel
        // child line; the IME chars land on that child.
        val ime = InstrumentationRegistry.getInstrumentation()
        val childMarker = "child-$ts"
        ime.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
        composeTestRule.waitForIdle()
        ime.sendStringSync(childMarker)
        composeTestRule.waitForIdle()

        composeTestRule.waitAndClickText(saveLabel)
        composeTestRule.waitUntilAtLeastOneExists(hasText(savedLabel), 15_000)

        val (liveAfter1, deletedAfter1) = descendantsByState(rootId)
        Log.i(TAG, "after save 1: live=$liveAfter1 deleted=$deletedAfter1")
        assertEquals(1, liveAfter1.size)
        assertTrue(deletedAfter1.isEmpty())

        ime.sendStringSync("X")
        composeTestRule.waitForIdle()

        composeTestRule.waitAndClickText(saveLabel)
        composeTestRule.waitUntilAtLeastOneExists(hasText(savedLabel), 15_000)

        val (liveAfter2, deletedAfter2) = descendantsByState(rootId)
        Log.i(TAG, "after save 2: live=$liveAfter2 deleted=$deletedAfter2")

        val newlyAllocated = liveAfter2 - liveAfter1
        val noLongerLive = liveAfter1 - liveAfter2
        assertTrue(
            "second save allocated/orphaned. Newly: $newlyAllocated. Dropped: $noLongerLive.",
            newlyAllocated.isEmpty() && noLongerLive.isEmpty(),
        )
        assertTrue(
            "second save soft-deleted children: $deletedAfter2",
            deletedAfter2.isEmpty(),
        )
    }
}
