package org.alkaline.taskbrain

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.runBlocking
import org.alkaline.taskbrain.EmulatorTestSupport.setSystemClipboardText
import org.alkaline.taskbrain.EmulatorTestSupport.waitAndClickContentDescription
import org.alkaline.taskbrain.EmulatorTestSupport.waitAndClickText
import org.alkaline.taskbrain.EmulatorTestSupport.waitForNoteInStore
import org.alkaline.taskbrain.saverefactor.SaveRefactorTestSupport.writeAsOtherClient
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Pin: paste→undo inside an embedded note (the inline editor of a
 * `[view find(...)]` match) must remove the pasted text on a single Undo
 * tap. With the bug, the pasted marker remains visible after undo.
 *
 * Test shape:
 *   1. Seed an embedded match note and a parent whose second line is a
 *      `[view find(...)]` directive matching it (direct Firestore writes,
 *      so notes arrive via the listener — same path a second client uses).
 *   2. Open the parent through the UI; the embedded note renders inline.
 *   3. Tap the embedded note's text. The inline editor's `onFocusChanged`
 *      calls `selectionCoordinator.activate(EditorId.View(...))`, which
 *      routes the command bar's Paste and the status bar's Undo to the
 *      embedded editor's controller.
 *   4. Put a unique marker on the system clipboard; tap Paste; assert the
 *      marker rendered.
 *   5. Tap Undo; assert the marker is gone and the embedded + parent
 *      content remain intact.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class EmbeddedNotePasteUndoFlowTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    companion object {
        @JvmStatic
        @BeforeClass
        fun classSetUp() = EmulatorTestSupport.requireEmulatorAndSignIn()
    }

    @Test
    fun pasteIntoEmbeddedNoteCanBeUndone() {
        val ts = System.currentTimeMillis()
        val embeddedTag = "embedded-$ts"
        val embeddedContent = "$embeddedTag-base"
        val title = "view-host-$ts"

        val parentId = "ext_${UUID.randomUUID()}"
        val directiveLineId = "ext_${UUID.randomUUID()}"
        val embeddedId = "ext_${UUID.randomUUID()}"

        runBlocking {
            val uid = FirebaseAuth.getInstance().currentUser!!.uid
            writeAsOtherClient(noteId = embeddedId, userId = uid, content = embeddedContent)
            writeAsOtherClient(
                noteId = directiveLineId,
                userId = uid,
                content = "[view find(name: pattern(\"$embeddedTag\" any*(0..)))]",
                parentNoteId = parentId,
                rootNoteId = parentId,
            )
            writeAsOtherClient(
                noteId = parentId,
                userId = uid,
                content = title,
                containedNotes = listOf(directiveLineId),
            )
        }

        composeTestRule.waitForNoteInStore(parentId)
        composeTestRule.waitForNoteInStore(directiveLineId)
        composeTestRule.waitForNoteInStore(embeddedId)

        val activity = composeTestRule.activity
        val allNotesLabel = activity.getString(R.string.title_note_list)
        val saveLabel = activity.getString(R.string.action_save)
        val pasteDesc = activity.getString(R.string.action_paste)
        val undoDesc = activity.getString(R.string.action_undo)

        composeTestRule.waitAndClickText(allNotesLabel)
        composeTestRule.waitAndClickText(title)
        composeTestRule.waitUntilAtLeastOneExists(hasText(saveLabel), 10_000)
        composeTestRule.waitUntilAtLeastOneExists(hasText(embeddedContent, substring = true), 10_000)

        // Tap the embedded note's text to activate its inline editor.
        composeTestRule.onNodeWithText(embeddedContent, substring = true).performClick()

        val pastedMarker = "PASTE$ts"
        setSystemClipboardText(pastedMarker, label = "embedded-paste-undo-test")

        composeTestRule.waitAndClickContentDescription(pasteDesc)
        composeTestRule.waitUntilAtLeastOneExists(hasText(pastedMarker, substring = true), 10_000)

        composeTestRule.waitAndClickContentDescription(undoDesc)

        // After undo: marker gone, embedded + parent content intact. With the
        // bug the marker is still present and waitUntil times out.
        composeTestRule.waitUntil(10_000) {
            composeTestRule.onAllNodes(hasText(pastedMarker, substring = true))
                .fetchSemanticsNodes().isEmpty()
        }
        composeTestRule.onNodeWithText(embeddedContent, substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText(title, substring = true).assertIsDisplayed()
    }
}
