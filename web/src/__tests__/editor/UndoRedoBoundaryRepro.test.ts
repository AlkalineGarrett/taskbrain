import { describe, it, expect } from 'vitest'
import { EditorState } from '@/editor/EditorState'
import { EditorController } from '@/editor/EditorController'
import { UndoManager } from '@/editor/UndoManager'
import { UnifiedUndoManager } from '@/editor/UnifiedUndoManager'

function setup() {
  const state = new EditorState()
  const undoManager = new UndoManager()
  const controller = new EditorController(state, undoManager)
  undoManager.setBaseline(state.lines, 0)
  undoManager.beginEditingLine(state.lines, 0, 0)
  const unified = new UnifiedUndoManager()
  unified.registerEditor('main', controller)
  return { state, controller, unified }
}

function typeChar(controller: EditorController, ch: string) {
  const line = controller.state.lines[0]!
  controller.updateLineContent(0, line.text + ch, line.text.length + 1)
}

describe('redo pins an undo boundary', () => {
  it('type, undo, redo, type, undo — only the second batch should be undone', () => {
    const { controller, unified, state } = setup()
    typeChar(controller, 'a')
    typeChar(controller, 'b')
    expect(state.lines[0]!.text).toBe('ab')

    unified.undo('main', () => {})
    expect(state.lines[0]!.text).toBe('')

    unified.redo('main', () => {})
    expect(state.lines[0]!.text).toBe('ab')

    typeChar(controller, 'c')
    typeChar(controller, 'd')
    expect(state.lines[0]!.text).toBe('abcd')

    unified.undo('main', () => {})
    expect(state.lines[0]!.text).toBe('ab')
    expect(unified.canUndo).toBe(true)

    unified.undo('main', () => {})
    expect(state.lines[0]!.text).toBe('')
    expect(unified.canUndo).toBe(false)
  })

  it('undo and redo are inverses across the boundary', () => {
    const { controller, unified, state } = setup()
    typeChar(controller, 'a')
    typeChar(controller, 'b')

    unified.undo('main', () => {})
    unified.redo('main', () => {})

    typeChar(controller, 'c')
    typeChar(controller, 'd')

    unified.undo('main', () => {})
    expect(state.lines[0]!.text).toBe('ab')
    unified.redo('main', () => {})
    expect(state.lines[0]!.text).toBe('abcd')

    unified.undo('main', () => {})
    expect(state.lines[0]!.text).toBe('ab')
    unified.undo('main', () => {})
    expect(state.lines[0]!.text).toBe('')
    unified.redo('main', () => {})
    expect(state.lines[0]!.text).toBe('ab')
    unified.redo('main', () => {})
    expect(state.lines[0]!.text).toBe('abcd')
  })
})
