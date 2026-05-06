package org.alkaline.taskbrain.saverefactor

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
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
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Live-sync contract — every server-confirmed snapshot, including the
 * echo of our own save, surfaces as `changedNoteIds`. Editor reload
 * protection lives in `CurrentNoteViewModel`'s `dirty`/`saving` /
 * content-equality guards, NOT in the data layer.
 */
class SnapshotEmissionTest {

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

    @Test
    fun ownSaveEchoSurfacesAsChangedNoteIds() = runBlocking {
        val rootId = repo().createMultiLineNote("first version").getOrThrow()
        waitForListener(rootId)
        collected.clear()

        repo().saveNoteWithChildren(
            rootId,
            listOf(NoteLine("second version", rootId)),
            extraOpsBuilder = null,
            localBases = null,
        ).getOrThrow()

        waitFor(timeoutMs = 5_000) { collected.any { rootId in it } }
    }

    @Test
    fun externalClientWriteSurfacesAsChangedNoteIds() = runBlocking {
        val rootId = repo().createMultiLineNote("seeded").getOrThrow()
        waitForListener(rootId)
        collected.clear()

        writeAsOtherClient(
            noteId = rootId,
            userId = FirebaseAuth.getInstance().currentUser!!.uid,
            content = "external-edit",
        )

        waitFor(timeoutMs = 5_000) { collected.any { rootId in it } }
    }
}
