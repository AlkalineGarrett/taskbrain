import { useCallback, useRef, type ChangeEvent, type ClipboardEvent, type CompositionEvent, type KeyboardEvent, type RefObject } from 'react'
import type { EditorController } from '@/editor/EditorController'
import type { EditorState } from '@/editor/EditorState'
import type { LineState } from '@/editor/LineState'
import { isOnFirstVisualRow, isOnLastVisualRow } from '@/editor/TextMeasure'

interface UseEditorLineKeyboardOptions {
  controller: EditorController
  editorState: EditorState
  lineIndex: number
  line: LineState
  content: string
  inputRef: RefObject<HTMLTextAreaElement | null>
  overlayRef: RefObject<HTMLDivElement | null>
}

interface EditorLineKeyboardHandlers {
  handleKeyDown: (e: KeyboardEvent<HTMLTextAreaElement>) => void
  handleChange: (e: ChangeEvent<HTMLTextAreaElement>) => void
  handleCompositionStart: () => void
  handleCompositionEnd: (e: CompositionEvent<HTMLTextAreaElement>) => void
  handlePaste: (e: ClipboardEvent<HTMLTextAreaElement>) => void
}

/**
 * Returns true if a key event that falls through to the default case in
 * handleKeyDown should trigger a cursor sync. Navigation keys (Home, End,
 * etc.) need syncing; typing characters do not — they are handled by
 * handleChange, and syncing would overwrite the new content with stale
 * closure data.
 */
export function shouldSyncCursorForKey(key: string): boolean {
  return key.length !== 1
}

export function useEditorLineKeyboard({
  controller,
  editorState,
  lineIndex,
  line,
  content,
  inputRef,
  overlayRef,
}: UseEditorLineKeyboardOptions): EditorLineKeyboardHandlers {
  const composingRef = useRef(false)
  const handleChange = useCallback(
    (e: ChangeEvent<HTMLTextAreaElement>) => {
      // If there's a cross-line selection, ignore native onChange — typing is handled in handleKeyDown.
      if (editorState.hasSelection) {
        e.target.value = content
        return
      }
      // During IME composition, don't commit intermediate text to editor state.
      if (composingRef.current) return
      const newContent = e.target.value
      const newCursor = e.target.selectionStart ?? newContent.length
      controller.updateLineContent(lineIndex, newContent, newCursor)
    },
    [controller, editorState, lineIndex, content, composingRef],
  )

  const handleCompositionStart = useCallback(() => {
    composingRef.current = true
  }, [composingRef])

  const handleCompositionEnd = useCallback(
    (e: CompositionEvent<HTMLTextAreaElement>) => {
      composingRef.current = false
      const newContent = e.currentTarget.value
      const newCursor = e.currentTarget.selectionStart ?? newContent.length
      controller.updateLineContent(lineIndex, newContent, newCursor)
    },
    [controller, lineIndex, composingRef],
  )

  /** Let the textarea handle intra-line arrow navigation, then sync the cursor position back. */
  const syncCursorAfterNativeNav = useCallback(() => {
    setTimeout(() => {
      const ta = inputRef.current
      if (!ta) return
      const newCursor = ta.selectionStart ?? 0
      line.updateContent(content, newCursor)
      editorState.requestFocusUpdate()
    }, 0)
  }, [line, content, editorState, inputRef])

  const handleKeyDown = useCallback(
    (e: KeyboardEvent<HTMLTextAreaElement>) => {
      const input = e.currentTarget
      const cursor = input.selectionStart ?? 0
      const hasSel = editorState.hasSelection

      if (e.metaKey || e.ctrlKey) {
        if (e.key === 'z') {
          e.preventDefault()
          if (e.shiftKey) controller.redo()
          else controller.undo()
          return
        }
        if (e.key === 'y') {
          e.preventDefault()
          controller.redo()
          return
        }
        if (e.key === 'a') {
          e.preventDefault()
          editorState.selectAll()
          return
        }
        if (e.key === 'c' && hasSel) {
          e.preventDefault()
          controller.copySelection()
          return
        }
        if (e.key === 'x' && hasSel) {
          e.preventDefault()
          controller.cutSelection()
          return
        }
        if (e.key === 'v') {
          // Let the native paste event fire — handled by onPaste.
          return
        }
        if (e.key === 's') {
          // Handled by the global window keydown listener (save).
          // Must not fall through to syncCursorAfterNativeNav, which would
          // queue a stale-content write via setTimeout(0) that fires after
          // sortCompletedToBottom has already updated line text.
          return
        }
        // Unhandled Cmd/Ctrl combo (e.g. Cmd+Left/Right) — let browser handle, sync cursor.
        syncCursorAfterNativeNav()
        return
      }

      if (hasSel && e.key.length === 1) {
        e.preventDefault()
        controller.paste(e.key, null)
        return
      }

      if (hasSel && (e.key === 'Backspace' || e.key === 'Delete')) {
        e.preventDefault()
        controller.deleteSelectionWithUndo()
        return
      }

      if (hasSel && e.key === 'Enter') {
        e.preventDefault()
        controller.deleteSelectionWithUndo()
        controller.splitLine(editorState.focusedLineIndex)
        return
      }

      if (e.shiftKey) {
        const curGlobal = editorState.getCursorGlobalOffset()
        switch (e.key) {
          case 'ArrowLeft': {
            e.preventDefault()
            const target = Math.max(0, curGlobal - 1)
            editorState.extendSelectionTo(target)
            return
          }
          case 'ArrowRight': {
            e.preventDefault()
            const maxOffset = editorState.text.length
            const target = Math.min(maxOffset, curGlobal + 1)
            editorState.extendSelectionTo(target)
            return
          }
          case 'ArrowUp': {
            const target = controller.findVisibleNeighbor(lineIndex - 1, -1)
            if (target !== null) {
              e.preventDefault()
              const prevLine = editorState.lines[target]!
              const prevLineStart = editorState.getLineStartOffset(target)
              const localPos = Math.min(line.cursorPosition, prevLine.text.length)
              editorState.extendSelectionTo(prevLineStart + localPos)
            }
            return
          }
          case 'ArrowDown': {
            const target = controller.findVisibleNeighbor(lineIndex + 1, 1)
            if (target !== null) {
              e.preventDefault()
              const nextLine = editorState.lines[target]!
              const nextLineStart = editorState.getLineStartOffset(target)
              const localPos = Math.min(line.cursorPosition, nextLine.text.length)
              editorState.extendSelectionTo(nextLineStart + localPos)
            }
            return
          }
        }
      }

      if (hasSel && (e.key === 'ArrowLeft' || e.key === 'ArrowRight' ||
                     e.key === 'ArrowUp' || e.key === 'ArrowDown')) {
        e.preventDefault()
        controller.clearSelection()
        return
      }

      switch (e.key) {
        case 'Enter':
          e.preventDefault()
          controller.splitLine(lineIndex)
          break

        case 'Backspace':
          if (cursor === 0 && input.selectionEnd === 0) {
            e.preventDefault()
            controller.deleteBackward(lineIndex)
          }
          break

        case 'Delete':
          if (cursor === content.length) {
            e.preventDefault()
            controller.deleteForward(lineIndex)
          }
          break

        case 'Tab':
          e.preventDefault()
          if (e.shiftKey) controller.unindent()
          else controller.indent()
          break

        case 'ArrowLeft': {
          const target = controller.findVisibleNeighbor(lineIndex - 1, -1)
          if (cursor === 0 && target !== null) {
            e.preventDefault()
            controller.setCursor(target, editorState.lines[target]!.text.length)
          } else {
            syncCursorAfterNativeNav()
          }
          break
        }

        case 'ArrowRight': {
          const target = controller.findVisibleNeighbor(lineIndex + 1, 1)
          if (cursor === content.length && target !== null) {
            e.preventDefault()
            const nextLine = editorState.lines[target]!
            controller.setCursor(target, nextLine.prefix.length)
          } else {
            syncCursorAfterNativeNav()
          }
          break
        }

        case 'ArrowUp': {
          const overlay = overlayRef.current
          if (overlay && !isOnFirstVisualRow(overlay, cursor)) {
            syncCursorAfterNativeNav()
          } else {
            e.preventDefault()
            const target = controller.findVisibleNeighbor(lineIndex - 1, -1)
            if (target !== null) {
              controller.setCursor(target, editorState.lines[target]!.text.length)
            }
          }
          break
        }

        case 'ArrowDown': {
          const overlay = overlayRef.current
          if (overlay && !isOnLastVisualRow(overlay, cursor, content.length)) {
            syncCursorAfterNativeNav()
          } else {
            e.preventDefault()
            const target = controller.findVisibleNeighbor(lineIndex + 1, 1)
            if (target !== null) {
              controller.setCursor(target, 0)
            }
          }
          break
        }

        default:
          if (shouldSyncCursorForKey(e.key)) {
            syncCursorAfterNativeNav()
          }
          break
      }
    },
    [controller, editorState, lineIndex, content, line, syncCursorAfterNativeNav, overlayRef],
  )

  const handlePaste = useCallback(
    (e: ClipboardEvent<HTMLTextAreaElement>) => {
      e.preventDefault()
      const plainText = e.clipboardData.getData('text/plain')
      const html = e.clipboardData.getData('text/html') || null
      controller.paste(plainText, html)
    },
    [controller],
  )

  return { handleKeyDown, handleChange, handleCompositionStart, handleCompositionEnd, handlePaste }
}
