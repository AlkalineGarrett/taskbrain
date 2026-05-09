package org.alkaline.taskbrain.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeletionSourceTest {

    @Test
    fun `newBatchId stamps the source label as a prefix`() {
        val id = DeletionSource.newBatchId(DeletionSource.WHOLE_NOTE)
        assertTrue("expected whole-note prefix, got: $id", id.startsWith("whole-note_"))
    }

    @Test
    fun `newBatchId produces a unique id per call within the same source`() {
        val a = DeletionSource.newBatchId(DeletionSource.SELECTION_DELETE)
        val b = DeletionSource.newBatchId(DeletionSource.SELECTION_DELETE)
        assertNotEquals(a, b)
    }

    @Test
    fun `sourceOf extracts the prefix`() {
        val id = DeletionSource.newBatchId(DeletionSource.PASTE_REPLACE)
        assertEquals("paste-replace", DeletionSource.sourceOf(id))
    }

    @Test
    fun `sourceOf returns null for null or malformed batchIds`() {
        assertNull(DeletionSource.sourceOf(null))
        // No underscore at all
        assertNull(DeletionSource.sourceOf("nounderscore"))
        // Trailing-only
        assertNull(DeletionSource.sourceOf("trailing_"))
        // Leading-only is still parseable as empty source — not a real concern,
        // but we want sourceOf("_uuid") to be null since the prefix is empty.
        assertNull(DeletionSource.sourceOf("_uuid"))
    }

    @Test
    fun `every enum label is non-empty and lowercase-with-hyphens`() {
        // Cheap consistency check so a typo'd label can't slip in.
        for (s in DeletionSource.values()) {
            assertTrue("empty label on $s", s.label.isNotEmpty())
            assertTrue(
                "label '${s.label}' must be lowercase with hyphens only",
                s.label.all { it.isLowerCase() || it == '-' || it.isDigit() },
            )
        }
    }
}
