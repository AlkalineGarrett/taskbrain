package org.alkaline.taskbrain.ui.currentnote

import org.alkaline.taskbrain.ui.currentnote.move.MoveTargetFinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoveLineLogicTest {

    // ==================== getIndentLevel ====================

    @Test
    fun `getIndentLevel returns 0 for plain text`() {
        val state = EditorState()
        state.updateFromText("hello")
        assertEquals(0, state.getIndentLevel(0))
    }

    @Test
    fun `getIndentLevel returns 0 for empty line`() {
        val state = EditorState()
        state.updateFromText("line1\n\nline3")
        assertEquals(0, state.getIndentLevel(1))
    }

    @Test
    fun `getIndentLevel returns count of leading tabs`() {
        val state = EditorState()
        state.updateFromText("\t\tindented")
        assertEquals(2, state.getIndentLevel(0))
    }

    @Test
    fun `getIndentLevel returns 1 for single tab`() {
        val state = EditorState()
        state.updateFromText("\titem")
        assertEquals(1, state.getIndentLevel(0))
    }

    @Test
    fun `getIndentLevel handles tabs with bullet`() {
        val state = EditorState()
        state.updateFromText("\t• item")
        assertEquals(1, state.getIndentLevel(0))
    }

    @Test
    fun `getIndentLevel returns 0 for invalid index`() {
        val state = EditorState()
        state.updateFromText("line")
        assertEquals(0, state.getIndentLevel(5))
    }

    // ==================== getLogicalBlock ====================

    @Test
    fun `getLogicalBlock returns single line when no children`() {
        val state = EditorState()
        state.updateFromText("line1\nline2\nline3")
        assertEquals(0..0, state.getLogicalBlock(0))
        assertEquals(1..1, state.getLogicalBlock(1))
        assertEquals(2..2, state.getLogicalBlock(2))
    }

    @Test
    fun `getLogicalBlock includes deeper indented children`() {
        val state = EditorState()
        state.updateFromText("parent\n\tchild1\n\tchild2\nsibling")
        assertEquals(0..2, state.getLogicalBlock(0))
    }

    @Test
    fun `getLogicalBlock includes nested children`() {
        val state = EditorState()
        state.updateFromText("parent\n\tchild\n\t\tgrandchild\nsibling")
        assertEquals(0..2, state.getLogicalBlock(0))
        assertEquals(1..2, state.getLogicalBlock(1))
        assertEquals(2..2, state.getLogicalBlock(2))
    }

    @Test
    fun `getLogicalBlock stops at same indent`() {
        val state = EditorState()
        state.updateFromText("item1\n\tchild\nitem2\n\tchild2")
        assertEquals(0..1, state.getLogicalBlock(0))
        assertEquals(2..3, state.getLogicalBlock(2))
    }

    @Test
    fun `getLogicalBlock handles empty lines as indent 0`() {
        val state = EditorState()
        state.updateFromText("parent\n\tchild\n\nsibling")
        // Empty line (indent 0) stops the block
        assertEquals(0..1, state.getLogicalBlock(0))
    }

    // ==================== getSelectedLineRange ====================

    @Test
    fun `getSelectedLineRange returns focused line when no selection`() {
        val state = EditorState()
        state.updateFromText("line1\nline2\nline3")
        state.focusedLineIndex = 1
        assertEquals(1..1, state.getSelectedLineRange())
    }

    @Test
    fun `getSelectedLineRange returns selected lines`() {
        val state = EditorState()
        state.updateFromText("line1\nline2\nline3\nline4")
        state.setSelection(6, 17) // line2 and line3
        assertEquals(1..2, state.getSelectedLineRange())
    }

    @Test
    fun `getSelectedLineRange handles single line selection`() {
        val state = EditorState()
        state.updateFromText("line1\nline2\nline3")
        state.setSelection(6, 11) // just line2
        assertEquals(1..1, state.getSelectedLineRange())
    }

    // ==================== findMoveUpTarget ====================

    @Test
    fun `findMoveUpTarget returns null at first line`() {
        val state = EditorState()
        state.updateFromText("line1\nline2")
        assertNull(state.findMoveUpTarget(0..0))
    }

    @Test
    fun `findMoveUpTarget returns previous line for simple case`() {
        val state = EditorState()
        state.updateFromText("line1\nline2\nline3")
        assertEquals(1, state.findMoveUpTarget(2..2))
    }

    @Test
    fun `findMoveUpTarget hops over sibling block`() {
        val state = EditorState()
        state.updateFromText("sibling1\n\tchild1\nitem\n\tchild")
        // Moving "item" + child (lines 2-3) up should hop to before "sibling1"
        assertEquals(0, state.findMoveUpTarget(2..3))
    }

    @Test
    fun `findMoveUpTarget hops over single deeper line`() {
        val state = EditorState()
        state.updateFromText("parent\n\tchild\nitem")
        // Moving "item" (line 2, indent 0) up - should hop over "parent" block (lines 0-1)
        // because "parent" is at same indent, and "\tchild" is its child
        assertEquals(0, state.findMoveUpTarget(2..2))
    }

    @Test
    fun `findMoveUpTarget hops over entire previous group with children`() {
        val state = EditorState()
        // Three groups, each with a parent and three children
        state.updateFromText("group1\n\tchild1\n\tchild2\n\tchild3\ngroup2\n\tchild4\n\tchild5\n\tchild6\ngroup3\n\tchild7\n\tchild8\n\tchild9")
        // Moving group3 (lines 8-11) up should land at position 4 (before group2)
        assertEquals(4, state.findMoveUpTarget(8..11))
    }

    // ==================== findMoveDownTarget ====================

    @Test
    fun `findMoveDownTarget returns null at last line`() {
        val state = EditorState()
        state.updateFromText("line1\nline2")
        assertNull(state.findMoveDownTarget(1..1))
    }

    @Test
    fun `findMoveDownTarget returns position after next line for simple case`() {
        val state = EditorState()
        state.updateFromText("line1\nline2\nline3")
        // Moving line1 down should go to position 2 (after line2)
        assertEquals(2, state.findMoveDownTarget(0..0))
    }

    @Test
    fun `findMoveDownTarget hops over sibling block`() {
        val state = EditorState()
        state.updateFromText("item\nsibling\n\tchild\nafter")
        // Moving "item" (line 0) down should hop over "sibling" + "child" (lines 1-2)
        assertEquals(3, state.findMoveDownTarget(0..0))
    }

    @Test
    fun `findMoveDownTarget hops over single deeper line`() {
        val state = EditorState()
        state.updateFromText("item\n\tchild\nsibling")
        // Moving "item" (line 0) down - "\tchild" is deeper, just hop over it
        assertEquals(2, state.findMoveDownTarget(0..0))
    }

    // ==================== getMoveTarget ====================

    @Test
    fun `getMoveTarget with selection uses selection range`() {
        val state = EditorState()
        state.updateFromText("line1\nline2\nline3\nline4")
        state.setSelection(6, 17) // lines 1-2
        // Move up should go to position 0
        assertEquals(0, state.getMoveTarget(moveUp = true))
    }

    @Test
    fun `getMoveTarget without selection uses logical block`() {
        val state = EditorState()
        state.updateFromText("item\n\tchild\nsibling")
        state.focusedLineIndex = 0
        // Should get logical block 0..1 and find target
        assertEquals(3, state.getMoveTarget(moveUp = false))
    }

    @Test
    fun `getMoveTarget moves one line when first selected is not shallowest`() {
        val state = EditorState()
        state.updateFromText("parent\n\tchild1\n\tchild2\nsibling")
        // Select "\tchild1\n\tchild2" (lines 1-2) - both at same indent
        state.setSelection(7, 22) // approximate selection
        // First line IS the shallowest (both at indent 1), so normal hopping
        val target = state.getMoveTarget(moveUp = false)
        assertEquals(4, target) // After sibling
    }

    @Test
    fun `getMoveTarget with different indent selection moves one line at a time`() {
        val state = EditorState()
        state.updateFromText("line1\n\tchild\nline2\nline3")
        // Select child + line2 (lines 1-2 with indent 1 and 0)
        // Line 1 has indent 1, line 2 has indent 0 - first is deeper than shallowest
        state.setSelection(6, 18)
        // First line (indent 1) is NOT the shallowest (0), so move one at a time
        assertEquals(0, state.getMoveTarget(moveUp = true)) // Just one line up
    }

    // ==================== wouldOrphanChildren ====================

    @Test
    fun `wouldOrphanChildren returns false without selection`() {
        val state = EditorState()
        state.updateFromText("parent\n\tchild")
        state.focusedLineIndex = 0
        assertFalse(state.wouldOrphanChildren())
    }

    @Test
    fun `wouldOrphanChildren returns false when selection includes all children`() {
        val state = EditorState()
        state.updateFromText("parent\n\tchild1\n\tchild2\nsibling")
        // Select entire logical block
        state.setSelection(0, 22) // parent + children
        assertFalse(state.wouldOrphanChildren())
    }

    @Test
    fun `wouldOrphanChildren returns true when selection excludes children`() {
        val state = EditorState()
        state.updateFromText("parent\n\tchild1\n\tchild2\nsibling")
        // Select just parent (line 0)
        state.setSelection(0, 6)
        assertTrue(state.wouldOrphanChildren())
    }

    @Test
    fun `wouldOrphanChildren returns true when first line is not shallowest`() {
        val state = EditorState()
        state.updateFromText("parent\n\tchild1\n\tchild2\nsibling")
        // Select child1 and child2 (lines 1-2) - first line (indent 1) is not shallowest
        state.setSelection(7, 22)
        // Both at indent 1, so first IS shallowest within selection - should be false
        assertFalse(state.wouldOrphanChildren())
    }

    @Test
    fun `wouldOrphanChildren returns true when selection spans different indent levels with deeper first`() {
        val state = EditorState()
        state.updateFromText("line0\n\tchild\nline2\nline3")
        // Select child + line2 (lines 1-2): first at indent 1, second at indent 0
        state.setSelection(6, 18)
        // First line (indent 1) > shallowest (indent 0) - should warn
        assertTrue(state.wouldOrphanChildren())
    }

    // ==================== moveLinesInternal ====================

    @Test
    fun `moveLinesInternal moves single line up`() {
        val state = EditorState()
        state.updateFromText("line1\nline2\nline3")
        state.focusedLineIndex = 2
        val newRange = state.moveLinesInternal(2..2, 1)
        assertEquals(1..1, newRange)
        assertEquals("line1\nline3\nline2", state.text)
    }

    @Test
    fun `moveLinesInternal moves single line down`() {
        val state = EditorState()
        state.updateFromText("line1\nline2\nline3")
        state.focusedLineIndex = 0
        val newRange = state.moveLinesInternal(0..0, 2)
        assertEquals(1..1, newRange)
        assertEquals("line2\nline1\nline3", state.text)
    }

    @Test
    fun `moveLinesInternal moves block up`() {
        val state = EditorState()
        state.updateFromText("first\nparent\n\tchild\nlast")
        state.focusedLineIndex = 1
        val newRange = state.moveLinesInternal(1..2, 0)
        assertEquals(0..1, newRange)
        assertEquals("parent\n\tchild\nfirst\nlast", state.text)
    }

    @Test
    fun `moveLinesInternal moves block down`() {
        val state = EditorState()
        state.updateFromText("parent\n\tchild\nmiddle\nlast")
        state.focusedLineIndex = 0
        val newRange = state.moveLinesInternal(0..1, 3)
        assertEquals(1..2, newRange)
        assertEquals("middle\nparent\n\tchild\nlast", state.text)
    }

    @Test
    fun `moveLinesInternal returns null for invalid source range`() {
        val state = EditorState()
        state.updateFromText("line1\nline2")
        assertNull(state.moveLinesInternal(-1..0, 1))
        assertNull(state.moveLinesInternal(0..5, 1))
    }

    @Test
    fun `moveLinesInternal returns null for target within source`() {
        val state = EditorState()
        state.updateFromText("line1\nline2\nline3")
        assertNull(state.moveLinesInternal(0..1, 1))
    }

    @Test
    fun `moveLinesInternal adjusts focused line index`() {
        val state = EditorState()
        state.updateFromText("line1\nline2\nline3")
        state.focusedLineIndex = 2
        state.moveLinesInternal(2..2, 0)
        assertEquals(0, state.focusedLineIndex)
    }

    @Test
    fun `moveLinesInternal updates selection when moving selected lines`() {
        val state = EditorState()
        state.updateFromText("line1\nline2\nline3")
        state.setSelection(6, 11) // line2
        state.moveLinesInternal(1..1, 0)
        // Selection should follow the moved line
        assertTrue(state.hasSelection)
    }

    // ==================== Empty line handling ====================

    @Test
    fun `empty lines are treated as indent 0`() {
        val state = EditorState()
        state.updateFromText("\tindented\n\nplain")
        assertEquals(1, state.getIndentLevel(0))
        assertEquals(0, state.getIndentLevel(1))
        assertEquals(0, state.getIndentLevel(2))
    }

    @Test
    fun `move over empty line hops one at a time`() {
        val state = EditorState()
        state.updateFromText("line1\n\nline3")
        state.focusedLineIndex = 2
        val target = state.findMoveUpTarget(2..2)
        assertEquals(1, target) // Empty line is its own group
    }

    // ==================== Hidden indices (showCompleted=false) ====================

    private fun lines(vararg texts: String): List<LineState> = texts.map { LineState(it) }

    @Test
    fun `findMoveUpTarget skips hidden lines`() {
        val l = lines("a", "☑ done", "b")
        val hidden = setOf(1)
        assertEquals(0, MoveTargetFinder.findMoveUpTarget(l, 2..2, hidden))
    }

    @Test
    fun `findMoveUpTarget returns null when target is hidden`() {
        val l = lines("☑ done", "active")
        val hidden = setOf(0)
        assertNull(MoveTargetFinder.findMoveUpTarget(l, 1..1, hidden))
    }

    @Test
    fun `findMoveUpTarget skips hidden block including children`() {
        val l = lines("a", "☑ parent", "\tchild", "b")
        val hidden = setOf(1, 2)
        assertEquals(0, MoveTargetFinder.findMoveUpTarget(l, 3..3, hidden))
    }

    @Test
    fun `findMoveDownTarget skips hidden lines`() {
        val l = lines("a", "☑ done", "b")
        val hidden = setOf(1)
        assertEquals(3, MoveTargetFinder.findMoveDownTarget(l, 0..0, hidden))
    }

    @Test
    fun `findMoveDownTarget returns null when all below are hidden`() {
        val l = lines("active", "☑ done1", "☑ done2")
        val hidden = setOf(1, 2)
        assertNull(MoveTargetFinder.findMoveDownTarget(l, 0..0, hidden))
    }

    @Test
    fun `findMoveDownTarget skips hidden block and finds target`() {
        val l = lines("a", "☑ parent", "\tchild", "b")
        val hidden = setOf(1, 2)
        assertEquals(4, MoveTargetFinder.findMoveDownTarget(l, 0..0, hidden))
    }

    @Test
    fun `findMoveUpTarget skips hidden block above visible target`() {
        // Lines: unchecked, ☑ done1, ☑ done2, empty, unchecked2
        // Moving unchecked2 up should land above the hidden block
        val l = lines("unchecked1", "☑ done1", "☑ done2", "", "unchecked2")
        val hidden = setOf(1, 2)
        assertEquals(1, MoveTargetFinder.findMoveUpTarget(l, 4..4, hidden))
    }

    @Test
    fun `findMoveUpTarget does not skip hidden block at deeper indent`() {
        // Lines: \t☐ parent, \t☑ child (hidden), Root, \t☐ mover
        // Moving mover up should swap with Root, not jump past hidden child
        val l = lines("\t☐ parent", "\t☑ child", "Root", "\t☐ mover")
        val hidden = setOf(1)
        assertEquals(2, MoveTargetFinder.findMoveUpTarget(l, 3..3, hidden))
    }

    @Test
    fun `findMoveUpTarget skips hidden block with children above visible target`() {
        val l = lines("unchecked1", "☑ parent", "\tchild", "", "unchecked2")
        val hidden = setOf(1, 2)
        assertEquals(1, MoveTargetFinder.findMoveUpTarget(l, 4..4, hidden))
    }

    @Test
    fun `findMoveDownTarget stops block expansion at hidden sibling`() {
        // Lines: unchecked1, unchecked2, ☑ done1, ☑ done2, empty
        // Moving unchecked1 down should swap with unchecked2, not jump past hidden lines
        val l = lines("unchecked1", "unchecked2", "☑ done1", "☑ done2", "")
        val hidden = setOf(2, 3)
        assertEquals(2, MoveTargetFinder.findMoveDownTarget(l, 0..0, hidden))
    }

    @Test
    fun `findMoveDownTarget still includes visible children in block expansion`() {
        // Lines: unchecked1, unchecked2, \tchild, ☑ done (hidden), empty
        val l = lines("unchecked1", "unchecked2", "\tchild", "☑ done", "")
        val hidden = setOf(3)
        assertEquals(3, MoveTargetFinder.findMoveDownTarget(l, 0..0, hidden))
    }

    @Test
    fun `getMoveTarget with hiddenIndices move up`() {
        val l = lines("a", "☑ done", "b")
        val hidden = setOf(1)
        assertEquals(0, MoveTargetFinder.getMoveTarget(l, false, EditorState().selection, 2, true, hidden))
    }

    @Test
    fun `getMoveTarget with hiddenIndices move down`() {
        val l = lines("a", "☑ done", "b")
        val hidden = setOf(1)
        assertEquals(3, MoveTargetFinder.getMoveTarget(l, false, EditorState().selection, 0, false, hidden))
    }

    // ==================== Edge cases ====================

    @Test
    fun `single line document disables both move directions`() {
        val state = EditorState()
        state.updateFromText("only line")
        assertNull(state.getMoveTarget(moveUp = true))
        assertNull(state.getMoveTarget(moveUp = false))
    }

    @Test
    fun `move updates cursor to follow moved line`() {
        val state = EditorState()
        state.updateFromText("line1\nline2\nline3")
        state.focusedLineIndex = 0
        state.moveLinesInternal(0..0, 3) // Move to end
        assertEquals(2, state.focusedLineIndex)
    }
}
