package org.alkaline.taskbrain

import android.util.Log
import android.view.KeyEvent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.alkaline.taskbrain.EmulatorTestSupport.waitAndClickContentDescription
import org.alkaline.taskbrain.EmulatorTestSupport.waitAndClickText
import org.alkaline.taskbrain.EmulatorTestSupport.waitForNoteInStore
import org.alkaline.taskbrain.data.NoteRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pin: a 2026-05-07 production save report tripped the content-drop guard
 * after a real user did:
 *   1. Open a note with 6 bulleted child lines (mix of depth 0 and 1).
 *   2. Insert a new bulleted line near the top ("• Problem statement").
 *   3. Select the last 5 children and tap Indent.
 *   4. Press Enter at the end of the last line.
 *   5. Tap Unindent on the new line.
 *   6. Tap Save.
 *
 * Diagnostic showed every child trackedLine arriving at save as a sentinel
 * — even the 6 lines whose content matched existing Firestore docs — so
 * `planSaveNoteWithChildren` allocated fresh refs for all of them and put
 * the originals on `toDelete`, tripping the guard.
 *
 * A controller-only data-layer test (drives `EditorController` directly,
 * skips Compose) does NOT reproduce — the bug must live in the load /
 * recomposition / IME-lifecycle layers above the controller. This test
 * launches `MainActivity` and exercises the flow through the real UI.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class IndentEnterUnindentSaveFlowTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    companion object {
        private const val TAG = "IndentEnterUnindent"
        @JvmStatic
        @BeforeClass
        fun classSetUp() = EmulatorTestSupport.requireEmulatorAndSignIn()
    }

    @Test
    fun indentTypeEnterUnindentSave_preservesAllChildIds() {
        val ts = System.currentTimeMillis()
        // Unique-per-run titles so a stale seed from a prior failed run
        // doesn't get clicked instead of the fresh one.
        val titleTag = "ftt-$ts"
        val title = "IK fine tuning $titleTag"
        val loraTag = "lora-$ts"
        val lowRankTag = "lowrank-$ts"
        val reduceTag = "reduce-$ts"
        val qloraTag = "qlora-$ts"
        val f32Tag = "f32-$ts"

        // Seed: parent + 6 children mirroring the user's tree.
        //   Lora            (depth 0)
        //     Low rank       (depth 1)
        //     Reduce memory  (depth 1)
        //   Qlora           (depth 0)
        //     F32            (depth 1)
        //   (empty bullet)  (depth 0)
        val repo = NoteRepository(
            FirebaseFirestore.getInstance(),
            com.google.firebase.auth.FirebaseAuth.getInstance(),
        )
        val seedContent = buildString {
            append(title).append('\n')
            append("• Lora-").append(loraTag).append('\n')
            append("\t• Low rank-").append(lowRankTag).append('\n')
            append("\t• Reduce memory-").append(reduceTag).append('\n')
            append("• Qlora-").append(qloraTag).append('\n')
            append("\t• F32-").append(f32Tag).append('\n')
            append("• ")
        }
        val rootId = runBlocking { repo.createMultiLineNote(seedContent).getOrThrow() }
        composeTestRule.waitForNoteInStore(rootId)

        // Capture the original child + grandchild ids before any UI edit.
        val (originalChildIds, originalAllDescendantIds) = runBlocking {
            val rootSnap = FirebaseFirestore.getInstance()
                .collection("notes").document(rootId).get().await()
            @Suppress("UNCHECKED_CAST")
            val childIds = rootSnap["containedNotes"] as List<String>
            val all = mutableSetOf<String>()
            all.addAll(childIds)
            for (cid in childIds) {
                val csnap = FirebaseFirestore.getInstance()
                    .collection("notes").document(cid).get().await()
                @Suppress("UNCHECKED_CAST")
                all.addAll((csnap["containedNotes"] as? List<String>) ?: emptyList())
            }
            childIds to all
        }
        Log.i(TAG, "seeded rootId=$rootId childIds=$originalChildIds " +
            "allDescendants=$originalAllDescendantIds")
        assertEquals("seed shape: 3 direct children", 3, originalChildIds.size)
        assertEquals("seed shape: 6 total descendants", 6, originalAllDescendantIds.size)

        val activity = composeTestRule.activity
        val allNotesLabel = activity.getString(R.string.title_note_list)
        val saveLabel = activity.getString(R.string.action_save)
        val savedLabel = activity.getString(R.string.status_saved)
        val indentDesc = activity.getString(R.string.command_indent)
        val unindentDesc = activity.getString(R.string.command_unindent)

        // Open the note via the all-notes list.
        composeTestRule.waitAndClickText(allNotesLabel)
        composeTestRule.waitAndClickText(title)
        composeTestRule.waitUntilAtLeastOneExists(hasText(saveLabel), 10_000)
        composeTestRule.waitUntilAtLeastOneExists(hasText(f32Tag, substring = true), 10_000)

        // The 2026-05-07 reproducing sequence:
        //   1. Type "ProblemStatement<ts>" via IME commitText.
        //   2. Press Enter via key event.
        //   3. Type "AfterEnter<ts>" via IME commitText.
        //   4. Tap Indent.
        //   5. Press Enter via key event.
        //   6. Tap Unindent.
        //   7. Tap Save.
        //
        // The save fired during this sequence shows the bug pattern in
        // logcat:  saveNoteWithChildren: sentinel origins={typed=1,
        // split=15}   — the same pattern the production crash report
        // had. The visual text-presence wait at step 3 may time out (as
        // it did in the reproducing run) — but the save fires anyway
        // and the log shows the sentinels.
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        val problemStatementMarker = "ProblemStatement$ts"
        instrumentation.sendStringSync(problemStatementMarker)
        composeTestRule.waitUntilAtLeastOneExists(
            hasText(problemStatementMarker, substring = true), 10_000,
        )
        Log.i(TAG, "step 1: typed '$problemStatementMarker', visible")

        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
        // After Enter, the OS InputConnection transitions to the new
        // line's LineImeState — that's an asynchronous handoff. Don't
        // wait for the post-Enter typing to be visible (intermediate
        // text-presence races with the IME reconnect on the AVD); the
        // structural save assertion below catches any real regression.
        composeTestRule.waitForIdle()
        val afterEnterMarker = "AfterEnter$ts"
        instrumentation.sendStringSync(afterEnterMarker)
        composeTestRule.waitForIdle()
        Log.i(TAG, "step 2: pressed Enter + typed '$afterEnterMarker'")

        composeTestRule.waitAndClickContentDescription(indentDesc)
        Log.i(TAG, "step 3: tapped Indent")

        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
        composeTestRule.waitAndClickContentDescription(unindentDesc)
        Log.i(TAG, "step 4: pressed Enter + tapped Unindent")

        // Save.
        composeTestRule.waitAndClickText(saveLabel)

        // Two outcomes possible:
        // (a) Save succeeds → "Saved" label appears. Assert no original
        //     descendant doc was deleted.
        // (b) Save fails (content-drop guard) → "Saved" never appears.
        //     We catch the timeout and surface it as a test failure with
        //     the diagnostic context.
        try {
            composeTestRule.waitUntilAtLeastOneExists(hasText(savedLabel), 15_000)
        } catch (e: Throwable) {
            Log.e(TAG, "Save did not reach 'Saved' state — likely the " +
                "content-drop guard fired. Check logcat tag 'NoteRepository' " +
                "for diagnostics.")
            throw AssertionError(
                "Save did not complete (timeout waiting for '$savedLabel'). " +
                    "Bug surface: content-drop guard likely tripped. " +
                    "rootId=$rootId originalDescendants=$originalAllDescendantIds",
                e,
            )
        }

        // Verify every original descendant is still live.
        runBlocking {
            for (id in originalAllDescendantIds) {
                val snap = FirebaseFirestore.getInstance()
                    .collection("notes").document(id).get().await()
                assertNotNull("original descendant $id was deleted from Firestore",
                    snap.data)
                val state = snap.getString("state")
                assertNotEquals(
                    "original descendant $id is in state 'deleted' after save",
                    "deleted", state,
                )
            }
        }
    }
}
