package org.alkaline.taskbrain

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.alkaline.taskbrain.EmulatorTestSupport.seedMultiLineNote
import org.alkaline.taskbrain.EmulatorTestSupport.setSystemClipboardText
import org.alkaline.taskbrain.EmulatorTestSupport.waitAndClickContentDescription
import org.alkaline.taskbrain.EmulatorTestSupport.waitAndClickText
import org.alkaline.taskbrain.EmulatorTestSupport.waitForNoteInStore
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end pin for paste→undo: text pasted via the command bar's Paste
 * button must be removed by a single tap on the Undo button. With the bug,
 * the pasted marker remains visible after undo.
 *
 * Test shape:
 *   1. Seed a two-line note and open it through the UI; the editor
 *      auto-focuses, so the command bar's Paste button becomes enabled.
 *   2. Put a unique marker on the system clipboard (`runOnMainSync` —
 *      `ClipboardManager.setPrimaryClip` is a main-thread API).
 *   3. Tap Paste; assert the marker is rendered.
 *   4. Tap Undo; assert the marker is gone and the seeded lines are intact.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class PasteUndoFlowTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    companion object {
        @JvmStatic
        @BeforeClass
        fun classSetUp() = EmulatorTestSupport.requireEmulatorAndSignIn()
    }

    @Test
    fun pasteCanBeUndone() {
        val ts = System.currentTimeMillis()
        val title = "paste-undo-$ts"
        val baseLine = "base-line-$ts"
        val seedId = seedMultiLineNote("$title\n$baseLine")
        composeTestRule.waitForNoteInStore(seedId)

        val activity = composeTestRule.activity
        val allNotesLabel = activity.getString(R.string.title_note_list)
        val saveLabel = activity.getString(R.string.action_save)
        val pasteDesc = activity.getString(R.string.action_paste)
        val undoDesc = activity.getString(R.string.action_undo)

        composeTestRule.waitAndClickText(allNotesLabel)
        composeTestRule.waitAndClickText(title)
        // Save being composed means the editor is mounted and the IME wired up.
        composeTestRule.waitUntilAtLeastOneExists(hasText(saveLabel), 10_000)
        composeTestRule.waitUntilAtLeastOneExists(hasText(baseLine, substring = true), 10_000)

        val pastedMarker = "PASTE$ts"
        setSystemClipboardText(pastedMarker, label = "paste-undo-test")

        composeTestRule.waitAndClickContentDescription(pasteDesc)
        composeTestRule.waitUntilAtLeastOneExists(hasText(pastedMarker, substring = true), 10_000)

        composeTestRule.waitAndClickContentDescription(undoDesc)

        // After undo, the pasted marker must be gone. With the bug it persists.
        composeTestRule.waitUntil(10_000) {
            composeTestRule.onAllNodes(hasText(pastedMarker, substring = true))
                .fetchSemanticsNodes().isEmpty()
        }

        // Seeded lines remain intact — undo restored, didn't blow away history.
        composeTestRule.onNodeWithText(title, substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText(baseLine, substring = true).assertIsDisplayed()
    }
}
