package org.alkaline.taskbrain.saverefactor

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.runBlocking
import org.alkaline.taskbrain.EmulatorTestSupport
import org.alkaline.taskbrain.data.NoteIdSentinel
import org.alkaline.taskbrain.data.NoteLine
import org.alkaline.taskbrain.data.NoteStore
import org.alkaline.taskbrain.saverefactor.SaveRefactorTestSupport.readRawNote
import org.alkaline.taskbrain.saverefactor.SaveRefactorTestSupport.repo
import org.alkaline.taskbrain.saverefactor.SaveRefactorTestSupport.waitFor
import org.alkaline.taskbrain.saverefactor.SaveRefactorTestSupport.waitForListener
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

/**
 * IT-stale-base — captureLocalBase race.
 *
 * In production, `CurrentNoteViewModel.captureLocalBase` previously read
 * from `NoteStore.snapshotLocalBases` immediately after the save's
 * `commit()` resolved. The Firestore listener may not yet have processed
 * the `hasPendingWrites=false` snapshot for the just-committed write, so
 * `rawNotes[noteId].containedNotes` could be empty (for a freshly created
 * note). The captured `localBases` was then stale (`{noteId -> []}`).
 *
 * The next save's 3-way `mergeContainedNotes`, with that stale base,
 * sees `base != remote` and treats every item the listener has since
 * caught up on as a "concurrent addition by another client" — re-adding
 * them to `local`. If the user has just CUT one of those items, the cut
 * is silently undone by the merge.
 *
 * The fix returns `postSaveContainedNotes` from the save methods so the
 * editor refreshes `currentLocalBases` from the save's own write rather
 * than from the (possibly-stale) listener cache.
 */
class StaleLocalBaseRaceTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun classSetUp() = EmulatorTestSupport.requireEmulatorAndSignIn()
    }

    @Before
    fun resetStore() {
        NoteStore.clear()
        NoteStore.clearPendingCutsForTest()
        NoteStore.start(
            FirebaseFirestore.getInstance(),
            FirebaseAuth.getInstance().currentUser!!.uid,
        )
    }

    /**
     * End-to-end editor-flow simulation. Refreshes `localBases` between
     * saves from each save's `postSaveContainedNotes` return value —
     * never going through the listener cache.
     *
     * Pre-fix this scenario corrupted t3's `containedNotes`: the editor
     * captured an empty base from `snapshotLocalBases` (race with the
     * listener echo for a newly-created note's first save), the next save
     * saw `base != remote` and the 3-way merge re-added the cut item as
     * a "concurrent addition by another client". The captured failure:
     * `final containedNotes=[a, v, b2, YnpuLd]` — cut item undone.
     *
     * Post-fix: the cut item stays out of `containedNotes` because
     * `currentLocalBases` is now sourced from the save's own write.
     */
    @Test
    fun secondSaveAfterCut_dropsCutLine_whenLocalBasesRefreshedFromSaveReturn() = runBlocking {
        val rootId = repo().createNote().getOrThrow()
        waitForListener(rootId)

        var currentLocalBases: Map<String, List<String>> =
            NoteStore.snapshotLocalBases(rootId)

        val typedA = NoteIdSentinel.new(NoteIdSentinel.Origin.TYPED)
        val typedV = NoteIdSentinel.new(NoteIdSentinel.Origin.TYPED)
        val typedY = NoteIdSentinel.new(NoteIdSentinel.Origin.TYPED)
        val typedB = NoteIdSentinel.new(NoteIdSentinel.Origin.TYPED)
        val firstSave = repo().saveNoteWithChildren(
            rootId,
            listOf(
                NoteLine("root", rootId),
                NoteLine("\ta", typedA),
                NoteLine("\tv", typedV),
                NoteLine("\tYnpuLd", typedY),
                NoteLine("\tb2", typedB),
            ),
            extraOpsBuilder = null,
            localBases = currentLocalBases,
        ).getOrThrow()
        // The fix: refresh from the save's own post-write state. Mirrors
        // CurrentNoteViewModel.persistCurrentNote post-fix.
        currentLocalBases = firstSave.postSaveContainedNotes

        val aId = firstSave.createdIds[1]!!
        val vId = firstSave.createdIds[2]!!
        val ynpuLdId = firstSave.createdIds[3]!!
        val b2Id = firstSave.createdIds[4]!!
        waitFor { NoteStore.getRawNoteById(rootId)?.containedNotes?.size == 4 }

        NoteStore.recordCut(ynpuLdId, "YnpuLd")
        repo().saveNoteWithChildren(
            rootId,
            listOf(
                NoteLine("root", rootId),
                NoteLine("\ta", aId),
                NoteLine("\tv", vId),
                NoteLine("\tb2", b2Id),
            ),
            extraOpsBuilder = null,
            localBases = currentLocalBases,
        ).getOrThrow()

        @Suppress("UNCHECKED_CAST")
        val finalContained = readRawNote(rootId)!!["containedNotes"] as List<String>
        assertEquals(listOf(aId, vId, b2Id), finalContained)
    }

    /**
     * Post-write contract: the save's return value carries the new
     * `containedNotes` for every saved id. This is the data the editor
     * uses to refresh its localBases without going through the listener
     * cache, side-stepping the race that powers the test above.
     */
    @Test
    fun saveNoteWithChildren_returnsPostSaveContainedNotesForRootAndDescendants() = runBlocking {
        val rootId = repo().createNote().getOrThrow()
        waitForListener(rootId)

        val typedA = NoteIdSentinel.new(NoteIdSentinel.Origin.TYPED)
        val typedB = NoteIdSentinel.new(NoteIdSentinel.Origin.TYPED)
        val save = repo().saveNoteWithChildren(
            rootId,
            listOf(
                NoteLine("root", rootId),
                NoteLine("\ta", typedA),
                NoteLine("\tb", typedB),
            ),
            extraOpsBuilder = null,
            localBases = NoteStore.snapshotLocalBases(rootId),
        ).getOrThrow()

        val aId = save.createdIds[1]!!
        val bId = save.createdIds[2]!!
        assertEquals(
            mapOf(
                rootId to listOf(aId, bId),
                aId to emptyList(),
                bId to emptyList(),
            ),
            save.postSaveContainedNotes,
        )
    }
}
