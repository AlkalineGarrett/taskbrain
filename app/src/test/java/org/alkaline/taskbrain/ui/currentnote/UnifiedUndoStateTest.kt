package org.alkaline.taskbrain.ui.currentnote

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure helpers backing the unified undo manager's context-id scheme.
 * The Composable factory itself requires a Compose runtime and is exercised
 * via instrumentation; only the string helpers are unit-tested here.
 */
class UnifiedUndoStateTest {

    @Test
    fun `inlineContextId prepends the inline prefix`() {
        assertEquals("inline:abc-123", inlineContextId("abc-123"))
    }

    @Test
    fun `noteIdFromInlineContextId strips the inline prefix`() {
        assertEquals("abc-123", noteIdFromInlineContextId("inline:abc-123"))
    }

    @Test
    fun `noteIdFromInlineContextId returns input unchanged when prefix absent`() {
        assertEquals("main", noteIdFromInlineContextId("main"))
    }

    @Test
    fun `inlineContextId and noteIdFromInlineContextId roundtrip`() {
        val ids = listOf("a", "long-note-id", "with:colons:in:it", "")
        for (id in ids) {
            assertEquals(id, noteIdFromInlineContextId(inlineContextId(id)))
        }
    }
}
