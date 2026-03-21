import { describe, it, expect } from 'vitest'
import { EditorState } from '../../editor/EditorState'
import { EditorController } from '../../editor/EditorController'
import { LineState } from '../../editor/LineState'
import { UndoManager } from '../../editor/UndoManager'
import { executePaste } from '../../editor/PasteHandler'
import { parseInternalLines } from '../../editor/ClipboardParser'
import { SELECTION_NONE } from '../../editor/EditorSelection'
import { matchLinesToIds } from '../../data/NoteRepository'
import type { NoteLine } from '../../data/Note'

// --- Helpers ---

function stateWithNoteLines(
  ...entries: Array<{ text: string; noteIds: string[] }>
): EditorState {
  const state = new EditorState()
  state.initFromNoteLines(entries)
  return state
}

function controllerWithNoteLines(
  ...entries: Array<{ text: string; noteIds: string[] }>
): EditorController {
  const state = stateWithNoteLines(...entries)
  return new EditorController(state)
}

// ==================== LineState noteIds ====================

describe('LineState noteIds', () => {
  it('preserves noteIds from constructor', () => {
    const line = new LineState('Hello', undefined, ['note1'])
    expect(line.noteIds).toEqual(['note1'])
  })

  it('defaults to empty noteIds', () => {
    const line = new LineState('Hello')
    expect(line.noteIds).toEqual([])
  })

  it('preserves multiple noteIds', () => {
    const line = new LineState('Hello', undefined, ['note1', 'note2'])
    expect(line.noteIds).toEqual(['note1', 'note2'])
  })
})

// ==================== EditorState.initFromNoteLines ====================

describe('EditorState.initFromNoteLines', () => {
  it('sets noteIds on each line', () => {
    const state = stateWithNoteLines(
      { text: 'First line', noteIds: ['note1'] },
      { text: 'Second line', noteIds: ['note2'] },
      { text: '', noteIds: [] },
    )

    expect(state.lines.length).toBe(3)
    expect(state.lines[0]!.noteIds).toEqual(['note1'])
    expect(state.lines[1]!.noteIds).toEqual(['note2'])
    expect(state.lines[2]!.noteIds).toEqual([])
  })

  it('resets focus and selection', () => {
    const state = new EditorState()
    state.focusedLineIndex = 5
    state.initFromNoteLines([
      { text: 'Line A', noteIds: ['noteA'] },
      { text: 'Line B', noteIds: ['noteB'] },
    ])

    expect(state.focusedLineIndex).toBe(0)
    expect(state.hasSelection).toBe(false)
  })
})

// ==================== updateFromText preserves noteIds ====================

describe('updateFromText preserves noteIds', () => {
  it('preserves noteIds via exact content match', () => {
    const state = stateWithNoteLines(
      { text: 'Line A', noteIds: ['noteA'] },
      { text: 'Line B', noteIds: ['noteB'] },
      { text: 'Line C', noteIds: ['noteC'] },
    )

    // Reorder: B, A, C
    state.updateFromText('Line B\nLine A\nLine C')

    expect(state.lines[0]!.noteIds).toEqual(['noteB'])
    expect(state.lines[1]!.noteIds).toEqual(['noteA'])
    expect(state.lines[2]!.noteIds).toEqual(['noteC'])
  })

  it('uses positional fallback for modified lines', () => {
    const state = stateWithNoteLines(
      { text: 'Original', noteIds: ['note1'] },
      { text: 'Line B', noteIds: ['note2'] },
    )

    // Modify first line, keep second
    state.updateFromText('Modified\nLine B')

    expect(state.lines[0]!.noteIds).toEqual(['note1']) // positional fallback
    expect(state.lines[1]!.noteIds).toEqual(['note2']) // exact match
  })

  it('gives empty noteIds to new lines', () => {
    const state = stateWithNoteLines(
      { text: 'Line A', noteIds: ['noteA'] },
    )

    state.updateFromText('Line A\nNew line\nAnother new')

    expect(state.lines[0]!.noteIds).toEqual(['noteA'])
    expect(state.lines[1]!.noteIds).toEqual([])
    expect(state.lines[2]!.noteIds).toEqual([])
  })

  it('handles duplicate content lines correctly', () => {
    const state = stateWithNoteLines(
      { text: 'Same', noteIds: ['note1'] },
      { text: 'Same', noteIds: ['note2'] },
      { text: 'Different', noteIds: ['note3'] },
    )

    state.updateFromText('Same\nSame\nDifferent')

    // First "Same" matches first old "Same" (note1), second matches second (note2)
    expect(state.lines[0]!.noteIds).toEqual(['note1'])
    expect(state.lines[1]!.noteIds).toEqual(['note2'])
    expect(state.lines[2]!.noteIds).toEqual(['note3'])
  })
})

// ==================== Split line propagation ====================

describe('splitLine preserves noteIds', () => {
  it('gives both fragments the same noteIds', () => {
    const controller = controllerWithNoteLines(
      { text: 'Hello World', noteIds: ['note1'] },
      { text: '', noteIds: [] },
    )
    controller.state.focusedLineIndex = 0
    controller.state.lines[0]!.updateFull('Hello World', 5) // cursor after "Hello"

    controller.splitLine(0)

    expect(controller.state.lines[0]!.text).toBe('Hello')
    expect(controller.state.lines[1]!.text).toBe(' World')
    expect(controller.state.lines[0]!.noteIds).toEqual(['note1'])
    expect(controller.state.lines[1]!.noteIds).toEqual(['note1'])
  })

  it('preserves noteIds when splitting at prefix boundary', () => {
    const controller = controllerWithNoteLines(
      { text: '• Content here', noteIds: ['note1'] },
      { text: '', noteIds: [] },
    )
    controller.state.focusedLineIndex = 0
    controller.state.lines[0]!.updateFull('• Content here', 2) // cursor after prefix

    controller.splitLine(0)

    expect(controller.state.lines[1]!.noteIds).toEqual(['note1'])
  })
})

// ==================== Merge line propagation ====================

describe('mergeToPreviousLine combines noteIds', () => {
  it('longer content noteIds come first', () => {
    const controller = controllerWithNoteLines(
      { text: 'Short', noteIds: ['noteA'] },
      { text: 'Much longer content', noteIds: ['noteB'] },
      { text: '', noteIds: [] },
    )
    controller.state.focusedLineIndex = 1
    controller.state.lines[1]!.updateFull('Much longer content', 0) // cursor at start

    controller.mergeToPreviousLine(1)

    expect(controller.state.lines[0]!.text).toBe('ShortMuch longer content')
    // noteB had longer content, so it should be first (primary)
    expect(controller.state.lines[0]!.noteIds).toEqual(['noteB', 'noteA'])
  })
})

describe('mergeNextLine combines noteIds', () => {
  it('longer content noteIds come first', () => {
    const controller = controllerWithNoteLines(
      { text: 'Long first line here', noteIds: ['noteA'] },
      { text: 'Short', noteIds: ['noteB'] },
      { text: '', noteIds: [] },
    )
    controller.state.focusedLineIndex = 0
    controller.state.lines[0]!.updateFull('Long first line here', 20) // cursor at end

    controller.mergeNextLine(0)

    expect(controller.state.lines[0]!.text).toBe('Long first line hereShort')
    // noteA had longer content, so it should be first (primary)
    expect(controller.state.lines[0]!.noteIds).toEqual(['noteA', 'noteB'])
  })

  it('deduplicates shared noteIds', () => {
    const controller = controllerWithNoteLines(
      { text: 'Line A', noteIds: ['shared', 'noteA'] },
      { text: 'Line B longer', noteIds: ['shared', 'noteB'] },
      { text: '', noteIds: [] },
    )
    controller.state.focusedLineIndex = 0

    controller.mergeNextLine(0)

    // noteB's line was longer, its noteIds come first; 'shared' appears once
    expect(controller.state.lines[0]!.noteIds).toEqual(['shared', 'noteB', 'noteA'])
  })
})

// ==================== Move lines propagation ====================

describe('moveLinesInternal preserves noteIds', () => {
  it('preserves noteIds when moving down', () => {
    const state = stateWithNoteLines(
      { text: 'Line A', noteIds: ['noteA'] },
      { text: 'Line B', noteIds: ['noteB'] },
      { text: 'Line C', noteIds: ['noteC'] },
    )

    // Move line 0 to after line 1 (targetIndex=2)
    state.moveLinesInternal(0, 0, 2)

    expect(state.lines[0]!.text).toBe('Line B')
    expect(state.lines[1]!.text).toBe('Line A')
    expect(state.lines[2]!.text).toBe('Line C')
    expect(state.lines[0]!.noteIds).toEqual(['noteB'])
    expect(state.lines[1]!.noteIds).toEqual(['noteA'])
    expect(state.lines[2]!.noteIds).toEqual(['noteC'])
  })

  it('preserves noteIds when moving up', () => {
    const state = stateWithNoteLines(
      { text: 'Line A', noteIds: ['noteA'] },
      { text: 'Line B', noteIds: ['noteB'] },
      { text: 'Line C', noteIds: ['noteC'] },
    )

    // Move line 2 to position 0
    state.moveLinesInternal(2, 2, 0)

    expect(state.lines[0]!.text).toBe('Line C')
    expect(state.lines[1]!.text).toBe('Line A')
    expect(state.lines[2]!.text).toBe('Line B')
    expect(state.lines[0]!.noteIds).toEqual(['noteC'])
    expect(state.lines[1]!.noteIds).toEqual(['noteA'])
    expect(state.lines[2]!.noteIds).toEqual(['noteB'])
  })

  it('preserves noteIds when moving a range', () => {
    const state = stateWithNoteLines(
      { text: 'Line A', noteIds: ['noteA'] },
      { text: 'Line B', noteIds: ['noteB'] },
      { text: 'Line C', noteIds: ['noteC'] },
      { text: 'Line D', noteIds: ['noteD'] },
    )

    // Move lines 0-1 to after line 2 (targetIndex=3)
    state.moveLinesInternal(0, 1, 3)

    expect(state.lines[0]!.text).toBe('Line C')
    expect(state.lines[1]!.text).toBe('Line A')
    expect(state.lines[2]!.text).toBe('Line B')
    expect(state.lines[3]!.text).toBe('Line D')
    expect(state.lines[0]!.noteIds).toEqual(['noteC'])
    expect(state.lines[1]!.noteIds).toEqual(['noteA'])
    expect(state.lines[2]!.noteIds).toEqual(['noteB'])
    expect(state.lines[3]!.noteIds).toEqual(['noteD'])
  })
})

// ==================== Undo/Redo preserves noteIds ====================

describe('undo/redo preserves noteIds', () => {
  it('undo restores noteIds', () => {
    const state = new EditorState()
    const undoManager = new UndoManager()
    const controller = new EditorController(state, undoManager)

    state.initFromNoteLines([
      { text: 'Line A', noteIds: ['noteA'] },
      { text: 'Line B', noteIds: ['noteB'] },
    ])
    undoManager.setBaseline(state.lines, state.focusedLineIndex)
    undoManager.beginEditingLine(state.lines, state.focusedLineIndex, 0)

    // Make a change
    state.lines[0]!.updateFull('Modified A', 10)
    controller.commitUndoState()

    // Undo
    controller.undo()

    expect(state.lines[0]!.text).toBe('Line A')
    expect(state.lines[0]!.noteIds).toEqual(['noteA'])
    expect(state.lines[1]!.noteIds).toEqual(['noteB'])
  })

  it('redo restores noteIds', () => {
    const state = new EditorState()
    const undoManager = new UndoManager()
    const controller = new EditorController(state, undoManager)

    state.initFromNoteLines([
      { text: 'Line A', noteIds: ['noteA'] },
      { text: 'Line B', noteIds: ['noteB'] },
    ])
    undoManager.setBaseline(state.lines, state.focusedLineIndex)
    undoManager.beginEditingLine(state.lines, state.focusedLineIndex, 0)

    // Make a change
    state.lines[0]!.updateFull('Modified A', 10)
    controller.commitUndoState()

    // Undo then redo
    controller.undo()
    controller.redo()

    expect(state.lines[0]!.text).toBe('Modified A')
    expect(state.lines[0]!.noteIds).toEqual(['noteA'])
  })
})

// ==================== Delete selection preserves noteIds ====================

describe('deleteSelection preserves noteIds', () => {
  it('preserves noteIds on surviving lines after deletion', () => {
    const controller = controllerWithNoteLines(
      { text: 'Line A', noteIds: ['noteA'] },
      { text: 'Line B', noteIds: ['noteB'] },
      { text: 'Line C', noteIds: ['noteC'] },
    )

    // Select "Line B\n" and delete
    const startOffset = 'Line A\n'.length
    const endOffset = 'Line A\nLine B\n'.length
    controller.state.setSelection(startOffset, endOffset)
    controller.deleteSelectionWithUndo()

    expect(controller.state.lines.length).toBe(2)
    expect(controller.state.lines[0]!.text).toBe('Line A')
    expect(controller.state.lines[1]!.text).toBe('Line C')
    expect(controller.state.lines[0]!.noteIds).toEqual(['noteA'])
    expect(controller.state.lines[1]!.noteIds).toEqual(['noteC'])
  })
})

// ==================== matchLinesToIds (NoteRepository) ====================

describe('matchLinesToIds integration', () => {
  it('matches reordered lines by content', () => {
    const existing: NoteLine[] = [
      { content: 'Parent', noteId: 'parent1' },
      { content: '\tChild A', noteId: 'childA' },
      { content: '\tChild B', noteId: 'childB' },
    ]
    const newContent = ['Parent', '\tChild B', '\tChild A']

    const result = matchLinesToIds('parent1', existing, newContent)

    expect(result[0]!.noteId).toBe('parent1')
    expect(result[1]!.noteId).toBe('childB')
    expect(result[2]!.noteId).toBe('childA')
  })

  it('assigns null to genuinely new lines', () => {
    const existing: NoteLine[] = [
      { content: 'Parent', noteId: 'parent1' },
    ]
    const newContent = ['Parent', '\tNew child']

    const result = matchLinesToIds('parent1', existing, newContent)

    expect(result[0]!.noteId).toBe('parent1')
    expect(result[1]!.noteId).toBeNull()
  })

  it('uses positional fallback for modified lines', () => {
    const existing: NoteLine[] = [
      { content: 'Parent', noteId: 'parent1' },
      { content: '\tOriginal child', noteId: 'child1' },
    ]
    const newContent = ['Parent', '\tModified child']

    const result = matchLinesToIds('parent1', existing, newContent)

    expect(result[0]!.noteId).toBe('parent1')
    expect(result[1]!.noteId).toBe('child1') // positional fallback
  })

  it('ensures first line always gets parentNoteId', () => {
    const existing: NoteLine[] = [
      { content: 'Old parent', noteId: 'parent1' },
      { content: 'New parent', noteId: 'child1' },
    ]
    // Content of line 0 changed to match what was line 1
    const newContent = ['New parent', 'Old parent']

    const result = matchLinesToIds('parent1', existing, newContent)

    // Even though "New parent" exact-matches child1, line 0 must be parent1
    expect(result[0]!.noteId).toBe('parent1')
  })
})

// ==================== Full-line paste preserves noteIds ====================

describe('full-line paste preserves noteIds', () => {
  it('inherits noteIds from deleted lines via content match', () => {
    const lines = [
      new LineState('Line A', undefined, ['noteA']),
      new LineState('Line B', undefined, ['noteB']),
      new LineState('Line C', undefined, ['noteC']),
    ]

    // Select all of lines A and B (full-line selection)
    const selection = { start: 0, end: 'Line A\nLine B\n'.length }
    // Paste back the same content (simulates cut + paste)
    const parsed = parseInternalLines('Line A\nLine B')
    const result = executePaste(lines, 0, selection, parsed)

    expect(result.lines[0]!.noteIds).toEqual(['noteA'])
    expect(result.lines[1]!.noteIds).toEqual(['noteB'])
    expect(result.lines[2]!.noteIds).toEqual(['noteC'])
  })

  it('inherits noteIds from deleted lines via positional fallback', () => {
    const lines = [
      new LineState('Original A', undefined, ['noteA']),
      new LineState('Original B', undefined, ['noteB']),
      new LineState('Line C', undefined, ['noteC']),
    ]

    // Select all of first two lines
    const selection = { start: 0, end: 'Original A\nOriginal B\n'.length }
    // Paste different content (simulates paste of new text over selection)
    const parsed = parseInternalLines('Modified A\nModified B')
    const result = executePaste(lines, 0, selection, parsed)

    // Positional fallback: modified lines inherit from same positions
    expect(result.lines[0]!.noteIds).toEqual(['noteA'])
    expect(result.lines[1]!.noteIds).toEqual(['noteB'])
    expect(result.lines[2]!.noteIds).toEqual(['noteC'])
  })

  it('gives empty noteIds to extra pasted lines beyond deleted range', () => {
    const lines = [
      new LineState('Line A', undefined, ['noteA']),
      new LineState('Line B', undefined, ['noteB']),
    ]

    // Select line A only
    const selection = { start: 0, end: 'Line A\n'.length }
    // Paste two lines where one was selected
    const parsed = parseInternalLines('New 1\nNew 2')
    const result = executePaste(lines, 0, selection, parsed)

    // First pasted line gets noteA (positional), second is new
    expect(result.lines[0]!.noteIds).toEqual(['noteA'])
    expect(result.lines[1]!.noteIds).toEqual([])
    expect(result.lines[2]!.noteIds).toEqual(['noteB'])
  })

  it('cut then full-line paste replaces with same noteIds', () => {
    // Simulates: select lines B,C → cut → select lines D → paste B,C over D
    // The pasted B,C should inherit from deleted D via content matching,
    // but since content differs, positional fallback gives D's noteId to first pasted line

    const lines = [
      new LineState('Line A', undefined, ['noteA']),
      new LineState('Line D', undefined, ['noteD']),
    ]

    // Full-line select Line D, paste Line B and Line C over it
    const selection = { start: 'Line A\n'.length, end: 'Line A\nLine D'.length }
    const parsed = parseInternalLines('Line B\nLine C')
    const result = executePaste(lines, 1, selection, parsed)

    expect(result.lines[0]!.text).toBe('Line A')
    expect(result.lines[0]!.noteIds).toEqual(['noteA'])
    // Line B gets noteD via positional fallback (it replaced Line D's position)
    expect(result.lines[1]!.text).toBe('Line B')
    expect(result.lines[1]!.noteIds).toEqual(['noteD'])
    // Line C is extra — no deleted line to match
    expect(result.lines[2]!.text).toBe('Line C')
    expect(result.lines[2]!.noteIds).toEqual([])
  })

  it('recovers noteIds from cut lines when pasting to a different location', () => {
    // Simulates: cut Line B (with noteB) → paste after Line C
    // The pasted line should recover noteB from the cutLines parameter
    const lines = [
      new LineState('Line A', undefined, ['noteA']),
      new LineState('Line C', undefined, ['noteC']),
      new LineState('', undefined, []),
    ]

    // Cursor on the empty line at end, no selection
    const parsed = parseInternalLines('Line B')
    const cutLines = [new LineState('Line B', undefined, ['noteB'])]
    const result = executePaste(lines, 2, SELECTION_NONE, parsed, cutLines)

    expect(result.lines[0]!.noteIds).toEqual(['noteA'])
    expect(result.lines[1]!.noteIds).toEqual(['noteC'])
    // The pasted Line B should recover its noteId from cutLines
    const pastedLine = result.lines.find(l => l.text.includes('Line B'))
    expect(pastedLine).toBeDefined()
    expect(pastedLine!.noteIds).toEqual(['noteB'])
  })

  it('recovers noteIds from cut lines with indent changes', () => {
    // Cut an indented line, paste at different indent → content matches after prefix strip
    const lines = [
      new LineState('Line A', undefined, ['noteA']),
      new LineState('', undefined, []),
    ]

    const parsed = parseInternalLines('\t- Item 1')
    const cutLines = [new LineState('\t\t- Item 1', undefined, ['noteItem'])]
    const result = executePaste(lines, 1, SELECTION_NONE, parsed, cutLines)

    // Content "Item 1" matches after prefix strip, so noteId is recovered
    const pastedLine = result.lines.find(l => l.text.includes('Item 1'))
    expect(pastedLine).toBeDefined()
    expect(pastedLine!.noteIds).toEqual(['noteItem'])
  })
})

// ==================== Full lifecycle: load → edit → save ====================

describe('full lifecycle: noteIds through load → edit → save', () => {
  it('preserves identity through split and merge cycle', () => {
    // Simulate load
    const state = new EditorState()
    const controller = new EditorController(state)
    state.initFromNoteLines([
      { text: 'Hello World', noteIds: ['note1'] },
      { text: 'Other line', noteIds: ['note2'] },
      { text: '', noteIds: [] },
    ])

    // Split line 0 at position 5
    state.focusedLineIndex = 0
    state.lines[0]!.updateFull('Hello World', 5)
    controller.splitLine(0)

    expect(state.lines[0]!.noteIds).toEqual(['note1'])
    expect(state.lines[1]!.noteIds).toEqual(['note1'])

    // Merge them back
    state.focusedLineIndex = 1
    state.lines[1]!.updateFull(state.lines[1]!.text, 0)
    controller.mergeToPreviousLine(1)

    // After merge, noteIds should include note1 (from both)
    expect(state.lines[0]!.text).toBe('Hello World')
    expect(state.lines[0]!.noteIds).toEqual(['note1'])
  })

  it('preserves identity through move and save', () => {
    const state = new EditorState()
    state.initFromNoteLines([
      { text: 'Task 1', noteIds: ['note1'] },
      { text: 'Task 2', noteIds: ['note2'] },
      { text: 'Task 3', noteIds: ['note3'] },
    ])

    // Move task 3 to top
    state.moveLinesInternal(2, 2, 0)

    // Simulate save: extract noteIds for matchLinesToIds
    const editorTexts = state.lines.map((l) => l.text)
    expect(editorTexts).toEqual(['Task 3', 'Task 1', 'Task 2'])

    const lineNoteIds = state.lines.map((l) => l.noteIds)
    expect(lineNoteIds).toEqual([['note3'], ['note1'], ['note2']])
  })
})
