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
}

export function CompletedPlaceholderRow({ count, indentLevel, isSelected, noteIdText, onGutterDragStart, onGutterDragUpdate }: CompletedPlaceholderRowProps) {
  const handleMouseDown = (e: MouseEvent) => {
    e.preventDefault()
    onGutterDragStart()
  }

  const handleMouseEnter = (e: MouseEvent) => {
    if (e.buttons === 1) {
      onGutterDragUpdate()
    }
  }

  // Match EditorLine's inner-wrapper paddingLeft so the placeholder's
  // text starts where the lines it collapses would have started. The
  // `--embedded-content-inset` var is set by `.noteSection` for
  // embedded placeholders; main-editor placeholders see the fallback
  // `0px`.
  const contentPaddingLeft = `calc(${0.25 + indentLevel * 0.6}rem + var(--embedded-content-inset, 0px))`

  return (
    <div className={styles.placeholder}>
      <div className={styles.noteIdCell}>{noteIdText || ' '}</div>
      <div
        className={`${styles.placeholderGutter}${isSelected ? ` ${styles.selected}` : ''}`}
        onMouseDown={handleMouseDown}
        onMouseEnter={handleMouseEnter}
      />
      <div className={`${styles.content}${isSelected ? ` ${styles.selected}` : ''}`} style={{ paddingLeft: contentPaddingLeft }}>
        {COMPLETED_COUNT.replace('%d', String(count))}
      </div>
    </div>
  )
}
