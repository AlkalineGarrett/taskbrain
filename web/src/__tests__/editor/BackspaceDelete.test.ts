import { describe, it, expect, vi } from 'vitest'
import { EditorState } from '../../editor/EditorState'
import { EditorController } from '../../editor/EditorController'
import { LineState } from '../../editor/LineState'
import { BULLET, CHECKBOX_UNCHECKED, CHECKBOX_CHECKED } from '../../editor/LinePrefixes'

// Mock navigator.clipboard
Object.defineProperty(navigator, 'clipboard', {
  value: { writeText: vi.fn().mockResolvedValue(undefined) },
  writable: true,
  configurable: true,
})

function controllerWithLines(...texts: string[]): { controller: EditorController; state: EditorState } {
  const state = new EditorState()
  state.lines = texts.map((t) => new LineState(t))
  state.focusedLineIndex = 0
  return { controller: new EditorController(state), state }
}

describe('deleteBackward at beginning of line', () => {
  it('merges into previous line with content (strips current prefix)', () => {
    const { controller, state } = controllerWithLines('First', `\t${BULLET}Second`)
    state.focusedLineIndex = 1
    state.lines[1]!.updateFull(`\t${BULLET}Second`, 0) // cursor at 0 (before prefix)

    controller.deleteBackward(1)

    expect(state.lines.length).toBe(1)
    expect(state.lines[0]!.text).toBe('FirstSecond')
    expect(state.focusedLineIndex).toBe(0)
  })

  it('places cursor at join point when merging into previous', () => {
    const { controller, state } = controllerWithLines('Hello', 'World')
    state.focusedLineIndex = 1
    state.lines[1]!.updateFull('World', 0)

    controller.deleteBackward(1)

    expect(state.lines[0]!.text).toBe('HelloWorld')
    expect(state.lines[0]!.cursorPosition).toBe(5) // after "Hello"
  })

  it('deletes empty previous line when current has content', () => {
    const { controller, state } = controllerWithLines('', `\t${BULLET}Content`)
    state.focusedLineIndex = 1
    state.lines[1]!.updateFull(`\t${BULLET}Content`, 0)

    controller.deleteBackward(1)

    expect(state.lines.length).toBe(1)
    expect(state.lines[0]!.text).toBe(`\t${BULLET}Content`) // keeps current with prefix
    expect(state.focusedLineIndex).toBe(0)
  })

  it('deletes empty previous line with prefix when current has content', () => {
    const { controller, state } = controllerWithLines(`\t${BULLET}`, `\t${BULLET}Content`)
    state.focusedLineIndex = 1
    state.lines[1]!.updateFull(`\t${BULLET}Content`, 0)

    controller.deleteBackward(1)

    expect(state.lines.length).toBe(1)
    expect(state.lines[0]!.text).toBe(`\t${BULLET}Content`)
    expect(state.focusedLineIndex).toBe(0)
  })

  it('deletes current line when neither has content', () => {
    const { controller, state } = controllerWithLines(`\t${BULLET}`, `\t${CHECKBOX_UNCHECKED}`)
    state.focusedLineIndex = 1
    state.lines[1]!.updateFull(`\t${CHECKBOX_UNCHECKED}`, 0)

    controller.deleteBackward(1)

    expect(state.lines.length).toBe(1)
    expect(state.lines[0]!.text).toBe(`\t${BULLET}`) // keeps previous's prefix
    expect(state.focusedLineIndex).toBe(0)
  })

  it('deletes current empty line when previous is also empty (no prefix)', () => {
    const { controller, state } = controllerWithLines('', '')
    state.focusedLineIndex = 1
    state.lines[1]!.updateFull('', 0)

    controller.deleteBackward(1)

    expect(state.lines.length).toBe(1)
    expect(state.lines[0]!.text).toBe('')
    expect(state.focusedLineIndex).toBe(0)
  })

  it('does nothing on first line', () => {
    const { controller, state } = controllerWithLines(`\t${BULLET}Hello`)
    state.focusedLineIndex = 0
    state.lines[0]!.updateFull(`\t${BULLET}Hello`, 0)

    controller.deleteBackward(0)

    expect(state.lines.length).toBe(1)
    expect(state.lines[0]!.text).toBe(`\t${BULLET}Hello`)
  })

  it('merges noteIds when deleting empty previous line', () => {
    const { controller, state } = controllerWithLines('', 'Content')
    state.lines[0]!.noteIds = ['noteA']
    state.lines[1]!.noteIds = ['noteB']
    state.focusedLineIndex = 1
    state.lines[1]!.updateFull('Content', 0)

    controller.deleteBackward(1)

    // Current line (with content) is kept; it should have merged noteIds
    expect(state.lines[0]!.noteIds).toEqual(['noteB', 'noteA'])
  })

  it('merges noteIds when deleting current empty line', () => {
    const { controller, state } = controllerWithLines(`\t${BULLET}`, '')
    state.lines[0]!.noteIds = ['noteA']
    state.lines[1]!.noteIds = ['noteB']
    state.focusedLineIndex = 1
    state.lines[1]!.updateFull('', 0)

    controller.deleteBackward(1)

    // Previous line is kept
    expect(state.lines[0]!.noteIds).toEqual(['noteA', 'noteB'])
  })

  it('skips hidden lines to find merge target', () => {
    const { controller, state } = controllerWithLines('First', 'Hidden', 'Third')
    controller.hiddenIndices = new Set([1])
    state.focusedLineIndex = 2
    state.lines[2]!.updateFull('Third', 0)

    controller.deleteBackward(2)

    expect(state.lines.length).toBe(2)
    expect(state.lines[0]!.text).toBe('FirstThird')
    expect(state.lines[0]!.cursorPosition).toBe(5)
    expect(state.lines[1]!.text).toBe('Hidden') // hidden line untouched
  })

  it('does nothing when all previous lines are hidden', () => {
    const { controller, state } = controllerWithLines('Hidden', 'Current')
    controller.hiddenIndices = new Set([0])
    state.focusedLineIndex = 1
    state.lines[1]!.updateFull('Current', 0)

    controller.deleteBackward(1)

    expect(state.lines.length).toBe(2) // nothing changed
  })

  it('merges preserving previous prefix and stripping current prefix', () => {
    const { controller, state } = controllerWithLines(`\t${BULLET}First`, `\t${CHECKBOX_UNCHECKED}Second`)
    state.focusedLineIndex = 1
    state.lines[1]!.updateFull(`\t${CHECKBOX_UNCHECKED}Second`, 0)

    controller.deleteBackward(1)

    expect(state.lines.length).toBe(1)
    expect(state.lines[0]!.text).toBe(`\t${BULLET}FirstSecond`)
  })

  it('merges noteIds when both have content (mergeToPreviousLine path)', () => {
    const { controller, state } = controllerWithLines('Previous', 'Current')
    state.lines[0]!.noteIds = ['noteA']
    state.lines[1]!.noteIds = ['noteB']
    state.focusedLineIndex = 1
    state.lines[1]!.updateFull('Current', 0)

    controller.deleteBackward(1)

    // mergeToPreviousLine: mergeNoteIds(previous, current)
    // previous has more content -> its noteIds first
    expect(state.lines[0]!.noteIds).toEqual(['noteA', 'noteB'])
  })

  it('preserves remaining lines after merge', () => {
    const { controller, state } = controllerWithLines('First', 'Second', 'Third')
    state.focusedLineIndex = 1
    state.lines[1]!.updateFull('Second', 0)

    controller.deleteBackward(1)

    expect(state.lines.length).toBe(2)
    expect(state.lines[0]!.text).toBe('FirstSecond')
    expect(state.lines[1]!.text).toBe('Third')
  })

  it('preserves cursor position of surviving line when deleting empty previous', () => {
    const { controller, state } = controllerWithLines('', `\t${BULLET}Content`)
    state.focusedLineIndex = 1
    // cursor at beginning of prefix
    state.lines[1]!.updateFull(`\t${BULLET}Content`, 0)

    controller.deleteBackward(1)

    // The surviving line keeps its cursor position and prefix
    expect(state.lines[0]!.text).toBe(`\t${BULLET}Content`)
    expect(state.lines[0]!.cursorPosition).toBe(0)
  })
})

describe('deleteBackward mid-line', () => {
  it('deletes character before cursor normally', () => {
    const { controller, state } = controllerWithLines('Hello')
    state.focusedLineIndex = 0
    state.lines[0]!.updateFull('Hello', 3) // cursor after "Hel"

    controller.deleteBackward(0)

    expect(state.lines[0]!.text).toBe('Helo')
    expect(state.lines[0]!.cursorPosition).toBe(2)
  })
})

describe('deleteForward (mergeNextLine)', () => {
  it('strips prefix of next line when merging', () => {
    const { controller, state } = controllerWithLines('First', `\t${BULLET}Second`)
    state.focusedLineIndex = 0
    state.lines[0]!.updateFull('First', 5) // cursor at end

    controller.deleteForward(0)

    expect(state.lines.length).toBe(1)
    expect(state.lines[0]!.text).toBe('FirstSecond')
  })

  it('deletes empty next line', () => {
    const { controller, state } = controllerWithLines('First', '')
    state.focusedLineIndex = 0
    state.lines[0]!.updateFull('First', 5)

    controller.deleteForward(0)

    expect(state.lines.length).toBe(1)
    expect(state.lines[0]!.text).toBe('First')
  })

  it('deletes next line that has only a prefix', () => {
    const { controller, state } = controllerWithLines('First', `\t${BULLET}`)
    state.focusedLineIndex = 0
    state.lines[0]!.updateFull('First', 5)

    controller.deleteForward(0)

    expect(state.lines.length).toBe(1)
    expect(state.lines[0]!.text).toBe('First') // no prefix appended
  })

  it('places cursor at original position', () => {
    const { controller, state } = controllerWithLines('Hello', 'World')
    state.focusedLineIndex = 0
    state.lines[0]!.updateFull('Hello', 5)

    controller.deleteForward(0)

    expect(state.lines[0]!.cursorPosition).toBe(5)
  })

  it('merges noteIds on delete forward', () => {
    const { controller, state } = controllerWithLines('LongerFirst', 'Second')
    state.lines[0]!.noteIds = ['noteA']
    state.lines[1]!.noteIds = ['noteB']
    state.focusedLineIndex = 0
    state.lines[0]!.updateFull('LongerFirst', 'LongerFirst'.length)

    controller.deleteForward(0)

    // currentLine has more content, so its noteIds come first
    expect(state.lines[0]!.noteIds).toEqual(['noteA', 'noteB'])
  })

  it('strips prefix when both lines have prefixes', () => {
    const { controller, state } = controllerWithLines(`\t${BULLET}First`, `\t${CHECKBOX_UNCHECKED}Second`)
    state.focusedLineIndex = 0
    state.lines[0]!.updateFull(`\t${BULLET}First`, `\t${BULLET}First`.length)

    controller.deleteForward(0)

    expect(state.lines.length).toBe(1)
    expect(state.lines[0]!.text).toBe(`\t${BULLET}FirstSecond`)
  })

  it('skips hidden lines to find merge target', () => {
    const { controller, state } = controllerWithLines('First', 'Hidden', 'Third')
    controller.hiddenIndices = new Set([1])
    state.focusedLineIndex = 0
    state.lines[0]!.updateFull('First', 5)

    controller.deleteForward(0)

    expect(state.lines.length).toBe(2)
    expect(state.lines[0]!.text).toBe('FirstThird')
    expect(state.lines[1]!.text).toBe('Hidden')
  })

  it('preserves remaining lines', () => {
    const { controller, state } = controllerWithLines('First', 'Second', 'Third')
    state.focusedLineIndex = 0
    state.lines[0]!.updateFull('First', 5)

    controller.deleteForward(0)

    expect(state.lines.length).toBe(2)
    expect(state.lines[0]!.text).toBe('FirstSecond')
    expect(state.lines[1]!.text).toBe('Third')
  })

  it('adopts next line when current is empty (preserves next prefix)', () => {
    // Empty unchecked checkbox + checked checkbox with content
    const { controller, state } = controllerWithLines(`${CHECKBOX_UNCHECKED}`, `${CHECKBOX_CHECKED}done`)
    state.focusedLineIndex = 0
    state.lines[0]!.updateFull(`${CHECKBOX_UNCHECKED}`, `${CHECKBOX_UNCHECKED}`.length)

    controller.deleteForward(0)

    expect(state.lines.length).toBe(1)
    expect(state.lines[0]!.text).toBe(`${CHECKBOX_CHECKED}done`)
  })

  it('adopts next line when current is empty bullet (preserves next prefix)', () => {
    const { controller, state } = controllerWithLines(`${BULLET}`, `\t${BULLET}content`)
    state.focusedLineIndex = 0
    state.lines[0]!.updateFull(`${BULLET}`, `${BULLET}`.length)

    controller.deleteForward(0)

    expect(state.lines.length).toBe(1)
    expect(state.lines[0]!.text).toBe(`\t${BULLET}content`)
  })

  it('keeps current when both lines are empty', () => {
    const { controller, state } = controllerWithLines(`${CHECKBOX_UNCHECKED}`, `${CHECKBOX_CHECKED}`)
    state.focusedLineIndex = 0
    state.lines[0]!.updateFull(`${CHECKBOX_UNCHECKED}`, `${CHECKBOX_UNCHECKED}`.length)

    controller.deleteForward(0)

    expect(state.lines.length).toBe(1)
    expect(state.lines[0]!.text).toBe(`${CHECKBOX_UNCHECKED}`)
  })

  it('merges next content into current when current has content', () => {
    const { controller, state } = controllerWithLines(`${CHECKBOX_UNCHECKED}task`, `${CHECKBOX_CHECKED}done`)
    state.focusedLineIndex = 0
    state.lines[0]!.updateFull(`${CHECKBOX_UNCHECKED}task`, `${CHECKBOX_UNCHECKED}task`.length)

    controller.deleteForward(0)

    expect(state.lines.length).toBe(1)
    expect(state.lines[0]!.text).toBe(`${CHECKBOX_UNCHECKED}taskdone`)
  })
})

describe('splitLine (Enter) on checked checkbox', () => {
  it('preserves checked state when splitting mid-line', () => {
    const { controller, state } = controllerWithLines(`${CHECKBOX_CHECKED}hello world`)
    state.focusedLineIndex = 0
    state.lines[0]!.updateFull(`${CHECKBOX_CHECKED}hello world`, `${CHECKBOX_CHECKED}hello`.length)

    controller.splitLine(0)

    expect(state.lines.length).toBe(2)
    expect(state.lines[0]!.text).toBe(`${CHECKBOX_CHECKED}hello`)
    expect(state.lines[1]!.text).toBe(`${CHECKBOX_CHECKED} world`)
  })

  it('unchecks when splitting at end of line', () => {
    const { controller, state } = controllerWithLines(`${CHECKBOX_CHECKED}done`)
    state.focusedLineIndex = 0
    state.lines[0]!.updateFull(`${CHECKBOX_CHECKED}done`, `${CHECKBOX_CHECKED}done`.length)

    controller.splitLine(0)

    expect(state.lines.length).toBe(2)
    expect(state.lines[0]!.text).toBe(`${CHECKBOX_CHECKED}done`)
    expect(state.lines[1]!.text).toBe(`${CHECKBOX_UNCHECKED}`)
  })

  it('unchecks on empty checked line (cursor at prefix end)', () => {
    const { controller, state } = controllerWithLines(`${CHECKBOX_CHECKED}`)
    state.focusedLineIndex = 0
    state.lines[0]!.updateFull(`${CHECKBOX_CHECKED}`, `${CHECKBOX_CHECKED}`.length)

    controller.splitLine(0)

    expect(state.lines.length).toBe(2)
    expect(state.lines[1]!.text).toBe(`${CHECKBOX_UNCHECKED}`)
  })

  it('unchecks current line when all content moves to new line (cursor at start of content)', () => {
    const { controller, state } = controllerWithLines(`${CHECKBOX_CHECKED}all content here`)
    state.focusedLineIndex = 0
    // Cursor right after prefix — all content goes to new line
    state.lines[0]!.updateFull(`${CHECKBOX_CHECKED}all content here`, `${CHECKBOX_CHECKED}`.length)

    controller.splitLine(0)

    expect(state.lines.length).toBe(2)
    // Current line is now empty → unchecked
    expect(state.lines[0]!.text).toBe(`${CHECKBOX_UNCHECKED}`)
    // New line has all the content → checked
    expect(state.lines[1]!.text).toBe(`${CHECKBOX_CHECKED}all content here`)
  })

  it('unchecks current line with indent when all content moves to new line', () => {
    const { controller, state } = controllerWithLines(`\t${CHECKBOX_CHECKED}indented task`)
    state.focusedLineIndex = 0
    state.lines[0]!.updateFull(`\t${CHECKBOX_CHECKED}indented task`, `\t${CHECKBOX_CHECKED}`.length)

    controller.splitLine(0)

    expect(state.lines.length).toBe(2)
    expect(state.lines[0]!.text).toBe(`\t${CHECKBOX_UNCHECKED}`)
    expect(state.lines[1]!.text).toBe(`\t${CHECKBOX_CHECKED}indented task`)
  })

  it('unchecked checkbox stays unchecked regardless of cursor position', () => {
    const { controller, state } = controllerWithLines(`${CHECKBOX_UNCHECKED}hello world`)
    state.focusedLineIndex = 0
    state.lines[0]!.updateFull(`${CHECKBOX_UNCHECKED}hello world`, `${CHECKBOX_UNCHECKED}hello`.length)

    controller.splitLine(0)

    expect(state.lines.length).toBe(2)
    expect(state.lines[1]!.text).toBe(`${CHECKBOX_UNCHECKED} world`)
  })

  it('preserves checked with indent when splitting mid-line', () => {
    const { controller, state } = controllerWithLines(`\t${CHECKBOX_CHECKED}task item`)
    state.focusedLineIndex = 0
    state.lines[0]!.updateFull(`\t${CHECKBOX_CHECKED}task item`, `\t${CHECKBOX_CHECKED}task`.length)

    controller.splitLine(0)

    expect(state.lines[0]!.text).toBe(`\t${CHECKBOX_CHECKED}task`)
    expect(state.lines[1]!.text).toBe(`\t${CHECKBOX_CHECKED} item`)
  })
})
