import { useCallback, useEffect, useRef } from 'react'
import type { DirectiveSegment } from '@/dsl/directives/DirectiveSegmenter'
import type { EditorState } from './EditorState'
import type { EditorController } from './EditorController'
import { hitTestLineFromPoint, positionDropCursorFromPoint } from './TextMeasure'

/**
 * Shared editor interaction handlers for both the main editor and view editors.
 * Provides drag selection, move drag, and gutter selection — all parameterized
 * by a container ref, a way to get the current EditorState/EditorController,
 * and an optional line-element data attribute.
 *
 * `getSegments`, when provided, lets the hit-tester map clicks on chip lines
 * back to source-space offsets — pass it for editors that render directive
 * chips so drag-selection lands on the right characters.
 */
export function useEditorInteractions(
  containerRef: React.RefObject<HTMLElement | null>,
  dropCursorRef: React.RefObject<HTMLElement | null>,
  getState: () => EditorState | null,
  getController: () => EditorController | null,
  lineAttr = 'data-line-index',
  getSegments: ((lineIndex: number) => DirectiveSegment[] | null) | null = null,
) {
  const isDraggingRef = useRef(false)
  const isMoveDraggingRef = useRef(false)
  const gutterAnchorRef = useRef<[number, number]>([-1, -1])

  // --- Hit testing ---

  const getGlobalOffset = useCallback((clientX: number, clientY: number): number | null => {
    const el = containerRef.current
    const state = getState()
    if (!el || !state) return null
    return hitTestLineFromPoint(
      el, state.lines, (i) => state.getLineStartOffset(i),
      clientX, clientY, lineAttr, getSegments,
    )?.globalOffset ?? null
  }, [containerRef, getState, lineAttr, getSegments])

  // --- Gutter selection ---

  const selectLineRange = useCallback((fromLine: number, toLine: number) => {
    const state = getState()
    const ctrl = getController()
    if (!state || !ctrl) return
    const first = Math.max(0, Math.min(fromLine, toLine))
    const last = Math.min(state.lines.length - 1, Math.max(fromLine, toLine))
    const start = state.getLineStartOffset(first)
    const lastLine = state.lines[last]
    let end = state.getLineStartOffset(last) + (lastLine?.text.length ?? 0)
    if ((lastLine?.text.length ?? 0) === 0 && last < state.lines.length - 1) {
      end += 1
    }
    ctrl.setSelection(start, end)
  }, [getState, getController])

  const handleGutterDragStart = useCallback((lineIndex: number) => {
    gutterAnchorRef.current = [lineIndex, lineIndex]
    selectLineRange(lineIndex, lineIndex)
  }, [selectLineRange])

  const handleGutterDragUpdate = useCallback((lineIndex: number) => {
    const [anchorStart, anchorEnd] = gutterAnchorRef.current
    if (anchorStart < 0) return
    selectLineRange(Math.min(anchorStart, lineIndex), Math.max(anchorEnd, lineIndex))
  }, [selectLineRange])

  const handleDragStart = useCallback((anchorGlobalOffset: number) => {
    const state = getState()
    if (!state) return
    isDraggingRef.current = true
    state.selectionAnchor = anchorGlobalOffset
  }, [getState])

  const handleMoveStart = useCallback(() => {
    isMoveDraggingRef.current = true
  }, [])

  // Single mousemove + mouseup listener for drag selection, move drag, and gutter reset
  useEffect(() => {
    const handleMouseMove = (e: globalThis.MouseEvent) => {
      if (isDraggingRef.current) {
        const state = getState()
        if (!state) return
        const offset = getGlobalOffset(e.clientX, e.clientY)
        if (offset != null) state.extendSelectionTo(offset)
      } else if (isMoveDraggingRef.current) {
        const cursor = dropCursorRef.current
        const el = containerRef.current
        const state = getState()
        if (!cursor || !el || !state) return
        positionDropCursorFromPoint(
          cursor, el, state.lines,
          (i) => state.getLineStartOffset(i),
          e.clientX, e.clientY, lineAttr, getSegments,
        )
      }
    }
    const handleMouseUp = (e: globalThis.MouseEvent) => {
      gutterAnchorRef.current = [-1, -1]
      if (isMoveDraggingRef.current) {
        isMoveDraggingRef.current = false
        if (dropCursorRef.current) dropCursorRef.current.style.display = 'none'
        const ctrl = getController()
        if (ctrl) {
          const offset = getGlobalOffset(e.clientX, e.clientY)
          if (offset != null) ctrl.moveSelectionTo(offset)
        }
        return
      }
      isDraggingRef.current = false
    }
    document.addEventListener('mousemove', handleMouseMove)
    document.addEventListener('mouseup', handleMouseUp)
    return () => {
      document.removeEventListener('mousemove', handleMouseMove)
      document.removeEventListener('mouseup', handleMouseUp)
    }
  }, [containerRef, dropCursorRef, getState, getController, getGlobalOffset, lineAttr, getSegments])

  return {
    handleDragStart,
    handleMoveStart,
    handleGutterDragStart,
    handleGutterDragUpdate,
    selectLineRange,
    getGlobalOffset,
    gutterAnchorRef,
  }
}
