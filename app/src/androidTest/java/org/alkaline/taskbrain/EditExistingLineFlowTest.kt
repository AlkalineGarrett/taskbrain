package org.alkaline.taskbrain

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.alkaline.taskbrain.EmulatorTestSupport.seedMultiLineNote
import org.alkaline.taskbrain.EmulatorTestSupport.waitAndClickText
import org.alkaline.taskbrain.EmulatorTestSupport.waitForNoteInStore
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Seeds a multi-line note, opens it, types into the editor's auto-focused
 * line via the IME, saves, and verifies the edit persists across a navigation
 * round-trip while the untouched lines remain intact.
 *
 * The editor renders lines as plain Text composables and routes IME input
 * through a single View-level [SimpleInputConnection], so a tap from
 * instrumentation can't reliably move focus to an arbitrary line. We rely
 * on the editor auto-focusing on open and append a unique marker via
 * Instrumentation.sendStringSync — the same path EditorSaveFlowTest uses for
 * new notes — which exercises the save-diff path equivalently regardless of
 * which line ends up focused.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class EditExistingLineFlowTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    companion object {
        @JvmStatic
        @BeforeClass
        fun classSetUp() = EmulatorTestSupport.requireEmulatorAndSignIn()
    }

    @Test
    fun editingOneLineSavesAndPersistsChange() {
        val ts = System.currentTimeMillis()
        // Each seed line ends with a unique tail that survives substring
        // search even after the editor inserts our marker into one of them.
        val titleTail = "title-$ts"
        val title = "edit-line-$titleTail"
        val firstChild = "first-child-$ts"
        val secondChild = "second-child-$ts"
        val thirdChild = "third-child-$ts"
        val seedId = seedMultiLineNote("$title\n$firstChild\n$secondChild\n$thirdChild")
        composeTestRule.waitForNoteInStore(seedId)

        val activity = composeTestRule.activity
        val allNotesLabel = activity.getString(R.string.title_note_list)
        val saveLabel = activity.getString(R.string.action_save)
        val savedLabel = activity.getString(R.string.status_saved)

        composeTestRule.waitAndClickText(allNotesLabel)
        composeTestRule.waitAndClickText(title)
        // Once Save is composed the editor is mounted and the IME wired up.
        composeTestRule.waitUntilAtLeastOneExists(hasText(saveLabel), 10_000)
        composeTestRule.waitUntilAtLeastOneExists(hasText(thirdChild), 10_000)

        val editMarker = "EDIT$ts"
        InstrumentationRegistry.getInstrumentation().sendStringSync(editMarker)

        composeTestRule.waitUntilAtLeastOneExists(hasText(editMarker, substring = true), 10_000)
        composeTestRule.waitAndClickText(saveLabel)
        composeTestRule.waitUntilAtLeastOneExists(hasText(savedLabel), 10_000)

        // Round-trip: navigate away and reopen. The reload comes from
        // NoteStore (or Firestore on cold cache), so the assertions below
        // confirm the edit was actually persisted, not just held in editor
        // memory. The note's row in All Notes shows whichever line is the
        // root, so we look it up by the title's unique tail in case the
        // editor inserted the marker into the title itself.
        composeTestRule.waitAndClickText(allNotesLabel)
        composeTestRule.waitUntilAtLeastOneExists(hasText(titleTail, substring = true), 10_000)
        composeTestRule.onNodeWithText(titleTail, substring = true).performClick()
        composeTestRule.waitUntilAtLeastOneExists(hasText(editMarker, substring = true), 10_000)
        composeTestRule.onNodeWithText(editMarker, substring = true).assertIsDisplayed()
        // All seeded lines stay searchable as substrings: typing only inserts
        // into one line, so the others are still byte-for-byte intact and the
        // edited one keeps its original text plus the inserted marker.
        composeTestRule.onNodeWithText(titleTail, substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText(firstChild, substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText(secondChild, substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText(thirdChild, substring = true).assertIsDisplayed()
    }
}
