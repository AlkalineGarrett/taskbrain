package org.alkaline.taskbrain

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.alkaline.taskbrain.EmulatorTestSupport.seedMultiLineNote
import org.alkaline.taskbrain.EmulatorTestSupport.waitAndClickText
import org.alkaline.taskbrain.EmulatorTestSupport.waitForNoteInStore
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Soft-deletes a note via its row's overflow menu and verifies the row
 * relocates to the "Deleted notes" section.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class DeleteNoteFlowTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    companion object {
        @JvmStatic
        @BeforeClass
        fun classSetUp() = EmulatorTestSupport.requireEmulatorAndSignIn()
    }

    @Test
    fun deletingNoteMovesItToDeletedSection() {
        val unique = "delete-flow-${System.currentTimeMillis()}"
        val seedId = seedMultiLineNote(unique)
        composeTestRule.waitForNoteInStore(seedId)

        val activity = composeTestRule.activity
        val allNotesLabel = activity.getString(R.string.title_note_list)
        val moreOptionsLabel = activity.getString(R.string.action_more_options)
        val deleteLabel = activity.getString(R.string.action_delete_note)
        val deletedHeader = activity.getString(R.string.section_deleted_notes)

        composeTestRule.waitAndClickText(allNotesLabel)
        composeTestRule.waitUntilAtLeastOneExists(hasText(unique), 10_000)

        // Default sort is RECENT and we just waited for our seed to land in
        // NoteStore, so it's the freshest note and its overflow icon is the
        // first "More options" button on screen.
        composeTestRule.waitUntilAtLeastOneExists(hasContentDescription(moreOptionsLabel), 10_000)
        composeTestRule.onAllNodesWithContentDescription(moreOptionsLabel)[0].performClick()
        composeTestRule.waitAndClickText(deleteLabel, timeoutMillis = 5_000)

        composeTestRule.waitUntilAtLeastOneExists(hasText(deletedHeader), 10_000)
        composeTestRule.onNodeWithText(deletedHeader).assertIsDisplayed()
        composeTestRule.onNodeWithText(unique).assertIsDisplayed()
    }
}
