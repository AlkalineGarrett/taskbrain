import { describe, it, expect, beforeEach } from 'vitest'
import { act, renderHook } from '@testing-library/react'
import { EditorState } from '@/editor/EditorState'
import { EditorController } from '@/editor/EditorController'
import { InlineEditSession } from '@/editor/InlineEditSession'
import { InlineSessionManager } from '@/editor/InlineSessionManager'
import { useActiveEditorSession } from '@/hooks/useActiveEditorSession'

function makeParent() {
  const state = new EditorState()
  state.initFromNoteLines([
    { text: 'Parent line 1', noteIds: [] },
    { text: 'Parent line 2', noteIds: [] },
  ])
  const controller = new EditorController(state)
  return { state, controller }
}

describe('useActiveEditorSession', () => {
  let parent: ReturnType<typeof makeParent>
  let sessionManager: InlineSessionManager

  beforeEach(() => {
    parent = makeParent()
    sessionManager = new InlineSessionManager()
  })

  it('starts with no active session and routes to parent', () => {
    const { result } = renderHook(() =>
      useActiveEditorSession(parent.controller, parent.state, sessionManager),
    )
    expect(result.current.activeSession).toBeNull()
    expect(result.current.activeController).toBe(parent.controller)
    expect(result.current.activeState).toBe(parent.state)
  })

  it('activateSession routes commands to the inline controller/state', () => {
    const session = new InlineEditSession('view1', 'A\nB')
    const { result } = renderHook(() =>
      useActiveEditorSession(parent.controller, parent.state, sessionManager),
    )

    act(() => result.current.activateSession(session))

    expect(result.current.activeSession).toBe(session)
    expect(result.current.activeController).toBe(session.controller)
    expect(result.current.activeState).toBe(session.editorState)
    expect(result.current.activeSessionRef.current).toBe(session)
  })

  it('activate clears parent selection and any prior view selection', () => {
    const session1 = new InlineEditSession('view1', 'A\nB')
    const session2 = new InlineEditSession('view2', 'C\nD')

    parent.state.setSelection(0, 6)
    session1.editorState.setSelection(0, 1)

    const { result } = renderHook(() =>
      useActiveEditorSession(parent.controller, parent.state, sessionManager),
    )

    act(() => result.current.activateSession(session1))
    act(() => result.current.activateSession(session2))

    expect(parent.state.hasSelection).toBe(false)
    expect(session1.editorState.hasSelection).toBe(false)
  })

  it('re-activating the same session does NOT clear its selection', () => {
    const session = new InlineEditSession('view1', 'A')
    const { result } = renderHook(() =>
      useActiveEditorSession(parent.controller, parent.state, sessionManager),
    )

    act(() => result.current.activateSession(session))
    session.editorState.setSelection(0, 1)
    act(() => result.current.activateSession(session))

    expect(session.editorState.hasSelection).toBe(true)
  })

  it('deactivateSession returns prior session and clears its selection', () => {
    const session = new InlineEditSession('view1', 'A')
    const { result } = renderHook(() =>
      useActiveEditorSession(parent.controller, parent.state, sessionManager),
    )

    act(() => result.current.activateSession(session))
    session.editorState.setSelection(0, 1)

    let returned: InlineEditSession | null = null
    act(() => {
      returned = result.current.deactivateSession()
    })

    expect(returned).toBe(session)
    expect(result.current.activeSession).toBeNull()
    expect(result.current.activeController).toBe(parent.controller)
    expect(session.editorState.hasSelection).toBe(false)
  })

  it('expectedSession guard makes deactivate a no-op when current does not match', () => {
    const sessionA = new InlineEditSession('viewA', 'A')
    const sessionB = new InlineEditSession('viewB', 'B')

    const { result } = renderHook(() =>
      useActiveEditorSession(parent.controller, parent.state, sessionManager),
    )

    act(() => result.current.activateSession(sessionB))
    sessionB.editorState.setSelection(0, 1)

    let returned: InlineEditSession | null = null
    act(() => {
      returned = result.current.deactivateSession(sessionA)
    })

    expect(returned).toBeNull()
    expect(result.current.activeSession).toBe(sessionB)
    expect(sessionB.editorState.hasSelection).toBe(true)
  })

  it('activeEditorCtx exposes the manager and current active state', () => {
    const session = new InlineEditSession('view1', 'A')
    const { result } = renderHook(() =>
      useActiveEditorSession(parent.controller, parent.state, sessionManager),
    )

    expect(result.current.activeEditorCtx.sessionManager).toBe(sessionManager)
    expect(result.current.activeEditorCtx.activeController).toBe(parent.controller)

    act(() => result.current.activateSession(session))
    expect(result.current.activeEditorCtx.activeController).toBe(session.controller)
    expect(result.current.activeEditorCtx.activeSession).toBe(session)
  })

  it('notifyActiveChange triggers a re-render so children re-evaluate disabled states', () => {
    let renders = 0
    const { result } = renderHook(() => {
      renders += 1
      return useActiveEditorSession(parent.controller, parent.state, sessionManager)
    })
    const before = renders
    act(() => result.current.notifyActiveChange())
    expect(renders).toBeGreaterThan(before)
  })
})
