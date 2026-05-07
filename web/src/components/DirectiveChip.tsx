import { useCallback, useState } from 'react'
import type { DirectiveResult } from '@/dsl/directives/DirectiveResult'
import { directiveResultToDisplayString, directiveResultToValue, isComputed } from '@/dsl/directives/DirectiveResult'
import { EMPTY_VIEW } from '@/strings'
import styles from './DirectiveChip.module.css'

interface DirectiveChipProps {
  sourceText: string
  result: DirectiveResult | null
  onClick?: () => void
  onButtonClick?: () => void
}

export function DirectiveChip({
  sourceText,
  result,
  onClick,
  onButtonClick,
}: DirectiveChipProps) {
  const [buttonState, setButtonState] = useState<'idle' | 'loading' | 'success' | 'error'>('idle')

  const value = result ? directiveResultToValue(result) : null
  const isButton = value?.kind === 'ButtonVal'
  const isView = value?.kind === 'ViewVal'

  // View directives render their resolved notes as flat sibling rows
  // BELOW the directive line, so the chip itself shouldn't duplicate
  // that content — show just the directive source text. Other
  // directives still show their computed display value.
  const displayText = isView
    ? sourceText
    : result ? directiveResultToDisplayString(result) : sourceText

  const chipClass = (() => {
    if (isButton) {
      if (buttonState === 'loading') return `${styles.chip} ${styles.button} ${styles.buttonLoading}`
      if (buttonState === 'success') return `${styles.chip} ${styles.button} ${styles.buttonSuccess}`
      if (buttonState === 'error') return `${styles.chip} ${styles.button} ${styles.buttonError}`
      return `${styles.chip} ${styles.button}`
    }
    if (result?.error) return `${styles.chip} ${styles.error}`
    if (result?.warning) return `${styles.chip} ${styles.warning}`
    if (isView) return `${styles.chip} ${styles.view}`
    if (result && isComputed(result)) return `${styles.chip} ${styles.computed}`
    return `${styles.chip} ${styles.pending}`
  })()

  const handleClick = useCallback(() => {
    if (isButton && onButtonClick) {
      setButtonState('loading')
      try {
        onButtonClick()
        setButtonState('success')
        setTimeout(() => setButtonState('idle'), 1500)
      } catch {
        setButtonState('error')
        setTimeout(() => setButtonState('idle'), 1500)
      }
    } else if (onClick) {
      onClick()
    }
  }, [isButton, onButtonClick, onClick])

  if (value?.kind === 'AlarmVal') {
    return <span>⏰</span>
  }

  // View directives — when there are matched notes, the chip shows
  // nothing on the directive line because the resolved notes render
  // as flat sibling rows below. When the view is empty, fall back to
  // a small "(Empty view)" chip so the line isn't completely silent
  // and the user has something to click to open the edit row.
  if (isView && value.kind === 'ViewVal') {
    if (value.notes.length === 0) {
      return (
        <span className={chipClass} onClick={handleClick} title={sourceText}>
          {EMPTY_VIEW}
        </span>
      )
    }
    return null
  }

  return (
    <span className={chipClass} onClick={handleClick} title={sourceText}>
      {isButton && value?.kind === 'ButtonVal' ? `▶ ${value.label}` : displayText}
    </span>
  )
}
