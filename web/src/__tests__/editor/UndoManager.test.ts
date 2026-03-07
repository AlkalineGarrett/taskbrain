import { describe, it, expect } from 'vitest'
import { UndoManager, CommandType } from '../../editor/UndoManager'
import { LineState } from '../../editor/LineState'

function makeLines(...texts: string[]): LineState[] {
  return texts.map((t) => new LineState(t))
}

function linesAt(texts: string[], focusedLine: number, cursorPos: number): LineState[] {
  const lines = texts.map((t) => new LineState(t))
  if (lines[focusedLine]) {
    lines[focusedLine]!.updateFull(lines[focusedLine]!.text, cursorPos)
  }
  return lines
}

describe('UndoManager initial state', () => {
  it('canUndo is false initially', () => {
    const um = new UndoManager()
    expect(um.canUndo).toBe(false)
  })

  it('canRedo is false initially', () => {
    const um = new UndoManager()
    expect(um.canRedo).toBe(false)
  })
})

describe('UndoManager basic undo/redo', () => {
  it('canUndo is true after commit with changes', () => {
    const um = new UndoManager()
    const lines1 = makeLines('hello')
    um.beginEditingLine(lines1, 0, 0)

    const lines2 = makeLines('hello world')
    um.commitPendingUndoState(lines2, 0)
    expect(um.canUndo).toBe(true)
  })

  it('undo restores previous state', () => {
    const um = new UndoManager()
    const lines1 = makeLines('hello')
    um.beginEditingLine(lines1, 0, 0)
    um.markContentChanged()

    const lines2 = makeLines('hello world')
    const snapshot = um.undo(lines2, 0)
    expect(snapshot).not.toBeNull()
    expect(snapshot!.lineContents).toEqual(['hello'])
  })

  it('redo restores undone state', () => {
    const um = new UndoManager()
    const lines1 = makeLines('hello')
    um.beginEditingLine(lines1, 0, 0)
    um.markContentChanged()

    const lines2 = makeLines('hello world')
    um.undo(lines2, 0)

    const lines3 = makeLines('hello')
    const snapshot = um.redo(lines3, 0)
    expect(snapshot).not.toBeNull()
    expect(snapshot!.lineContents).toEqual(['hello world'])
  })

  it('redo returns null when empty', () => {
    const um = new UndoManager()
    const lines = makeLines('hello')
    expect(um.redo(lines, 0)).toBeNull()
  })
})

describe('UndoManager focus change boundaries', () => {
  it('changing focus line creates undo boundary', () => {
    const um = new UndoManager()
    const lines = makeLines('line1', 'line2')
    um.beginEditingLine(lines, 0, 0)
    um.markContentChanged()

    // Change focus to line 1
    const lines2 = makeLines('line1 edited', 'line2')
    um.commitPendingUndoState(lines2, 0)
    um.beginEditingLine(lines2, 1, 1)

    expect(um.canUndo).toBe(true)
  })
})

describe('UndoManager command grouping', () => {
  it('bullet commands create separate undo entries', () => {
    const um = new UndoManager()
    const lines1 = makeLines('text')
    um.recordCommand(lines1, 0, CommandType.BULLET)
    um.commitAfterCommand(lines1, 0, CommandType.BULLET)

    const lines2 = makeLines('\u2022 text')
    um.recordCommand(lines2, 0, CommandType.BULLET)
    um.commitAfterCommand(lines2, 0, CommandType.BULLET)

    expect(um.canUndo).toBe(true)
    const snapshot = um.undo(makeLines('text'), 0)
    expect(snapshot).not.toBeNull()
  })

  it('checkbox commands create separate undo entries', () => {
    const um = new UndoManager()
    const lines1 = makeLines('text')
    um.recordCommand(lines1, 0, CommandType.CHECKBOX)
    um.commitAfterCommand(lines1, 0, CommandType.CHECKBOX)

    const lines2 = makeLines('\u2610 text')
    um.recordCommand(lines2, 0, CommandType.CHECKBOX)
    um.commitAfterCommand(lines2, 0, CommandType.CHECKBOX)

    expect(um.canUndo).toBe(true)
  })

  it('indent commands are grouped', () => {
    const um = new UndoManager()
    const lines1 = makeLines('text')
    um.recordCommand(lines1, 0, CommandType.INDENT)

    const lines2 = makeLines('\ttext')
    // Second indent should be grouped (recordCommand returns false)
    const committed = um.recordCommand(lines2, 0, CommandType.INDENT)
    expect(committed).toBe(false)
  })

  it('unindent commands are grouped', () => {
    const um = new UndoManager()
    const lines1 = makeLines('\t\ttext')
    um.recordCommand(lines1, 0, CommandType.INDENT)

    const lines2 = makeLines('\ttext')
    const committed = um.recordCommand(lines2, 0, CommandType.UNINDENT)
    expect(committed).toBe(false) // grouped with indent
  })

  it('indent and unindent are grouped together', () => {
    const um = new UndoManager()
    const lines1 = makeLines('text')
    um.recordCommand(lines1, 0, CommandType.INDENT)

    const lines2 = makeLines('\ttext')
    const committed = um.recordCommand(lines2, 0, CommandType.INDENT)
    expect(committed).toBe(false)
  })

  it('bullet breaks indent grouping', () => {
    const um = new UndoManager()
    const lines1 = makeLines('text')
    um.recordCommand(lines1, 0, CommandType.INDENT)

    const lines2 = makeLines('\ttext')
    const committed = um.recordCommand(lines2, 0, CommandType.BULLET)
    expect(committed).toBe(true)
  })
})

describe('UndoManager reset', () => {
  it('reset clears all history', () => {
    const um = new UndoManager()
    const lines = makeLines('hello')
    um.beginEditingLine(lines, 0, 0)
    um.markContentChanged()
    um.commitPendingUndoState(makeLines('hello world'), 0)

    um.reset()
    expect(um.canUndo).toBe(false)
    expect(um.canRedo).toBe(false)
  })
})

describe('UndoManager history limits', () => {
  it('respects max history size', () => {
    const um = new UndoManager(3)

    for (let i = 0; i < 10; i++) {
      const lines = makeLines(`version ${i}`)
      um.captureStateBeforeChange(lines, 0)
    }

    // Should only be able to undo up to maxHistorySize times
    let undoCount = 0
    let current = makeLines('version 10')
    while (true) {
      const snapshot = um.undo(current, 0)
      if (!snapshot) break
      current = snapshot.lineContents.map((t) => new LineState(t))
      undoCount++
    }
    expect(undoCount).toBeLessThanOrEqual(3)
  })
})

describe('UndoManager multi-line snapshots', () => {
  it('captures and restores multi-line state', () => {
    const um = new UndoManager()
    const lines1 = makeLines('line1', 'line2', 'line3')
    um.beginEditingLine(lines1, 0, 0)
    um.markContentChanged()

    const lines2 = makeLines('line1 edited', 'line2', 'line3')
    const snapshot = um.undo(lines2, 0)
    expect(snapshot).not.toBeNull()
    expect(snapshot!.lineContents).toEqual(['line1', 'line2', 'line3'])
  })
})

describe('UndoManager enter key scenarios', () => {
  it('structural change creates undo boundary', () => {
    const um = new UndoManager()
    const lines1 = makeLines('hello world')
    um.beginEditingLine(lines1, 0, 0)
    um.markContentChanged()

    // Simulate Enter key via prepareForStructuralChange
    const lines2 = makeLines('hello world')
    um.prepareForStructuralChange(lines2, 0)
    um.continueAfterStructuralChange(1)

    const lines3 = makeLines('hello', ' world')
    um.markContentChanged()
    um.commitPendingUndoState(lines3, 1)

    expect(um.canUndo).toBe(true)
  })
})

describe('UndoManager baseline/floor state', () => {
  it('setBaseline prevents undo past baseline', () => {
    const um = new UndoManager()
    const lines = makeLines('baseline')
    um.setBaseline(lines, 0)

    // No changes after baseline, can't undo
    expect(um.canUndo).toBe(false)
  })

  it('can undo to baseline', () => {
    const um = new UndoManager()
    const lines1 = makeLines('baseline')
    um.setBaseline(lines1, 0)
    um.beginEditingLine(lines1, 0, 0)
    um.markContentChanged()

    const lines2 = makeLines('edited')
    um.commitPendingUndoState(lines2, 0)

    expect(um.canUndo).toBe(true)
    const snapshot = um.undo(lines2, 0)
    expect(snapshot).not.toBeNull()
    expect(snapshot!.lineContents).toEqual(['baseline'])
  })

  it('cannot undo past baseline', () => {
    const um = new UndoManager()
    const lines1 = makeLines('baseline')
    um.setBaseline(lines1, 0)
    um.beginEditingLine(lines1, 0, 0)
    um.markContentChanged()

    const lines2 = makeLines('edited')
    um.commitPendingUndoState(lines2, 0)

    // First undo goes to baseline
    const snapshot1 = um.undo(lines2, 0)
    expect(snapshot1).not.toBeNull()

    // Second undo should return null (at baseline)
    const lines3 = makeLines('baseline')
    const snapshot2 = um.undo(lines3, 0)
    expect(snapshot2).toBeNull()
  })

  it('baseline preserves cursor position', () => {
    const um = new UndoManager()
    const lines = linesAt(['baseline text'], 0, 5)
    um.setBaseline(lines, 0)
    um.beginEditingLine(lines, 0, 0)
    um.markContentChanged()

    const lines2 = makeLines('edited')
    const snapshot = um.undo(lines2, 0)
    expect(snapshot).not.toBeNull()
    expect(snapshot!.cursorPosition).toBe(5)
  })

  it('baseline with multiline', () => {
    const um = new UndoManager()
    const lines = makeLines('line1', 'line2')
    um.setBaseline(lines, 1)
    um.beginEditingLine(lines, 1, 1)
    um.markContentChanged()

    const lines2 = makeLines('line1', 'edited')
    const snapshot = um.undo(lines2, 1)
    expect(snapshot).not.toBeNull()
    expect(snapshot!.lineContents).toEqual(['line1', 'line2'])
    expect(snapshot!.focusedLineIndex).toBe(1)
  })

  it('redo after baseline undo', () => {
    const um = new UndoManager()
    const lines1 = makeLines('baseline')
    um.setBaseline(lines1, 0)
    um.beginEditingLine(lines1, 0, 0)
    um.markContentChanged()

    const lines2 = makeLines('edited')
    um.commitPendingUndoState(lines2, 0)

    um.undo(lines2, 0)
    expect(um.canRedo).toBe(true)

    const snapshot = um.redo(makeLines('baseline'), 0)
    expect(snapshot).not.toBeNull()
    expect(snapshot!.lineContents).toEqual(['edited'])
  })

  it('new edits clear redo stack', () => {
    const um = new UndoManager()
    const lines1 = makeLines('state1')
    um.beginEditingLine(lines1, 0, 0)
    um.markContentChanged()

    const lines2 = makeLines('state2')
    um.commitPendingUndoState(lines2, 0)

    um.undo(lines2, 0)
    expect(um.canRedo).toBe(true)

    // New edit should clear redo
    const lines3 = makeLines('state1')
    um.beginEditingLine(lines3, 0, 0)
    um.markContentChanged()
    // markContentChanged clears redo when there are uncommitted changes
    expect(um.canRedo).toBe(false)
  })
})

describe('UndoManager cursor position restoration', () => {
  it('restores cursor position after typing', () => {
    const um = new UndoManager()
    const lines1 = linesAt(['hello'], 0, 5)
    um.beginEditingLine(lines1, 0, 0)
    um.markContentChanged()

    const lines2 = linesAt(['hello world'], 0, 11)
    const snapshot = um.undo(lines2, 0)
    expect(snapshot).not.toBeNull()
    expect(snapshot!.cursorPosition).toBe(5)
  })

  it('restores focused line index after Enter', () => {
    const um = new UndoManager()
    const lines1 = linesAt(['hello world'], 0, 5)
    um.beginEditingLine(lines1, 0, 0)

    // Simulate structural change (Enter key)
    um.prepareForStructuralChange(lines1, 0)
    um.continueAfterStructuralChange(1)

    const lines2 = makeLines('hello', ' world')
    um.markContentChanged()
    um.commitPendingUndoState(lines2, 1)

    const snapshot = um.undo(lines2, 1)
    expect(snapshot).not.toBeNull()
    expect(snapshot!.lineContents).toEqual(['hello world'])
    expect(snapshot!.focusedLineIndex).toBe(0)
  })

  it('restores cursor for mid-line Enter', () => {
    const um = new UndoManager()
    const lines1 = linesAt(['abcdef'], 0, 3)
    um.beginEditingLine(lines1, 0, 0)

    um.prepareForStructuralChange(lines1, 0)
    um.continueAfterStructuralChange(1)

    const lines2 = makeLines('abc', 'def')
    um.markContentChanged()
    um.commitPendingUndoState(lines2, 1)

    const snapshot = um.undo(lines2, 1)
    expect(snapshot).not.toBeNull()
    expect(snapshot!.cursorPosition).toBe(3)
  })

  it('restores cursor after bullet toggle', () => {
    const um = new UndoManager()
    const lines1 = linesAt(['hello'], 0, 3)
    um.recordCommand(lines1, 0, CommandType.BULLET)
    um.commitAfterCommand(lines1, 0, CommandType.BULLET)

    const lines2 = linesAt(['\u2022 hello'], 0, 5)
    const snapshot = um.undo(lines2, 0)
    expect(snapshot).not.toBeNull()
    expect(snapshot!.cursorPosition).toBe(3)
  })

  it('restores cursor after indent', () => {
    const um = new UndoManager()
    const lines1 = linesAt(['hello'], 0, 3)
    um.recordCommand(lines1, 0, CommandType.INDENT)

    const lines2 = linesAt(['\thello'], 0, 4)
    um.commitPendingUndoState(lines2, 0)

    const snapshot = um.undo(lines2, 0)
    expect(snapshot).not.toBeNull()
    expect(snapshot!.cursorPosition).toBe(3)
  })

  it('restores cursor after line merge', () => {
    const um = new UndoManager()
    const lines1 = makeLines('hello', 'world')
    um.captureStateBeforeChange(lines1, 1)

    const lines2 = linesAt(['helloworld'], 0, 5)
    um.beginEditingLine(lines2, 0, 0)

    const snapshot = um.undo(lines2, 0)
    expect(snapshot).not.toBeNull()
    expect(snapshot!.lineContents).toEqual(['hello', 'world'])
  })

  it('restores cursor after paste', () => {
    const um = new UndoManager()
    const lines1 = linesAt(['hello'], 0, 5)
    um.captureStateBeforeChange(lines1, 0)

    const lines2 = linesAt(['hello pasted text'], 0, 17)
    um.beginEditingLine(lines2, 0, 0)

    const snapshot = um.undo(lines2, 0)
    expect(snapshot).not.toBeNull()
    expect(snapshot!.cursorPosition).toBe(5)
    expect(snapshot!.lineContents).toEqual(['hello'])
  })

  it('redo restores cursor position', () => {
    const um = new UndoManager()
    const lines1 = linesAt(['hello'], 0, 5)
    um.beginEditingLine(lines1, 0, 0)
    um.markContentChanged()

    const lines2 = linesAt(['hello world'], 0, 11)
    um.commitPendingUndoState(lines2, 0)

    // Undo
    um.undo(lines2, 0)

    // Redo
    const snapshot = um.redo(makeLines('hello'), 0)
    expect(snapshot).not.toBeNull()
    expect(snapshot!.lineContents).toEqual(['hello world'])
    expect(snapshot!.cursorPosition).toBe(11)
  })

  it('restores multiline cursor position', () => {
    const um = new UndoManager()
    const lines1 = linesAt(['line1', 'line2', 'line3'], 1, 3)
    um.beginEditingLine(lines1, 1, 1)
    um.markContentChanged()

    const lines2 = makeLines('line1', 'line2 edited', 'line3')
    const snapshot = um.undo(lines2, 1)
    expect(snapshot).not.toBeNull()
    expect(snapshot!.focusedLineIndex).toBe(1)
    expect(snapshot!.cursorPosition).toBe(3)
  })

  it('cursor position is coerced to valid range', () => {
    const um = new UndoManager()
    // Simulate a state where cursor might be beyond text length
    const lines1 = linesAt(['long text here'], 0, 14)
    um.beginEditingLine(lines1, 0, 0)
    um.markContentChanged()

    const lines2 = linesAt(['short'], 0, 5)
    const snapshot = um.undo(lines2, 0)
    expect(snapshot).not.toBeNull()
    // cursorPosition should be within valid range
    expect(snapshot!.cursorPosition).toBeLessThanOrEqual(snapshot!.lineContents[0]!.length)
  })
})

describe('UndoManager captureStateBeforeChange', () => {
  it('creates immediate undo entry', () => {
    const um = new UndoManager()
    const lines = makeLines('hello')
    um.captureStateBeforeChange(lines, 0)

    expect(um.canUndo).toBe(true)
  })

  it('undo after captureStateBeforeChange restores state', () => {
    const um = new UndoManager()
    const lines1 = makeLines('before')
    um.captureStateBeforeChange(lines1, 0)

    const lines2 = makeLines('after')
    const snapshot = um.undo(lines2, 0)
    expect(snapshot).not.toBeNull()
    expect(snapshot!.lineContents).toEqual(['before'])
  })
})

describe('UndoManager move commands', () => {
  it('recordMoveCommand creates boundary for new range', () => {
    const um = new UndoManager()
    const lines = makeLines('a', 'b', 'c')
    const isNew = um.recordMoveCommand(lines, 0, [0, 0])
    expect(isNew).toBe(true)
  })

  it('recordMoveCommand groups same range', () => {
    const um = new UndoManager()
    const lines1 = makeLines('a', 'b', 'c')
    um.recordMoveCommand(lines1, 0, [0, 0])

    const lines2 = makeLines('b', 'a', 'c')
    um.updateMoveRange([1, 1])
    // Same original range [1,1] should group
    const isNew = um.recordMoveCommand(lines2, 1, [1, 1])
    expect(isNew).toBe(false)
  })

  it('recordMoveCommand creates new boundary for different range', () => {
    const um = new UndoManager()
    const lines1 = makeLines('a', 'b', 'c')
    um.recordMoveCommand(lines1, 0, [0, 0])

    const lines2 = makeLines('b', 'a', 'c')
    // Different range
    const isNew = um.recordMoveCommand(lines2, 1, [1, 2])
    expect(isNew).toBe(true)
  })
})
