package org.alkaline.taskbrain

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.runBlocking
import org.alkaline.taskbrain.EmulatorTestSupport.waitAndClickText
import org.alkaline.taskbrain.EmulatorTestSupport.waitForNoteInStore
import org.alkaline.taskbrain.data.NoteStore
import org.alkaline.taskbrain.saverefactor.SaveRefactorTestSupport.waitFor
import org.alkaline.taskbrain.saverefactor.SaveRefactorTestSupport.writeAsOtherClient
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * End-to-end pin for the live-sync contract: a top-level note created
 * locally on the device shows up in a `[view find(...)]` directive that
 * lives in another, already-open note. The data-layer mirror of this is
 * `NoteStoreOwnEchoIncrementalTest`; this version drives the actual UI
 * through Compose against the Firebase emulator.
 *
 * Test shape:
 *   1. Seed the host note (with the `[view find(...)]` directive on its
 *      second line) and one pre-existing match `B` via direct Firestore
 *      writes — same path a second client's writes would take.
 *   2. Open the host through the UI; assert `B`'s name renders inside
 *      the view directive's inline content (sanity check).
 *   3. From All-notes, tap "Add note", type a NEW matching marker `C`,
 *      and save — the exact code path the user takes.
 *   4. Reopen the host. Assert that BOTH `B` and `C` render inside the
 *      view.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ViewDirectiveLocalCreatePropagationTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    companion object {
        @JvmStatic
        @BeforeClass
        fun classSetUp() = EmulatorTestSupport.requireEmulatorAndSignIn()
    }

    @Test
    fun newLocalNoteAppearsInExistingViewDirective() {
        val ts = System.currentTimeMillis()
        val markerB = "marker-$ts-B"
        val markerC = "marker-$ts-C"
        val title = "view-host-$ts"

        val hostId = "ext_${UUID.randomUUID()}"
        val directiveLineId = "ext_${UUID.randomUUID()}"
        val bId = "ext_${UUID.randomUUID()}"

        // Seed the host (root + child line) and the pre-existing match B
        // via direct Firestore writes — bypassing NoteRepository so they
        // arrive at the listener exactly as a second client's writes would.
        runBlocking {
            val uid = FirebaseAuth.getInstance().currentUser!!.uid
            writeAsOtherClient(
                noteId = directiveLineId,
                userId = uid,
                content = "[view find(name: pattern(\"marker-$ts-\" any*(0..)))]",
                parentNoteId = hostId,
                rootNoteId = hostId,
            )
            writeAsOtherClient(
                noteId = hostId,
                userId = uid,
                content = title,
                containedNotes = listOf(directiveLineId),
            )
            writeAsOtherClient(noteId = bId, userId = uid, content = markerB)
        }

        composeTestRule.waitForNoteInStore(hostId)
        composeTestRule.waitForNoteInStore(directiveLineId)
        composeTestRule.waitForNoteInStore(bId)

        val activity = composeTestRule.activity
        val allNotesLabel = activity.getString(R.string.title_note_list)
        val addNoteLabel = activity.getString(R.string.action_add_note)
        val saveLabel = activity.getString(R.string.action_save)
        val savedLabel = activity.getString(R.string.status_saved)

        // ── 1. Open the host. The view directive matches B and renders
        //       its content inline beneath the directive line.
        composeTestRule.waitAndClickText(allNotesLabel)
        composeTestRule.waitAndClickText(title)
        composeTestRule.waitUntilAtLeastOneExists(hasText(markerB, substring = true), 10_000)

        // ── 2. Create a NEW top-level note via the user-facing flow:
        //       Add note → type marker → Save.
        composeTestRule.waitAndClickText(allNotesLabel)
        composeTestRule.waitAndClickText(addNoteLabel)
        composeTestRule.waitUntilAtLeastOneExists(hasText(saveLabel), 10_000)

        InstrumentationRegistry.getInstrumentation().sendStringSync(markerC)
        composeTestRule.waitUntilAtLeastOneExists(hasText(markerC), 10_000)

        composeTestRule.waitAndClickText(saveLabel)
        composeTestRule.waitUntilAtLeastOneExists(hasText(savedLabel), 10_000)

        // Wait for the listener to deliver C into `NoteStore.notes` — that's
        // the surface the view directive's `find(...)` reads, so this is
        // exactly the signal we need before navigating back to the host.
        runBlocking {
            waitFor(timeoutMs = 10_000) {
                NoteStore.notes.value.any { it.content == markerC }
            }
        }

        // ── 3. Reopen the host. The view directive should now match
        //       BOTH B and C and render both names inline. With the
        //       bug, C never enters `NoteStore.notes`, the cached view
        //       result is judged fresh, and only B renders.
        composeTestRule.waitAndClickText(allNotesLabel)
        composeTestRule.waitAndClickText(title)

        composeTestRule.waitUntilAtLeastOneExists(hasText(markerB, substring = true), 10_000)
        composeTestRule.onNodeWithText(markerB, substring = true).assertIsDisplayed()

        composeTestRule.waitUntilAtLeastOneExists(hasText(markerC, substring = true), 10_000)
        composeTestRule.onNodeWithText(markerC, substring = true).assertIsDisplayed()
    }
}
