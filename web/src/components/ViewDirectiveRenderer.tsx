import type { Note } from '@/data/Note'
import type { ViewVal } from '@/dsl/runtime/DslValue'
import type { DirectiveResult } from '@/dsl/directives/DirectiveResult'
import { directiveResultToValue } from '@/dsl/directives/DirectiveResult'
import { DirectiveLineContent } from './DirectiveLineContent'
import styles from './ViewDirectiveRenderer.module.css'

interface ViewDirectiveRendererProps {
  viewVal: ViewVal
  /** Directive results for rendering nested directives within viewed notes */
  directiveResults: Map<string, DirectiveResult>
  /** Called when user clicks on a viewed note's content to edit it */
  onNoteClick?: (noteId: string) => void
  /** Called when a directive in a viewed note is refreshed */
  onDirectiveRefresh?: (key: string, sourceText: string) => void
}

/**
 * Renders viewed notes inline with separators, supporting nested directive rendering.
 */
export function ViewDirectiveRenderer({
  viewVal,
  directiveResults,
  onNoteClick,
  onDirectiveRefresh,
}: ViewDirectiveRendererProps) {
  const { notes, renderedContents } = viewVal

  if (notes.length === 0) {
    return <div className={styles.emptyView}>[empty view]</div>
  }

  return (
    <div className={styles.viewContainer}>
      {notes.map((note, noteIndex) => (
        <div key={note.id}>
          {noteIndex > 0 && <hr className={styles.separator} />}
          <ViewNoteSection
            note={note}
            renderedContent={renderedContents?.[noteIndex] ?? null}
            directiveResults={directiveResults}
            onClick={onNoteClick ? () => onNoteClick(note.id) : undefined}
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
  onClick?: () => void
  onDirectiveRefresh?: (key: string, sourceText: string) => void
}

function ViewNoteSection({
  note,
  renderedContent,
  directiveResults,
  onClick,
  onDirectiveRefresh,
}: ViewNoteSectionProps) {
  const content = renderedContent ?? note.content
  const lines = content.split('\n')

  return (
    <div
      className={styles.noteSection}
      onClick={onClick}
      role={onClick ? 'button' : undefined}
      tabIndex={onClick ? 0 : undefined}
    >
      {lines.map((line, lineIndex) => (
        <div key={lineIndex} className={styles.noteLine}>
          <DirectiveLineContent
            content={line}
            lineIndex={lineIndex}
            results={directiveResults}
            onDirectiveRefresh={onDirectiveRefresh}
          />
        </div>
      ))}
    </div>
  )
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
