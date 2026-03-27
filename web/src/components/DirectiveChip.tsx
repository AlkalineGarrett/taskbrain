import { useCallback, useState } from 'react'
import type { DirectiveResult } from '@/dsl/directives/DirectiveResult'
import { directiveResultToDisplayString, directiveResultToValue, isComputed } from '@/dsl/directives/DirectiveResult'
import { ViewDirectiveRenderer } from './ViewDirectiveRenderer'
import styles from './DirectiveChip.module.css'

interface DirectiveChipProps {
  sourceText: string
  result: DirectiveResult | null
  /** All directive results (needed for nested directives in views) */
  allResults?: Map<string, DirectiveResult>
  onClick?: () => void
  onButtonClick?: () => void
  onViewNoteSave?: (noteId: string, newContent: string) => Promise<void>
  onDirectiveRefresh?: (key: string, sourceText: string) => void
  /** Called when the gear icon on a view directive is clicked */
  onEditDirective?: () => void
}

export function DirectiveChip({
  sourceText,
  result,
  allResults,
  onClick,
  onButtonClick,
  onViewNoteSave,
  onDirectiveRefresh,
  onEditDirective,
}: DirectiveChipProps) {
  const [buttonState, setButtonState] = useState<'idle' | 'loading' | 'success' | 'error'>('idle')

  const value = result ? directiveResultToValue(result) : null
  const isButton = value?.kind === 'ButtonVal'
  const isView = value?.kind === 'ViewVal'

  const displayText = result
    ? directiveResultToDisplayString(result)
    : sourceText

  const chipClass = (() => {
    if (isButton) {
      if (buttonState === 'loading') return `${styles.chip} ${styles.button} ${styles.buttonLoading}`
      if (buttonState === 'success') return `${styles.chip} ${styles.button} ${styles.buttonSuccess}`
      if (buttonState === 'error') return `${styles.chip} ${styles.button} ${styles.buttonError}`
      return `${styles.chip} ${styles.button}`
    }
    if (result?.error) return `${styles.chip} ${styles.error}`
    if (result?.warning) return `${styles.chip} ${styles.warning}`
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

  if (isView && value?.kind === 'ViewVal') {
    return (
      <ViewDirectiveRenderer
        viewVal={value}
        onNoteSave={onViewNoteSave}
        onEditDirective={onEditDirective}
      />
    )
  }

  return (
    <span className={chipClass} onClick={handleClick} title={sourceText}>
      {isButton && value?.kind === 'ButtonVal' ? `▶ ${value.label}` : displayText}
    </span>
  )
}
