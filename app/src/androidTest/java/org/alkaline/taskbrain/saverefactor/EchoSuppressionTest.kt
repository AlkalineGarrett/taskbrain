package org.alkaline.taskbrain.saverefactor

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import org.alkaline.taskbrain.EmulatorTestSupport
import org.alkaline.taskbrain.data.NoteLine
import org.alkaline.taskbrain.data.NoteStore
import org.alkaline.taskbrain.saverefactor.SaveRefactorTestSupport.repo
import org.alkaline.taskbrain.saverefactor.SaveRefactorTestSupport.waitFor
import org.alkaline.taskbrain.saverefactor.SaveRefactorTestSupport.waitForListener
import org.alkaline.taskbrain.saverefactor.SaveRefactorTestSupport.writeAsOtherClient
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Phase 3 echo suppression — the editor must NOT see its own save's
 * server echo as an external change. A second client's write to the same
 * doc, by contrast, MUST surface as `changedNoteIds`.
 *
 * Maps to IT-3a (own echo suppressed) and IT-3b (other client's write
 * not suppressed).
 */
class EchoSuppressionTest {

    private val collected = CopyOnWriteArrayList<Set<String>>()
    private var collectorScope: CoroutineScope? = null
    private var collectorJob: Job? = null

    companion object {
        @JvmStatic
        @BeforeClass
        fun classSetUp() = EmulatorTestSupport.requireEmulatorAndSignIn()
    }

    @Before
    fun startListenerAndCollector() {
        NoteStore.clear()
        NoteStore.start(
            FirebaseFirestore.getInstance(),
            FirebaseAuth.getInstance().currentUser!!.uid,
        )
        collected.clear()
        val scope = CoroutineScope(Dispatchers.Default)
        collectorScope = scope
        collectorJob = NoteStore.changedNoteIds
            .onEach { collected.add(it) }
            .launchIn(scope)
    }

    @After
    fun stopCollector() {
        collectorJob?.cancel()
        collectorScope?.cancel()
    }

    /**
     * IT-3a — saving our own write produces no `changedNoteIds` emission
     * for that note id, so a follow-up edit isn't clobbered by the echo
     * reload.
     */
    @Test
    fun ownSaveEchoIsSuppressedFromChangedNoteIds() = runBlocking {
        val rootId = repo().createMultiLineNote("first version").getOrThrow()
        waitForListener(rootId)
        // Drain anything emitted from the create itself.
        collected.clear()

        // Save again — this is the echo we want suppressed.
        val tracked = listOf(NoteLine("second version", rootId))
        repo().saveNoteWithChildren(
            rootId, tracked, extraOpsBuilder = null, localBases = null,
        ).getOrThrow()

        // Give Firestore a moment to deliver the echo snapshot. We're
        // checking absence, so a fixed window beats a polled predicate.
        delay(2_000)

        val emittedForRoot = collected.flatten().count { it == rootId }
        assertTrue(
            "expected no changedNoteIds emission for $rootId after own save echo, got $collected",
            emittedForRoot == 0,
        )
    }

    /**
     * IT-3b — when a different client (different opId) writes to the
     * same doc, the listener MUST emit `changedNoteIds` so the editor
     * reloads.
     */
    @Test
    fun externalClientWriteIsNotSuppressed() = runBlocking {
        val rootId = repo().createMultiLineNote("seeded").getOrThrow()
        waitForListener(rootId)
        collected.clear()

        // Simulate a second client overwriting the doc with a foreign opId.
        writeAsOtherClient(
            noteId = rootId,
            userId = FirebaseAuth.getInstance().currentUser!!.uid,
            content = "external-edit",
        )

        // Wait for the listener to deliver and the changedNoteIds flow
        // to emit our note id.
        waitFor(timeoutMs = 5_000) {
            collected.any { rootId in it }
        }
        assertFalse(
            "expected an external changedNoteIds emission for $rootId, got $collected",
            collected.none { rootId in it },
        )
    }
}
