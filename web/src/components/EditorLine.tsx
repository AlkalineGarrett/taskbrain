import { useRef, useEffect, useCallback, type MouseEvent } from 'react'
import type { EditorController } from '@/editor/EditorController'
import type { EditorState } from '@/editor/EditorState'
import type { DirectiveResult } from '@/dsl/directives/DirectiveResult'
import { hasCheckbox } from '@/editor/LinePrefixes'
import { hasDirectives, segmentLine, isViewSegment } from '@/dsl/directives/DirectiveSegmenter'
import { directiveResultToValue } from '@/dsl/directives/DirectiveResult'
import { DirectiveLineContent } from './DirectiveLineContent'
import { computeFocusHighlight } from '@/editor/FocusHighlight'
import { useSelectionRects } from '@/hooks/useSelectionRects'
import { useTextareaAutoResize } from '@/hooks/useTextareaAutoResize'
import { useEditorLineKeyboard } from '@/hooks/useEditorLineKeyboard'
import { useEditorLineMouse } from '@/hooks/useEditorLineMouse'
import styles from './EditorLine.module.css'

interface EditorLineProps {
  lineIndex: number
  controller: EditorController
  editorState: EditorState
  directiveResults?: Map<string, DirectiveResult>
  onDirectiveEdit?: (key: string, newSourceText: string) => void
  onDirectiveRefresh?: (key: string, sourceText: string) => void
  onButtonClick?: (key: string) => void
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

  const lineSelection = editorState.getLineSelection(lineIndex)
  const contentSelection = lineSelection
    ? [
        Math.max(0, lineSelection[0] - prefix.length),
        Math.min(content.length, lineSelection[1] - prefix.length),
      ] as [number, number]
    : null
  const hasContentSelection = contentSelection != null && contentSelection[0] < contentSelection[1]

  const selectionRects = useSelectionRects({
    contentSelection,
    content,
    line,
    directiveResults,
    overlayRef,
    directiveContentRef,
  })

  useTextareaAutoResize(inputRef, content)

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

  const { handleKeyDown, handleChange, handleCompositionStart, handleCompositionEnd, handlePaste } =
    useEditorLineKeyboard({
      controller, editorState, lineIndex, line, content, inputRef, overlayRef,
    })

  const { handleMouseDown, handleContextMenu } = useEditorLineMouse({
    controller, editorState, lineIndex, line, content, prefix, directiveResults,
    inputRef, overlayRef, directiveContentRef, onDragStart, onMoveStart,
  })

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

  const lineHasDirectives = directiveResults && hasDirectives(content)
  const viewSegment = lineHasDirectives ? segmentLine(content, line.effectiveId, directiveResults, line.noteIds[0]).find(
    (s) => s.kind === 'Directive' && isViewSegment(s),
  ) : undefined
  const hasViewDirective = viewSegment != null
  // Only hide the parent gutter when the view has content (its own gutter replaces it).
  const viewNotes = hasViewDirective && viewSegment.kind === 'Directive' && viewSegment.result
    ? (directiveResultToValue(viewSegment.result) as { notes: unknown[] } | null)?.notes : undefined
  const hasNonEmptyView = (viewNotes?.length ?? 0) > 0

  const showDirectiveChips = lineHasDirectives && (!isFocused || hasViewDirective)

  const noteIdText = line.noteIds.join(', ')

  return (
    <div className={`${styles.line} ${isFocused ? styles.focused : ''}`}>
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
            lineNoteId={line.noteIds[0]}
            results={directiveResults}
            onDirectiveEdit={onDirectiveEdit}
            onDirectiveRefresh={onDirectiveRefresh}
            onButtonClick={onButtonClick}
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
