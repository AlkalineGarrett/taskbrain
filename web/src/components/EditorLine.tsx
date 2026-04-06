import { useRef, useEffect, useLayoutEffect, useCallback, useState, type KeyboardEvent, type ChangeEvent, type MouseEvent, type ClipboardEvent, type CompositionEvent } from 'react'
import type { EditorController } from '@/editor/EditorController'
import type { EditorState } from '@/editor/EditorState'
import type { DirectiveResult } from '@/dsl/directives/DirectiveResult'
import { hasCheckbox } from '@/editor/LinePrefixes'
import { hasDirectives, segmentLine, isViewSegment } from '@/dsl/directives/DirectiveSegmenter'
import { directiveResultToValue } from '@/dsl/directives/DirectiveResult'
import { DirectiveLineContent } from './DirectiveLineContent'
import { getCharOffsetFromPoint, getCharOffsetHidingTextarea, getWordBoundsAt, isOnFirstVisualRow, isOnLastVisualRow, mapDisplayOffsetToSource, mapSourceOffsetToDisplay } from '@/editor/TextMeasure'
import { computeFocusHighlight } from '@/editor/FocusHighlight'
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
  onViewNoteSave?: (noteId: string, newContent: string) => Promise<Map<number, string>>
  onDragStart?: (anchorGlobalOffset: number) => void
  onGutterDragStart?: (lineIndex: number, clientY?: number) => void
  onGutterDragUpdate?: (lineIndex: number, clientY?: number) => void
  onMoveStart?: () => void
  /** Hide noteIdCell (used for lines inside view directives that render their own noteId column). */
  hideNoteId?: boolean
  /** Hide selectionGutter (used for lines inside view directives that render their own gutter). */
  hideGutter?: boolean
  /** When false, the focus effect won't call focus() on the textarea. Used by embedded
   *  editors (views) to prevent stealing focus when the view session isn't active. */
  allowAutoFocus?: boolean
  /** When false, the focused-line highlight is hidden even if the line is focused in state.
   *  Defaults to `allowAutoFocus !== false`. Main editor passes this separately so it can
   *  suppress the highlight when a view is active without blocking focus transitions. */
  showFocusHighlight?: boolean
}

export function EditorLine({
  lineIndex,
  controller,
  editorState,
  directiveResults,
  onDirectiveEdit,
  onDirectiveRefresh,
  onButtonClick,
  onViewNoteSave,
  onDragStart,
  onGutterDragStart,
  onGutterDragUpdate,
  onMoveStart,
  hideNoteId,
  hideGutter,
  allowAutoFocus,
  showFocusHighlight: showFocusHighlightProp,
}: EditorLineProps) {
  const inputRef = useRef<HTMLTextAreaElement>(null)
  const overlayRef = useRef<HTMLDivElement>(null)
  const directiveContentRef = useRef<HTMLDivElement>(null)
  const composingRef = useRef(false)
  const line = editorState.lines[lineIndex]
  if (!line) return null

  const isFocusedInState = lineIndex === editorState.focusedLineIndex
  const { highlight: isFocused, autoFocus: autoFocusAllowed } = computeFocusHighlight(
    isFocusedInState, allowAutoFocus, showFocusHighlightProp,
  )
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

  // Compute selection highlight rectangles from text nodes in either overlay or directive content
  const [selectionRects, setSelectionRects] = useState<DOMRect[]>([])
  useLayoutEffect(() => {
    if (!hasContentSelection) {
      if (selectionRects.length > 0) setSelectionRects([])
      return
    }

    // Use overlay for regular lines, directive content for chip lines
    const el = overlayRef.current ?? directiveContentRef.current
    if (!el) {
      if (selectionRects.length > 0) setSelectionRects([])
      return
    }

    // Convert source-space selection to display-space for directive lines
    let selStart = contentSelection[0]
    let selEnd = contentSelection[1]
    if (!overlayRef.current && directiveContentRef.current) {
      const segments = segmentLine(content, line.effectiveId, directiveResults ?? new Map())
      selStart = mapSourceOffsetToDisplay(selStart, segments)
      selEnd = mapSourceOffsetToDisplay(selEnd, segments)
    }

    // Walk text nodes to find the range
    const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT)
    let startNode: Node | null = null
    let startOffset = 0
    let endNode: Node | null = null
    let endOffset = 0
    let accumulated = 0
    let textNode = walker.nextNode()
    while (textNode) {
      const len = textNode.textContent?.length ?? 0
      if (!startNode && accumulated + len > selStart) {
        startNode = textNode
        startOffset = selStart - accumulated
      }
      if (accumulated + len >= selEnd) {
        endNode = textNode
        endOffset = selEnd - accumulated
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
    const elRect = el.getBoundingClientRect()
    const parsedLineHeight = parseFloat(getComputedStyle(el).lineHeight)
    const lineHeight = isNaN(parsedLineHeight) ? el.getBoundingClientRect().height : parsedLineHeight
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
        r.left - elRect.left,
        top - elRect.top,
        r.right - r.left,
        lineHeight,
      ))
    }
    setSelectionRects(rects)
  }, [contentSelection?.[0], contentSelection?.[1], content, hasContentSelection, lineIndex, directiveResults])

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

  // Focus management — sets native textarea selection for focused line.
  // Uses isFocusedInState so the effect fires even when the visual highlight is
  // suppressed (e.g., clicking a main editor line while a view is active —
  // the highlight won't show until deactivation, but focus must transfer immediately).
  // allowAutoFocus===false (view editors when inactive) prevents stealing focus.
  useEffect(() => {
    if (!isFocusedInState || !inputRef.current) return
    if (!autoFocusAllowed) return
    if (document.activeElement !== inputRef.current) {
      inputRef.current.focus({ preventScroll: true })
    }
    if (hasContentSelection) {
      inputRef.current.setSelectionRange(contentSelection[0], contentSelection[1])
    } else {
      const cursor = line.contentCursorPosition
      inputRef.current.setSelectionRange(cursor, cursor)
    }
  }, [isFocusedInState, editorState.stateVersion, autoFocusAllowed])

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
        if (e.key === 's') {
          // Handled by the global window keydown listener (save).
          // Must not fall through to syncCursorAfterNativeNav, which would
          // queue a stale-content write via setTimeout(0) that fires after
          // sortCompletedToBottom has already updated line text.
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

  /** Compute the source-space char index from a mouse event, using whichever element is rendered. */
  const getSourceCharIndex = useCallback(
    (e: MouseEvent): number => {
      // Try textarea overlay first (regular lines)
      const overlay = overlayRef.current
      const textarea = inputRef.current
      if (overlay && textarea) {
        const offset = getCharOffsetHidingTextarea(overlay, textarea, e.clientX, e.clientY)
        if (offset != null) return offset
      }
      // Try directive content (chip lines) — map display offset to source offset
      const directiveEl = directiveContentRef.current
      if (directiveEl) {
        const displayOffset = getCharOffsetFromPoint(directiveEl, e.clientX, e.clientY)
        if (displayOffset != null) {
          const segments = segmentLine(content, line.effectiveId, directiveResults ?? new Map())
          return mapDisplayOffsetToSource(displayOffset, segments)
        }
      }
      return content.length
    },
    [content, lineIndex, directiveResults],
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
    [getSourceCharIndex, controller, editorState, lineIndex, prefix.length, content, line, onDragStart, onMoveStart],
  )

  const handleFocus = useCallback(() => {
    if (lineIndex !== editorState.focusedLineIndex) {
      if (!editorState.hasSelection) {
        controller.focusLine(lineIndex)
      }
    }
  }, [controller, editorState, lineIndex])

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
      onGutterDragStart?.(lineIndex, e.clientY)
    },
    [lineIndex, onGutterDragStart],
  )

  const handleGutterMouseEnter = useCallback(
    (e: MouseEvent<HTMLDivElement>) => {
      if (e.buttons === 1) {
        onGutterDragUpdate?.(lineIndex, e.clientY)
      }
    },
    [lineIndex, onGutterDragUpdate],
  )

  // Check if any directive on this line is a view — views stay rendered even when focused
  const lineHasDirectives = directiveResults && hasDirectives(content)
  const viewSegment = lineHasDirectives ? segmentLine(content, line.effectiveId, directiveResults).find(
    (s) => s.kind === 'Directive' && isViewSegment(s),
  ) : undefined
  const hasViewDirective = viewSegment != null
  // Only hide the parent gutter when the view has content (its own gutter replaces it).
  const viewNotes = hasViewDirective && viewSegment.kind === 'Directive'
    ? (directiveResultToValue(viewSegment.result!) as { notes: unknown[] } | null)?.notes : undefined
  const hasNonEmptyView = (viewNotes?.length ?? 0) > 0

  // Show directive chips for unfocused lines, or always for lines with view directives
  const showDirectiveChips = lineHasDirectives && (!isFocused || hasViewDirective)

  const noteIdText = line.noteIds.join(', ')

  return (
    <div
      className={`${styles.line} ${isFocused ? styles.focused : ''}`}
    >
      {!hideNoteId && !hideGutter && <div className={`${styles.noteIdCell}${hasNonEmptyView ? ` ${styles.noteIdCellNarrow}` : ''}`}>{noteIdText || '\u00A0'}</div>}
      {!hideGutter && <div
        className={`${styles.selectionGutter}${isLineSelected ? ` ${styles.selected}` : ''}${hasNonEmptyView ? ` ${styles.selectionGutterHidden}` : ''}`}
        onMouseDown={handleGutterMouseDown}
        onMouseEnter={handleGutterMouseEnter}
      />}
      <div style={{ paddingLeft: `calc(${0.25 + indentLevel * 0.6}rem + var(--view-border-inset, 0px))`, display: 'flex', flex: 1, minWidth: 0, ['--view-gutter-offset' as string]: `calc(${0.25 + indentLevel * 0.6}rem + 7px)` }}>
      {displayPrefix ? (
        <div className={styles.gutter} onClick={handleGutterClick}>
          <span className={styles.prefix}>{displayPrefix}</span>
        </div>
      ) : null}
      {showDirectiveChips ? (
        <div ref={directiveContentRef} className={`${styles.directiveContent}${hasContentSelection ? ` ${styles.grabbable}` : ''}`} data-directive-content onMouseDown={hasNonEmptyView ? undefined : handleMouseDown} onContextMenu={hasNonEmptyView ? undefined : handleContextMenu}>
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
          <DirectiveLineContent
            content={content}
            lineId={line.effectiveId}
            results={directiveResults}
            onDirectiveEdit={onDirectiveEdit}
            onDirectiveRefresh={onDirectiveRefresh}
            onButtonClick={onButtonClick}
            onViewNoteSave={onViewNoteSave}
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


