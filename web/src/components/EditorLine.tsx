import { useRef, useEffect, useCallback, type KeyboardEvent, type ChangeEvent } from 'react'
import type { EditorController } from '@/editor/EditorController'
import type { EditorState } from '@/editor/EditorState'
import type { DirectiveResult } from '@/dsl/directives/DirectiveResult'
import { hasCheckbox } from '@/editor/LinePrefixes'
import { hasDirectives } from '@/dsl/directives/DirectiveSegmenter'
import { DirectiveLineContent } from './DirectiveLineContent'
import styles from './EditorLine.module.css'

interface EditorLineProps {
  lineIndex: number
  controller: EditorController
  editorState: EditorState
  directiveResults?: Map<string, DirectiveResult>
  onDirectiveEdit?: (key: string, newSourceText: string) => void
  onDirectiveRefresh?: (key: string, sourceText: string) => void
  onButtonClick?: (key: string) => void
  onViewNoteClick?: (noteId: string) => void
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
}: EditorLineProps) {
  const inputRef = useRef<HTMLInputElement>(null)
  const line = editorState.lines[lineIndex]
  if (!line) return null

  const isFocused = lineIndex === editorState.focusedLineIndex
  const prefix = line.prefix
  const content = line.content
  const indentLevel = prefix.match(/^\t*/)?.[0].length ?? 0
  const displayPrefix = prefix.replace(/^\t+/, '')

  // Focus management
  useEffect(() => {
    if (isFocused && inputRef.current && document.activeElement !== inputRef.current) {
      inputRef.current.focus()
      const cursor = line.contentCursorPosition
      inputRef.current.setSelectionRange(cursor, cursor)
    }
  }, [isFocused, editorState.stateVersion])

  const handleChange = useCallback(
    (e: ChangeEvent<HTMLInputElement>) => {
      const newContent = e.target.value
      const newCursor = e.target.selectionStart ?? newContent.length
      controller.updateLineContent(lineIndex, newContent, newCursor)
    },
    [controller, lineIndex],
  )

  const handleKeyDown = useCallback(
    (e: KeyboardEvent<HTMLInputElement>) => {
      const input = e.currentTarget
      const cursor = input.selectionStart ?? 0

      // Ctrl/Cmd shortcuts
      if (e.metaKey || e.ctrlKey) {
        if (e.key === 'z') {
          e.preventDefault()
          if (e.shiftKey) {
            controller.redo()
          } else {
            controller.undo()
          }
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

        case 'ArrowUp':
          if (lineIndex > 0) {
            e.preventDefault()
            controller.setCursor(lineIndex - 1, editorState.lines[lineIndex - 1]!.text.length)
          }
          break

        case 'ArrowDown':
          if (lineIndex < editorState.lines.length - 1) {
            e.preventDefault()
            controller.setCursor(lineIndex + 1, 0)
          }
          break
      }
    },
    [controller, editorState, lineIndex, content.length],
  )

  const handleFocus = useCallback(() => {
    if (lineIndex !== editorState.focusedLineIndex) {
      controller.focusLine(lineIndex)
    }
  }, [controller, editorState, lineIndex])

  const handleGutterClick = useCallback(() => {
    if (hasCheckbox(line.text)) {
      controller.toggleCheckboxOnLine(lineIndex)
    } else {
      controller.focusLine(lineIndex)
    }
  }, [controller, line.text, lineIndex])

  // Show directive chips for unfocused lines that contain directives
  const showDirectiveChips = !isFocused && directiveResults && hasDirectives(content)

  return (
    <div className={`${styles.line} ${isFocused ? styles.focused : ''}`}>
      <div
        className={styles.gutter}
        style={{ paddingLeft: `${indentLevel * 1.5}rem` }}
        onClick={handleGutterClick}
      >
        {displayPrefix && (
          <span className={styles.prefix}>{displayPrefix.trim()}</span>
        )}
      </div>
      {showDirectiveChips ? (
        <div className={styles.directiveContent} onClick={handleFocus}>
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
        <input
          ref={inputRef}
          className={styles.input}
          value={content}
          onChange={handleChange}
          onKeyDown={handleKeyDown}
          onFocus={handleFocus}
          spellCheck={false}
          autoComplete="off"
        />
      )}
    </div>
  )
}
