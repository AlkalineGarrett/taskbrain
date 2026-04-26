import { useCallback, useEffect, useRef, type MutableRefObject } from 'react'
import type { EditorController } from '@/editor/EditorController'
import type { EditorState } from '@/editor/EditorState'
import type { InlineEditSession } from '@/editor/InlineEditSession'
import { useEditorInteractions } from '@/editor/useEditorInteractions'

interface UseGutterRoutingOptions {
  controller: EditorController
  editorState: EditorState
  activeSessionRef: MutableRefObject<InlineEditSession | null>
  activateSession: (session: InlineEditSession) => void
  deactivateSession: (expectedSession?: InlineEditSession) => InlineEditSession | null
  /** CSS class added to the editor container while a move-drag is in progress. */
  moveDraggingClassName?: string
}

/**
 * Composes useEditorInteractions with view-line-aware gutter routing: clicks
 * on a view directive's gutter activate that view's inline session and select
 * within it; clicks elsewhere deactivate any active session and fall through.
 *
 * Also wraps move-drag start/end so the editor container gets a CSS class while
 * the drag is in progress (the underlying interactions hook handles the drop
 * cursor but not the container styling).
 */
export function useGutterRouting({
  controller,
  editorState,
  activeSessionRef,
  activateSession,
  deactivateSession,
  moveDraggingClassName,
}: UseGutterRoutingOptions) {
  const editorRef = useRef<HTMLDivElement>(null)
  const dropCursorRef = useRef<HTMLDivElement>(null)
  const getParentState = useCallback(() => editorState, [editorState])
  const getParentController = useCallback(() => controller, [controller])

  const {
    handleDragStart,
    handleMoveStart: baseHandleMoveStart,
    handleGutterDragStart: baseGutterDragStart,
    handleGutterDragUpdate: baseGutterDragUpdate,
    selectLineRange,
    gutterAnchorRef,
  } = useEditorInteractions(editorRef, dropCursorRef, getParentState, getParentController)

  const handleMoveStart = useCallback(() => {
    baseHandleMoveStart()
    if (moveDraggingClassName) editorRef.current?.classList.add(moveDraggingClassName)
  }, [baseHandleMoveStart, moveDraggingClassName])

  useEffect(() => {
    if (!moveDraggingClassName) return
    const handleMouseUp = () => editorRef.current?.classList.remove(moveDraggingClassName)
    document.addEventListener('mouseup', handleMouseUp)
    return () => document.removeEventListener('mouseup', handleMouseUp)
  }, [moveDraggingClassName])

  const resolveViewLineAtY = useCallback((clientY: number): { viewLineIndex: number; session: InlineEditSession } | null => {
    const viewLineEl = document.elementsFromPoint(window.innerWidth / 2, clientY)
      .find(el => el.hasAttribute('data-view-line-index'))
    if (!viewLineEl) return null
    const viewLineIndex = parseInt(viewLineEl.getAttribute('data-view-line-index')!)
    const targetNoteId = viewLineEl.getAttribute('data-view-note-id')
    const existing = activeSessionRef.current
    if (existing?.noteId === targetNoteId) return { viewLineIndex, session: existing }
    return null
  }, [activeSessionRef])

  // Mirrors selectLineRange from useEditorInteractions but binds to an inline
  // session's state/controller instead of the parent's. The interactions hook
  // closes over a single getState/getController pair, so a second invocation
  // for the session would create a second set of drag/anchor refs we don't
  // want — better to compute the bounds inline here.
  const selectViewLineRange = (
    session: InlineEditSession,
    first: number,
    last: number,
  ) => {
    const state = session.editorState
    const ctrl = session.controller
    const start = state.getLineStartOffset(first)
    const lastLine = state.lines[last]
    let end = state.getLineStartOffset(last) + (lastLine?.text.length ?? 0)
    if ((lastLine?.text.length ?? 0) === 0 && last < state.lines.length - 1) end += 1
    ctrl.setSelection(start, end)
  }

  const handleGutterDragStart = useCallback((lineIndex: number, clientY?: number) => {
    if (clientY != null) {
      const viewHit = resolveViewLineAtY(clientY)
      if (viewHit) {
        const { viewLineIndex, session } = viewHit
        activateSession(session)
        gutterAnchorRef.current = [viewLineIndex, viewLineIndex]
        selectViewLineRange(session, viewLineIndex, viewLineIndex)
        return
      }
    }
    // Parent gutter: deactivate any active view session so commands route to parent
    if (activeSessionRef.current) deactivateSession()
    baseGutterDragStart(lineIndex)
  }, [baseGutterDragStart, resolveViewLineAtY, activateSession, deactivateSession, gutterAnchorRef, activeSessionRef])

  const handleGutterDragUpdate = useCallback((lineIndex: number, clientY?: number) => {
    if (clientY != null) {
      const viewHit = resolveViewLineAtY(clientY)
      if (viewHit) {
        const { viewLineIndex, session } = viewHit
        const [anchorStart, anchorEnd] = gutterAnchorRef.current
        if (anchorStart < 0) return
        const state = session.editorState
        const first = Math.max(0, Math.min(anchorStart, viewLineIndex))
        const last = Math.min(state.lines.length - 1, Math.max(anchorEnd, viewLineIndex))
        selectViewLineRange(session, first, last)
        return
      }
    }
    baseGutterDragUpdate(lineIndex)
  }, [baseGutterDragUpdate, resolveViewLineAtY, gutterAnchorRef])

  return {
    editorRef,
    dropCursorRef,
    handleDragStart,
    handleMoveStart,
    handleGutterDragStart,
    handleGutterDragUpdate,
    selectLineRange,
    gutterAnchorRef,
  }
}
