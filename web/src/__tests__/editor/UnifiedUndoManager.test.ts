import { describe, it, expect } from 'vitest'
import { EditorState } from '../../editor/EditorState'
import { EditorController } from '../../editor/EditorController'
import { UndoManager } from '../../editor/UndoManager'
import { UnifiedUndoManager } from '../../editor/UnifiedUndoManager'

function createEditor(): EditorController {
  const state = new EditorState()
  const undoManager = new UndoManager()
  return new EditorController(state, undoManager)
}

/**
 * Simulate a user edit: update text, mark changed, then commit.
 * This creates a real undo entry on the editor's UndoManager.
 */
function simulateEdit(controller: EditorController, newText: string): void {
  // Begin editing so a pending snapshot is captured
  controller.undoManager.beginEditingLine(
    controller.state.lines,
    controller.state.focusedLineIndex,
    controller.state.focusedLineIndex,
  )
  // Apply the text change
  controller.state.updateFromText(newText)
  // Mark that content has changed (enables undo)
  controller.undoManager.markContentChanged()
  // Commit the pending state (pushes to undo stack)
  controller.commitUndoState()
}

describe('UnifiedUndoManager initial state', () => {
  it('canUndo is false with no editors', () => {
    const unified = new UnifiedUndoManager()
    expect(unified.canUndo).toBe(false)
  })

  it('canRedo is false with no editors', () => {
    const unified = new UnifiedUndoManager()
    expect(unified.canRedo).toBe(false)
  })

  it('stateVersion starts at 0', () => {
    const unified = new UnifiedUndoManager()
    expect(unified.stateVersion).toBe(0)
  })
})

describe('UnifiedUndoManager register/unregister', () => {
  it('registerEditor sets up callbacks on the UndoManager', () => {
    const unified = new UnifiedUndoManager()
    const controller = createEditor()

    unified.registerEditor('main', controller)
    expect(controller.undoManager.onEntryPushed).not.toBeNull()
    expect(controller.undoManager.onRedoCleared).not.toBeNull()
  })

  it('unregisterEditor clears callbacks', () => {
    const unified = new UnifiedUndoManager()
    const controller = createEditor()

    unified.registerEditor('main', controller)
    unified.unregisterEditor('main')
    expect(controller.undoManager.onEntryPushed).toBeNull()
    expect(controller.undoManager.onRedoCleared).toBeNull()
  })

  it('unregisterEditor is safe for non-existent ID', () => {
    const unified = new UnifiedUndoManager()
    expect(() => unified.unregisterEditor('nonexistent')).not.toThrow()
  })
})

describe('UnifiedUndoManager canUndo', () => {
  it('returns true when a registered editor has uncommitted changes', () => {
    const unified = new UnifiedUndoManager()
    const controller = createEditor()
    unified.registerEditor('main', controller)

    // Begin editing and mark changed but do NOT commit
    controller.undoManager.beginEditingLine(
      controller.state.lines,
      controller.state.focusedLineIndex,
      controller.state.focusedLineIndex,
    )
    controller.state.updateFromText('some text')
    controller.undoManager.markContentChanged()

    expect(unified.canUndo).toBe(true)
  })

  it('returns true when unified undo stack has entries', () => {
    const unified = new UnifiedUndoManager()
    const controller = createEditor()
    unified.registerEditor('main', controller)

    simulateEdit(controller, 'hello')
    expect(unified.canUndo).toBe(true)
  })

  it('returns false when no edits have been made', () => {
    const unified = new UnifiedUndoManager()
    const controller = createEditor()
    unified.registerEditor('main', controller)

    expect(unified.canUndo).toBe(false)
  })
})

describe('UnifiedUndoManager canRedo', () => {
  it('returns false initially', () => {
    const unified = new UnifiedUndoManager()
    const controller = createEditor()
    unified.registerEditor('main', controller)
    expect(unified.canRedo).toBe(false)
  })

  it('returns true after undo', () => {
    const unified = new UnifiedUndoManager()
    const controller = createEditor()
    unified.registerEditor('main', controller)

    simulateEdit(controller, 'hello')
    unified.undo('main', () => {})

    expect(unified.canRedo).toBe(true)
  })
})

describe('UnifiedUndoManager chronological undo across editors', () => {
  it('undo targets the most recently edited editor', () => {
    const unified = new UnifiedUndoManager()
    const mainCtrl = createEditor()
    const inlineCtrl = createEditor()

    unified.registerEditor('main', mainCtrl)
    unified.registerEditor('inline-A', inlineCtrl)

    // Edit main first, then inline-A
    simulateEdit(mainCtrl, 'main edit')
    simulateEdit(inlineCtrl, 'inline edit')

    // First undo should target inline-A (most recent)
    const result1 = unified.undo('inline-A', () => {})
    expect(result1).not.toBeNull()
    expect(result1!.contextId).toBe('inline-A')

    // Second undo should target main
    const activatedEditors: string[] = []
    const result2 = unified.undo('inline-A', (id) => activatedEditors.push(id))
    expect(result2).not.toBeNull()
    expect(result2!.contextId).toBe('main')
    expect(activatedEditors).toContain('main')
  })

  it('redo restores in correct order after undo', () => {
    const unified = new UnifiedUndoManager()
    const mainCtrl = createEditor()
    const inlineCtrl = createEditor()

    unified.registerEditor('main', mainCtrl)
    unified.registerEditor('inline-A', inlineCtrl)

    simulateEdit(mainCtrl, 'main edit')
    simulateEdit(inlineCtrl, 'inline edit')

    // Undo both
    unified.undo('inline-A', () => {})
    unified.undo('inline-A', () => {})

    // Redo should replay in chronological order: main first, then inline-A
    const redo1 = unified.redo('main', () => {})
    expect(redo1).not.toBeNull()
    expect(redo1!.contextId).toBe('main')

    const redo2 = unified.redo('main', () => {})
    expect(redo2).not.toBeNull()
    expect(redo2!.contextId).toBe('inline-A')
  })
})

describe('UnifiedUndoManager activateEditor callback', () => {
  it('is invoked when undo targets a different editor', () => {
    const unified = new UnifiedUndoManager()
    const mainCtrl = createEditor()
    const inlineCtrl = createEditor()

    unified.registerEditor('main', mainCtrl)
    unified.registerEditor('inline-A', inlineCtrl)

    simulateEdit(mainCtrl, 'main edit')

    const activated: string[] = []
    // Undo from inline-A context, should switch to main
    unified.undo('inline-A', (id) => activated.push(id))
    expect(activated).toEqual(['main'])
  })

  it('is NOT invoked when undo targets the same editor', () => {
    const unified = new UnifiedUndoManager()
    const mainCtrl = createEditor()
    unified.registerEditor('main', mainCtrl)

    simulateEdit(mainCtrl, 'main edit')

    const activated: string[] = []
    unified.undo('main', (id) => activated.push(id))
    expect(activated).toEqual([])
  })

  it('is invoked for redo targeting a different editor', () => {
    const unified = new UnifiedUndoManager()
    const mainCtrl = createEditor()
    const inlineCtrl = createEditor()

    unified.registerEditor('main', mainCtrl)
    unified.registerEditor('inline-A', inlineCtrl)

    simulateEdit(inlineCtrl, 'inline edit')
    unified.undo('inline-A', () => {})

    const activated: string[] = []
    // Redo from main context, should switch to inline-A
    unified.redo('main', (id) => activated.push(id))
    expect(activated).toEqual(['inline-A'])
  })
})

describe('UnifiedUndoManager abandonHistory', () => {
  it('clears both undo and redo stacks', () => {
    const unified = new UnifiedUndoManager()
    const controller = createEditor()
    unified.registerEditor('main', controller)

    simulateEdit(controller, 'edit 1')
    simulateEdit(controller, 'edit 2')
    unified.undo('main', () => {})

    expect(unified.canUndo).toBe(true)
    expect(unified.canRedo).toBe(true)

    unified.abandonHistory()

    expect(unified.canUndo).toBe(false)
    expect(unified.canRedo).toBe(false)
  })

  it('increments stateVersion', () => {
    const unified = new UnifiedUndoManager()
    const before = unified.stateVersion
    unified.abandonHistory()
    expect(unified.stateVersion).toBeGreaterThan(before)
  })
})

describe('UnifiedUndoManager reset', () => {
  it('clears stacks, editors, and callbacks', () => {
    const unified = new UnifiedUndoManager()
    const controller = createEditor()
    unified.registerEditor('main', controller)

    simulateEdit(controller, 'edit')

    unified.reset()

    expect(unified.canUndo).toBe(false)
    expect(unified.canRedo).toBe(false)
    expect(controller.undoManager.onEntryPushed).toBeNull()
    expect(controller.undoManager.onRedoCleared).toBeNull()
  })
})

describe('UnifiedUndoManager stateVersion tracking', () => {
  it('increments on edit commit (via onEntryPushed)', () => {
    const unified = new UnifiedUndoManager()
    const controller = createEditor()
    unified.registerEditor('main', controller)

    const before = unified.stateVersion
    simulateEdit(controller, 'hello')
    expect(unified.stateVersion).toBeGreaterThan(before)
  })

  it('increments on undo', () => {
    const unified = new UnifiedUndoManager()
    const controller = createEditor()
    unified.registerEditor('main', controller)

    simulateEdit(controller, 'hello')
    const before = unified.stateVersion
    unified.undo('main', () => {})
    expect(unified.stateVersion).toBeGreaterThan(before)
  })

  it('increments on redo', () => {
    const unified = new UnifiedUndoManager()
    const controller = createEditor()
    unified.registerEditor('main', controller)

    simulateEdit(controller, 'hello')
    unified.undo('main', () => {})
    const before = unified.stateVersion
    unified.redo('main', () => {})
    expect(unified.stateVersion).toBeGreaterThan(before)
  })
})

describe('UnifiedUndoManager edge cases', () => {
  it('undo returns null when stack is empty', () => {
    const unified = new UnifiedUndoManager()
    const controller = createEditor()
    unified.registerEditor('main', controller)

    const result = unified.undo('main', () => {})
    expect(result).toBeNull()
  })

  it('redo returns null when redo stack is empty', () => {
    const unified = new UnifiedUndoManager()
    const controller = createEditor()
    unified.registerEditor('main', controller)

    const result = unified.redo('main', () => {})
    expect(result).toBeNull()
  })

  it('undo returns null when target editor was unregistered', () => {
    const unified = new UnifiedUndoManager()
    const controller = createEditor()
    unified.registerEditor('main', controller)

    simulateEdit(controller, 'edit')
    unified.unregisterEditor('main')

    // The unified stack still has the entry, but the editor is gone
    const result = unified.undo('main', () => {})
    expect(result).toBeNull()
  })

  it('new edit after undo clears unified redo stack', () => {
    const unified = new UnifiedUndoManager()
    const controller = createEditor()
    unified.registerEditor('main', controller)

    simulateEdit(controller, 'edit 1')
    unified.undo('main', () => {})
    expect(unified.canRedo).toBe(true)

    // A new edit should clear redo on both per-editor and unified level
    simulateEdit(controller, 'edit 2')
    expect(unified.canRedo).toBe(false)
  })
})
