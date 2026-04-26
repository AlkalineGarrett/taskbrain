import { describe, it, expect, beforeEach, vi } from 'vitest'
import { useRef } from 'react'
import { act, renderHook } from '@testing-library/react'
import { EditorState } from '@/editor/EditorState'
import { EditorController } from '@/editor/EditorController'
import { UndoManager } from '@/editor/UndoManager'
import { InlineEditSession } from '@/editor/InlineEditSession'
import { InlineSessionManager } from '@/editor/InlineSessionManager'
import { useUnifiedUndo } from '@/hooks/useUnifiedUndo'

function createMainEditor() {
  const state = new EditorState()
  state.initFromNoteLines([{ text: 'hello', noteIds: [] }])
  const undo = new UndoManager()
  undo.setBaseline(state.lines, state.focusedLineIndex)
  return new EditorController(state, undo)
}

/** Stage and commit a real undo entry. */
function commitEdit(controller: EditorController, newText: string) {
  controller.undoManager.beginEditingLine(
    controller.state.lines,
    controller.state.focusedLineIndex,
    controller.state.focusedLineIndex,
  )
  controller.state.updateFromText(newText)
  controller.undoManager.markContentChanged()
  controller.commitUndoState()
}

function setup(initial: { activeSession?: InlineEditSession | null } = {}) {
  const controller = createMainEditor()
  const sessionManager = new InlineSessionManager()
  const invalidate = vi.fn()
  const activate = vi.fn()
  const deactivate = vi.fn(() => null)

  const { result, rerender } = renderHook(() => {
    const activeSessionRef = useRef<InlineEditSession | null>(initial.activeSession ?? null)
    const undo = useUnifiedUndo({
      controller,
      sessionManager,
      activeSessionRef,
      activateSession: activate,
      deactivateSession: deactivate,
      invalidateAndRecompute: invalidate,
    })
    return { undo, activeSessionRef }
  })

  return { controller, sessionManager, invalidate, activate, deactivate, result, rerender }
}

describe('useUnifiedUndo', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('registers the main controller on mount', () => {
    const { controller } = setup()
    expect(controller.undoManager.onEntryPushed).not.toBeNull()
  })

  it('main edits feed the unified undo stack', () => {
    const { controller, result } = setup()
    expect(result.current.undo.unifiedUndoManager.canUndo).toBe(false)
    commitEdit(controller, 'hello world')
    expect(result.current.undo.unifiedUndoManager.canUndo).toBe(true)
  })

  it('handleUndo invokes invalidateAndRecompute', () => {
    const { controller, invalidate, result } = setup()
    commitEdit(controller, 'hello world')

    act(() => result.current.undo.handleUndo())
    expect(invalidate).toHaveBeenCalled()
  })

  it('handleRedo invokes invalidateAndRecompute even with nothing to redo', () => {
    const { invalidate, result } = setup()
    act(() => result.current.undo.handleRedo())
    expect(invalidate).toHaveBeenCalled()
  })

  it('routes undo to a different inline editor when its entry is on top', () => {
    const { controller, sessionManager, activate, result, rerender } = setup()

    // Add the inline session, then force a re-render so the
    // hook's effect picks it up and registers the inline controller.
    sessionManager.ensureSessions([{
      id: 'view1', userId: '', parentNoteId: null, content: 'A', createdAt: null, updatedAt: null,
      tags: [], containedNotes: [], state: null, path: '', rootNoteId: null,
      showCompleted: true, onceCache: {},
    }])
    rerender()
    const session = sessionManager.getSession('view1')!

    // Push main first so its context goes on the unified stack…
    commitEdit(controller, 'main edit')
    // …then push the inline edit so its context lands on top.
    commitEdit(session.controller, 'A!')

    act(() => result.current.undo.handleUndo())
    expect(activate).toHaveBeenCalled()
  })

  it('deactivates inline sessions when undo lands on the main editor', () => {
    const inlineSession = new InlineEditSession('view2', 'A')
    const { controller, deactivate, result } = setup({ activeSession: inlineSession })

    commitEdit(controller, 'main only')
    act(() => result.current.undo.handleUndo())
    // The hook's getActiveContextId reflects the active session; landing on
    // 'main' from an active inline session should deactivate.
    expect(deactivate).toHaveBeenCalled()
  })
})
