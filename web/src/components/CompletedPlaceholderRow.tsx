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
  const indentPx = indentLevel * 24

  const handleMouseDown = (e: MouseEvent) => {
    e.preventDefault()
    onGutterDragStart()
  }

  const handleMouseEnter = (e: MouseEvent) => {
    if (e.buttons === 1) {
      onGutterDragUpdate()
    }
  }

  return (
    <div className={styles.placeholder}>
      <div className={styles.noteIdCell}>{noteIdText || '\u00A0'}</div>
      <div
        className={`${styles.gutter}${isSelected ? ` ${styles.selected}` : ''}`}
        onMouseDown={handleMouseDown}
        onMouseEnter={handleMouseEnter}
      />
      <div className={`${styles.content}${isSelected ? ` ${styles.selected}` : ''}`} style={{ paddingLeft: `${indentPx + 4}px` }}>
        {COMPLETED_COUNT.replace('%d', String(count))}
      </div>
    </div>
  )
}
