package org.alkaline.taskbrain.ui.currentnote.selection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandleDragStateTest {

    @Test
    fun `forward selection — start handle anchors at min, end handle at max`() {
        val sel = EditorSelection(start = 10, end = 30)

        val startAnchor = pickDragAnchor(sel, isStartHandle = true)
        assertEquals(10, startAnchor.anchorOffset)
        assertEquals(30, startAnchor.fixedOffset)

        val endAnchor = pickDragAnchor(sel, isStartHandle = false)
        assertEquals(30, endAnchor.anchorOffset)
        assertEquals(10, endAnchor.fixedOffset)
    }

    @Test
    fun `reverse selection — start handle still anchors at min, end handle at max`() {
        // Visual handles always sit at min/max; drag math must agree, or grabbing
        // the visual right handle would drag from the left end's pixel position.
        val sel = EditorSelection(start = 30, end = 10)

        val startAnchor = pickDragAnchor(sel, isStartHandle = true)
        assertEquals(10, startAnchor.anchorOffset)
        assertEquals(30, startAnchor.fixedOffset)

        val endAnchor = pickDragAnchor(sel, isStartHandle = false)
        assertEquals(30, endAnchor.anchorOffset)
        assertEquals(10, endAnchor.fixedOffset)
    }

    @Test
    fun `forEndHandle is set iff the dragged handle is the end handle`() {
        // Needed for selections whose max sits at offset 0 of the next line — the
        // end handle visually walks back to the prior line's end, drag math must too.
        val sel = EditorSelection(start = 0, end = 50)

        assertFalse(pickDragAnchor(sel, isStartHandle = true).forEndHandle)
        assertTrue(pickDragAnchor(sel, isStartHandle = false).forEndHandle)
    }

    @Test
    fun `collapsed selection — anchor and fixed offsets are equal`() {
        val sel = EditorSelection(start = 15, end = 15)

        val anchor = pickDragAnchor(sel, isStartHandle = false)
        assertEquals(15, anchor.anchorOffset)
        assertEquals(15, anchor.fixedOffset)
    }
}
