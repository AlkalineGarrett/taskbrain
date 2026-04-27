package org.alkaline.taskbrain

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.alkaline.taskbrain.EmulatorTestSupport.seedMultiLineNote
import org.alkaline.taskbrain.EmulatorTestSupport.waitAndClickText
import org.alkaline.taskbrain.EmulatorTestSupport.waitForNoteInStore
import org.alkaline.taskbrain.ui.currentnote.util.LinePrefixes
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Toggles "Show completed" on the StatusBar overflow menu and verifies a
 * checked checkbox line is replaced by an "(N completed)" placeholder
 * when hidden, and reappears when shown.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ShowCompletedToggleTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    companion object {
        @JvmStatic
        @BeforeClass
        fun classSetUp() = EmulatorTestSupport.requireEmulatorAndSignIn()
    }

    @Test
    fun togglingShowCompletedHidesAndReturnsCheckedLine() {
        val ts = System.currentTimeMillis()
        val title = "show-completed-title-$ts"
        // The editor renders the "☑ " checkbox prefix as a separate Text
        // composable from the rest of the line, so we look for the unique
        // tail rather than the whole line.
        val checkedTail = "done-$ts"
        val seedId = seedMultiLineNote("$title\n${LinePrefixes.CHECKBOX_CHECKED}$checkedTail")
        composeTestRule.waitForNoteInStore(seedId, timeoutMillis = 15_000)

        val activity = composeTestRule.activity
        val allNotesLabel = activity.getString(R.string.title_note_list)
        val moreOptionsLabel = activity.getString(R.string.action_more_options)
        val showCompletedLabel = activity.getString(R.string.action_show_completed)
        val placeholder = activity.getString(R.string.completed_count, 1)

        composeTestRule.waitAndClickText(allNotesLabel)
        composeTestRule.waitAndClickText(title)
        composeTestRule.waitUntilAtLeastOneExists(hasText(checkedTail), 10_000)

        // Only "More options" on the CurrentNote screen is the StatusBar
        // overflow, so index 0 is unambiguous.
        composeTestRule.waitUntilAtLeastOneExists(hasContentDescription(moreOptionsLabel), 5_000)
        composeTestRule.onAllNodesWithContentDescription(moreOptionsLabel)[0].performClick()
        composeTestRule.waitAndClickText(showCompletedLabel, timeoutMillis = 5_000)

        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithText(checkedTail).fetchSemanticsNodes().isEmpty() &&
                composeTestRule.onAllNodesWithText(placeholder).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(placeholder).assertIsDisplayed()

        composeTestRule.onAllNodesWithContentDescription(moreOptionsLabel)[0].performClick()
        composeTestRule.waitAndClickText(showCompletedLabel, timeoutMillis = 5_000)
        composeTestRule.waitUntilAtLeastOneExists(hasText(checkedTail), 10_000)
        composeTestRule.onNodeWithText(checkedTail).assertIsDisplayed()
    }
}
