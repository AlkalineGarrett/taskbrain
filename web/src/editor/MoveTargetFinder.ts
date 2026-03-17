import type { LineState } from './LineState'
import type { EditorSelection } from './EditorSelection'
import * as SC from './SelectionCoordinates'
import * as IU from './IndentationUtils'

export function findMoveUpTarget(lines: LineState[], rangeFirst: number, rangeLast: number, hiddenIndices: Set<number> = new Set()): number | null {
  void rangeLast
  if (rangeFirst <= 0) return null
  const firstIndent = IU.getIndentLevel(lines, rangeFirst)
  let target = rangeFirst - 1

  // Skip hidden lines and deeper lines (children of a previous group)
  while (target > 0 && (hiddenIndices.has(target) || IU.getIndentLevel(lines, target) > firstIndent)) {
    target--
  }
  // If target itself is hidden, no valid move
  if (hiddenIndices.has(target)) return null

  // Also skip past any contiguous hidden block immediately above the target.
  // Completed items are sorted to the bottom of their section, so they sit
  // between the visible lines and the section separator. The hidden block
  // and the separator should be treated as one unit for move purposes.
  // Only skip if the block's shallowest indent <= the target's indent
  // (don't jump across indent boundaries).
  const targetIndent = IU.getIndentLevel(lines, target)
  let probe = target
  let shallowest = Infinity
  while (probe > 0 && hiddenIndices.has(probe - 1)) {
    probe--
    shallowest = Math.min(shallowest, IU.getIndentLevel(lines, probe))
  }
  if (shallowest <= targetIndent) {
    target = probe
  }

  return target
}

export function findMoveDownTarget(lines: LineState[], rangeFirst: number, rangeLast: number, hiddenIndices: Set<number> = new Set()): number | null {
  // Find first non-hidden line after the range
  let target = rangeLast + 1
  while (target <= lines.length - 1 && hiddenIndices.has(target)) {
    target++
  }
  if (target > lines.length - 1) return null

  const firstIndent = IU.getIndentLevel(lines, rangeFirst)
  const targetIndent = IU.getIndentLevel(lines, target)

  // Find end of target's logical block (children only — deeper indent)
  if (targetIndent <= firstIndent) {
    while (target < lines.length - 1) {
      const next = target + 1
      if (IU.getIndentLevel(lines, next) > targetIndent) {
        target++
      } else {
        break
      }
    }
  }

  return target + 1
}

export function getMoveTarget(
  lines: LineState[],
  hasSel: boolean,
  selection: EditorSelection,
  focusedLineIndex: number,
  moveUp: boolean,
  hiddenIndices: Set<number> = new Set(),
): number | null {
  let rangeFirst: number, rangeLast: number
  if (hasSel) {
    ;[rangeFirst, rangeLast] = SC.getSelectedLineRange(lines, selection, focusedLineIndex)
  } else {
    ;[rangeFirst, rangeLast] = IU.getLogicalBlock(lines, focusedLineIndex)
  }

  if (hasSel) {
    let shallowest = Infinity
    for (let i = rangeFirst; i <= rangeLast; i++) {
      shallowest = Math.min(shallowest, IU.getIndentLevel(lines, i))
    }
    const firstIndent = IU.getIndentLevel(lines, rangeFirst)
    if (firstIndent > shallowest) {
      if (moveUp) {
        return rangeFirst > 0 ? rangeFirst - 1 : null
      }
      return rangeLast < lines.length - 1 ? rangeLast + 2 : null
    }
  }

  return moveUp
    ? findMoveUpTarget(lines, rangeFirst, rangeLast, hiddenIndices)
    : findMoveDownTarget(lines, rangeFirst, rangeLast, hiddenIndices)
}

export function wouldOrphanChildren(
  lines: LineState[],
  hasSel: boolean,
  selection: EditorSelection,
  focusedLineIndex: number,
): boolean {
  if (!hasSel) return false
  const [selFirst, selLast] = SC.getSelectedLineRange(lines, selection, focusedLineIndex)

  let shallowest = Infinity
  for (let i = selFirst; i <= selLast; i++) {
    shallowest = Math.min(shallowest, IU.getIndentLevel(lines, i))
  }
  const firstIndent = IU.getIndentLevel(lines, selFirst)
  if (firstIndent > shallowest) return true

  const [, blockLast] = IU.getLogicalBlock(lines, selFirst)
  return selLast < blockLast
}
