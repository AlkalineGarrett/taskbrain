package org.alkaline.taskbrain

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.alkaline.taskbrain.data.NoteRepository
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UI smoke test against the Firebase Emulator Suite. Launches MainActivity,
 * creates a note via NoteRepository, then taps the "All notes" tab and
 * asserts the note's content renders in the list.
 *
 * Prereqs:
 *   1. `firebase emulators:start` from the repo root.
 *   2. Run with `-PuseFirebaseEmulator=true`.
 *
 * Without the build flag the test self-skips via [Assume].
 *
 * Anonymous sign-in must complete BEFORE the activity launches so the
 * MainScreen LaunchedEffect picks the signed-in nav path on first
 * composition (avoids a navController.navigate-before-NavHost race).
 * Notification permission requests are suppressed in emulator mode (see
 * MainActivity.requestNotificationPermissionIfNeeded).
 */
@RunWith(AndroidJUnit4::class)
class CreateNoteFlowTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    private val auth get() = FirebaseAuth.getInstance()
    private val db get() = FirebaseFirestore.getInstance()

    companion object {
        @JvmStatic
        @BeforeClass
        fun classSetUp() {
            Assume.assumeTrue(
                "Set -PuseFirebaseEmulator=true and start Firebase emulators before running",
                BuildConfig.USE_FIREBASE_EMULATOR
            )
            runBlocking {
                val auth = FirebaseAuth.getInstance()
                if (auth.currentUser == null) {
                    withTimeout(10_000) { auth.signInAnonymously().await() }
                }
            }
        }
    }

    @Test
    fun createdNoteAppearsInAllNotesView() {
        runBlocking {
            val unique = "emulator-smoke-${System.currentTimeMillis()}"
            NoteRepository(db, auth).createMultiLineNote(unique).getOrThrow()

            val allNotesLabel = composeTestRule.activity.getString(R.string.title_note_list)
            composeTestRule.waitUntil(timeoutMillis = 10_000) {
                composeTestRule.onAllNodesWithText(allNotesLabel).fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.onNodeWithText(allNotesLabel).performClick()

            composeTestRule.waitUntil(timeoutMillis = 10_000) {
                composeTestRule.onAllNodesWithText(unique).fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.onNodeWithText(unique).assertIsDisplayed()
        }
    }
}
