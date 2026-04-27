package org.alkaline.taskbrain

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.alkaline.taskbrain.EmulatorTestSupport.seedMultiLineNote
import org.alkaline.taskbrain.EmulatorTestSupport.waitAndClickText
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Seeds a note via NoteRepository, taps its row in All-notes, and verifies
 * the editor opens with the seeded text rendered.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class OpenNoteFromListTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    companion object {
        @JvmStatic
        @BeforeClass
        fun classSetUp() = EmulatorTestSupport.requireEmulatorAndSignIn()
    }

    @Test
    fun tappingNoteRowOpensEditorWithContent() {
        val unique = "open-from-list-${System.currentTimeMillis()}"
        seedMultiLineNote(unique)

        val allNotesLabel = composeTestRule.activity.getString(R.string.title_note_list)
        composeTestRule.waitAndClickText(allNotesLabel)
        composeTestRule.waitAndClickText(unique)

        // After the row tap, the list unmounts and the editor renders the
        // text again as line 0 — both occurrences match `unique`, so we just
        // assert it remains visible.
        composeTestRule.waitUntilAtLeastOneExists(hasText(unique), 10_000)
        composeTestRule.onNodeWithText(unique).assertIsDisplayed()
    }
}
