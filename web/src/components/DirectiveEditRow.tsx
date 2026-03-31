import { useState, useRef, useEffect, useCallback, type KeyboardEvent } from 'react'
import { DIRECTIVE_PLACEHOLDER, REFRESH, CANCEL, CONFIRM } from '@/strings'
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
      } else if ((e.metaKey || e.ctrlKey) && e.key === 'a') {
        // Let the input handle Ctrl+A natively (select all within this input).
        // Stop propagation to prevent the global handler from selecting all editor lines.
        e.stopPropagation()
      }
    },
    [handleConfirm, onCancel],
  )

  return (
    <div onMouseDown={(e) => e.stopPropagation()}>
      <div className={styles.editRow}>
        <input
          ref={inputRef}
          className={styles.editInput}
          value={text}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={handleKeyDown}
          placeholder={DIRECTIVE_PLACEHOLDER}
        />
        <button className={`${styles.editButton}`} onClick={handleRefresh} title={REFRESH}>
          ↻
        </button>
        <button className={`${styles.editButton} ${styles.confirmButton}`} onClick={handleConfirm} title={CONFIRM}>
          ✓
        </button>
        <button className={`${styles.editButton} ${styles.cancelButton}`} onClick={onCancel} title={CANCEL}>
          ✗
        </button>
      </div>
      {errorMessage && <div className={styles.errorMessage}>{errorMessage}</div>}
    </div>
  )
}
