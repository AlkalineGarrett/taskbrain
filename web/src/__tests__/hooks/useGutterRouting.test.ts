import { describe, it, expect, beforeEach, vi } from 'vitest'
import { useRef } from 'react'
import { act, renderHook } from '@testing-library/react'

const { interactionsSpy, baseHandlers } = vi.hoisted(() => {
  const handlers = {
    handleDragStart: vi.fn(),
    handleMoveStart: vi.fn(),
    handleGutterDragStart: vi.fn(),
    handleGutterDragUpdate: vi.fn(),
    selectLineRange: vi.fn(),
    gutterAnchorRef: { current: [-1, -1] as [number, number] },
  }
  return {
    interactionsSpy: vi.fn(() => handlers),
    baseHandlers: handlers,
  }
})

vi.mock('@/editor/useEditorInteractions', () => ({
  useEditorInteractions: interactionsSpy,
}))

import { EditorState } from '@/editor/EditorState'
import { EditorController } from '@/editor/EditorController'
import { InlineEditSession } from '@/editor/InlineEditSession'
import { useGutterRouting } from '@/hooks/useGutterRouting'

function makeParent() {
  const state = new EditorState()
  state.initFromNoteLines([
    { text: 'parent A', noteIds: [] },
    { text: 'parent B', noteIds: [] },
  ])
  return { state, controller: new EditorController(state) }
}

interface RoutingSetup {
  activeSession?: InlineEditSession | null
  moveDraggingClassName?: string
}

function setup(opts: RoutingSetup = {}) {
  const parent = makeParent()
  const activate = vi.fn()
  const deactivate = vi.fn(() => null)

  const { result } = renderHook(() => {
    const activeSessionRef = useRef<InlineEditSession | null>(opts.activeSession ?? null)
    return useGutterRouting({
      controller: parent.controller,
      editorState: parent.state,
      activeSessionRef,
      activateSession: activate,
      deactivateSession: deactivate,
      moveDraggingClassName: opts.moveDraggingClassName,
    })
  })

  return { parent, activate, deactivate, result }
}

describe('useGutterRouting', () => {
  let elementsFromPointMock: ReturnType<typeof vi.fn>
  beforeEach(() => {
    Object.values(baseHandlers).forEach(v => {
      if (typeof v === 'function' && 'mockClear' in v) (v as ReturnType<typeof vi.fn>).mockClear()
    })
    interactionsSpy.mockClear()
    baseHandlers.gutterAnchorRef.current = [-1, -1]
    // jsdom does not implement elementsFromPoint; stub it for each test.
    elementsFromPointMock = vi.fn(() => [] as Element[])
    ;(document as unknown as { elementsFromPoint: typeof elementsFromPointMock }).elementsFromPoint = elementsFromPointMock
  })

  it('forwards parent gutter clicks to the underlying interactions when no view line is hit', () => {
    elementsFromPointMock.mockReturnValue([])
    const { result } = setup()
    act(() => result.current.handleGutterDragStart(1, 100))
    expect(baseHandlers.handleGutterDragStart).toHaveBeenCalledWith(1)
  })

  it('routes gutter clicks on a view line to the matching session and selects the line there', () => {
    const session = new InlineEditSession('view1', 'view A\nview B')
    const setSelectionSpy = vi.spyOn(session.controller, 'setSelection').mockImplementation(() => {})

    const fakeEl = document.createElement('div')
    fakeEl.setAttribute('data-view-line-index', '1')
    fakeEl.setAttribute('data-view-note-id', 'view1')
    elementsFromPointMock.mockReturnValue([fakeEl])

    const { result, activate, parent } = setup({ activeSession: session })
    act(() => result.current.handleGutterDragStart(99, 200))

    expect(activate).toHaveBeenCalledWith(session)
    expect(setSelectionSpy).toHaveBeenCalled()
    // The base parent handler must NOT fire when a view line was hit.
    expect(baseHandlers.handleGutterDragStart).not.toHaveBeenCalled()
    expect(parent.state.hasSelection).toBe(false)
  })

  it('deactivates an active session when the parent gutter is clicked outside any view', () => {
    const session = new InlineEditSession('view1', 'A')
    elementsFromPointMock.mockReturnValue([])

    const { result, deactivate } = setup({ activeSession: session })
    act(() => result.current.handleGutterDragStart(0, 50))

    expect(deactivate).toHaveBeenCalled()
    expect(baseHandlers.handleGutterDragStart).toHaveBeenCalledWith(0)
  })

  it('extends an existing view-line gutter selection when dragging within the view', () => {
    const session = new InlineEditSession('view1', 'L0\nL1\nL2\nL3')
    const setSelectionSpy = vi.spyOn(session.controller, 'setSelection').mockImplementation(() => {})

    const fakeEl = document.createElement('div')
    fakeEl.setAttribute('data-view-line-index', '2')
    fakeEl.setAttribute('data-view-note-id', 'view1')
    elementsFromPointMock.mockReturnValue([fakeEl])

    const { result } = setup({ activeSession: session })
    // Pretend a previous gutter-start set the anchor to line 0.
    baseHandlers.gutterAnchorRef.current = [0, 0]

    act(() => result.current.handleGutterDragUpdate(99, 300))
    expect(setSelectionSpy).toHaveBeenCalled()
    expect(baseHandlers.handleGutterDragUpdate).not.toHaveBeenCalled()
  })

  it('falls through to the parent gutter update when no view line is hit', () => {
    elementsFromPointMock.mockReturnValue([])
    const { result } = setup()
    act(() => result.current.handleGutterDragUpdate(3, 400))
    expect(baseHandlers.handleGutterDragUpdate).toHaveBeenCalledWith(3)
  })

  it('handleMoveStart adds the moveDragging class and mouseup removes it', () => {
    const { result } = setup({ moveDraggingClassName: 'moveDragging' })
    const editorEl = document.createElement('div')
    document.body.appendChild(editorEl)
    // Manually attach the ref so the class is added/removed on the right element.
    ;(result.current.editorRef as { current: HTMLDivElement | null }).current = editorEl

    act(() => result.current.handleMoveStart())
    expect(editorEl.classList.contains('moveDragging')).toBe(true)
    expect(baseHandlers.handleMoveStart).toHaveBeenCalled()

    document.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }))
    expect(editorEl.classList.contains('moveDragging')).toBe(false)
    document.body.removeChild(editorEl)
  })

  it('handleMoveStart skips the CSS class when no className is provided', () => {
    const { result } = setup()
    const editorEl = document.createElement('div')
    ;(result.current.editorRef as { current: HTMLDivElement | null }).current = editorEl

    act(() => result.current.handleMoveStart())
    // No class added, but the underlying base handler still fires.
    expect(editorEl.classList.length).toBe(0)
    expect(baseHandlers.handleMoveStart).toHaveBeenCalled()
  })
})
