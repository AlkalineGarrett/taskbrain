package org.alkaline.taskbrain

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.test.platform.app.InstrumentationRegistry
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

    /**
     * True when running on an Android emulator (AVD). False on a
     * physical device. Detected via `Build.HARDWARE` — `ranchu` is
     * the current emulator, `goldfish` covers older AVDs. Belt-and-
     * suspenders with `Build.FINGERPRINT` for the rare case of a
     * non-AOSP emulator with custom hardware string.
     */
    private fun isRunningOnEmulator(): Boolean {
        val hw = android.os.Build.HARDWARE.lowercase()
        if (hw.contains("ranchu") || hw.contains("goldfish")) return true
        val fingerprint = android.os.Build.FINGERPRINT.lowercase()
        return fingerprint.startsWith("generic") || fingerprint.contains("emulator")
    }

    fun requireEmulatorAndSignIn() {
        // Hard guard: the Firebase emulator runs on the host machine's
        // localhost, which only the AVD can reach (via 10.0.2.2). On a
        // real device the app would either fail to connect or — far
        // worse — hit the production Firestore project instead and
        // mutate real data. Refuse to run with a clear error if the
        // test was started on a physical device.
        check(isRunningOnEmulator()) {
            "Emulator-only test cannot run on a physical device " +
                "(Build.HARDWARE=${android.os.Build.HARDWARE}, " +
                "Build.FINGERPRINT=${android.os.Build.FINGERPRINT}). " +
                "Use an AVD: scripts/test-env-up.sh --with-avd"
        }
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

    /**
     * Set the system clipboard to [text]. `setPrimaryClip` is a main-thread API,
     * so this hops to the main looper via the instrumentation.
     */
    fun setSystemClipboardText(text: String, label: String = "test-clipboard") {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val clipboard = instrumentation.targetContext
                .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        }
    }

    /**
     * Returns (live, deleted) doc-id sets for descendants of [rootId] —
     * read directly from Firestore so tests assert wire state, not local
     * mirror state. Used to detect orphan accumulation across saves.
     */
    suspend fun descendantsByState(rootId: String): Pair<Set<String>, Set<String>> {
        val snap = FirebaseFirestore.getInstance()
            .collection("notes")
            .whereEqualTo("rootNoteId", rootId)
            .get().await()
        val live = snap.documents
            .filter { it.getString("state") != "deleted" }
            .map { it.id }
            .toSet()
        val deleted = snap.documents
            .filter { it.getString("state") == "deleted" }
            .map { it.id }
            .toSet()
        return live to deleted
    }
}
