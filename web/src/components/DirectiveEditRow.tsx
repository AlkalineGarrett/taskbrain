import { useState, useRef, useEffect, useCallback, type KeyboardEvent } from 'react'
import styles from './DirectiveEditRow.module.css'

interface DirectiveEditRowProps {
  initialSourceText: string
  errorMessage?: string | null
  onConfirm: (newSourceText: string) => void
  onCancel: () => void
  onRefresh: (sourceText: string) => void
}

export function DirectiveEditRow({
  initialSourceText,
  errorMessage,
  onConfirm,
  onCancel,
  onRefresh,
}: DirectiveEditRowProps) {
  // Strip brackets for editing
  const inner = initialSourceText.slice(1, -1)
  const [text, setText] = useState(inner)
  const inputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    inputRef.current?.focus()
    inputRef.current?.select()
  }, [])

  const handleConfirm = useCallback(() => {
    onConfirm(`[${text}]`)
  }, [text, onConfirm])

  const handleRefresh = useCallback(() => {
    onRefresh(`[${text}]`)
  }, [text, onRefresh])

  const handleKeyDown = useCallback(
    (e: KeyboardEvent<HTMLInputElement>) => {
      if (e.key === 'Enter') {
        e.preventDefault()
        handleConfirm()
      } else if (e.key === 'Escape') {
        e.preventDefault()
        onCancel()
      }
    },
    [handleConfirm, onCancel],
  )

  return (
    <div>
      <div className={styles.editRow}>
        <input
          ref={inputRef}
          className={styles.editInput}
          value={text}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={handleKeyDown}
          placeholder="Directive source..."
        />
        <button className={`${styles.editButton}`} onClick={handleRefresh} title="Refresh">
          ↻
        </button>
        <button className={`${styles.editButton} ${styles.confirmButton}`} onClick={handleConfirm} title="Confirm">
          ✓
        </button>
        <button className={`${styles.editButton} ${styles.cancelButton}`} onClick={onCancel} title="Cancel">
          ✗
        </button>
      </div>
      {errorMessage && <div className={styles.errorMessage}>{errorMessage}</div>}
    </div>
  )
}
