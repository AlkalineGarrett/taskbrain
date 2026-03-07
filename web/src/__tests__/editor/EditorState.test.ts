import { describe, it, expect } from 'vitest'
import { EditorState } from '../../editor/EditorState'
import { LineState } from '../../editor/LineState'
import { BULLET, CHECKBOX_UNCHECKED, CHECKBOX_CHECKED } from '../../editor/LinePrefixes'

function stateWithText(text: string, focusedLine = 0): EditorState {
  const state = new EditorState()
  state.updateFromText(text)
  state.focusedLineIndex = Math.min(focusedLine, state.lines.length - 1)
  return state
}

function stateWithLines(...texts: string[]): EditorState {
  const state = new EditorState()
  state.lines = texts.map((t) => new LineState(t))
  state.focusedLineIndex = 0
  return state
}

describe('EditorState text property', () => {
  it('joins lines with newlines', () => {
    const state = stateWithLines('abc', 'def', 'ghi')
    expect(state.text).toBe('abc\ndef\nghi')
  })

  it('single line', () => {
    const state = stateWithLines('hello')
    expect(state.text).toBe('hello')
  })

  it('empty state', () => {
    const state = new EditorState()
    expect(state.text).toBe('')
  })
})

describe('EditorState updateFromText', () => {
  it('creates lines from text', () => {
    const state = new EditorState()
    state.updateFromText('abc\ndef\nghi')
    expect(state.lines.length).toBe(3)
    expect(state.lines[0]!.text).toBe('abc')
    expect(state.lines[1]!.text).toBe('def')
    expect(state.lines[2]!.text).toBe('ghi')
  })

  it('handles empty lines', () => {
    const state = new EditorState()
    state.updateFromText('abc\n\nghi')
    expect(state.lines.length).toBe(3)
    expect(state.lines[1]!.text).toBe('')
  })

  it('clamps focusedLineIndex', () => {
    const state = new EditorState()
    state.focusedLineIndex = 10
    state.updateFromText('abc\ndef')
    expect(state.focusedLineIndex).toBe(1)
  })
})

describe('EditorState deleteSelection', () => {
  it('removes selected text', () => {
    const state = stateWithText('hello world')
    state.setSelection(5, 11) // " world"
    const pos = state.deleteSelectionInternal()
    expect(pos).toBe(5)
    expect(state.text).toBe('hello')
  })

  it('middle selection', () => {
    const state = stateWithText('abcdef')
    state.setSelection(2, 4) // "cd"
    state.deleteSelectionInternal()
    expect(state.text).toBe('abef')
  })

  it('multi-line selection', () => {
    const state = stateWithText('abc\ndef\nghi')
    // "abc\ndef\nghi" offsets: a=0,b=1,c=2,\n=3,d=4,e=5,f=6,\n=7,g=8,h=9,i=10
    state.setSelection(2, 9) // "c\ndef\nh"
    state.deleteSelectionInternal()
    expect(state.text).toBe('abhi')
  })

  it('no selection returns -1', () => {
    const state = stateWithText('hello')
    const pos = state.deleteSelectionInternal()
    expect(pos).toBe(-1)
  })

  it('clears selection after delete', () => {
    const state = stateWithText('hello world')
    state.setSelection(0, 5)
    state.deleteSelectionInternal()
    expect(state.hasSelection).toBe(false)
  })

  it('removes empty lines created by deletion', () => {
    const state = stateWithText('abc\ndef\nghi')
    // Select "def" on line 1, leaving an empty line
    state.setSelection(4, 7) // "def"
    state.deleteSelectionInternal()
    // The empty line at position of deletion should be removed
    expect(state.lines.some((l, i) => l.text === '' && i < state.lines.length - 1)).toBe(false)
  })

  it('keeps last line even if empty', () => {
    const state = stateWithText('abc')
    state.setSelection(0, 3)
    state.deleteSelectionInternal()
    expect(state.lines.length).toBeGreaterThanOrEqual(1)
  })

  it('preserves pre-existing empty lines not at cursor', () => {
    const state = stateWithText('abc\n\ndef')
    // Select text only in first line
    state.setSelection(0, 2) // "ab"
    state.deleteSelectionInternal()
    // The pre-existing empty line should still be there
    expect(state.text).toContain('\n')
  })
})

describe('EditorState replaceSelection', () => {
  it('replaces selected text', () => {
    const state = stateWithText('hello world')
    state.setSelection(6, 11) // "world"
    const pos = state.replaceSelectionInternal('earth')
    expect(state.text).toBe('hello earth')
    expect(pos).toBe(11) // cursor after "earth"
  })

  it('empty replacement deletes selection', () => {
    const state = stateWithText('hello world')
    state.setSelection(5, 11)
    state.replaceSelectionInternal('')
    expect(state.text).toBe('hello')
  })

  it('multi-line replacement', () => {
    const state = stateWithText('abc\ndef\nghi')
    // offsets: a=0,b=1,c=2,\n=3,d=4,e=5,f=6,\n=7,g=8,h=9,i=10
    state.setSelection(2, 9) // "c\ndef\nh"
    state.replaceSelectionInternal('X')
    expect(state.text).toBe('abXhi')
  })

  it('clears selection after replace', () => {
    const state = stateWithText('hello')
    state.setSelection(0, 5)
    state.replaceSelectionInternal('bye')
    expect(state.hasSelection).toBe(false)
  })

  it('inserts at cursor when no selection', () => {
    const state = stateWithText('hello')
    state.focusedLineIndex = 0
    state.lines[0]!.updateFull('hello', 5)
    const pos = state.replaceSelectionInternal(' world')
    expect(state.text).toBe('hello world')
    expect(pos).toBe(11)
  })
})

describe('EditorState selectAll/clearSelection', () => {
  it('selectAll selects entire text', () => {
    const state = stateWithText('abc\ndef')
    state.selectAll()
    expect(state.hasSelection).toBe(true)
    expect(state.getSelectedText()).toBe('abc\ndef')
  })

  it('clearSelection removes selection', () => {
    const state = stateWithText('abc')
    state.setSelection(0, 3)
    state.clearSelection()
    expect(state.hasSelection).toBe(false)
  })
})

describe('EditorState indent/unindent/toggle on current line', () => {
  it('indentInternal indents focused line', () => {
    const state = stateWithText('hello')
    state.focusedLineIndex = 0
    state.indentInternal()
    expect(state.lines[0]!.text).toBe('\thello')
  })

  it('unindentInternal unindents focused line', () => {
    const state = stateWithText('\thello')
    state.focusedLineIndex = 0
    state.unindentInternal()
    expect(state.lines[0]!.text).toBe('hello')
  })

  it('toggleBulletInternal toggles bullet on focused line', () => {
    const state = stateWithText('hello')
    state.focusedLineIndex = 0
    state.toggleBulletInternal()
    expect(state.lines[0]!.text).toBe(`${BULLET}hello`)
  })

  it('toggleCheckboxInternal toggles checkbox on focused line', () => {
    const state = stateWithText('hello')
    state.focusedLineIndex = 0
    state.toggleCheckboxInternal()
    expect(state.lines[0]!.text).toBe(`${CHECKBOX_UNCHECKED}hello`)
  })
})

describe('EditorState handleSpaceWithSelection', () => {
  it('returns false when no selection', () => {
    const state = stateWithText('hello')
    expect(state.handleSpaceWithSelectionInternal()).toBe(false)
  })

  it('indents selected lines on space', () => {
    const state = stateWithText('abc\ndef')
    state.setSelection(0, 7) // select all
    const result = state.handleSpaceWithSelectionInternal()
    expect(result).toBe(true)
    expect(state.lines[0]!.text).toBe('\tabc')
    expect(state.lines[1]!.text).toBe('\tdef')
  })
})

describe('EditorState getSelectedText', () => {
  it('returns empty when no selection', () => {
    const state = stateWithText('hello')
    expect(state.getSelectedText()).toBe('')
  })

  it('returns selected text', () => {
    const state = stateWithText('hello world')
    state.setSelection(0, 5)
    expect(state.getSelectedText()).toBe('hello')
  })
})
