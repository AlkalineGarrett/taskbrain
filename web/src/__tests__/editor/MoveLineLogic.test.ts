import { describe, it, expect } from 'vitest'
import { LineState } from '../../editor/LineState'
import { getIndentLevel, getLogicalBlock } from '../../editor/IndentationUtils'
import { findMoveUpTarget, findMoveDownTarget, getMoveTarget, wouldOrphanChildren } from '../../editor/MoveTargetFinder'
import { calculateMove } from '../../editor/MoveExecutor'
import { getSelectedLineRange } from '../../editor/SelectionCoordinates'
import { SELECTION_NONE } from '../../editor/EditorSelection'

function makeLines(...texts: string[]): LineState[] {
  return texts.map((t) => new LineState(t))
}

describe('getIndentLevel', () => {
  it('plain text has indent 0', () => {
    const lines = makeLines('hello')
    expect(getIndentLevel(lines, 0)).toBe(0)
  })

  it('empty text has indent 0', () => {
    const lines = makeLines('')
    expect(getIndentLevel(lines, 0)).toBe(0)
  })

  it('tabs give indent level', () => {
    const lines = makeLines('\t\thello')
    expect(getIndentLevel(lines, 0)).toBe(2)
  })

  it('tab + bullet', () => {
    const lines = makeLines('\t\u2022 hello')
    expect(getIndentLevel(lines, 0)).toBe(1)
  })

  it('invalid index returns 0', () => {
    const lines = makeLines('hello')
    expect(getIndentLevel(lines, 5)).toBe(0)
  })
})

describe('getLogicalBlock', () => {
  it('single line block', () => {
    const lines = makeLines('a', 'b', 'c')
    expect(getLogicalBlock(lines, 1)).toEqual([1, 1])
  })

  it('includes deeper children', () => {
    const lines = makeLines('parent', '\tchild1', '\tchild2', 'sibling')
    expect(getLogicalBlock(lines, 0)).toEqual([0, 2])
  })

  it('nested blocks', () => {
    const lines = makeLines('a', '\tb', '\t\tc', '\td', 'e')
    expect(getLogicalBlock(lines, 0)).toEqual([0, 3])
  })

  it('stops at same indent level', () => {
    const lines = makeLines('a', '\tb', 'c', '\td')
    expect(getLogicalBlock(lines, 0)).toEqual([0, 1])
  })

  it('handles empty lines in block', () => {
    // Empty lines have indent 0, so they break the block
    const lines = makeLines('a', '\tb', '', '\td')
    expect(getLogicalBlock(lines, 0)).toEqual([0, 1])
  })
})

describe('getSelectedLineRange', () => {
  it('no selection returns focused line', () => {
    const lines = makeLines('a', 'b', 'c')
    expect(getSelectedLineRange(lines, SELECTION_NONE, 1)).toEqual([1, 1])
  })

  it('returns range of selected lines', () => {
    const lines = makeLines('abc', 'def', 'ghi')
    // Select from middle of line 0 to middle of line 2: "bc\ndef\ng"
    expect(getSelectedLineRange(lines, { start: 1, end: 9 }, 0)).toEqual([0, 2])
  })

  it('adjusts end line when selection ends at line boundary', () => {
    const lines = makeLines('abc', 'def', 'ghi')
    // Select "abc\n" = 0 to 4, end is at start of line 1
    expect(getSelectedLineRange(lines, { start: 0, end: 4 }, 0)).toEqual([0, 0])
  })
})

describe('findMoveUpTarget', () => {
  it('returns null when at first line', () => {
    const lines = makeLines('a', 'b', 'c')
    expect(findMoveUpTarget(lines, 0, 0)).toBeNull()
  })

  it('simple move up', () => {
    const lines = makeLines('a', 'b', 'c')
    expect(findMoveUpTarget(lines, 1, 1)).toBe(0)
  })

  it('hops over block', () => {
    const lines = makeLines('a', '\tb', '\tc', 'd')
    // Moving 'd' (index 3) up should hop over block [0,2]
    expect(findMoveUpTarget(lines, 3, 3)).toBe(0)
  })
})

describe('findMoveDownTarget', () => {
  it('returns null when at last line', () => {
    const lines = makeLines('a', 'b', 'c')
    expect(findMoveDownTarget(lines, 2, 2)).toBeNull()
  })

  it('simple move down', () => {
    const lines = makeLines('a', 'b', 'c')
    expect(findMoveDownTarget(lines, 0, 0)).toBe(2) // insert after line 1
  })

  it('hops over block', () => {
    const lines = makeLines('a', 'b', '\tc', '\td')
    // Moving 'a' (index 0) down should hop over block at line 1 [1,3]
    expect(findMoveDownTarget(lines, 0, 0)).toBe(4)
  })
})

describe('getMoveTarget', () => {
  it('with selection uses selected range', () => {
    const lines = makeLines('a', 'b', 'c')
    const sel = { start: 0, end: 1 } // selects line 0
    const target = getMoveTarget(lines, true, sel, 0, false)
    expect(target).not.toBeNull()
  })

  it('without selection uses logical block', () => {
    const lines = makeLines('a', '\tb', 'c')
    const target = getMoveTarget(lines, false, SELECTION_NONE, 0, false)
    // Block is [0,1], move down target should be after 'c'
    expect(target).toBe(3)
  })
})

describe('wouldOrphanChildren', () => {
  it('returns false without selection', () => {
    const lines = makeLines('a', '\tb', 'c')
    expect(wouldOrphanChildren(lines, false, SELECTION_NONE, 0)).toBe(false)
  })

  it('returns true when selection excludes children', () => {
    const lines = makeLines('parent', '\tchild1', '\tchild2')
    // Select only parent (line 0), children would be orphaned
    const sel = { start: 0, end: 6 } // "parent"
    expect(wouldOrphanChildren(lines, true, sel, 0)).toBe(true)
  })

  it('returns false when selection includes all children', () => {
    const lines = makeLines('parent', '\tchild1', '\tchild2')
    // Select all three lines
    const sel = { start: 0, end: 100 }
    expect(wouldOrphanChildren(lines, true, sel, 0)).toBe(false)
  })

  it('returns true when first selected line is not shallowest', () => {
    const lines = makeLines('a', '\tb', 'c')
    // Select only \tb (indent 1), but 'c' (indent 0) is shallowest
    // Actually, selecting just line 1
    const lineStartLine1 = 2 // "a\n" = 2
    const sel = { start: lineStartLine1, end: lineStartLine1 + 2 } // "\tb"
    expect(wouldOrphanChildren(lines, true, sel, 1)).toBe(false) // no children after
  })
})

describe('calculateMove (moveLinesInternal)', () => {
  it('single line move up', () => {
    const lines = makeLines('a', 'b', 'c')
    const result = calculateMove(lines, 1, 1, 0, 1, null)
    expect(result).not.toBeNull()
    expect(result!.newLines).toEqual(['b', 'a', 'c'])
  })

  it('single line move down', () => {
    const lines = makeLines('a', 'b', 'c')
    const result = calculateMove(lines, 0, 0, 2, 0, null)
    expect(result).not.toBeNull()
    expect(result!.newLines).toEqual(['b', 'a', 'c'])
  })

  it('block move up', () => {
    const lines = makeLines('a', 'b', '\tc')
    const result = calculateMove(lines, 1, 2, 0, 1, null)
    expect(result).not.toBeNull()
    expect(result!.newLines).toEqual(['b', '\tc', 'a'])
  })

  it('block move down', () => {
    const lines = makeLines('a', '\tb', 'c')
    const result = calculateMove(lines, 0, 1, 3, 0, null)
    expect(result).not.toBeNull()
    expect(result!.newLines).toEqual(['c', 'a', '\tb'])
  })

  it('invalid range returns null', () => {
    const lines = makeLines('a', 'b')
    expect(calculateMove(lines, -1, 0, 1, 0, null)).toBeNull()
    expect(calculateMove(lines, 0, 5, 1, 0, null)).toBeNull()
  })

  it('target within source returns null', () => {
    const lines = makeLines('a', 'b', 'c')
    expect(calculateMove(lines, 0, 2, 1, 0, null)).toBeNull()
  })

  it('adjusts focused line when within source', () => {
    const lines = makeLines('a', 'b', 'c')
    const result = calculateMove(lines, 1, 1, 0, 1, null)
    expect(result).not.toBeNull()
    expect(result!.newFocusedLineIndex).toBe(0)
  })

  it('adjusts focused line when outside source', () => {
    const lines = makeLines('a', 'b', 'c')
    // Move line 0 to after line 2 (target=3), focused on line 1
    const result = calculateMove(lines, 0, 0, 3, 1, null)
    expect(result).not.toBeNull()
    // Line 1 (b) moves to index 0
    expect(result!.newFocusedLineIndex).toBe(0)
  })

  it('updates selection during move', () => {
    const lines = makeLines('abc', 'def', 'ghi')
    const sel = { start: 0, end: 3 } // selects "abc"
    const result = calculateMove(lines, 0, 0, 2, 0, sel)
    expect(result).not.toBeNull()
    expect(result!.newSelection).not.toBeNull()
  })
})

describe('findMoveUpTarget with hiddenIndices', () => {
  it('skips hidden lines when scanning backward', () => {
    // Lines: a, ☑ done, b  — hide index 1
    const lines = makeLines('a', '☑ done', 'b')
    const hidden = new Set([1])
    expect(findMoveUpTarget(lines, 2, 2, hidden)).toBe(0)
  })

  it('returns null when all lines above are hidden', () => {
    const lines = makeLines('title', '☑ done1', '☑ done2', 'active')
    const hidden = new Set([1, 2])
    // Line 3 trying to move up, lines 1-2 are hidden, line 0 is at same indent (valid)
    expect(findMoveUpTarget(lines, 3, 3, hidden)).toBe(0)
  })

  it('returns null when only line 0 remains and target is hidden', () => {
    const lines = makeLines('☑ done', 'active')
    const hidden = new Set([0])
    expect(findMoveUpTarget(lines, 1, 1, hidden)).toBeNull()
  })

  it('skips hidden block including children', () => {
    // Lines: a, ☑ parent, \tchild, b — hide indices 1,2
    const lines = makeLines('a', '☑ parent', '\tchild', 'b')
    const hidden = new Set([1, 2])
    expect(findMoveUpTarget(lines, 3, 3, hidden)).toBe(0)
  })
})

describe('findMoveDownTarget with hiddenIndices', () => {
  it('skips hidden lines when scanning forward', () => {
    const lines = makeLines('a', '☑ done', 'b')
    const hidden = new Set([1])
    expect(findMoveDownTarget(lines, 0, 0, hidden)).toBe(3)
  })

  it('returns null when all lines below are hidden', () => {
    const lines = makeLines('active', '☑ done1', '☑ done2')
    const hidden = new Set([1, 2])
    expect(findMoveDownTarget(lines, 0, 0, hidden)).toBeNull()
  })

  it('skips hidden block and finds target after it', () => {
    const lines = makeLines('a', '☑ parent', '\tchild', 'b')
    const hidden = new Set([1, 2])
    // Moving 'a' down should skip hidden 1,2 and land after 'b'
    expect(findMoveDownTarget(lines, 0, 0, hidden)).toBe(4)
  })
})

describe('findMoveUpTarget skips hidden block above visible target', () => {
  it('jumps past hidden block between visible lines', () => {
    // Lines: unchecked, ☑ done1, ☑ done2, empty, unchecked2
    // Moving unchecked2 up should land above the hidden block, not between it and the empty line
    const lines = makeLines('unchecked1', '☑ done1', '☑ done2', '', 'unchecked2')
    const hidden = new Set([1, 2])
    expect(findMoveUpTarget(lines, 4, 4, hidden)).toBe(1)
  })

  it('does not skip hidden block at deeper indent', () => {
    // Lines: ☐ parent (indent 1), ☑ child (hidden, indent 1), Root (indent 0), ☐ mover (indent 1)
    // Moving mover up should swap with Root, not jump past the hidden child
    const lines = makeLines('\t☐ parent', '\t☑ child', 'Root', '\t☐ mover')
    const hidden = new Set([1])
    expect(findMoveUpTarget(lines, 3, 3, hidden)).toBe(2)
  })

  it('skips hidden block with children above visible target', () => {
    // Lines: unchecked, ☑ parent, \tchild (both hidden), empty, unchecked2
    const lines = makeLines('unchecked1', '☑ parent', '\tchild', '', 'unchecked2')
    const hidden = new Set([1, 2])
    expect(findMoveUpTarget(lines, 4, 4, hidden)).toBe(1)
  })
})

describe('findMoveDownTarget does not expand block through hidden siblings', () => {
  it('stops block expansion at hidden sibling', () => {
    // Lines: unchecked1, unchecked2, ☑ done1, ☑ done2, empty
    // Moving unchecked1 down should swap with unchecked2, not jump past hidden lines
    const lines = makeLines('unchecked1', 'unchecked2', '☑ done1', '☑ done2', '')
    const hidden = new Set([2, 3])
    expect(findMoveDownTarget(lines, 0, 0, hidden)).toBe(2)
  })

  it('still includes visible children in block expansion', () => {
    // Lines: unchecked1, unchecked2, \tchild, ☑ done (hidden), empty
    // Moving unchecked1 down should swap past unchecked2 + its child, stop at hidden sibling
    const lines = makeLines('unchecked1', 'unchecked2', '\tchild', '☑ done', '')
    const hidden = new Set([3])
    expect(findMoveDownTarget(lines, 0, 0, hidden)).toBe(3)
  })
})

describe('getMoveTarget with hiddenIndices', () => {
  it('uses hiddenIndices for move up', () => {
    const lines = makeLines('a', '☑ done', 'b')
    const hidden = new Set([1])
    const target = getMoveTarget(lines, false, SELECTION_NONE, 2, true, hidden)
    expect(target).toBe(0)
  })

  it('uses hiddenIndices for move down', () => {
    const lines = makeLines('a', '☑ done', 'b')
    const hidden = new Set([1])
    const target = getMoveTarget(lines, false, SELECTION_NONE, 0, false, hidden)
    expect(target).toBe(3)
  })
})

describe('empty line handling in moves', () => {
  it('empty lines have indent 0 and affect block boundaries', () => {
    const lines = makeLines('a', '', 'b')
    // Empty line breaks block
    expect(getLogicalBlock(lines, 0)).toEqual([0, 0])
  })
})
