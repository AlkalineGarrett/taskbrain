import { describe, it, expect, vi } from 'vitest'
import { EditorState } from '../../editor/EditorState'
import { EditorController } from '../../editor/EditorController'
import { LineState } from '../../editor/LineState'
import { BULLET, CHECKBOX_UNCHECKED, CHECKBOX_CHECKED } from '../../editor/LinePrefixes'

// Mock navigator.clipboard for cut/copy tests
const mockClipboard = { writeText: vi.fn().mockResolvedValue(undefined) }
Object.defineProperty(navigator, 'clipboard', {
  value: mockClipboard,
  writable: true,
  configurable: true,
})

function controllerWithText(text: string, focusedLine = 0): EditorController {
  const state = new EditorState()
  state.updateFromText(text)
  state.focusedLineIndex = Math.min(focusedLine, state.lines.length - 1)
  return new EditorController(state)
}

function controllerWithLines(...texts: string[]): EditorController {
  const state = new EditorState()
  state.lines = texts.map((t) => new LineState(t))
  state.focusedLineIndex = 0
  return new EditorController(state)
}

describe('EditorController getSelectedText', () => {
  it('returns selected text', () => {
    const ctrl = controllerWithText('hello world')
    ctrl.state.setSelection(0, 5)
    expect(ctrl.state.getSelectedText()).toBe('hello')
  })

  it('returns empty when no selection', () => {
    const ctrl = controllerWithText('hello')
    expect(ctrl.state.getSelectedText()).toBe('')
  })
})

describe('EditorController copy', () => {
  it('copies selected text to clipboard', () => {
    const ctrl = controllerWithText('hello world')
    ctrl.state.setSelection(6, 11) // "world"
    ctrl.copySelection()
    expect(mockClipboard.writeText).toHaveBeenCalledWith('world')
  })
})

describe('EditorController cut', () => {
  it('removes selected text and returns it', () => {
    const ctrl = controllerWithText('hello world')
    ctrl.state.setSelection(5, 11) // " world"
    const result = ctrl.cutSelection()
    expect(result).toBe(' world')
    expect(ctrl.state.text).toBe('hello')
  })

  it('places cursor at selection start', () => {
    const ctrl = controllerWithText('hello world')
    ctrl.state.setSelection(5, 11)
    ctrl.cutSelection()
    expect(ctrl.state.focusedLineIndex).toBe(0)
    expect(ctrl.state.lines[0]!.cursorPosition).toBe(5)
  })

  it('returns null when no selection', () => {
    const ctrl = controllerWithText('hello')
    expect(ctrl.cutSelection()).toBeNull()
  })
})

describe('EditorController delete', () => {
  it('removes selected text', () => {
    const ctrl = controllerWithText('hello world')
    ctrl.state.setSelection(5, 11)
    ctrl.deleteSelectionWithUndo()
    expect(ctrl.state.text).toBe('hello')
  })

  it('places cursor at selection start', () => {
    const ctrl = controllerWithText('hello world')
    ctrl.state.setSelection(5, 11)
    ctrl.deleteSelectionWithUndo()
    expect(ctrl.state.lines[0]!.cursorPosition).toBe(5)
  })

  it('does nothing when no selection', () => {
    const ctrl = controllerWithText('hello')
    ctrl.deleteSelectionWithUndo()
    expect(ctrl.state.text).toBe('hello')
  })
})

describe('EditorController selectAll', () => {
  it('selects entire text', () => {
    const ctrl = controllerWithText('hello\nworld')
    ctrl.state.selectAll()
    expect(ctrl.state.hasSelection).toBe(true)
    expect(ctrl.state.getSelectedText()).toBe('hello\nworld')
  })

  it('preserves text content', () => {
    const ctrl = controllerWithText('hello\nworld')
    const textBefore = ctrl.state.text
    ctrl.state.selectAll()
    expect(ctrl.state.text).toBe(textBefore)
  })
})

describe('EditorController paste/insertText', () => {
  it('paste inserts text at cursor', () => {
    const ctrl = controllerWithText('hello')
    ctrl.state.lines[0]!.updateFull('hello', 5)
    ctrl.paste(' world')
    expect(ctrl.state.text).toBe('hello world')
  })

  it('paste replaces selection', () => {
    const ctrl = controllerWithText('hello world')
    ctrl.state.setSelection(6, 11) // "world"
    ctrl.paste('earth')
    expect(ctrl.state.text).toBe('hello earth')
  })

  it('insertText inserts at cursor position', () => {
    const ctrl = controllerWithText('helo')
    ctrl.state.lines[0]!.updateFull('helo', 3)
    ctrl.insertText(0, 'l')
    expect(ctrl.state.text).toBe('hello')
  })

  it('insertText handles multi-line input', () => {
    const ctrl = controllerWithText('hello')
    ctrl.state.lines[0]!.updateFull('hello', 5)
    ctrl.insertText(0, '\nworld')
    expect(ctrl.state.lines.length).toBeGreaterThan(1)
  })
})

describe('EditorController toggle operations', () => {
  it('toggleBullet adds bullet', () => {
    const ctrl = controllerWithText('hello')
    ctrl.toggleBullet()
    expect(ctrl.state.lines[0]!.text).toBe(`${BULLET}hello`)
  })

  it('toggleCheckbox adds checkbox', () => {
    const ctrl = controllerWithText('hello')
    ctrl.toggleCheckbox()
    expect(ctrl.state.lines[0]!.text).toBe(`${CHECKBOX_UNCHECKED}hello`)
  })

  it('indent adds tab', () => {
    const ctrl = controllerWithText('hello')
    ctrl.indent()
    expect(ctrl.state.lines[0]!.text).toBe('\thello')
  })

  it('unindent removes tab', () => {
    const ctrl = controllerWithText('\thello')
    ctrl.unindent()
    expect(ctrl.state.lines[0]!.text).toBe('hello')
  })
})

describe('EditorController undo/redo integration', () => {
  it('undo after toggleBullet', () => {
    const ctrl = controllerWithText('hello')
    ctrl.toggleBullet()
    expect(ctrl.state.lines[0]!.text).toBe(`${BULLET}hello`)

    const snapshot = ctrl.undo()
    expect(snapshot).not.toBeNull()
    expect(ctrl.state.lines[0]!.text).toBe('hello')
  })

  it('redo after undo', () => {
    const ctrl = controllerWithText('hello')
    ctrl.toggleBullet()
    ctrl.undo()
    const snapshot = ctrl.redo()
    expect(snapshot).not.toBeNull()
    expect(ctrl.state.lines[0]!.text).toBe(`${BULLET}hello`)
  })
})

describe('EditorController selection operations', () => {
  it('hasSelection returns correct state', () => {
    const ctrl = controllerWithText('hello')
    expect(ctrl.hasSelection()).toBe(false)
    ctrl.state.setSelection(0, 3)
    expect(ctrl.hasSelection()).toBe(true)
  })

  it('setSelection with same start and end sets cursor', () => {
    const ctrl = controllerWithText('hello')
    ctrl.setSelection(3, 3)
    expect(ctrl.state.hasSelection).toBe(false)
    expect(ctrl.state.lines[0]!.cursorPosition).toBe(3)
  })

  it('setSelection with different start and end creates selection', () => {
    const ctrl = controllerWithText('hello')
    ctrl.setSelection(1, 4)
    expect(ctrl.state.hasSelection).toBe(true)
  })

  it('clearSelection removes selection and sets cursor', () => {
    const ctrl = controllerWithText('hello world')
    ctrl.state.setSelection(3, 8)
    ctrl.clearSelection()
    expect(ctrl.state.hasSelection).toBe(false)
  })
})

describe('EditorController handleSpaceWithSelection', () => {
  it('returns false when no selection', () => {
    const ctrl = controllerWithText('hello')
    expect(ctrl.handleSpaceWithSelection()).toBe(false)
  })

  it('indents selected lines', () => {
    const ctrl = controllerWithText('abc\ndef')
    ctrl.state.setSelection(0, 7)
    const result = ctrl.handleSpaceWithSelection()
    expect(result).toBe(true)
    expect(ctrl.state.lines[0]!.text).toBe('\tabc')
    expect(ctrl.state.lines[1]!.text).toBe('\tdef')
  })
})

describe('EditorController move operations', () => {
  it('moveUp moves line up', () => {
    const ctrl = controllerWithLines('a', 'b', 'c')
    ctrl.state.focusedLineIndex = 1
    const result = ctrl.moveUp()
    expect(result).toBe(true)
    expect(ctrl.state.lines.map((l) => l.text)).toEqual(['b', 'a', 'c'])
  })

  it('moveDown moves line down', () => {
    const ctrl = controllerWithLines('a', 'b', 'c')
    ctrl.state.focusedLineIndex = 0
    const result = ctrl.moveDown()
    expect(result).toBe(true)
    expect(ctrl.state.lines.map((l) => l.text)).toEqual(['b', 'a', 'c'])
  })

  it('moveUp at top returns false', () => {
    const ctrl = controllerWithLines('a', 'b')
    ctrl.state.focusedLineIndex = 0
    expect(ctrl.moveUp()).toBe(false)
  })

  it('moveDown at bottom returns false', () => {
    const ctrl = controllerWithLines('a', 'b')
    ctrl.state.focusedLineIndex = 1
    expect(ctrl.moveDown()).toBe(false)
  })
})

describe('EditorController getMoveState', () => {
  it('getMoveUpState disabled at first line', () => {
    const ctrl = controllerWithLines('a', 'b')
    ctrl.state.focusedLineIndex = 0
    expect(ctrl.getMoveUpState().isEnabled).toBe(false)
  })

  it('getMoveDownState disabled at last line', () => {
    const ctrl = controllerWithLines('a', 'b')
    ctrl.state.focusedLineIndex = 1
    expect(ctrl.getMoveDownState().isEnabled).toBe(false)
  })

  it('getMoveUpState enabled for middle line', () => {
    const ctrl = controllerWithLines('a', 'b', 'c')
    ctrl.state.focusedLineIndex = 1
    expect(ctrl.getMoveUpState().isEnabled).toBe(true)
  })

  it('getMoveDownState enabled for middle line', () => {
    const ctrl = controllerWithLines('a', 'b', 'c')
    ctrl.state.focusedLineIndex = 1
    expect(ctrl.getMoveDownState().isEnabled).toBe(true)
  })
})

describe('EditorController deleteBackward/deleteForward with hidden lines', () => {
  it('deleteBackward skips hidden lines when merging', () => {
    const ctrl = controllerWithLines('a', 'hidden1', 'hidden2', 'b')
    ctrl.hiddenIndices = new Set([1, 2])
    ctrl.state.focusedLineIndex = 3
    ctrl.state.lines[3]!.updateFull('b', 0)
    ctrl.deleteBackward(3)
    expect(ctrl.state.lines[0]!.text).toBe('ab')
  })

  it('deleteForward skips hidden lines when merging', () => {
    const ctrl = controllerWithLines('a', 'hidden1', 'hidden2', 'b')
    ctrl.hiddenIndices = new Set([1, 2])
    ctrl.state.focusedLineIndex = 0
    ctrl.state.lines[0]!.updateFull('a', 1)
    ctrl.deleteForward(0)
    expect(ctrl.state.lines[0]!.text).toBe('ab')
  })
})

describe('EditorController toggleCheckbox tracks recentlyCheckedIndices for all selected lines', () => {
  it('single line: tracks the toggled line', () => {
    const ctrl = controllerWithLines(`${CHECKBOX_UNCHECKED}a`, `${CHECKBOX_UNCHECKED}b`)
    ctrl.state.focusedLineIndex = 0
    ctrl.toggleCheckbox()
    expect(ctrl.recentlyCheckedIndices.has(0)).toBe(true)
    expect(ctrl.recentlyCheckedIndices.has(1)).toBe(false)
  })

  it('multi-line selection: tracks all toggled lines', () => {
    const ctrl = controllerWithLines(`${CHECKBOX_UNCHECKED}a`, `${CHECKBOX_UNCHECKED}b`, `${CHECKBOX_UNCHECKED}c`)
    ctrl.state.setSelection(0, ctrl.state.text.length)
    ctrl.toggleCheckbox()
    expect(ctrl.recentlyCheckedIndices.has(0)).toBe(true)
    expect(ctrl.recentlyCheckedIndices.has(1)).toBe(true)
    expect(ctrl.recentlyCheckedIndices.has(2)).toBe(true)
  })

  it('does not track lines that were not unchecked', () => {
    const ctrl = controllerWithLines(`${CHECKBOX_UNCHECKED}a`, 'plain', `${CHECKBOX_UNCHECKED}c`)
    ctrl.state.setSelection(0, ctrl.state.text.length)
    ctrl.toggleCheckbox()
    // Lines 0 and 2 were unchecked → now checked → tracked
    expect(ctrl.recentlyCheckedIndices.has(0)).toBe(true)
    expect(ctrl.recentlyCheckedIndices.has(2)).toBe(true)
    // Line 1 was plain → now has checkbox unchecked → not tracked as "recently checked"
    expect(ctrl.recentlyCheckedIndices.has(1)).toBe(false)
  })
})

describe('EditorController paste undo boundary', () => {
  it('typing after paste creates separate undo entry', () => {
    const ctrl = controllerWithLines('hello')
    ctrl.state.focusedLineIndex = 0
    ctrl.state.lines[0]!.updateFull('hello', 5)

    // Paste ' world'
    ctrl.paste(' world')
    expect(ctrl.state.lines[0]!.text).toBe('hello world')

    // Simulate typing: focus a different line then come back to trigger beginEditingLine,
    // then update content which calls markContentChanged
    ctrl.focusLine(0)
    // After paste, editingLineIndex is null (committed). Manually begin editing to
    // simulate what happens when the input element receives focus after paste.
    ctrl.undoManager.beginEditingLine(
      ctrl.state.lines, ctrl.state.focusedLineIndex, ctrl.state.focusedLineIndex,
    )
    ctrl.updateLineContent(0, 'hello world!', 12)
    expect(ctrl.state.lines[0]!.text).toBe('hello world!')

    // First undo should undo the typing
    ctrl.undo()
    expect(ctrl.state.lines[0]!.text).toBe('hello world')

    // Second undo should undo the paste
    ctrl.undo()
    expect(ctrl.state.lines[0]!.text).toBe('hello')
  })
})
