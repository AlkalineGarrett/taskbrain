package org.alkaline.taskbrain.saverefactor

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.runBlocking
import org.alkaline.taskbrain.EmulatorTestSupport
import org.alkaline.taskbrain.data.NoteIdSentinel
import org.alkaline.taskbrain.data.NoteStore
import org.alkaline.taskbrain.saverefactor.SaveRefactorTestSupport.forgetListenerSnapshot
import org.alkaline.taskbrain.saverefactor.SaveRefactorTestSupport.repo
import org.alkaline.taskbrain.saverefactor.SaveRefactorTestSupport.waitFor
import org.alkaline.taskbrain.saverefactor.SaveRefactorTestSupport.waitForListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

/**
 * Emulator coverage for the async-only fix's session-init contract:
 * `NoteRepository.loadNoteLinesAwait` must resolve to structurally-valid
 * lines (real ids only) regardless of whether the listener has delivered
 * the note yet.
 *
 * Maps to IT-Aa and IT-Ab in `docs/save-refactor-emulator-test-plan.md`.
 */
class LoadNoteLinesAwaitTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun classSetUp() = EmulatorTestSupport.requireEmulatorAndSignIn()
    }

    @Before
    fun resetStore() {
        // Tests need a fresh listener attach so the previous test's
        // pendingSaves and rawNotes don't leak across.
        NoteStore.clear()
        NoteStore.start(
            FirebaseFirestore.getInstance(),
            FirebaseAuth.getInstance().currentUser!!.uid,
        )
    }

    /**
     * IT-Aa — listener fast path: a note created via the repo resolves
     * via the awaitPendingSave/listener fast path before any timeout.
     */
    @Test
    fun loadNoteLinesAwait_resolvesViaListenerFastPath() = runBlocking {
        val rootId = repo().createMultiLineNote("parent\n\tchild a\n\tchild b").getOrThrow()
        // Don't wait for the listener — exercise the awaitNoteLoaded path
        // (which awaits pendingSave first, then changedNoteIds).
        val lines = repo().loadNoteLinesAwait(rootId).getOrThrow()
        assertEquals(3, lines.size)
        assertEquals(rootId, lines[0].noteId)
        // Descendants must carry real ids — never sentinels, never the
        // (now-impossible) null.
        assertTrue("descendant 1 must have a real id, got ${lines[1].noteId}",
            !NoteIdSentinel.isSentinel(lines[1].noteId))
        assertTrue("descendant 2 must have a real id, got ${lines[2].noteId}",
            !NoteIdSentinel.isSentinel(lines[2].noteId))
    }

    /**
     * IT-Ab — listener-gone fallback: forget the listener snapshot so
     * `awaitNoteLoaded` times out, then `loadNoteLinesAwait` falls back
     * to a one-shot Firestore read. Lines still carry real ids.
     */
    @Test
    fun loadNoteLinesAwait_fallsBackToFirestoreReadOnTimeout() = runBlocking {
        // Seed via repo and let the listener echo populate NoteStore so
        // we know the doc is committed.
        val rootId = repo().createMultiLineNote("parent\n\tchild").getOrThrow()
        waitForListener(rootId)
        // Now forget the listener entirely. Detached state mimics a
        // never-loaded note for this test's purposes (rawNotes is empty).
        forgetListenerSnapshot()

        // Without restarting the listener, awaitNoteLoaded will time out
        // after NOTE_STORE_AWAIT_MS (1500ms) and the helper falls through
        // to loadNoteWithChildren's one-shot Firestore read.
        val lines = repo().loadNoteLinesAwait(rootId).getOrThrow()
        assertEquals(2, lines.size)
        assertEquals(rootId, lines[0].noteId)
        assertNotNull(lines[1].noteId)
        assertTrue(!NoteIdSentinel.isSentinel(lines[1].noteId))
    }

    /**
     * IT-Aa companion — verifies the awaitPendingSave bridge: a save
     * in flight bridges to the listener echo without falling through to
     * the timeout path.
     */
    @Test
    fun loadNoteLinesAwait_bridgesPendingSaveToListenerEcho() = runBlocking {
        val rootId = repo().createMultiLineNote("p\n\tc1").getOrThrow()
        // The pending save Deferred for createMultiLineNote settled when
        // createMultiLineNote returned. Issue another save to set up a
        // genuine in-flight state.
        val anchor = repo().loadNoteLinesAwait(rootId).getOrThrow()
        assertEquals(2, anchor.size)
        // Single-doc round-trip wait: ensure the second call doesn't
        // accidentally allocate fresh ids due to a missed echo.
        waitFor(timeoutMs = 5_000) {
            val lines = NoteStore.getNoteLinesById(rootId)
            lines != null && lines.size == 2 && !NoteIdSentinel.isSentinel(lines[1].noteId)
        }
    }
}
