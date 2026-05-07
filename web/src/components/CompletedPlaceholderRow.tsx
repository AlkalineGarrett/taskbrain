import type { MouseEvent } from 'react'
import { COMPLETED_COUNT } from '@/strings'
import styles from './CompletedPlaceholderRow.module.css'

interface CompletedPlaceholderRowProps {
  count: number
  indentLevel: number
  isSelected: boolean
  noteIdText: string
  onGutterDragStart: () => void
  onGutterDragUpdate: () => void
  /** `view` renders inside a `[view find(...)]` directive's embedded note,
   *  matching the alignment of `.viewLineRow`. Default `main`. */
  variant?: 'main' | 'view'
}

export function CompletedPlaceholderRow({ count, indentLevel, isSelected, noteIdText, onGutterDragStart, onGutterDragUpdate, variant = 'main' }: CompletedPlaceholderRowProps) {
  const handleMouseDown = (e: MouseEvent) => {
    e.preventDefault()
    onGutterDragStart()
  }

  const handleMouseEnter = (e: MouseEvent) => {
    if (e.buttons === 1) {
      onGutterDragUpdate()
    }
  }

  const placeholderClass = variant === 'view' ? styles.placeholderView : styles.placeholder
  const noteIdClass = variant === 'view'
    ? `${styles.noteIdCell} ${styles.noteIdCellView}`
    : styles.noteIdCell
  const gutterClass = variant === 'view'
    ? `${styles.placeholderGutter} ${styles.placeholderGutterView}${isSelected ? ` ${styles.selected}` : ''}`
    : `${styles.placeholderGutter}${isSelected ? ` ${styles.selected}` : ''}`

  // Match EditorLine's inner-wrapper paddingLeft so the placeholder's
  // text starts where the collapsed lines would have started:
  //   `0.25rem + indentLevel * 0.6rem`  matches EditorLine's per-indent unit
  //   `var(--view-border-inset, 0px)`   picks up the 7px embedded-view
  //                                      inset (set by `.viewLineContent`)
  //                                      so the embedded placeholder's text
  //                                      doesn't sit 7px LEFT of where the
  //                                      embedded lines render.
  const contentPaddingLeft =
    `calc(${0.25 + indentLevel * 0.6}rem + var(--view-border-inset, 0px))`

  return (
    <div className={placeholderClass}>
      <div className={noteIdClass}>{noteIdText || '\u00A0'}</div>
      <div
        className={gutterClass}
        onMouseDown={handleMouseDown}
        onMouseEnter={handleMouseEnter}
      />
      <div className={`${styles.content}${isSelected ? ` ${styles.selected}` : ''}`} style={{ paddingLeft: contentPaddingLeft }}>
        {COMPLETED_COUNT.replace('%d', String(count))}
      </div>
    </div>
  )
}
