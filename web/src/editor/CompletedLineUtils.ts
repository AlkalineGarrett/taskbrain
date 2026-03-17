/**
 * Pure functions for computing visibility and sort order of completed (checked) lines.
 *
 * All algorithms work on flat lists of tab-indented line texts.
 * Index 0 is the title line and is never hidden.
 *
 * Keep in sync with Android: CompletedLineUtils.kt
 */

import { getIndentLevelFromText } from './IndentationUtils'

const CHECKED_PREFIX_RE = /^\t*☑ /

/** Returns true if the line text starts with a checked checkbox (after tabs). */
export function isCheckedCheckbox(text: string): boolean {
  return CHECKED_PREFIX_RE.test(text)
}

/**
 * Computes which line indices should be hidden when showCompleted is false.
 * A checked line and its entire subtree (logical block) are hidden.
 * Index 0 (title line) is never hidden.
 */
export function computeHiddenIndices(lines: string[], showCompleted: boolean): Set<number> {
  if (showCompleted) return new Set()
  const hidden = new Set<number>()
  for (let i = 1; i < lines.length; i++) {
    if (hidden.has(i)) continue
    if (isCheckedCheckbox(lines[i]!)) {
      const [start, end] = getLogicalBlockFromTexts(lines, i)
      for (let j = start; j <= end; j++) hidden.add(j)
    }
  }
  return hidden
}

// ── Display items ────────────────────────────────────────────────────

export interface VisibleLine {
  type: 'visible'
  realIndex: number
}

export interface CompletedPlaceholder {
  type: 'placeholder'
  count: number
  indentLevel: number
  /** First real line index in the hidden block */
  startIndex: number
  /** Last real line index in the hidden block (inclusive) */
  endIndex: number
}

export type DisplayItem = VisibleLine | CompletedPlaceholder

/**
 * Computes display items from lines: either visible lines or placeholder summaries
 * for contiguous hidden blocks.
 */
export function computeDisplayItems(lines: string[], showCompleted: boolean): DisplayItem[] {
  if (showCompleted) return lines.map((_, i) => ({ type: 'visible', realIndex: i }))
  return computeDisplayItemsFromHidden(lines, computeHiddenIndices(lines, false))
}

/**
 * Computes display items from a pre-computed hidden set.
 * Useful when the caller needs to modify the hidden set (e.g. excluding recently-checked lines).
 */
export function computeDisplayItemsFromHidden(lines: string[], hidden: Set<number>): DisplayItem[] {
  if (hidden.size === 0) return lines.map((_, i) => ({ type: 'visible', realIndex: i }))
  const result: DisplayItem[] = []
  let i = 0
  while (i < lines.length) {
    if (!hidden.has(i)) {
      result.push({ type: 'visible', realIndex: i })
      i++
    } else {
      const startIndex = i
      const placeholderIndent = getIndentLevelFromText(lines[i]!)
      let count = 0
      while (i < lines.length && hidden.has(i)) {
        if (getIndentLevelFromText(lines[i]!) === placeholderIndent) count++
        i++
      }
      result.push({ type: 'placeholder', count, indentLevel: placeholderIndent, startIndex, endIndex: i - 1 })
    }
  }
  return result
}

// ── Sort completed to bottom ─────────────────────────────────────────

interface TreeNode {
  text: string
  children: TreeNode[]
}

/**
 * Sorts checked subtrees to the bottom of each sibling group, recursively.
 * Empty lines act as barriers: checked items don't cross them.
 * Index 0 (title) is never moved.
 */
export function sortCompletedToBottom(lineTexts: string[]): string[] {
  if (lineTexts.length <= 1) return lineTexts
  const roots = parseForest(lineTexts.slice(1))
  const sorted = partitionNodes(roots)
  return [lineTexts[0]!, ...flatten(sorted)]
}

function parseForest(lines: string[]): TreeNode[] {
  const roots: TreeNode[] = []
  const stack: { depth: number; node: TreeNode }[] = []

  for (const line of lines) {
    const depth = getIndentLevelFromText(line)
    const node: TreeNode = { text: line, children: [] }

    while (stack.length > 0 && stack[stack.length - 1]!.depth >= depth) {
      stack.pop()
    }

    if (stack.length === 0) {
      roots.push(node)
    } else {
      stack[stack.length - 1]!.node.children.push(node)
    }

    // Only push non-empty lines onto the stack (empty lines can't have children)
    if (line.replace(/^\t+/, '') !== '') {
      stack.push({ depth, node })
    }
  }
  return roots
}

function isSpacer(node: TreeNode): boolean {
  return node.text.replace(/^\t+/, '') === ''
}

function partitionNodes(nodes: TreeNode[]): TreeNode[] {
  const sections: TreeNode[][] = []
  let currentSection: TreeNode[] = []

  for (const node of nodes) {
    if (isSpacer(node)) {
      if (currentSection.length > 0) {
        sections.push(currentSection)
        currentSection = []
      }
      sections.push([node])
    } else {
      currentSection.push(node)
    }
  }
  if (currentSection.length > 0) {
    sections.push(currentSection)
  }

  const result: TreeNode[] = []
  for (const section of sections) {
    if (section.length === 1 && isSpacer(section[0]!)) {
      result.push(section[0]!)
    } else {
      const recursed = section.map((node) => ({
        text: node.text,
        children: partitionNodes(node.children),
      }))
      const unchecked: TreeNode[] = []
      const checked: TreeNode[] = []
      for (const node of recursed) {
        if (isCheckedCheckbox(node.text)) {
          checked.push(node)
        } else {
          unchecked.push(node)
        }
      }
      result.push(...unchecked, ...checked)
    }
  }
  return result
}

function flatten(nodes: TreeNode[]): string[] {
  const result: string[] = []
  for (const node of nodes) {
    result.push(node.text)
    result.push(...flatten(node.children))
  }
  return result
}

// ── Visibility modifiers ────────────────────────────────────────────

/**
 * Computes the effective hidden set by excluding recently-checked lines
 * (and their subtrees) so they remain visible at reduced opacity.
 */
export function computeEffectiveHidden(
  hiddenIndices: Set<number>,
  recentlyChecked: Set<number>,
  lines: string[],
): Set<number> {
  if (recentlyChecked.size === 0) return hiddenIndices
  const result = new Set(hiddenIndices)
  for (const idx of recentlyChecked) {
    const [start, end] = getLogicalBlockFromTexts(lines, idx)
    for (let j = start; j <= end; j++) result.delete(j)
  }
  return result
}

/**
 * Computes which lines should render at reduced opacity: recently-checked lines
 * that would be hidden if not for the recently-checked exclusion.
 */
export function computeFadedIndices(
  hiddenIndices: Set<number>,
  recentlyChecked: Set<number>,
  lines: string[],
): Set<number> {
  if (recentlyChecked.size === 0) return new Set()
  const result = new Set<number>()
  for (const idx of recentlyChecked) {
    if (!hiddenIndices.has(idx)) continue
    const [start, end] = getLogicalBlockFromTexts(lines, idx)
    for (let j = start; j <= end; j++) result.add(j)
  }
  return result
}

// ── Focus helpers ────────────────────────────────────────────────────

/**
 * Returns the nearest visible (non-hidden) line index, preferring lines above.
 */
export function nearestVisibleLine(
  lines: string[],
  focusedIndex: number,
  hiddenSet: Set<number>,
): number {
  if (!hiddenSet.has(focusedIndex)) return focusedIndex
  for (let i = focusedIndex; i >= 0; i--) {
    if (!hiddenSet.has(i)) return i
  }
  for (let i = focusedIndex; i < lines.length; i++) {
    if (!hiddenSet.has(i)) return i
  }
  return 0
}

// ── Private helpers ──────────────────────────────────────────────────

export function getLogicalBlockFromTexts(lines: string[], startIndex: number): [number, number] {
  if (startIndex < 0 || startIndex >= lines.length) return [startIndex, startIndex]
  const startIndent = getIndentLevelFromText(lines[startIndex]!)
  let endIndex = startIndex
  for (let i = startIndex + 1; i < lines.length; i++) {
    if (getIndentLevelFromText(lines[i]!) <= startIndent) break
    endIndex = i
  }
  return [startIndex, endIndex]
}
