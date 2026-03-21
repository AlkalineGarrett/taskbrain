import { useRef, useEffect, useLayoutEffect, useCallback, useState, type KeyboardEvent, type ChangeEvent, type MouseEvent, type ClipboardEvent, type CompositionEvent } from 'react'
import type { EditorController } from '@/editor/EditorController'
import type { EditorState } from '@/editor/EditorState'
import type { DirectiveResult } from '@/dsl/directives/DirectiveResult'
import { hasCheckbox } from '@/editor/LinePrefixes'
import { hasDirectives, segmentLine } from '@/dsl/directives/DirectiveSegmenter'
import { DirectiveLineContent } from './DirectiveLineContent'
import { getCharOffsetFromPoint, getCharOffsetHidingTextarea, getWordBoundsAt, isOnFirstVisualRow, isOnLastVisualRow, mapDisplayOffsetToSource } from '@/editor/TextMeasure'
import styles from './EditorLine.module.css'

/**
 * Returns true if a key event that falls through to the default case in handleKeyDown
 * should trigger a cursor sync. Navigation keys (Home, End, etc.) need syncing;
 * typing characters do not — they are handled by handleChange, and syncing would
 * overwrite the new content with stale closure data.
 */
export function shouldSyncCursorForKey(key: string): boolean {
  return key.length !== 1
}

interface EditorLineProps {
  lineIndex: number
  controller: EditorController
  editorState: EditorState
  directiveResults?: Map<string, DirectiveResult>
  onDirectiveEdit?: (key: string, newSourceText: string) => void
  onDirectiveRefresh?: (key: string, sourceText: string) => void
  onButtonClick?: (key: string) => void
  onViewNoteClick?: (noteId: string) => void
  onDragStart?: (anchorGlobalOffset: number) => void
  onGutterDragStart?: (lineIndex: number) => void
  onGutterDragUpdate?: (lineIndex: number) => void
  onMoveStart?: () => void
}

export function EditorLine({
  lineIndex,
  controller,
  editorState,
  directiveResults,
  onDirectiveEdit,
  onDirectiveRefresh,
  onButtonClick,
  onViewNoteClick,
  onDragStart,
  onGutterDragStart,
  onGutterDragUpdate,
  onMoveStart,
}: EditorLineProps) {
  const inputRef = useRef<HTMLTextAreaElement>(null)
  const overlayRef = useRef<HTMLDivElement>(null)
  const directiveContentRef = useRef<HTMLDivElement>(null)
  const composingRef = useRef(false)
  const line = editorState.lines[lineIndex]
  if (!line) return null

  const isFocused = lineIndex === editorState.focusedLineIndex
  const prefix = line.prefix
  const content = line.content
  const indentLevel = prefix.match(/^\t*/)?.[0].length ?? 0
  const displayPrefix = prefix.replace(/^\t+/, '')

  // Compute per-line selection range (offsets into line.text)
  const lineSelection = editorState.getLineSelection(lineIndex)
  // Convert to content-relative offsets (excluding prefix)
  const contentSelection = lineSelection
    ? [
        Math.max(0, lineSelection[0] - prefix.length),
        Math.min(content.length, lineSelection[1] - prefix.length),
      ] as [number, number]
    : null
  const hasContentSelection = contentSelection && contentSelection[0] < contentSelection[1]

  // Compute selection highlight rectangles from overlay text nodes
  const [selectionRects, setSelectionRects] = useState<DOMRect[]>([])
  useLayoutEffect(() => {
    const overlay = overlayRef.current
    if (!overlay || !hasContentSelection) {
      if (selectionRects.length > 0) setSelectionRects([])
      return
    }
    const walker = document.createTreeWalker(overlay, NodeFilter.SHOW_TEXT)
    let remaining = contentSelection[0]
    let startNode: Node | null = null
    let startOffset = 0
    let endNode: Node | null = null
    let endOffset = 0
    let accumulated = 0
    let textNode = walker.nextNode()
    while (textNode) {
      const len = textNode.textContent?.length ?? 0
      if (!startNode && accumulated + len > remaining) {
        startNode = textNode
        startOffset = remaining - accumulated
      }
      if (accumulated + len >= contentSelection[1]) {
        endNode = textNode
        endOffset = contentSelection[1] - accumulated
        break
      }
      accumulated += len
      textNode = walker.nextNode()
    }
    if (!startNode || !endNode) {
      if (selectionRects.length > 0) setSelectionRects([])
      return
    }
    const range = document.createRange()
    range.setStart(startNode, startOffset)
    range.setEnd(endNode, endOffset)
    const overlayRect = overlay.getBoundingClientRect()
    const parsedLineHeight = parseFloat(getComputedStyle(overlay).lineHeight)
    const lineHeight = isNaN(parsedLineHeight) ? overlay.getBoundingClientRect().height : parsedLineHeight
    const rects: DOMRect[] = []
    const rawRects = range.getClientRects()
    // Deduplicate rects (Range can return multiple rects per visual row)
    const seenTops: number[] = []
    for (let i = 0; i < rawRects.length; i++) {
      const r = rawRects[i]!
      if (seenTops.some(t => Math.abs(t - r.top) < 2)) continue
      seenTops.push(r.top)
      // Expand to full line-height, centered on the glyph center
      const glyphCenter = r.top + r.height / 2
      const top = glyphCenter - lineHeight / 2
      rects.push(new DOMRect(
        r.left - overlayRect.left,
        top - overlayRect.top,
        r.right - r.left,
        lineHeight,
      ))
    }
    setSelectionRects(rects)
  }, [contentSelection?.[0], contentSelection?.[1], content, hasContentSelection])

  // Auto-resize textarea to fit wrapped content
  useLayoutEffect(() => {
    const textarea = inputRef.current
    if (!textarea) return
    textarea.style.height = '0'
    textarea.style.height = `${textarea.scrollHeight}px`
  }, [content])

  // Re-resize on container width changes (wrapping may change)
  useEffect(() => {
    const textarea = inputRef.current
    if (!textarea?.parentElement) return
    const resize = () => {
      textarea.style.height = '0'
      textarea.style.height = `${textarea.scrollHeight}px`
    }
    const observer = new ResizeObserver(resize)
    observer.observe(textarea.parentElement)
    return () => observer.disconnect()
  }, [])

  // Focus management — sets native textarea selection for focused line
  useEffect(() => {
    if (!isFocused || !inputRef.current) return
    if (document.activeElement !== inputRef.current) {
      inputRef.current.focus({ preventScroll: true })
    }
    if (hasContentSelection) {
      inputRef.current.setSelectionRange(contentSelection[0], contentSelection[1])
    } else {
      const cursor = line.contentCursorPosition
      inputRef.current.setSelectionRange(cursor, cursor)
    }
  }, [isFocused, editorState.stateVersion])

  const handleChange = useCallback(
    (e: ChangeEvent<HTMLTextAreaElement>) => {
      // If there's a cross-line selection, ignore native onChange — typing is handled in handleKeyDown
      if (editorState.hasSelection) {
        // Revert the textarea to the expected content
        e.target.value = content
        return
      }
      // During IME composition, don't commit intermediate text to editor state
      if (composingRef.current) return
      const newContent = e.target.value
      const newCursor = e.target.selectionStart ?? newContent.length
      controller.updateLineContent(lineIndex, newContent, newCursor)
    },
    [controller, editorState, lineIndex, content],
  )

  const handleCompositionStart = useCallback(() => { composingRef.current = true }, [])
  const handleCompositionEnd = useCallback(
    (e: CompositionEvent<HTMLTextAreaElement>) => {
      composingRef.current = false
      // Commit the final composed text
      const newContent = e.currentTarget.value
      const newCursor = e.currentTarget.selectionStart ?? newContent.length
      controller.updateLineContent(lineIndex, newContent, newCursor)
    },
    [controller, lineIndex],
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
  }, [line, content, editorState])

  const handleKeyDown = useCallback(
    (e: KeyboardEvent<HTMLTextAreaElement>) => {
      const input = e.currentTarget
      const cursor = input.selectionStart ?? 0
      const hasSel = editorState.hasSelection

      // Ctrl/Cmd shortcuts
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
          // Let the native paste event fire — handled by onPaste
          return
        }
        // Unhandled Cmd/Ctrl combo (e.g. Cmd+Left/Right) — let browser handle, sync cursor
        syncCursorAfterNativeNav()
        return
      }

      // Typing with cross-line selection: replace selection with typed character
      if (hasSel && e.key.length === 1) {
        e.preventDefault()
        controller.paste(e.key, null)
        return
      }

      // Backspace/Delete with cross-line selection
      if (hasSel && (e.key === 'Backspace' || e.key === 'Delete')) {
        e.preventDefault()
        controller.deleteSelectionWithUndo()
        return
      }

      // Enter with selection: delete selection then split
      if (hasSel && e.key === 'Enter') {
        e.preventDefault()
        controller.deleteSelectionWithUndo()
        controller.splitLine(editorState.focusedLineIndex)
        return
      }

      // Shift+Arrow: extend selection
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
            let target = lineIndex - 1
            while (target >= 0 && controller.hiddenIndices.has(target)) target--
            if (target >= 0) {
              e.preventDefault()
              const prevLine = editorState.lines[target]!
              const prevLineStart = editorState.getLineStartOffset(target)
              const localPos = Math.min(line.cursorPosition, prevLine.text.length)
              editorState.extendSelectionTo(prevLineStart + localPos)
            }
            return
          }
          case 'ArrowDown': {
            let target = lineIndex + 1
            while (target < editorState.lines.length && controller.hiddenIndices.has(target)) target++
            if (target < editorState.lines.length) {
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

      // Arrow keys collapse selection when no shift
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
          if (e.shiftKey) {
            controller.unindent()
          } else {
            controller.indent()
          }
          break

        case 'ArrowLeft': {
          let target = lineIndex - 1
          while (target >= 0 && controller.hiddenIndices.has(target)) target--
          if (cursor === 0 && target >= 0) {
            e.preventDefault()
            controller.setCursor(target, editorState.lines[target]!.text.length)
          } else {
            syncCursorAfterNativeNav()
          }
          break
        }

        case 'ArrowRight': {
          let target = lineIndex + 1
          while (target < editorState.lines.length && controller.hiddenIndices.has(target)) target++
          if (cursor === content.length && target < editorState.lines.length) {
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
            let target = lineIndex - 1
            while (target >= 0 && controller.hiddenIndices.has(target)) target--
            if (target >= 0) {
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
            let target = lineIndex + 1
            while (target < editorState.lines.length && controller.hiddenIndices.has(target)) target++
            if (target < editorState.lines.length) {
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
    [controller, editorState, lineIndex, content, syncCursorAfterNativeNav],
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

  const getCharIndexFromEvent = useCallback(
    (e: MouseEvent): number => {
      const overlay = overlayRef.current
      const textarea = inputRef.current
      if (overlay && textarea) {
        const offset = getCharOffsetHidingTextarea(overlay, textarea, e.clientX, e.clientY)
        if (offset != null) return offset
      }
      // Fallback: clicking past the end of content
      return content.length
    },
    [content.length],
  )

  const handleContextMenu = useCallback(
    (e: MouseEvent<HTMLTextAreaElement>) => {
      // Position cursor at right-click location so native Cut/Copy/Paste operate on the right spot
      const charIdx = getCharIndexFromEvent(e)
      const globalOffset = editorState.getLineStartOffset(lineIndex) + prefix.length + charIdx
      if (!editorState.hasSelection) {
        controller.setCursorFromGlobalOffset(globalOffset)
      }
    },
    [getCharIndexFromEvent, controller, editorState, lineIndex, prefix.length],
  )

  const handleMouseDown = useCallback(
    (e: MouseEvent<HTMLTextAreaElement | HTMLDivElement>) => {
      // Prevent native textarea selection so we can handle cross-line drag
      e.preventDefault()

      const charIdx = getCharIndexFromEvent(e)
      const globalOffset = editorState.getLineStartOffset(lineIndex) + prefix.length + charIdx

      // Click inside existing selection: start drag-move
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
        // Double-click: select word
        const wordBounds = getWordBoundsAt(content, charIdx)
        const lineStart = editorState.getLineStartOffset(lineIndex) + prefix.length
        controller.setSelection(lineStart + wordBounds[0], lineStart + wordBounds[1])
      } else if (e.detail >= 3) {
        // Triple-click: select whole line
        const lineStart = editorState.getLineStartOffset(lineIndex)
        const lineEnd = lineStart + line.text.length
        controller.setSelection(lineStart, lineEnd)
      } else {
        // Single click: place cursor and start drag
        controller.setCursorFromGlobalOffset(globalOffset)
        onDragStart?.(globalOffset)
      }
    },
    [getCharIndexFromEvent, controller, editorState, lineIndex, prefix.length, content, line, onDragStart],
  )

  const handleFocus = useCallback(() => {
    if (lineIndex !== editorState.focusedLineIndex) {
      // Don't clear selection on focus — shift+click and drag need it
      if (!editorState.hasSelection) {
        controller.focusLine(lineIndex)
      }
    }
  }, [controller, editorState, lineIndex])

  /** Click on unfocused directive line: map click position to source offset and place cursor. */
  const handleDirectiveContentMouseDown = useCallback(
    (e: MouseEvent<HTMLDivElement>) => {
      if (editorState.hasSelection) return
      e.preventDefault()
      const el = directiveContentRef.current
      if (!el) {
        controller.focusLine(lineIndex)
        return
      }
      const displayOffset = getCharOffsetFromPoint(el, e.clientX, e.clientY)
      if (displayOffset == null) {
        controller.focusLine(lineIndex)
        return
      }
      const segments = segmentLine(content, lineIndex, directiveResults ?? new Map())
      const sourceOffset = mapDisplayOffsetToSource(displayOffset, segments)
      const globalOffset = editorState.getLineStartOffset(lineIndex) + prefix.length + sourceOffset
      controller.setCursorFromGlobalOffset(globalOffset)
    },
    [controller, editorState, lineIndex, content, prefix.length, directiveResults],
  )

  const handleGutterClick = useCallback(() => {
    if (hasCheckbox(line.text)) {
      controller.toggleCheckboxOnLine(lineIndex)
    } else {
      controller.focusLine(lineIndex)
    }
  }, [controller, line.text, lineIndex])

  // Selection gutter: is this line (partially) selected?
  const isLineSelected = lineSelection != null

  const handleGutterMouseDown = useCallback(
    (e: MouseEvent<HTMLDivElement>) => {
      e.preventDefault()
      onGutterDragStart?.(lineIndex)
    },
    [lineIndex, onGutterDragStart],
  )

  const handleGutterMouseEnter = useCallback(
    (e: MouseEvent<HTMLDivElement>) => {
      // Only extend if primary button is held (drag in progress)
      if (e.buttons === 1) {
        onGutterDragUpdate?.(lineIndex)
      }
    },
    [lineIndex, onGutterDragUpdate],
  )

  // Show directive chips for unfocused lines that contain directives
  const showDirectiveChips = !isFocused && directiveResults && hasDirectives(content)

  return (
    <div
      className={`${styles.line} ${isFocused ? styles.focused : ''}`}
    >
      <div
        className={`${styles.selectionGutter}${isLineSelected ? ` ${styles.selected}` : ''}`}
        onMouseDown={handleGutterMouseDown}
        onMouseEnter={handleGutterMouseEnter}
      />
      <div style={{ paddingLeft: `${0.25 + indentLevel * 0.6}rem`, display: 'flex', flex: 1, minWidth: 0 }}>
      {displayPrefix ? (
        <div className={styles.gutter} onClick={handleGutterClick}>
          <span className={styles.prefix}>{displayPrefix}</span>
        </div>
      ) : null}
      {showDirectiveChips ? (
        <div ref={directiveContentRef} className={styles.directiveContent} onMouseDown={handleDirectiveContentMouseDown}>
          <DirectiveLineContent
            content={content}
            lineIndex={lineIndex}
            results={directiveResults}
            onDirectiveEdit={onDirectiveEdit}
            onDirectiveRefresh={onDirectiveRefresh}
            onButtonClick={onButtonClick}
            onViewNoteClick={onViewNoteClick}
          />
        </div>
      ) : (
        <div className={styles.inputWrapper}>
          <textarea
            ref={inputRef}
            className={`${styles.input}${hasContentSelection ? ` ${styles.grabbable}` : ''}`}
            value={content}
            rows={1}
            onChange={handleChange}
            onKeyDown={handleKeyDown}
            onMouseDown={handleMouseDown}
            onContextMenu={handleContextMenu}
            onPaste={handlePaste}
            onFocus={handleFocus}
            onCompositionStart={handleCompositionStart}
            onCompositionEnd={handleCompositionEnd}
            spellCheck={false}
            autoComplete="off"
          />
          {selectionRects.length > 0 && (
            <div className={styles.highlightLayer} aria-hidden>
              {selectionRects.map((r, i) => (
                <div
                  key={i}
                  className={styles.selectionHighlight}
                  style={{ left: r.x, top: r.y, width: r.width, height: r.height }}
                />
              ))}
            </div>
          )}
          <div ref={overlayRef} className={styles.textOverlay} data-text-overlay aria-hidden>
            {content}
          </div>
        </div>
      )}
      </div>
    </div>
  )
}


