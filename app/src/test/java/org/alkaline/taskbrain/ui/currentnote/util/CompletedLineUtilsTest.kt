package org.alkaline.taskbrain.ui.currentnote.util

import org.alkaline.taskbrain.ui.currentnote.util.CompletedLineUtils.DisplayItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletedLineUtilsTest {

    // ==================== isCheckedCheckbox ====================

    @Test
    fun `isCheckedCheckbox returns true for checked line`() {
        assertTrue(CompletedLineUtils.isCheckedCheckbox("☑ Done"))
    }

    @Test
    fun `isCheckedCheckbox returns true for indented checked line`() {
        assertTrue(CompletedLineUtils.isCheckedCheckbox("\t\t☑ Done"))
    }

    @Test
    fun `isCheckedCheckbox returns false for unchecked line`() {
        assertFalse(CompletedLineUtils.isCheckedCheckbox("☐ Todo"))
    }

    @Test
    fun `isCheckedCheckbox returns false for plain line`() {
        assertFalse(CompletedLineUtils.isCheckedCheckbox("Plain text"))
    }

    @Test
    fun `isCheckedCheckbox returns false for empty line`() {
        assertFalse(CompletedLineUtils.isCheckedCheckbox(""))
    }

    // ==================== computeHiddenIndices ====================

    @Test
    fun `showCompleted true returns empty set`() {
        val lines = listOf("Title", "☑ Done", "☐ Active")
        assertEquals(emptySet<Int>(), CompletedLineUtils.computeHiddenIndices(lines, true))
    }

    @Test
    fun `no checked lines returns empty set`() {
        val lines = listOf("Title", "☐ Active", "• Bullet")
        assertEquals(emptySet<Int>(), CompletedLineUtils.computeHiddenIndices(lines, false))
    }

    @Test
    fun `checked line is hidden`() {
        val lines = listOf("Title", "☑ Done", "☐ Active")
        assertEquals(setOf(1), CompletedLineUtils.computeHiddenIndices(lines, false))
    }

    @Test
    fun `checked line with children hides entire subtree`() {
        val lines = listOf("Title", "☑ Done", "\tChild 1", "\tChild 2", "☐ Active")
        assertEquals(setOf(1, 2, 3), CompletedLineUtils.computeHiddenIndices(lines, false))
    }

    @Test
    fun `nested checked hides subtree`() {
        val lines = listOf("Title", "Parent", "\t☑ Done", "\t\tGrandchild", "\tSibling")
        assertEquals(setOf(2, 3), CompletedLineUtils.computeHiddenIndices(lines, false))
    }

    @Test
    fun `title line is never hidden even if checked`() {
        val lines = listOf("☑ Checked Title", "☐ Active")
        assertEquals(emptySet<Int>(), CompletedLineUtils.computeHiddenIndices(lines, false))
    }

    @Test
    fun `multiple checked lines with children`() {
        val lines = listOf("Title", "☑ Done1", "\tChild1", "☑ Done2", "\tChild2", "☐ Active")
        assertEquals(setOf(1, 2, 3, 4), CompletedLineUtils.computeHiddenIndices(lines, false))
    }

    // ==================== computeDisplayItems ====================

    @Test
    fun `showCompleted true returns all visible lines`() {
        val lines = listOf("Title", "☑ Done", "☐ Active")
        val items = CompletedLineUtils.computeDisplayItems(lines, true)
        assertEquals(3, items.size)
        assertEquals(DisplayItem.VisibleLine(0), items[0])
        assertEquals(DisplayItem.VisibleLine(1), items[1])
        assertEquals(DisplayItem.VisibleLine(2), items[2])
    }

    @Test
    fun `hidden lines produce placeholder with correct count`() {
        val lines = listOf("Title", "☑ Done1", "☑ Done2", "☐ Active")
        val items = CompletedLineUtils.computeDisplayItems(lines, false)
        assertEquals(3, items.size)
        assertEquals(DisplayItem.VisibleLine(0), items[0])
        assertEquals(DisplayItem.CompletedPlaceholder(2, 0, 1), items[1])
        assertEquals(DisplayItem.VisibleLine(3), items[2])
    }

    @Test
    fun `placeholder counts only top-level hidden items`() {
        val lines = listOf("Title", "☑ Done", "\tChild1", "\tChild2", "☐ Active")
        val items = CompletedLineUtils.computeDisplayItems(lines, false)
        assertEquals(3, items.size)
        assertEquals(DisplayItem.VisibleLine(0), items[0])
        // 1 top-level checked item at indent 0, 2 children at indent 1
        assertEquals(DisplayItem.CompletedPlaceholder(1, 0, 1), items[1])
        assertEquals(DisplayItem.VisibleLine(4), items[2])
    }

    @Test
    fun `indented placeholder has correct indent level`() {
        val lines = listOf("Title", "Parent", "\t☑ Done", "\t☐ Active")
        val items = CompletedLineUtils.computeDisplayItems(lines, false)
        assertEquals(4, items.size)
        assertEquals(DisplayItem.VisibleLine(0), items[0])
        assertEquals(DisplayItem.VisibleLine(1), items[1])
        assertEquals(DisplayItem.CompletedPlaceholder(1, 1, 2), items[2])
        assertEquals(DisplayItem.VisibleLine(3), items[3])
    }

    // ==================== sortCompletedToBottom ====================

    @Test
    fun `empty input returns empty`() {
        assertEquals(emptyList<String>(), CompletedLineUtils.sortCompletedToBottom(emptyList()))
    }

    @Test
    fun `single line returns unchanged`() {
        assertEquals(listOf("Title"), CompletedLineUtils.sortCompletedToBottom(listOf("Title")))
    }

    @Test
    fun `no checked lines returns unchanged`() {
        val lines = listOf("Title", "☐ A", "☐ B")
        assertEquals(lines, CompletedLineUtils.sortCompletedToBottom(lines))
    }

    @Test
    fun `checked lines move to bottom`() {
        val lines = listOf("Title", "☑ Done", "☐ Active")
        val expected = listOf("Title", "☐ Active", "☑ Done")
        assertEquals(expected, CompletedLineUtils.sortCompletedToBottom(lines))
    }

    @Test
    fun `multiple checked and unchecked sort correctly`() {
        val lines = listOf("Title", "☑ Done A", "☐ Active B", "☐ Active C", "☑ Done D")
        val expected = listOf("Title", "☐ Active B", "☐ Active C", "☑ Done A", "☑ Done D")
        assertEquals(expected, CompletedLineUtils.sortCompletedToBottom(lines))
    }

    @Test
    fun `checked subtrees stay intact`() {
        val lines = listOf("Title", "☑ Done", "\tChild", "☐ Active")
        val expected = listOf("Title", "☐ Active", "☑ Done", "\tChild")
        assertEquals(expected, CompletedLineUtils.sortCompletedToBottom(lines))
    }

    @Test
    fun `nested levels sort independently`() {
        val lines = listOf(
            "Title",
            "Parent",
            "\t☑ Done child",
            "\t☐ Active child",
        )
        val expected = listOf(
            "Title",
            "Parent",
            "\t☐ Active child",
            "\t☑ Done child",
        )
        assertEquals(expected, CompletedLineUtils.sortCompletedToBottom(lines))
    }

    @Test
    fun `empty lines act as barriers`() {
        val lines = listOf(
            "Title",
            "☑ Done A",
            "☐ Active B",
            "",
            "☑ Done C",
            "☐ Active D",
        )
        val expected = listOf(
            "Title",
            "☐ Active B",
            "☑ Done A",
            "",
            "☐ Active D",
            "☑ Done C",
        )
        assertEquals(expected, CompletedLineUtils.sortCompletedToBottom(lines))
    }

    @Test
    fun `already sorted is idempotent`() {
        val lines = listOf("Title", "☐ Active", "☑ Done")
        assertEquals(lines, CompletedLineUtils.sortCompletedToBottom(lines))
    }

    @Test
    fun `deep nesting sorts at each level`() {
        val lines = listOf(
            "Title",
            "Parent",
            "\tChild",
            "\t\t☑ Done grandchild",
            "\t\t☐ Active grandchild",
        )
        val expected = listOf(
            "Title",
            "Parent",
            "\tChild",
            "\t\t☐ Active grandchild",
            "\t\t☑ Done grandchild",
        )
        assertEquals(expected, CompletedLineUtils.sortCompletedToBottom(lines))
    }

    // ==================== sortCompletedToBottomIndexed ====================

    @Test
    fun `indexed empty input returns empty`() {
        assertEquals(emptyList<Int>(), CompletedLineUtils.sortCompletedToBottomIndexed(emptyList()))
    }

    @Test
    fun `indexed single line returns identity`() {
        assertEquals(listOf(0), CompletedLineUtils.sortCompletedToBottomIndexed(listOf("Title")))
    }

    @Test
    fun `indexed no checked lines returns identity`() {
        val lines = listOf("Title", "☐ A", "☐ B")
        assertEquals(listOf(0, 1, 2), CompletedLineUtils.sortCompletedToBottomIndexed(lines))
    }

    @Test
    fun `indexed checked lines move to bottom`() {
        val lines = listOf("Title", "☑ Done", "☐ Active")
        // "☐ Active" (orig 2) moves to position 1, "☑ Done" (orig 1) moves to position 2
        assertEquals(listOf(0, 2, 1), CompletedLineUtils.sortCompletedToBottomIndexed(lines))
    }

    @Test
    fun `indexed multiple checked and unchecked`() {
        val lines = listOf("Title", "☑ Done A", "☐ Active B", "☐ Active C", "☑ Done D")
        // Unchecked first: Active B (2), Active C (3); then checked: Done A (1), Done D (4)
        assertEquals(listOf(0, 2, 3, 1, 4), CompletedLineUtils.sortCompletedToBottomIndexed(lines))
    }

    @Test
    fun `indexed checked subtrees stay intact`() {
        val lines = listOf("Title", "☑ Done", "\tChild", "☐ Active")
        // Active (3) first, then Done (1) + Child (2)
        assertEquals(listOf(0, 3, 1, 2), CompletedLineUtils.sortCompletedToBottomIndexed(lines))
    }

    @Test
    fun `indexed nested levels sort independently`() {
        val lines = listOf("Title", "Parent", "\t☑ Done child", "\t☐ Active child")
        // Title (0), Parent (1), Active child (3), Done child (2)
        assertEquals(listOf(0, 1, 3, 2), CompletedLineUtils.sortCompletedToBottomIndexed(lines))
    }

    @Test
    fun `indexed empty lines act as barriers`() {
        val lines = listOf("Title", "☑ Done A", "☐ Active B", "", "☑ Done C", "☐ Active D")
        // Section 1: Active B (2), Done A (1); barrier (3); Section 2: Active D (5), Done C (4)
        assertEquals(listOf(0, 2, 1, 3, 5, 4), CompletedLineUtils.sortCompletedToBottomIndexed(lines))
    }

    @Test
    fun `indexed permutation produces same text as sortCompletedToBottom`() {
        val lines = listOf("Title", "☑ Done A", "☐ Active B", "Parent", "\t☑ Child done", "\t☐ Child active")
        val sortedTexts = CompletedLineUtils.sortCompletedToBottom(lines)
        val permutation = CompletedLineUtils.sortCompletedToBottomIndexed(lines)
        val textsFromPermutation = permutation.map { lines[it] }
        assertEquals(sortedTexts, textsFromPermutation)
    }

    // ==================== nearestVisibleLine ====================

    @Test
    fun `nearestVisibleLine returns same index if not hidden`() {
        assertEquals(2, CompletedLineUtils.nearestVisibleLine(listOf("a", "b", "c"), 2, emptySet()))
    }

    @Test
    fun `nearestVisibleLine prefers line above`() {
        val lines = listOf("Title", "Visible", "☑ Hidden", "Below")
        val hidden = setOf(2)
        assertEquals(1, CompletedLineUtils.nearestVisibleLine(lines, 2, hidden))
    }

    @Test
    fun `nearestVisibleLine falls back to line below`() {
        val lines = listOf("☑ Hidden", "☑ Hidden2", "Visible")
        val hidden = setOf(0, 1)
        assertEquals(2, CompletedLineUtils.nearestVisibleLine(lines, 0, hidden))
    }

    @Test
    fun `nearestVisibleLine returns 0 when all hidden`() {
        val lines = listOf("a", "b")
        val hidden = setOf(0, 1)
        assertEquals(0, CompletedLineUtils.nearestVisibleLine(lines, 1, hidden))
    }
}
