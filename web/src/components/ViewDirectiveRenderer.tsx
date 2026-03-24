import { useState, useCallback, useRef, useEffect } from 'react'
import type { Note } from '@/data/Note'
import type { ViewVal } from '@/dsl/runtime/DslValue'
import type { DirectiveResult } from '@/dsl/directives/DirectiveResult'
import { directiveResultToValue } from '@/dsl/directives/DirectiveResult'
import { EMPTY_VIEW } from '@/strings'
import { DirectiveLineContent } from './DirectiveLineContent'
import styles from './ViewDirectiveRenderer.module.css'

interface ViewDirectiveRendererProps {
  viewVal: ViewVal
  /** Directive results for rendering nested directives within viewed notes */
  directiveResults: Map<string, DirectiveResult>
  /** Called to save edited note content; returns a promise that resolves when done */
  onNoteSave?: (noteId: string, newContent: string) => Promise<void>
  /** Called when a directive in a viewed note is refreshed */
  onDirectiveRefresh?: (key: string, sourceText: string) => void
  /** Called when the gear icon is clicked to switch to directive editing mode */
  onEditDirective?: () => void
}

/**
 * Renders viewed notes inline with separators, supporting nested directive rendering.
 * Clicking a note section makes it editable in place.
 */
export function ViewDirectiveRenderer({
  viewVal,
  directiveResults,
  onNoteSave,
  onDirectiveRefresh,
  onEditDirective,
}: ViewDirectiveRendererProps) {
  const { notes, renderedContents } = viewVal
  const [editingNoteId, setEditingNoteId] = useState<string | null>(null)

  if (notes.length === 0) {
    return <div className={styles.emptyView}>{EMPTY_VIEW}</div>
  }

  return (
    <div className={styles.viewContainer}>
      {onEditDirective && (
        <button
          className={styles.editButton}
          onClick={(e) => { e.stopPropagation(); onEditDirective() }}
          title="Edit directive"
          aria-label="Edit directive"
        >
          ⚙
        </button>
      )}
      {notes.map((note, noteIndex) => (
        <div key={note.id}>
          {noteIndex > 0 && <hr className={styles.separator} />}
          <ViewNoteSection
            note={note}
            renderedContent={renderedContents?.[noteIndex] ?? null}
            directiveResults={directiveResults}
            isEditing={editingNoteId === note.id}
            onStartEditing={() => setEditingNoteId(note.id)}
            onSave={onNoteSave ? async (content) => {
              await onNoteSave(note.id, content)
              setEditingNoteId(null)
            } : undefined}
            onCancel={() => setEditingNoteId(null)}
            onDirectiveRefresh={onDirectiveRefresh}
          />
        </div>
      ))}
    </div>
  )
}

interface ViewNoteSectionProps {
  note: Note
  renderedContent: string | null
  directiveResults: Map<string, DirectiveResult>
  isEditing: boolean
  onStartEditing: () => void
  onSave?: (newContent: string) => Promise<void>
  onCancel: () => void
  onDirectiveRefresh?: (key: string, sourceText: string) => void
}

function ViewNoteSection({
  note,
  renderedContent,
  directiveResults,
  isEditing,
  onStartEditing,
  onSave,
  onCancel,
  onDirectiveRefresh,
}: ViewNoteSectionProps) {
  const displayContent = renderedContent ?? note.content
  const [editContent, setEditContent] = useState(note.content)
  const [saving, setSaving] = useState(false)
  const textareaRef = useRef<HTMLTextAreaElement>(null)

  // Reset edit content when entering edit mode
  useEffect(() => {
    if (isEditing) {
      setEditContent(note.content)
    }
  }, [isEditing, note.content])

  // Auto-focus and auto-size textarea
  useEffect(() => {
    if (isEditing && textareaRef.current) {
      textareaRef.current.focus()
      resizeTextarea(textareaRef.current)
    }
  }, [isEditing])

  const handleSave = useCallback(async () => {
    if (!onSave) return
    if (editContent === note.content) {
      onCancel()
      return
    }
    setSaving(true)
    try {
      await onSave(editContent)
    } catch (e) {
      console.error('Failed to save inline edit:', e)
    } finally {
      setSaving(false)
    }
  }, [editContent, note.content, onSave, onCancel])

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.preventDefault()
        onCancel()
      } else if ((e.metaKey || e.ctrlKey) && e.key === 's') {
        e.preventDefault()
        void handleSave()
      }
    },
    [onCancel, handleSave],
  )

  const handleBlur = useCallback(() => {
    void handleSave()
  }, [handleSave])

  if (isEditing) {
    return (
      <div className={styles.noteSectionEditing}>
        <textarea
          ref={textareaRef}
          className={styles.inlineTextarea}
          value={editContent}
          onChange={(e) => {
            setEditContent(e.target.value)
            resizeTextarea(e.target)
          }}
          onKeyDown={handleKeyDown}
          onBlur={handleBlur}
          disabled={saving}
          spellCheck={false}
        />
      </div>
    )
  }

  const lines = displayContent.split('\n')
  return (
    <div
      className={styles.noteSection}
      onClick={onSave ? onStartEditing : undefined}
      role={onSave ? 'button' : undefined}
      tabIndex={onSave ? 0 : undefined}
    >
      {lines.map((line, lineIndex) => (
        <div key={lineIndex} className={styles.noteLine}>
          <DirectiveLineContent
            content={line}
            lineId={`view:${lineIndex}`}
            results={directiveResults}
            onDirectiveRefresh={onDirectiveRefresh}
          />
        </div>
      ))}
    </div>
  )
}

function resizeTextarea(textarea: HTMLTextAreaElement) {
  textarea.style.height = '0'
  textarea.style.height = `${textarea.scrollHeight}px`
}

/**
 * Helper: extract ViewVal from a DirectiveResult, if it is one.
 */
export function extractViewVal(result: DirectiveResult | null): ViewVal | null {
  if (!result) return null
  const val = directiveResultToValue(result)
  if (val?.kind === 'ViewVal') return val
  return null
}
