package org.alkaline.taskbrain.ui.currentnote.util

/**
 * Pure functions for computing visibility and sort order of completed (checked) lines.
 *
 * All algorithms work on flat lists of tab-indented line texts.
 * Index 0 is the title line and is never hidden.
 */
object CompletedLineUtils {

    private val CHECKED_PREFIX_PATTERN = Regex("""^\t*☑ """)

    /** Returns true if the line text starts with a checked checkbox (after tabs). */
    fun isCheckedCheckbox(text: String): Boolean =
        CHECKED_PREFIX_PATTERN.containsMatchIn(text)

    /**
     * Computes which line indices should be hidden when showCompleted is false.
     * A checked line and its entire subtree (logical block) are hidden.
     * Index 0 (title line) is never hidden.
     */
    fun computeHiddenIndices(lines: List<String>, showCompleted: Boolean): Set<Int> {
        if (showCompleted) return emptySet()
        val hidden = mutableSetOf<Int>()
        for (i in lines.indices) {
            if (i == 0) continue
            if (i in hidden) continue
            if (isCheckedCheckbox(lines[i])) {
                val block = getLogicalBlockFromTexts(lines, i)
                hidden.addAll(block)
            }
        }
        return hidden
    }

    // ── Display items ────────────────────────────────────────────────────

    sealed class DisplayItem {
        data class VisibleLine(val realIndex: Int) : DisplayItem()
        data class CompletedPlaceholder(
            val count: Int,
            val indentLevel: Int,
            val blockStartIndex: Int,
        ) : DisplayItem()
    }

    /**
     * Computes display items from lines: either visible lines or placeholder summaries
     * for contiguous hidden blocks.
     */
    fun computeDisplayItems(lines: List<String>, showCompleted: Boolean): List<DisplayItem> {
        if (showCompleted) return lines.indices.map { DisplayItem.VisibleLine(it) }
        return computeDisplayItemsFromHidden(lines, computeHiddenIndices(lines, false))
    }

    /**
     * Computes display items from a pre-computed hidden set.
     * Useful when the caller needs to modify the hidden set (e.g. excluding recently-checked lines).
     */
    fun computeDisplayItemsFromHidden(lines: List<String>, hidden: Set<Int>): List<DisplayItem> {
        if (hidden.isEmpty()) return lines.indices.map { DisplayItem.VisibleLine(it) }
        val result = mutableListOf<DisplayItem>()
        var i = 0
        while (i < lines.size) {
            if (i !in hidden) {
                result.add(DisplayItem.VisibleLine(i))
                i++
            } else {
                val blockStart = i
                val placeholderIndent = IndentationUtils.getIndentLevel(lines[i])
                var count = 0
                while (i < lines.size && i in hidden) {
                    if (IndentationUtils.getIndentLevel(lines[i]) == placeholderIndent) count++
                    i++
                }
                result.add(DisplayItem.CompletedPlaceholder(count, placeholderIndent, blockStart))
            }
        }
        return result
    }

    // ── Sort completed to bottom ─────────────────────────────────────────

    private data class TreeNode(
        val text: String,
        val originalIndex: Int = -1,
        val children: MutableList<TreeNode> = mutableListOf(),
    )

    /**
     * Sorts checked subtrees to the bottom of each sibling group, recursively.
     * Empty lines act as barriers: checked items don't cross them.
     * Index 0 (title) is never moved.
     */
    fun sortCompletedToBottom(lineTexts: List<String>): List<String> {
        if (lineTexts.size <= 1) return lineTexts
        val roots = parseForest(lineTexts.drop(1))
        val sorted = partitionNodes(roots)
        return listOf(lineTexts[0]) + flatten(sorted)
    }

    /**
     * Returns the index permutation produced by sorting completed lines to the bottom.
     * `result[i]` is the original line index that should appear at position `i` after sorting.
     */
    fun sortCompletedToBottomIndexed(lineTexts: List<String>): List<Int> {
        if (lineTexts.size <= 1) return lineTexts.indices.toList()
        val roots = parseForest(lineTexts.drop(1))
        val sorted = partitionNodes(roots)
        return listOf(0) + flattenIndices(sorted)
    }

    /**
     * Parses a flat list of tab-indented lines (excluding title) into a forest of TreeNodes.
     * Each node records its original index (offset by +1 for the title line).
     */
    private fun parseForest(lines: List<String>): List<TreeNode> {
        val roots = mutableListOf<TreeNode>()
        data class StackEntry(val depth: Int, val node: TreeNode)
        val stack = mutableListOf<StackEntry>()

        for ((index, line) in lines.withIndex()) {
            val depth = IndentationUtils.getIndentLevel(line)
            val node = TreeNode(line, originalIndex = index + 1)

            // Pop stack until we find a parent at a shallower depth
            while (stack.isNotEmpty() && stack.last().depth >= depth) {
                stack.removeLast()
            }

            if (stack.isEmpty()) {
                roots.add(node)
            } else {
                stack.last().node.children.add(node)
            }

            // Only push non-empty lines onto the stack (empty lines can't have children)
            if (line.trimStart('\t').isNotEmpty()) {
                stack.add(StackEntry(depth, node))
            }
        }
        return roots
    }

    /**
     * Recursively partitions children: within each section (separated by spacer nodes),
     * unchecked subtrees come first, checked subtrees come last.
     */
    private fun partitionNodes(nodes: List<TreeNode>): List<TreeNode> {
        // Split into sections separated by spacer nodes
        val sections = mutableListOf<MutableList<TreeNode>>()
        var currentSection = mutableListOf<TreeNode>()

        for (node in nodes) {
            if (isSpacer(node)) {
                if (currentSection.isNotEmpty()) {
                    sections.add(currentSection)
                    currentSection = mutableListOf()
                }
                // Add spacer as its own single-element section (preserved in place)
                sections.add(mutableListOf(node))
            } else {
                currentSection.add(node)
            }
        }
        if (currentSection.isNotEmpty()) {
            sections.add(currentSection)
        }

        val result = mutableListOf<TreeNode>()
        for (section in sections) {
            if (section.size == 1 && isSpacer(section[0])) {
                result.add(section[0])
            } else {
                // Recurse into each node's children first
                val recursed = section.map { node ->
                    TreeNode(node.text, node.originalIndex, partitionNodes(node.children).toMutableList())
                }
                // Then partition: unchecked first, checked last
                val (checked, unchecked) = recursed.partition { isCheckedCheckbox(it.text) }
                result.addAll(unchecked)
                result.addAll(checked)
            }
        }
        return result
    }

    private fun isSpacer(node: TreeNode): Boolean =
        node.text.trimStart('\t').isEmpty()

    private fun flatten(nodes: List<TreeNode>): List<String> {
        val result = mutableListOf<String>()
        for (node in nodes) {
            result.add(node.text)
            result.addAll(flatten(node.children))
        }
        return result
    }

    private fun flattenIndices(nodes: List<TreeNode>): List<Int> {
        val result = mutableListOf<Int>()
        for (node in nodes) {
            result.add(node.originalIndex)
            result.addAll(flattenIndices(node.children))
        }
        return result
    }

    // ── Visibility modifiers ────────────────────────────────────────────

    /**
     * Computes the effective hidden set by excluding recently-checked lines
     * (and their subtrees) so they remain visible at reduced opacity.
     */
    fun computeEffectiveHidden(
        hiddenIndices: Set<Int>,
        recentlyChecked: Set<Int>,
        lines: List<String>
    ): Set<Int> {
        if (recentlyChecked.isEmpty()) return hiddenIndices
        val excluded = mutableSetOf<Int>()
        for (idx in recentlyChecked) {
            val block = getLogicalBlockFromTexts(lines, idx)
            excluded.addAll(block)
        }
        return hiddenIndices - excluded
    }

    /**
     * Computes which lines should render at reduced opacity: recently-checked lines
     * that would be hidden if not for the recently-checked exclusion.
     */
    fun computeFadedIndices(
        hiddenIndices: Set<Int>,
        recentlyChecked: Set<Int>,
        lines: List<String>
    ): Set<Int> {
        if (recentlyChecked.isEmpty()) return emptySet()
        val result = mutableSetOf<Int>()
        for (idx in recentlyChecked) {
            if (idx !in hiddenIndices) continue
            val block = getLogicalBlockFromTexts(lines, idx)
            for (j in block) result.add(j)
        }
        return result
    }

    // ── Focus helpers ────────────────────────────────────────────────────

    /**
     * Returns the nearest visible (non-hidden) line index, preferring lines above.
     */
    fun nearestVisibleLine(lines: List<String>, focusedIndex: Int, hiddenSet: Set<Int>): Int {
        if (focusedIndex !in hiddenSet) return focusedIndex
        // Prefer lines above
        for (i in focusedIndex downTo 0) {
            if (i !in hiddenSet) return i
        }
        // Fall back to lines below
        for (i in focusedIndex until lines.size) {
            if (i !in hiddenSet) return i
        }
        return 0
    }

    // ── Private helpers ──────────────────────────────────────────────────

    /**
     * Gets the logical block range from text strings (like IndentationUtils.getLogicalBlock
     * but operates on plain strings rather than LineState).
     */
    fun getLogicalBlockFromTexts(lines: List<String>, startIndex: Int): IntRange {
        if (startIndex !in lines.indices) return startIndex..startIndex
        val startIndent = IndentationUtils.getIndentLevel(lines[startIndex])
        var endIndex = startIndex
        for (i in (startIndex + 1) until lines.size) {
            if (IndentationUtils.getIndentLevel(lines[i]) <= startIndent) break
            endIndex = i
        }
        return startIndex..endIndex
    }
}
