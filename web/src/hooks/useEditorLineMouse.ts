import { useCallback, type MouseEvent, type RefObject } from 'react'
import type { EditorController } from '@/editor/EditorController'
import type { EditorState } from '@/editor/EditorState'
import type { LineState } from '@/editor/LineState'
import type { DirectiveResult } from '@/dsl/directives/DirectiveResult'
import { segmentLine } from '@/dsl/directives/DirectiveSegmenter'
import {
  getCharOffsetFromPoint,
  getCharOffsetHidingTextarea,
  getWordBoundsAt,
  mapDisplayOffsetToSource,
} from '@/editor/TextMeasure'

interface UseEditorLineMouseOptions {
  controller: EditorController
  editorState: EditorState
  lineIndex: number
  line: LineState
  content: string
  prefix: string
  directiveResults: Map<string, DirectiveResult> | undefined
  inputRef: RefObject<HTMLTextAreaElement | null>
  overlayRef: RefObject<HTMLDivElement | null>
  directiveContentRef: RefObject<HTMLDivElement | null>
  onDragStart?: (anchorGlobalOffset: number) => void
  onMoveStart?: () => void
}

interface EditorLineMouseHandlers {
  handleMouseDown: (e: MouseEvent<HTMLTextAreaElement | HTMLDivElement>) => void
  handleContextMenu: (e: MouseEvent<HTMLTextAreaElement | HTMLDivElement>) => void
}

/**
 * Mouse handlers for an editor line. Resolves the source-space char index from
 * a click using whichever element is rendered (textarea overlay for plain
 * lines, directive content for chip lines), then routes to:
 *   - shift-click → extend selection
 *   - double-click → select word
 *   - triple+ click → select line
 *   - click inside existing selection → drag-move
 *   - plain click → place cursor + start drag-select
 */
export function useEditorLineMouse({
  controller,
  editorState,
  lineIndex,
  line,
  content,
  prefix,
  directiveResults,
  inputRef,
  overlayRef,
  directiveContentRef,
  onDragStart,
  onMoveStart,
}: UseEditorLineMouseOptions): EditorLineMouseHandlers {
  /** Compute the source-space char index from a mouse event, using whichever element is rendered. */
  const getSourceCharIndex = useCallback(
    (e: MouseEvent): number => {
      const overlay = overlayRef.current
      const textarea = inputRef.current
      if (overlay && textarea) {
        const offset = getCharOffsetHidingTextarea(overlay, textarea, e.clientX, e.clientY)
        if (offset != null) return offset
      }
      const directiveEl = directiveContentRef.current
      if (directiveEl) {
        const displayOffset = getCharOffsetFromPoint(directiveEl, e.clientX, e.clientY)
        if (displayOffset != null) {
          const segments = segmentLine(content, line.effectiveId, directiveResults ?? new Map(), line.noteIds[0])
          return mapDisplayOffsetToSource(displayOffset, segments)
        }
      }
      return content.length
    },
    [content, line, directiveResults, inputRef, overlayRef, directiveContentRef],
  )

  const handleContextMenu = useCallback(
    (e: MouseEvent<HTMLTextAreaElement | HTMLDivElement>) => {
      const charIdx = getSourceCharIndex(e)
      const globalOffset = editorState.getLineStartOffset(lineIndex) + prefix.length + charIdx
      if (!editorState.hasSelection) {
        controller.setCursorFromGlobalOffset(globalOffset)
      }
    },
    [getSourceCharIndex, controller, editorState, lineIndex, prefix.length],
  )

  const handleMouseDown = useCallback(
    (e: MouseEvent<HTMLTextAreaElement | HTMLDivElement>) => {
      e.preventDefault()

      const charIdx = getSourceCharIndex(e)
      const globalOffset = editorState.getLineStartOffset(lineIndex) + prefix.length + charIdx

      if (!e.shiftKey && e.detail === 1 && editorState.hasSelection) {
        const [selStart, selEnd] = editorState.getEffectiveSelectionRange()
        if (globalOffset >= selStart && globalOffset <= selEnd) {
          onMoveStart?.()
          return
        }
      }

      if (e.shiftKey) {
        editorState.extendSelectionTo(globalOffset)
      } else if (e.detail === 2) {
        const wordBounds = getWordBoundsAt(content, charIdx)
        const lineStart = editorState.getLineStartOffset(lineIndex) + prefix.length
        controller.setSelection(lineStart + wordBounds[0], lineStart + wordBounds[1])
      } else if (e.detail >= 3) {
        const lineStart = editorState.getLineStartOffset(lineIndex)
        const lineEnd = lineStart + line.text.length
        controller.setSelection(lineStart, lineEnd)
      } else {
        controller.setCursorFromGlobalOffset(globalOffset)
        onDragStart?.(globalOffset)
      }
    },
    [getSourceCharIndex, controller, editorState, lineIndex, prefix.length, content, line, onDragStart, onMoveStart],
  )

  return { handleMouseDown, handleContextMenu }
}
