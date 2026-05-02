package org.alkaline.taskbrain.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NoteStoreTest {

    @Before
    fun resetSingleton() {
        // The singleton's pendingOpIds map is timer-pruned; reset between tests.
        NoteStore.clearPendingOpsForTest()
    }

    @Test
    fun `enqueueSave runs operations sequentially in submission order`() = runTest(StandardTestDispatcher()) {
        val order = mutableListOf<String>()
        val firstReleased = CompletableDeferred<Unit>()

        val firstJob = async {
            NoteStore.enqueueSave {
                order.add("first-start")
                firstReleased.await()
                order.add("first-end")
                "A"
            }
        }

        val secondJob = async {
            NoteStore.enqueueSave {
                order.add("second-start")
                order.add("second-end")
                "B"
            }
        }

        // Let both coroutines reach their first suspension point. Only the
        // first should have entered the critical section.
        advanceUntilIdle()
        assertEquals(listOf("first-start"), order)

        firstReleased.complete(Unit)
        assertEquals("A", firstJob.await())
        assertEquals("B", secondJob.await())
        assertEquals(
            listOf("first-start", "first-end", "second-start", "second-end"),
            order,
        )
    }

    @Test
    fun `enqueueSave continues queue after a failed operation`() = runTest {
        var caught: Throwable? = null
        try {
            NoteStore.enqueueSave<Unit> { throw RuntimeException("boom") }
        } catch (e: RuntimeException) {
            caught = e
        }
        assertTrue("expected throw to surface", caught is RuntimeException)
        assertEquals("boom", caught?.message)

        // Queue must still accept and run subsequent operations.
        val result = NoteStore.enqueueSave { "ok" }
        assertEquals("ok", result)
    }

    // ── Pending-op echo suppression ────────────────────────────────────

    @Test
    fun `isOurEcho returns true while a registered op is in-window`() {
        NoteStore.registerPendingOp("op-1")
        assertTrue(NoteStore.isOurEcho("op-1"))
    }

    @Test
    fun `isOurEcho returns false for null and unknown opId`() {
        assertFalse(NoteStore.isOurEcho(null))
        assertFalse(NoteStore.isOurEcho("never-registered"))
    }

    @Test
    fun `multiple opIds coexist independently`() {
        NoteStore.registerPendingOp("op-a")
        NoteStore.registerPendingOp("op-b")
        assertTrue(NoteStore.isOurEcho("op-a"))
        assertTrue(NoteStore.isOurEcho("op-b"))
        assertFalse(NoteStore.isOurEcho("op-c"))
    }
}
