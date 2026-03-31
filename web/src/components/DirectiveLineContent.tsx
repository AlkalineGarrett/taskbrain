import { useState, useCallback } from 'react'
import type { DirectiveResult } from '@/dsl/directives/DirectiveResult'
import { isViewResult } from '@/dsl/directives/DirectiveResult'
import { segmentLine } from '@/dsl/directives/DirectiveSegmenter'
import { DirectiveChip } from './DirectiveChip'
import { DirectiveEditRow } from './DirectiveEditRow'
import styles from './DirectiveLineContent.module.css'

interface DirectiveLineContentProps {
  content: string
  lineId: string
  results: Map<string, DirectiveResult>
  onDirectiveEdit?: (oldSourceText: string, newSourceText: string) => void
  onDirectiveRefresh?: (key: string, sourceText: string) => void
  onButtonClick?: (key: string) => void
  onViewNoteSave?: (noteId: string, newContent: string) => Promise<void>
}

/**
 * Renders a line's content with directives shown as chips.
 * When a chip is clicked, it expands to show a DirectiveEditRow.
 */
export function DirectiveLineContent({
  content,
  lineId,
  results,
  onDirectiveEdit,
  onDirectiveRefresh,
  onButtonClick,
  onViewNoteSave,
}: DirectiveLineContentProps) {
  const [editingKey, setEditingKey] = useState<string | null>(null)

  const segments = segmentLine(content, lineId, results)

  const handleChipClick = useCallback((key: string) => {
    setEditingKey((prev) => (prev === key ? null : key))
  }, [])

  const handleConfirm = useCallback(
    (oldSourceText: string, newSourceText: string) => {
      onDirectiveEdit?.(oldSourceText, newSourceText)
      setEditingKey(null)
    },
    [onDirectiveEdit],
  )

  const handleRefresh = useCallback(
    (key: string, sourceText: string) => {
      onDirectiveRefresh?.(key, sourceText)
    },
    [onDirectiveRefresh],
  )

  const handleCancel = useCallback(() => {
    setEditingKey(null)
  }, [])

  // Build the edit row (if editing) and determine whether it's a view directive
  const editSegment = editingKey
    ? segments.find((s) => s.kind === 'Directive' && s.key === editingKey) ?? null
    : null
  const editRow = editSegment?.kind === 'Directive' ? (
    <DirectiveEditRow
      initialSourceText={editSegment.sourceText}
      errorMessage={editSegment.result?.error}
      onConfirm={(newText) => handleConfirm(editSegment.sourceText, newText)}
      onCancel={handleCancel}
      onRefresh={(text) => handleRefresh(editingKey!, text)}
    />
  ) : null
  const isEditingView = editSegment?.kind === 'Directive' && isViewResult(editSegment.result)

  return (
    <div style={{ width: '100%' }}>
      {isEditingView && editRow}
      <div className={styles.lineContent}>
        {segments.map((segment, i) => {
          if (segment.kind === 'Text') {
            return (
              <span key={i} className={styles.textSegment}>
                {segment.content}
              </span>
            )
          }

          return (
            <DirectiveChip
              key={segment.key}
              sourceText={segment.sourceText}
              result={segment.result}
              onClick={() => handleChipClick(segment.key)}
              onButtonClick={onButtonClick ? () => onButtonClick(segment.key) : undefined}
              onViewNoteSave={onViewNoteSave}
              onEditDirective={() => handleChipClick(segment.key)}
            />
          )
        })}
      </div>
      {!isEditingView && editRow}
    </div>
  )
}
