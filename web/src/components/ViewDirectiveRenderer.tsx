import { useState, useCallback, useRef, useEffect } from 'react'
import type { Note } from '@/data/Note'
import type { ViewVal } from '@/dsl/runtime/DslValue'
import { directiveResultToValue } from '@/dsl/directives/DirectiveResult'
import type { DirectiveResult } from '@/dsl/directives/DirectiveResult'
import { EMPTY_VIEW, SAVE, SAVING, SAVE_ERROR_BANNER, SAVE_ERROR_DISMISS } from '@/strings'
import { isViewNoteDirty } from './viewNoteDirty'
import styles from './ViewDirectiveRenderer.module.css'

interface ViewDirectiveRendererProps {
  viewVal: ViewVal
  /** Called to save edited note content; returns a promise that resolves when done */
  onNoteSave?: (noteId: string, newContent: string) => Promise<void>
  /** Called when the gear icon is clicked to switch to directive editing mode */
  onEditDirective?: () => void
}

/**
 * Renders viewed notes inline with separators, supporting nested directive rendering.
 * Clicking a note section makes it editable in place.
 */
export function ViewDirectiveRenderer({
  viewVal,
  onNoteSave,
  onEditDirective,
}: ViewDirectiveRendererProps) {
  const { notes } = viewVal
  const [dirtyNoteId, setDirtyNoteId] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const saveRef = useRef<(() => Promise<void>) | null>(null)

  // Track per-note dirty callbacks so only one save button shows at a time
  const dirtyCallbacks = useRef(new Map<string, (dirty: boolean) => void>())
  const getDirtyCallback = useCallback((noteId: string) => {
    let cb = dirtyCallbacks.current.get(noteId)
    if (!cb) {
      cb = (dirty: boolean) => {
        setDirtyNoteId(prev => dirty ? noteId : (prev === noteId ? null : prev))
      }
      dirtyCallbacks.current.set(noteId, cb)
    }
    return cb
  }, [])

  const handleOverlaySave = useCallback(async () => {
    if (!saveRef.current) return
    setSaving(true)
    try {
      await saveRef.current()
    } finally {
      setSaving(false)
    }
  }, [])

  if (notes.length === 0) {
    return <div className={styles.emptyView}>{EMPTY_VIEW}</div>
  }

  return (
    <div className={styles.viewContainer}>
      {dirtyNoteId && (
        <button
          className={styles.inlineSaveButton}
          onClick={(e) => { e.stopPropagation(); e.preventDefault(); void handleOverlaySave() }}
          onMouseDown={(e) => e.preventDefault()}
          disabled={saving}
        >
          {saving ? SAVING : SAVE}
        </button>
      )}
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
            onSave={onNoteSave ? async (content) => {
              await onNoteSave(note.id, content)
            } : undefined}
            onDirtyChange={getDirtyCallback(note.id)}
            saveRef={dirtyNoteId === note.id ? saveRef : undefined}
          />
        </div>
      ))}
    </div>
  )
}

interface ViewNoteSectionProps {
  note: Note
  onSave?: (newContent: string) => Promise<void>
  onDirtyChange?: (dirty: boolean) => void
  saveRef?: React.MutableRefObject<(() => Promise<void>) | null>
}

const PENDING_VIEW_SAVE_ERROR_KEY = 'pendingViewSaveError'

function ViewNoteSection({
  note,
  onSave,
  onDirtyChange,
  saveRef,
}: ViewNoteSectionProps) {
  const [editContent, setEditContent] = useState(note.content)
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)
  const lastSavedContentRef = useRef<string | null>(null)
  const textareaRef = useRef<HTMLTextAreaElement>(null)

  // Refs for unmount save — must always point to latest values
  const editContentRef = useRef(editContent)
  editContentRef.current = editContent
  const noteContentRef = useRef(note.content)
  noteContentRef.current = note.content
  const onSaveRef = useRef(onSave)
  onSaveRef.current = onSave

  // Check for save errors persisted from a previous unmount
  useEffect(() => {
    try {
      const raw = sessionStorage.getItem(PENDING_VIEW_SAVE_ERROR_KEY)
      if (raw) {
        sessionStorage.removeItem(PENDING_VIEW_SAVE_ERROR_KEY)
        const { noteId } = JSON.parse(raw) as { noteId: string; message: string }
        if (noteId === note.id) {
          setSaveError(SAVE_ERROR_BANNER)
        }
      }
    } catch { /* sessionStorage may be unavailable */ }
  }, [note.id])

  // Sync with external content changes (e.g., after save reloads data)
  useEffect(() => {
    setEditContent(note.content)
    lastSavedContentRef.current = null
  }, [note.content])

  // Auto-size textarea to fit content
  useEffect(() => {
    if (textareaRef.current) {
      resizeTextarea(textareaRef.current)
    }
  }, [editContent])

  // Report dirty state to parent
  const isDirty = isViewNoteDirty(editContent, note.content, lastSavedContentRef.current)
  useEffect(() => {
    onDirtyChange?.(isDirty)
  }, [isDirty, onDirtyChange])

  // Fire-and-forget save on unmount (e.g., navigation away)
  useEffect(() => {
    const noteId = note.id
    return () => {
      const content = editContentRef.current
      const original = noteContentRef.current
      const save = onSaveRef.current
      if (save && isViewNoteDirty(content, original, lastSavedContentRef.current)) {
        void save(content).catch((err) => {
          const msg = err instanceof Error ? err.message : SAVE_ERROR_BANNER
          try { sessionStorage.setItem(PENDING_VIEW_SAVE_ERROR_KEY, JSON.stringify({ noteId, message: msg })) } catch { /* ignore */ }
        })
      }
    }
  }, [note.id])

  const handleSave = useCallback(async () => {
    if (!onSave) return
    if (editContent === note.content) return
    // Mark as saved eagerly so unmount cleanup won't duplicate the save
    lastSavedContentRef.current = editContent
    setSaving(true)
    setSaveError(null)
    try {
      await onSave(editContent)
    } catch (e) {
      const msg = e instanceof Error ? e.message : SAVE_ERROR_BANNER
      setSaveError(msg)
      lastSavedContentRef.current = null // Reset on failure so retry is possible
    } finally {
      setSaving(false)
    }
  }, [editContent, note.content, onSave])

  useEffect(() => {
    if (saveRef) saveRef.current = handleSave
    return () => { if (saveRef) saveRef.current = null }
  }, [saveRef, handleSave])

  const handleBlur = useCallback(() => void handleSave(), [handleSave])

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key === 's') {
        e.preventDefault()
        void handleSave()
      }
    },
    [handleSave],
  )

  return (
    <div className={styles.noteSection}>
      {saveError && (
        <div className={styles.inlineSaveError}>
          <span>{SAVE_ERROR_BANNER}</span>
          <button className={styles.inlineSaveErrorDismiss} onClick={() => setSaveError(null)}>{SAVE_ERROR_DISMISS}</button>
        </div>
      )}
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
        readOnly={!onSave}
        spellCheck={false}
      />
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
