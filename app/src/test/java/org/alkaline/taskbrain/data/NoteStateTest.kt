package org.alkaline.taskbrain.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteStateTest {

    @Test
    fun `isLive treats null as the canonical active state`() {
        // Firestore's storage shape is: live notes have no state field.
        // null arrives at the deserializer and must be treated as alive,
        // otherwise every legacy note would be filtered out.
        assertTrue(isLive(null))
    }

    @Test
    fun `isLive accepts the explicit active marker`() {
        assertTrue(isLive(NoteState.ACTIVE))
    }

    @Test
    fun `isLive rejects soft-deleted notes`() {
        assertFalse(isLive(NoteState.DELETED))
    }

    @Test
    fun `isLive rejects cut-delete notes`() {
        // cut-delete docs are parked awaiting paste — they must not appear in
        // reconstructed trees. Reclaim is via paste, not via reconstruction.
        assertFalse(isLive(NoteState.CUT_DELETE))
    }

    @Test
    fun `isLive rejects unknown state values conservatively`() {
        // Forward-compatible: a future state we haven't taught reconstruction
        // about should hide the note rather than render it in an undefined way.
        assertFalse(isLive("future-state-we-dont-know-about"))
    }
}
