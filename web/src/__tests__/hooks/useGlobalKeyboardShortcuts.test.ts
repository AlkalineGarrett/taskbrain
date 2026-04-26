import { describe, it, expect, beforeEach, vi } from 'vitest'
import { useRef } from 'react'
import { renderHook } from '@testing-library/react'
import { EditorState } from '@/editor/EditorState'
import { EditorController } from '@/editor/EditorController'
import { InlineEditSession } from '@/editor/InlineEditSession'
import { useGlobalKeyboardShortcuts } from '@/hooks/useGlobalKeyboardShortcuts'

function makeEditor() {
  const state = new EditorState()
  state.initFromNoteLines([
    { text: 'one', noteIds: [] },
    { text: 'two', noteIds: [] },
  ])
  return { state, controller: new EditorController(state) }
}

function dispatchKey(opts: KeyboardEventInit & { key: string }) {
  // jsdom doesn't apply the cancellation/bubbling inferred from KeyboardEventInit
  // unless we set defaults explicitly.
  const e = new KeyboardEvent('keydown', { bubbles: true, cancelable: true, ...opts })
  window.dispatchEvent(e)
  return e
}

interface SetupOptions {
  activeSession?: InlineEditSession | null
}

function setup(saveAll = vi.fn(), opts: SetupOptions = {}) {
  const { state, controller } = makeEditor()
  const handleUndo = vi.fn()
  const handleRedo = vi.fn()
  vi.spyOn(controller, 'cutSelection').mockImplementation(() => null)
  vi.spyOn(controller, 'copySelection').mockImplementation(() => {})
  vi.spyOn(controller, 'deleteSelectionWithUndo').mockImplementation(() => {})
  vi.spyOn(state, 'selectAll').mockImplementation(() => {})

  renderHook(() => {
    const activeSessionRef = useRef<InlineEditSession | null>(opts.activeSession ?? null)
    useGlobalKeyboardShortcuts({
      saveAll,
      controller,
      editorState: state,
      activeSessionRef,
      handleUndo,
      handleRedo,
    })
  })

  return { state, controller, handleUndo, handleRedo, saveAll }
}

describe('useGlobalKeyboardShortcuts', () => {
  beforeEach(() => {
    // Defocus any previous test input.
    if (document.activeElement && 'blur' in document.activeElement) {
      (document.activeElement as HTMLElement).blur()
    }
  })

  it('Cmd/Ctrl+S triggers saveAll regardless of focus', () => {
    const saveAll = vi.fn()
    setup(saveAll)
    const e = dispatchKey({ key: 's', metaKey: true })
    expect(saveAll).toHaveBeenCalled()
    expect(e.defaultPrevented).toBe(true)
  })

  it('Cmd/Ctrl+S also fires when a textarea is focused', () => {
    const saveAll = vi.fn()
    setup(saveAll)
    const ta = document.createElement('textarea')
    document.body.appendChild(ta)
    ta.focus()
    dispatchKey({ key: 's', ctrlKey: true })
    expect(saveAll).toHaveBeenCalled()
    document.body.removeChild(ta)
  })

  it('Other shortcuts are ignored when a textarea is focused', () => {
    const { handleUndo } = setup()
    const ta = document.createElement('textarea')
    document.body.appendChild(ta)
    ta.focus()
    dispatchKey({ key: 'z', metaKey: true })
    expect(handleUndo).not.toHaveBeenCalled()
    document.body.removeChild(ta)
  })

  it('Cmd/Ctrl+Z triggers undo, Shift+Cmd/Ctrl+Z triggers redo', () => {
    const { handleUndo, handleRedo } = setup()
    dispatchKey({ key: 'z', metaKey: true })
    expect(handleUndo).toHaveBeenCalledTimes(1)
    expect(handleRedo).not.toHaveBeenCalled()

    dispatchKey({ key: 'z', metaKey: true, shiftKey: true })
    expect(handleRedo).toHaveBeenCalledTimes(1)
  })

  it('Cmd/Ctrl+Y triggers redo', () => {
    const { handleRedo } = setup()
    dispatchKey({ key: 'y', metaKey: true })
    expect(handleRedo).toHaveBeenCalledTimes(1)
  })

  it('Cmd/Ctrl+A invokes selectAll on the active state', () => {
    const { state } = setup()
    dispatchKey({ key: 'a', metaKey: true })
    expect(state.selectAll).toHaveBeenCalled()
  })

  it('Cmd/Ctrl+X invokes cut only when there is a selection', () => {
    const { controller, state } = setup()
    dispatchKey({ key: 'x', metaKey: true })
    expect(controller.cutSelection).not.toHaveBeenCalled()

    state.setSelection(0, 3)
    dispatchKey({ key: 'x', metaKey: true })
    expect(controller.cutSelection).toHaveBeenCalled()
  })

  it('Cmd/Ctrl+C invokes copy only when there is a selection', () => {
    const { controller, state } = setup()
    dispatchKey({ key: 'c', metaKey: true })
    expect(controller.copySelection).not.toHaveBeenCalled()

    state.setSelection(0, 3)
    dispatchKey({ key: 'c', metaKey: true })
    expect(controller.copySelection).toHaveBeenCalled()
  })

  it('Backspace and Delete trigger deleteSelectionWithUndo when a selection exists', () => {
    const { controller, state } = setup()
    state.setSelection(0, 3)
    dispatchKey({ key: 'Backspace' })
    expect(controller.deleteSelectionWithUndo).toHaveBeenCalledTimes(1)

    state.setSelection(0, 3)
    dispatchKey({ key: 'Delete' })
    expect(controller.deleteSelectionWithUndo).toHaveBeenCalledTimes(2)
  })

  it('routes commands to the active inline session when one is active', () => {
    const session = new InlineEditSession('view1', 'inline')
    const cutSpy = vi.spyOn(session.controller, 'cutSelection').mockImplementation(() => null)
    setup(undefined, { activeSession: session })
    session.editorState.setSelection(0, 3)
    dispatchKey({ key: 'x', metaKey: true })
    expect(cutSpy).toHaveBeenCalled()
  })
})
