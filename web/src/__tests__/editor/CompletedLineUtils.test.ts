import { describe, it, expect } from 'vitest'
import {
  isCheckedCheckbox,
  computeHiddenIndices,
  computeDisplayItems,
  sortCompletedToBottom,
  nearestVisibleLine,
} from '../../editor/CompletedLineUtils'

describe('isCheckedCheckbox', () => {
  it('returns true for checked line', () => {
    expect(isCheckedCheckbox('☑ Done')).toBe(true)
  })

  it('returns true for indented checked line', () => {
    expect(isCheckedCheckbox('\t\t☑ Done')).toBe(true)
  })

  it('returns false for unchecked line', () => {
    expect(isCheckedCheckbox('☐ Todo')).toBe(false)
  })

  it('returns false for plain line', () => {
    expect(isCheckedCheckbox('Plain text')).toBe(false)
  })

  it('returns false for empty line', () => {
    expect(isCheckedCheckbox('')).toBe(false)
  })
})

describe('computeHiddenIndices', () => {
  it('showCompleted true returns empty set', () => {
    const lines = ['Title', '☑ Done', '☐ Active']
    expect(computeHiddenIndices(lines, true)).toEqual(new Set())
  })

  it('no checked lines returns empty set', () => {
    const lines = ['Title', '☐ Active', '• Bullet']
    expect(computeHiddenIndices(lines, false)).toEqual(new Set())
  })

  it('checked line is hidden', () => {
    const lines = ['Title', '☑ Done', '☐ Active']
    expect(computeHiddenIndices(lines, false)).toEqual(new Set([1]))
  })

  it('checked line with children hides entire subtree', () => {
    const lines = ['Title', '☑ Done', '\tChild 1', '\tChild 2', '☐ Active']
    expect(computeHiddenIndices(lines, false)).toEqual(new Set([1, 2, 3]))
  })

  it('nested checked hides subtree', () => {
    const lines = ['Title', 'Parent', '\t☑ Done', '\t\tGrandchild', '\tSibling']
    expect(computeHiddenIndices(lines, false)).toEqual(new Set([2, 3]))
  })

  it('title line is never hidden even if checked', () => {
    const lines = ['☑ Checked Title', '☐ Active']
    expect(computeHiddenIndices(lines, false)).toEqual(new Set())
  })

  it('multiple checked lines with children', () => {
    const lines = ['Title', '☑ Done1', '\tChild1', '☑ Done2', '\tChild2', '☐ Active']
    expect(computeHiddenIndices(lines, false)).toEqual(new Set([1, 2, 3, 4]))
  })
})

describe('computeDisplayItems', () => {
  it('showCompleted true returns all visible lines', () => {
    const lines = ['Title', '☑ Done', '☐ Active']
    const items = computeDisplayItems(lines, true)
    expect(items).toEqual([
      { type: 'visible', realIndex: 0 },
      { type: 'visible', realIndex: 1 },
      { type: 'visible', realIndex: 2 },
    ])
  })

  it('hidden lines produce placeholder with correct count', () => {
    const lines = ['Title', '☑ Done1', '☑ Done2', '☐ Active']
    const items = computeDisplayItems(lines, false)
    expect(items).toEqual([
      { type: 'visible', realIndex: 0 },
      { type: 'placeholder', count: 2, indentLevel: 0, startIndex: 1, endIndex: 2 },
      { type: 'visible', realIndex: 3 },
    ])
  })

  it('placeholder counts only top-level hidden items', () => {
    const lines = ['Title', '☑ Done', '\tChild1', '\tChild2', '☐ Active']
    const items = computeDisplayItems(lines, false)
    expect(items).toEqual([
      { type: 'visible', realIndex: 0 },
      { type: 'placeholder', count: 1, indentLevel: 0, startIndex: 1, endIndex: 3 },
      { type: 'visible', realIndex: 4 },
    ])
  })

  it('indented placeholder has correct indent level', () => {
    const lines = ['Title', 'Parent', '\t☑ Done', '\t☐ Active']
    const items = computeDisplayItems(lines, false)
    expect(items).toEqual([
      { type: 'visible', realIndex: 0 },
      { type: 'visible', realIndex: 1 },
      { type: 'placeholder', count: 1, indentLevel: 1, startIndex: 2, endIndex: 2 },
      { type: 'visible', realIndex: 3 },
    ])
  })

})

describe('sortCompletedToBottom', () => {
  it('empty input returns empty', () => {
    expect(sortCompletedToBottom([])).toEqual([])
  })

  it('single line returns unchanged', () => {
    expect(sortCompletedToBottom(['Title'])).toEqual(['Title'])
  })

  it('no checked lines returns unchanged', () => {
    const lines = ['Title', '☐ A', '☐ B']
    expect(sortCompletedToBottom(lines)).toEqual(lines)
  })

  it('checked lines move to bottom', () => {
    const lines = ['Title', '☑ Done', '☐ Active']
    expect(sortCompletedToBottom(lines)).toEqual(['Title', '☐ Active', '☑ Done'])
  })

  it('multiple checked and unchecked sort correctly', () => {
    const lines = ['Title', '☑ Done A', '☐ Active B', '☐ Active C', '☑ Done D']
    expect(sortCompletedToBottom(lines)).toEqual([
      'Title',
      '☐ Active B',
      '☐ Active C',
      '☑ Done A',
      '☑ Done D',
    ])
  })

  it('checked subtrees stay intact', () => {
    const lines = ['Title', '☑ Done', '\tChild', '☐ Active']
    expect(sortCompletedToBottom(lines)).toEqual(['Title', '☐ Active', '☑ Done', '\tChild'])
  })

  it('nested levels sort independently', () => {
    const lines = ['Title', 'Parent', '\t☑ Done child', '\t☐ Active child']
    expect(sortCompletedToBottom(lines)).toEqual([
      'Title',
      'Parent',
      '\t☐ Active child',
      '\t☑ Done child',
    ])
  })

  it('empty lines act as barriers', () => {
    const lines = ['Title', '☑ Done A', '☐ Active B', '', '☑ Done C', '☐ Active D']
    expect(sortCompletedToBottom(lines)).toEqual([
      'Title',
      '☐ Active B',
      '☑ Done A',
      '',
      '☐ Active D',
      '☑ Done C',
    ])
  })

  it('already sorted is idempotent', () => {
    const lines = ['Title', '☐ Active', '☑ Done']
    expect(sortCompletedToBottom(lines)).toEqual(lines)
  })

  it('deep nesting sorts at each level', () => {
    const lines = [
      'Title',
      'Parent',
      '\tChild',
      '\t\t☑ Done grandchild',
      '\t\t☐ Active grandchild',
    ]
    expect(sortCompletedToBottom(lines)).toEqual([
      'Title',
      'Parent',
      '\tChild',
      '\t\t☐ Active grandchild',
      '\t\t☑ Done grandchild',
    ])
  })
})

describe('nearestVisibleLine', () => {
  it('returns same index if not hidden', () => {
    expect(nearestVisibleLine(['a', 'b', 'c'], 2, new Set())).toBe(2)
  })

  it('prefers line above', () => {
    const lines = ['Title', 'Visible', '☑ Hidden', 'Below']
    expect(nearestVisibleLine(lines, 2, new Set([2]))).toBe(1)
  })

  it('falls back to line below', () => {
    const lines = ['☑ Hidden', '☑ Hidden2', 'Visible']
    expect(nearestVisibleLine(lines, 0, new Set([0, 1]))).toBe(2)
  })

  it('returns 0 when all hidden', () => {
    const lines = ['a', 'b']
    expect(nearestVisibleLine(lines, 1, new Set([0, 1]))).toBe(0)
  })
})
