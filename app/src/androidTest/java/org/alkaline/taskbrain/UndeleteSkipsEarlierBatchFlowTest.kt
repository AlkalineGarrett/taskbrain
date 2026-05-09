package org.alkaline.taskbrain

import android.util.Log
import android.view.KeyEvent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.waitUntilDoesNotExist
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.alkaline.taskbrain.EmulatorTestSupport.descendantsByState
import org.alkaline.taskbrain.EmulatorTestSupport.waitAndClickText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end UI regression test for the per-line `deletionBatchId` design.
 *
 *  1. Create a fresh 3-line note via the editor (title + line-a + line-b).
 *  2. Save → all 3 docs go LIVE with no `deletionBatchId`.
 *  3. Tap the gutter at line-a (the middle line). The gutter tap completes
 *     a whole-line selection and the selection context menu pops up.
 *  4. Tap "Delete" in the context menu → the editor records line-a's
 *     noteId in `pendingSoftDeletes` with source `SELECTION_DELETE`.
 *  5. Save → line-a is soft-deleted with `selection-delete_<uuid>`.
 *  6. Navigate to All-notes and delete the whole note via row overflow →
 *     `softDeleteNote` stamps `whole-note_<uuid>` on the title and line-b.
 *  7. Restore the note from the deleted section. Only descendants whose
 *     `deletionBatchId` matches the root's `whole-note_*` come back.
 *  8. Open the restored note: line-a stays deleted (different earlier
 *     batch), line-b reappears, the editor renders title + line-b only.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class UndeleteSkipsEarlierBatchFlowTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    companion object {
        private const val TAG = "UndeleteSkipsEarlierBatchFlowTest"

        @JvmStatic
        @BeforeClass
        fun classSetUp() = EmulatorTestSupport.requireEmulatorAndSignIn()
    }

    @Test
    fun undeleteRestoresOnlyTheSameBatchAndLeavesEarlierDeletionsDeleted() {
        val activity = composeTestRule.activity
        val allNotesLabel = activity.getString(R.string.title_note_list)
        val addNoteLabel = activity.getString(R.string.action_add_note)
        val saveLabel = activity.getString(R.string.action_save)
        val savedLabel = activity.getString(R.string.status_saved)
        val deleteSelectionLabel = activity.getString(R.string.action_delete) // "Delete" in selection menu
        val moreOptionsLabel = activity.getString(R.string.action_more_options)
        val deleteNoteLabel = activity.getString(R.string.action_delete_note)
        val restoreLabel = activity.getString(R.string.action_restore_note)
        val deletedHeader = activity.getString(R.string.section_deleted_notes)
        val needsFixLabel = activity.getString(R.string.status_needs_fix) // "Fix"

        val ts = System.currentTimeMillis()
        val title = "undelete-flow-title-$ts"
        val lineA = "undelete-flow-line-a-$ts"
        val lineB = "undelete-flow-line-b-$ts"
        val ime = InstrumentationRegistry.getInstrumentation()

        // ── Step 1+2: Type 3 lines in a fresh editor and save ──────────
        composeTestRule.waitAndClickText(allNotesLabel)
        composeTestRule.waitAndClickText(addNoteLabel)
        composeTestRule.waitUntilAtLeastOneExists(hasText(saveLabel), 10_000)

        ime.sendStringSync(title)
        ime.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
        composeTestRule.waitForIdle()
        ime.sendStringSync(lineA)
        ime.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
        composeTestRule.waitForIdle()
        ime.sendStringSync(lineB)
        composeTestRule.waitUntilAtLeastOneExists(hasText(lineB, substring = true), 10_000)

        composeTestRule.waitAndClickText(saveLabel)
        composeTestRule.waitUntilAtLeastOneExists(hasText(savedLabel), 15_000)

        val rootId = findRootIdByTitle(title)
            ?: error("Did not find a Firestore root note containing '$title' after save")
        Log.i(TAG, "Saved fresh note rootId=$rootId")

        val (liveAfterCreate, deletedAfterCreate) = runBlocking { descendantsByState(rootId) }
        assertEquals(
            "expected 2 live descendants (line-a + line-b); got live=$liveAfterCreate",
            2, liveAfterCreate.size,
        )
        assertTrue(
            "expected no deleted descendants pre-delete; got $deletedAfterCreate",
            deletedAfterCreate.isEmpty(),
        )

        // Identify line-a vs line-b on the wire — we'll need both ids for
        // the post-restore assertion.
        val descendantsByContent = runBlocking {
            FirebaseFirestore.getInstance()
                .collection("notes")
                .whereEqualTo("rootNoteId", rootId)
                .get().await()
                .documents
                .associate { it.id to (it.getString("content") ?: "") }
        }
        val lineAId = descendantsByContent.entries.first { it.value.contains(lineA) }.key
        val lineBId = descendantsByContent.entries.first { it.value.contains(lineB) }.key
        Log.i(TAG, "lineAId=$lineAId lineBId=$lineBId")

        // ── Step 3+4: Tap gutter on the middle line (line-a) and pick
        //    "Delete" from the selection context menu. ─────────────────
        // The gutter has 3 cells (one per line) of roughly equal height.
        // Clicking dead-center lands on cell index 1 — line-a — which
        // calls gutterSelectionState.selectLine(1, state) →
        // state.selectLineRange(1..1) and pops up the selection menu via
        // EditorSelectionLayer.onSelectionCompleted.
        composeTestRule.onNodeWithTag("editor-gutter").performTouchInput { click() }
        composeTestRule.waitUntilAtLeastOneExists(hasText(deleteSelectionLabel), 10_000)
        composeTestRule.onNodeWithText(deleteSelectionLabel).performClick()

        // Editor should drop line-a from the visible content; the typed
        // "line-a-..." string disappears once deleteSelectionInternal
        // commits.
        composeTestRule.waitUntilDoesNotExist(hasText(lineA, substring = true), 10_000)

        composeTestRule.waitAndClickText(saveLabel)
        composeTestRule.waitUntilAtLeastOneExists(hasText(savedLabel), 15_000)

        val (liveAfterLineDelete, deletedAfterLineDelete) =
            runBlocking { descendantsByState(rootId) }
        Log.i(TAG, "after line-a delete: live=$liveAfterLineDelete deleted=$deletedAfterLineDelete")
        assertEquals(
            "after deleting line-a, expected 1 live descendant (line-b); got live=$liveAfterLineDelete",
            setOf(lineBId), liveAfterLineDelete,
        )
        assertEquals(
            "after deleting line-a, expected 1 deleted descendant (line-a); got deleted=$deletedAfterLineDelete",
            setOf(lineAId), deletedAfterLineDelete,
        )
        val lineABatch = batchIdOf(lineAId)
        assertTrue(
            "line-a's deletionBatchId should be selection-delete_*, was: $lineABatch",
            lineABatch?.startsWith("selection-delete_") == true,
        )

        // ── Step 5: Delete the whole note from All-notes ───────────────
        composeTestRule.waitAndClickText(allNotesLabel)
        composeTestRule.waitUntilAtLeastOneExists(hasText(title), 10_000)
        // Address the overflow on OUR note's row specifically — there may
        // be stale notes from prior emulator-shared test runs whose rows
        // also expose a "More options" button. Anchoring to the title
        // text via ancestor lookup keeps the test stable across them.
        composeTestRule
            .onNode(hasContentDescription(moreOptionsLabel) and hasAnyAncestor(hasText(title)))
            .performClick()
        composeTestRule.waitAndClickText(deleteNoteLabel, timeoutMillis = 5_000)

        composeTestRule.waitUntilAtLeastOneExists(hasText(deletedHeader), 10_000)
        composeTestRule.waitUntil(15_000) {
            val (live, deleted) = runBlocking { descendantsByState(rootId) }
            live.isEmpty() && deleted.size == 2
        }
        val lineBBatch = batchIdOf(lineBId)
        Log.i(TAG, "after whole-note delete: lineABatch=${batchIdOf(lineAId)} lineBBatch=$lineBBatch")
        assertTrue(
            "line-b should be stamped whole-note_* after softDeleteNote, was: $lineBBatch",
            lineBBatch?.startsWith("whole-note_") == true,
        )
        assertEquals(
            "line-a's batchId must remain unchanged (earlier batch) after the whole-note delete",
            lineABatch, batchIdOf(lineAId),
        )

        // ── Step 6a: Open the *deleted* note and verify the editor
        //    renders it via the same-batch reconstruction path. ────────
        // The reconstruction path for a deleted parent (`getNoteLinesById`
        // → `indexChildrenByParent` with `includeDeletedBatchId =
        // root.deletionBatchId`) is only exercised here. A missing field
        // on `NoteStore.parseNote` (the snapshot-listener parser) silently
        // produces `deletionBatchId=null` for every note, the batch-match
        // check returns false, same-batch descendants fail `acceptable()`
        // and get dropped as orphan refs, the editor renders only the
        // root line, and the save button flips to "Fix" — the live
        // regression a user reported in this exact flow.
        composeTestRule.onNodeWithText(title).performClick()
        // Saved status appears after the deleted note loads cleanly. If the
        // batch-match path had failed, this would be "Fix" instead.
        composeTestRule.waitUntilAtLeastOneExists(hasText(savedLabel), 10_000)
        composeTestRule.waitUntilAtLeastOneExists(hasText(lineB, substring = true), 10_000)
        composeTestRule.onNodeWithText(lineB, substring = true).assertIsDisplayed()
        // line-a was deleted in an earlier batch — must NOT render in the
        // deleted-note view (different batchId from the whole-note delete).
        run {
            val hits = composeTestRule
                .onAllNodesWithText(lineA, substring = true)
                .fetchSemanticsNodes().size
            assertEquals(
                "deleted-note view must not render line-a (different batch from the delete)",
                0, hits,
            )
        }
        // No "Fix" indicator — reconstruction must complete clean (no
        // orphan-ref drops) when batchIds match correctly.
        run {
            val hits = composeTestRule
                .onAllNodesWithText(needsFixLabel)
                .fetchSemanticsNodes().size
            assertEquals(
                "deleted-note view must not show the 'Fix' status — that would mean reconstruction dropped same-batch children as orphan refs",
                0, hits,
            )
        }
        // Back to All-notes for the restore step.
        composeTestRule.waitAndClickText(allNotesLabel)
        composeTestRule.waitUntilAtLeastOneExists(hasText(deletedHeader), 10_000)

        // ── Step 6b: Restore via deleted-section overflow ─────────────
        // Address by title-ancestor for the same reason as the delete step
        // (stale rows from prior test runs).
        composeTestRule
            .onNode(hasContentDescription(moreOptionsLabel) and hasAnyAncestor(hasText(title)))
            .performClick()
        composeTestRule.waitAndClickText(restoreLabel, timeoutMillis = 5_000)

        composeTestRule.waitUntil(15_000) {
            val (live, _) = runBlocking { descendantsByState(rootId) }
            live.size == 1
        }

        // ── Step 7: Verify line-a stayed deleted, line-b alive ─────────
        val (liveAfterRestore, deletedAfterRestore) =
            runBlocking { descendantsByState(rootId) }
        Log.i(TAG, "after restore: live=$liveAfterRestore deleted=$deletedAfterRestore")
        assertEquals(
            "after restore, only line-b should be live; got live=$liveAfterRestore",
            setOf(lineBId), liveAfterRestore,
        )
        assertEquals(
            "after restore, line-a alone should remain deleted; got deleted=$deletedAfterRestore",
            setOf(lineAId), deletedAfterRestore,
        )
        // line-b's batchId must be cleared on restore (so a future re-delete
        // starts a fresh batch).
        assertEquals(
            "restored line-b's deletionBatchId should be null, was: ${batchIdOf(lineBId)}",
            null, batchIdOf(lineBId),
        )
        // line-a retains its earlier-delete batchId — neither the
        // whole-note delete nor the restore touched it.
        assertEquals(
            "line-a's deletionBatchId must still be the earlier selection-delete id",
            lineABatch, batchIdOf(lineAId),
        )

        // ── Step 8: Open the restored note; verify line-a is gone, line-b
        //    is rendered. ────────────────────────────────────────────
        composeTestRule.waitUntilDoesNotExist(hasText(deletedHeader), 10_000)
        composeTestRule.onNodeWithText(title).performClick()
        composeTestRule.waitUntilAtLeastOneExists(hasText(lineB, substring = true), 10_000)
        composeTestRule.onNodeWithText(lineB, substring = true).assertIsDisplayed()
        val lineAHits = composeTestRule
            .onAllNodesWithText(lineA, substring = true)
            .fetchSemanticsNodes().size
        assertEquals(
            "editor should not render line-a after restore (its deletion was a different batch)",
            0, lineAHits,
        )
    }

    /**
     * The just-saved note is the unique top-level Firestore doc whose
     * content includes [title]. Lookup is by content rather than by
     * snooping internal ViewModel state — keeps the test agnostic to
     * undocumented APIs.
     */
    private fun findRootIdByTitle(title: String): String? = runBlocking {
        val uid = FirebaseAuth.getInstance().uid
            ?: error("anonymous sign-in didn't produce a uid")
        val snap = FirebaseFirestore.getInstance()
            .collection("notes")
            .whereEqualTo("userId", uid)
            .whereEqualTo("parentNoteId", null)
            .get()
            .await()
        snap.documents.firstOrNull { (it.getString("content") ?: "").contains(title) }?.id
    }

    /** Read `deletionBatchId` directly from the Firestore doc. */
    private fun batchIdOf(noteId: String): String? = runBlocking {
        val doc = FirebaseFirestore.getInstance()
            .collection("notes")
            .document(noteId)
            .get()
            .await()
        doc.getString("deletionBatchId")
    }
}
