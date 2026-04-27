package org.alkaline.taskbrain

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.alkaline.taskbrain.EmulatorTestSupport.waitAndClickText
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the editor → save path that [CreateNoteFlowTest] bypasses:
 * tap "Add note" to open an empty editor, type via the system IME, tap
 * Save, switch to All-notes, and assert the typed text appears as a row.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class EditorSaveFlowTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    companion object {
        @JvmStatic
        @BeforeClass
        fun classSetUp() = EmulatorTestSupport.requireEmulatorAndSignIn()
    }

    @Test
    fun typedNoteIsSavedAndAppearsInList() {
        val activity = composeTestRule.activity
        val allNotesLabel = activity.getString(R.string.title_note_list)
        val addNoteLabel = activity.getString(R.string.action_add_note)
        val saveLabel = activity.getString(R.string.action_save)
        val savedLabel = activity.getString(R.string.status_saved)

        composeTestRule.waitAndClickText(allNotesLabel)
        composeTestRule.waitAndClickText(addNoteLabel)

        // Wait for the empty editor to mount; once the Save button is
        // composed the IME connection is wired up and ready to receive keys.
        composeTestRule.waitUntilAtLeastOneExists(hasText(saveLabel), 10_000)

        val typed = "typed-and-saved-${System.currentTimeMillis()}"
        // sendStringSync routes through the focused View's IME connection
        // (SimpleInputConnection.commitText), matching what a real
        // soft-keyboard would deliver.
        InstrumentationRegistry.getInstrumentation().sendStringSync(typed)

        composeTestRule.waitUntilAtLeastOneExists(hasText(typed), 10_000)
        composeTestRule.waitAndClickText(saveLabel)
        composeTestRule.waitUntilAtLeastOneExists(hasText(savedLabel), 10_000)

        composeTestRule.waitAndClickText(allNotesLabel)
        composeTestRule.waitUntilAtLeastOneExists(hasText(typed), 10_000)
        composeTestRule.onNodeWithText(typed).assertIsDisplayed()
    }
}
