import { useCallback, useEffect, useRef } from 'react'
import type { DirectiveSegment } from '@/dsl/directives/DirectiveSegmenter'
import type { EditorState } from './EditorState'
import type { EditorController } from './EditorController'
import type { UnifiedUndoManager } from './UnifiedUndoManager'
import { hitTestLineFromPoint, positionDropCursorAtHit } from './TextMeasure'
import { dropTargetRegistry } from './DropTargetRegistry'

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
  unifiedUndoManager: UnifiedUndoManager | null = null,
) {
  const isDraggingRef = useRef(false)
  const isMoveDraggingRef = useRef(false)
  const gutterAnchorRef = useRef<[number, number]>([-1, -1])

  // Register this editor as a cross-editor drop target so a move-drag started
  // anywhere can drop into it. Re-registers if the container or drop cursor
  // element changes; the registry holds the concrete elements, not refs.
  useEffect(() => {
    const containerEl = containerRef.current
    if (!containerEl) return
    return dropTargetRegistry.register({
      containerEl,
      dropCursorEl: dropCursorRef.current,
      getState,
      getController,
      lineAttr,
      getSegments,
    })
  }, [containerRef, dropCursorRef, getState, getController, lineAttr, getSegments])

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

  // Single mousemove + mouseup listener for drag selection, move drag, and gutter reset.
  // Move-drag hit-tests across the cross-editor registry so a drag started here
  // can drop into another embedded editor; selection-drag stays scoped to this editor.
  useEffect(() => {
    const handleMouseMove = (e: globalThis.MouseEvent) => {
      if (isDraggingRef.current) {
        const state = getState()
        if (!state) return
        const offset = getGlobalOffset(e.clientX, e.clientY)
        if (offset != null) state.extendSelectionTo(offset)
      } else if (isMoveDraggingRef.current) {
        const result = dropTargetRegistry.findTargetAtPoint(e.clientX, e.clientY)
        if (!result) {
          dropTargetRegistry.hideAllDropCursorsExcept(null)
          return
        }
        const { target, hit } = result
        if (target.dropCursorEl) {
          positionDropCursorAtHit(target.dropCursorEl, target.containerEl, hit)
        }
        dropTargetRegistry.hideAllDropCursorsExcept(target)
      }
    }
    const handleMouseUp = (e: globalThis.MouseEvent) => {
      gutterAnchorRef.current = [-1, -1]
      if (isMoveDraggingRef.current) {
        isMoveDraggingRef.current = false
        dropTargetRegistry.hideAllDropCursorsExcept(null)
        const sourceCtrl = getController()
        if (!sourceCtrl) return
        const result = dropTargetRegistry.findTargetAtPoint(e.clientX, e.clientY)
        if (!result) return
        const targetCtrl = result.target.getController()
        if (targetCtrl === sourceCtrl) {
          sourceCtrl.moveSelectionTo(result.hit.globalOffset)
        } else if (targetCtrl) {
          // Cross-editor drop: wrap source-delete + target-paste in one undo
          // group so a single press of cmd-z reverts both halves.
          const performMove = () => sourceCtrl.moveSelectionAcrossEditors(targetCtrl, result.hit.globalOffset)
          if (unifiedUndoManager) unifiedUndoManager.withGroup(performMove)
          else performMove()
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
  }, [getState, getController, getGlobalOffset, gutterAnchorRef, unifiedUndoManager])

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
