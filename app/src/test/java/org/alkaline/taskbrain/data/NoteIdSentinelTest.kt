package org.alkaline.taskbrain.data

import org.junit.Assert.*
import org.junit.Test

class NoteIdSentinelTest {
    @Test
    fun `new - produces sentinel with correct prefix and origin code`() {
        val id = NoteIdSentinel.new(NoteIdSentinel.Origin.PASTE)
        assertTrue("should start with @", id.startsWith("@"))
        assertTrue("should contain origin code", id.contains("paste_"))
        assertTrue("should have entropy after the code", id.length > "@paste_".length)
    }

    @Test
    fun `isSentinel - recognizes sentinels and rejects real ids`() {
        assertTrue(NoteIdSentinel.isSentinel("@paste_abc123"))
        assertTrue(NoteIdSentinel.isSentinel(NoteIdSentinel.new(NoteIdSentinel.Origin.SPLIT)))
        assertFalse(NoteIdSentinel.isSentinel("aE5eVp2p2uiusUXqVa8T")) // real Firestore id
        assertFalse(NoteIdSentinel.isSentinel(null))
        assertFalse(NoteIdSentinel.isSentinel(""))
    }

    @Test
    fun `originOf - extracts origin code`() {
        assertEquals("paste", NoteIdSentinel.originOf(NoteIdSentinel.new(NoteIdSentinel.Origin.PASTE)))
        assertEquals("split", NoteIdSentinel.originOf(NoteIdSentinel.new(NoteIdSentinel.Origin.SPLIT)))
        assertEquals("agent", NoteIdSentinel.originOf("@agent_xyz"))
        assertNull(NoteIdSentinel.originOf("aE5eVp2p2uiusUXqVa8T"))
        assertNull(NoteIdSentinel.originOf(null))
    }

    @Test
    fun `new - generates distinct ids on each call`() {
        val ids = (1..20).map { NoteIdSentinel.new(NoteIdSentinel.Origin.PASTE) }
        assertEquals("all ids should be distinct", ids.size, ids.toSet().size)
    }

    @Test
    fun `all origins round-trip through originOf`() {
        for (origin in NoteIdSentinel.Origin.values()) {
            val id = NoteIdSentinel.new(origin)
            assertEquals(origin.code, NoteIdSentinel.originOf(id))
        }
    }
}
