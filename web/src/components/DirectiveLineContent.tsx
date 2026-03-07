import { useState, useCallback } from 'react'
import type { DirectiveResult } from '@/dsl/directives/DirectiveResult'
import { segmentLine } from '@/dsl/directives/DirectiveSegmenter'
import { DirectiveChip } from './DirectiveChip'
import { DirectiveEditRow } from './DirectiveEditRow'
import styles from './DirectiveLineContent.module.css'

interface DirectiveLineContentProps {
  content: string
  lineIndex: number
  results: Map<string, DirectiveResult>
  onDirectiveEdit?: (key: string, newSourceText: string) => void
  onDirectiveRefresh?: (key: string, sourceText: string) => void
  onButtonClick?: (key: string) => void
  onViewNoteClick?: (noteId: string) => void
}

/**
 * Renders a line's content with directives shown as chips.
 * When a chip is clicked, it expands to show a DirectiveEditRow.
 */
export function DirectiveLineContent({
  content,
  lineIndex,
  results,
  onDirectiveEdit,
  onDirectiveRefresh,
  onButtonClick,
  onViewNoteClick,
}: DirectiveLineContentProps) {
  const [editingKey, setEditingKey] = useState<string | null>(null)

  const segments = segmentLine(content, lineIndex, results)

  const handleChipClick = useCallback((key: string) => {
    setEditingKey((prev) => (prev === key ? null : key))
  }, [])

  const handleConfirm = useCallback(
    (key: string, newSourceText: string) => {
      onDirectiveEdit?.(key, newSourceText)
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

  return (
    <div>
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
              allResults={results}
              onClick={() => handleChipClick(segment.key)}
              onButtonClick={onButtonClick ? () => onButtonClick(segment.key) : undefined}
              onViewNoteClick={onViewNoteClick}
              onDirectiveRefresh={onDirectiveRefresh}
            />
          )
        })}
      </div>

      {editingKey && (() => {
        const editSegment = segments.find((s) => s.kind === 'Directive' && s.key === editingKey)
        if (!editSegment || editSegment.kind !== 'Directive') return null
        return (
          <DirectiveEditRow
            initialSourceText={editSegment.sourceText}
            errorMessage={editSegment.result?.error}
            onConfirm={(newText) => handleConfirm(editingKey, newText)}
            onCancel={handleCancel}
            onRefresh={(text) => handleRefresh(editingKey, text)}
          />
        )
      })()}
    </div>
  )
}
