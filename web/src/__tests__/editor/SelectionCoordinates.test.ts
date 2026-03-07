import { describe, it, expect } from 'vitest'
import { LineState } from '../../editor/LineState'
import {
  getLineStartOffset,
  getLineAndLocalOffset,
  getLineSelection,
  getEffectiveSelectionRange,
} from '../../editor/SelectionCoordinates'
import { SELECTION_NONE } from '../../editor/EditorSelection'

function makeLines(...texts: string[]): LineState[] {
  return texts.map((t) => new LineState(t))
}

describe('getLineStartOffset', () => {
  const lines = makeLines('abc', 'defg', 'hi')

  it('first line starts at 0', () => {
    expect(getLineStartOffset(lines, 0)).toBe(0)
  })

  it('second line starts after first + newline', () => {
    // "abc" = 3 chars + 1 newline = offset 4
    expect(getLineStartOffset(lines, 1)).toBe(4)
  })

  it('third line starts after first two + newlines', () => {
    // "abc\ndefg\n" = 3+1+4+1 = 9
    expect(getLineStartOffset(lines, 2)).toBe(9)
  })

  it('varying lengths', () => {
    const ls = makeLines('a', 'bb', 'ccc')
    expect(getLineStartOffset(ls, 0)).toBe(0)
    expect(getLineStartOffset(ls, 1)).toBe(2) // "a" + \n
    expect(getLineStartOffset(ls, 2)).toBe(5) // "a\nbb\n"
  })
})

describe('getLineAndLocalOffset', () => {
  const lines = makeLines('abc', 'defg', 'hi')
  // full text: "abc\ndefg\nhi" (offsets: 0-2=abc, 3=\n, 4-7=defg, 8=\n, 9-10=hi)

  it('start of first line', () => {
    expect(getLineAndLocalOffset(lines, 0)).toEqual([0, 0])
  })

  it('middle of first line', () => {
    expect(getLineAndLocalOffset(lines, 2)).toEqual([0, 2])
  })

  it('end of first line', () => {
    expect(getLineAndLocalOffset(lines, 3)).toEqual([0, 3])
  })

  it('start of second line', () => {
    expect(getLineAndLocalOffset(lines, 4)).toEqual([1, 0])
  })

  it('middle of second line', () => {
    expect(getLineAndLocalOffset(lines, 6)).toEqual([1, 2])
  })

  it('end of second line', () => {
    expect(getLineAndLocalOffset(lines, 8)).toEqual([1, 4])
  })

  it('start of third line', () => {
    expect(getLineAndLocalOffset(lines, 9)).toEqual([2, 0])
  })

  it('end of third line', () => {
    expect(getLineAndLocalOffset(lines, 11)).toEqual([2, 2])
  })

  it('beyond total length returns end of last line', () => {
    expect(getLineAndLocalOffset(lines, 100)).toEqual([2, 2])
  })
})

describe('getLineSelection', () => {
  const lines = makeLines('abc', 'defg', 'hi')
  // "abc\ndefg\nhi"

  it('no selection returns null', () => {
    expect(getLineSelection(lines, 0, SELECTION_NONE)).toBeNull()
  })

  it('selection fully within a line', () => {
    // Select "ef" in "defg" (global 5-7, line 1 local 1-3)
    expect(getLineSelection(lines, 1, { start: 5, end: 7 })).toEqual([1, 3])
  })

  it('partial selection at start of line', () => {
    // Select from middle of line 0 to middle of line 1
    // "bc\nde" = global 1 to 6
    expect(getLineSelection(lines, 0, { start: 1, end: 6 })).toEqual([1, 3])
  })

  it('partial selection at end of line', () => {
    // Same selection, check line 1
    expect(getLineSelection(lines, 1, { start: 1, end: 6 })).toEqual([0, 2])
  })

  it('unselected line returns null', () => {
    // Selection only in line 0
    expect(getLineSelection(lines, 2, { start: 0, end: 2 })).toBeNull()
  })

  it('multi-line spanning selection', () => {
    // Select all: "abc\ndefg\nhi" = 0 to 11
    expect(getLineSelection(lines, 0, { start: 0, end: 11 })).toEqual([0, 3])
    expect(getLineSelection(lines, 1, { start: 0, end: 11 })).toEqual([0, 4])
    expect(getLineSelection(lines, 2, { start: 0, end: 11 })).toEqual([0, 2])
  })
})

describe('getEffectiveSelectionRange', () => {
  it('returns selection range for empty selection text', () => {
    const [start, end] = getEffectiveSelectionRange('hello', { start: 2, end: 2 })
    expect(start).toBe(2)
    expect(end).toBe(2)
  })

  it('returns correct range for normal selection', () => {
    const [start, end] = getEffectiveSelectionRange('hello world', { start: 2, end: 7 })
    expect(start).toBe(2)
    expect(end).toBe(7)
  })

  it('handles reversed selection', () => {
    const [start, end] = getEffectiveSelectionRange('hello world', { start: 7, end: 2 })
    expect(start).toBe(2)
    expect(end).toBe(7)
  })

  it('extends full line selection to include newline', () => {
    // "abc\ndef" - selecting "abc" from 0 to 3, next char is \n at index 3
    const [start, end] = getEffectiveSelectionRange('abc\ndef', { start: 0, end: 3 })
    expect(start).toBe(0)
    expect(end).toBe(4) // extended past the newline
  })

  it('does not extend for partial line selection', () => {
    // "abc\ndef" - selecting "bc" from 1 to 3
    const [start, end] = getEffectiveSelectionRange('abc\ndef', { start: 1, end: 3 })
    expect(start).toBe(1)
    expect(end).toBe(3) // NOT extended (doesn't start at line boundary)
  })
})
