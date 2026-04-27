package org.alkaline.taskbrain

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.alkaline.taskbrain.data.NoteRepository
import org.alkaline.taskbrain.data.NoteStore
import org.junit.Assume

/**
 * Shared `@BeforeClass` setup and helpers for instrumentation tests that
 * require the Firebase Emulator Suite. Self-skips when
 * `-PuseFirebaseEmulator=true` was not passed at build time, otherwise
 * ensures an anonymous Firebase user exists before MainActivity is launched
 * (so MainScreen picks the signed-in start destination on first composition).
 */
object EmulatorTestSupport {
    private const val DEFAULT_WAIT_MS = 10_000L

    fun requireEmulatorAndSignIn() {
        Assume.assumeTrue(
            "Set -PuseFirebaseEmulator=true and start Firebase emulators before running",
            BuildConfig.USE_FIREBASE_EMULATOR
        )
        runBlocking {
            val auth = FirebaseAuth.getInstance()
            if (auth.currentUser == null) {
                withTimeout(DEFAULT_WAIT_MS) { auth.signInAnonymously().await() }
            }
        }
    }

    fun seedMultiLineNote(content: String): String = runBlocking {
        NoteRepository(FirebaseFirestore.getInstance(), FirebaseAuth.getInstance())
            .createMultiLineNote(content)
            .getOrThrow()
    }

    /**
     * Waits until the snapshot listener has received [seedId]. Necessary
     * after seeding via NoteRepository because the batch.commit().await()
     * returns before the snapshot is delivered to NoteStore.
     */
    @OptIn(ExperimentalTestApi::class)
    fun ComposeContentTestRule.waitForNoteInStore(
        seedId: String,
        timeoutMillis: Long = DEFAULT_WAIT_MS,
    ) {
        waitUntil(timeoutMillis) { NoteStore.getRawNoteById(seedId) != null }
    }

    @OptIn(ExperimentalTestApi::class)
    fun ComposeContentTestRule.waitAndClickText(
        text: String,
        timeoutMillis: Long = DEFAULT_WAIT_MS,
    ) {
        waitUntilAtLeastOneExists(hasText(text), timeoutMillis)
        onNodeWithText(text).performClick()
    }

    @OptIn(ExperimentalTestApi::class)
    fun ComposeContentTestRule.waitAndClickContentDescription(
        description: String,
        timeoutMillis: Long = DEFAULT_WAIT_MS,
    ) {
        waitUntilAtLeastOneExists(hasContentDescription(description), timeoutMillis)
        onNodeWithContentDescription(description).performClick()
    }
}
