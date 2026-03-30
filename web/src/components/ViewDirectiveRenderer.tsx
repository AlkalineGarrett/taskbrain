import { useState, useCallback, useRef, useEffect, useMemo } from 'react'
import type { Note } from '@/data/Note'
import type { ViewVal } from '@/dsl/runtime/DslValue'
import { directiveResultToValue } from '@/dsl/directives/DirectiveResult'
import type { DirectiveResult } from '@/dsl/directives/DirectiveResult'
import { noteStore } from '@/data/NoteStore'
import { InlineEditSession } from '@/editor/InlineEditSession'
import { useActiveEditor } from '@/editor/ActiveEditorContext'
import { useEditorInteractions } from '@/editor/useEditorInteractions'
import { EditorLine } from './EditorLine'
import { EMPTY_VIEW, SAVE, SAVING, SAVE_ERROR_BANNER, SAVE_ERROR_DISMISS } from '@/strings'
import styles from './ViewDirectiveRenderer.module.css'

interface ViewDirectiveRendererProps {
  viewVal: ViewVal
  /** Called to save edited note content; returns a promise that resolves when done */
  onNoteSave?: (noteId: string, newContent: string) => Promise<void>
  /** Called when the gear icon is clicked to switch to directive editing mode */
  onEditDirective?: () => void
}

/**
 * Renders viewed notes inline with separators.
 * Each note section uses a per-line editor (EditorLine) with its own
 * InlineEditSession for full editing parity with the main editor.
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
    <div className={styles.viewWrapper}>
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
      <div className={styles.viewContainer}>
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
  const { activateSession, deactivateSession, activeSession, notifyActiveChange } = useActiveEditor()
  const [saveError, setSaveError] = useState<string | null>(null)
  const [, setRenderVersion] = useState(0)
  const containerRef = useRef<HTMLDivElement>(null)
  const lastSavedContentRef = useRef<string | null>(null)
  const onSaveRef = useRef(onSave)
  onSaveRef.current = onSave

  // Create or retrieve the session for this note.
  // The session is recreated when note.content changes (e.g., after save reloads data),
  // but only if we're not currently editing this note.
  const sessionRef = useRef<InlineEditSession | null>(null)
  const isActiveHere = activeSession?.noteId === note.id

  // Derive per-line noteIds from NoteStore's tree structure
  const lineNoteIds = useMemo(() => {
    const noteLines = noteStore.getNoteLinesById(note.id)
    if (!noteLines) return undefined
    return noteLines.map(nl => nl.noteId ? [nl.noteId] : [])
  }, [note.id, note.content])

  const getOrCreateSession = useCallback(() => {
    if (!sessionRef.current || sessionRef.current.noteId !== note.id) {
      sessionRef.current = new InlineEditSession(note.id, note.content, lineNoteIds)
    }
    return sessionRef.current
  }, [note.id, note.content, lineNoteIds])

  // Sync with external content changes when not actively editing
  useEffect(() => {
    if (!isActiveHere) {
      sessionRef.current = null // Force recreate on next focus
      lastSavedContentRef.current = null
      setRenderVersion(v => v + 1)
    }
  }, [note.content, isActiveHere])

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

  // Wire up editor state callbacks when active.
  // notifyActiveChange bumps NoteEditorScreen's version so CommandBar re-renders.
  useEffect(() => {
    if (!isActiveHere || !sessionRef.current) return
    const state = sessionRef.current.editorState
    state.onTextChange = () => {
      sessionRef.current?.updateHiddenIndices()
      setRenderVersion(v => v + 1)
    }
    state.onSelectionChange = () => { setRenderVersion(v => v + 1); notifyActiveChange() }
    return () => {
      state.onTextChange = null
      state.onSelectionChange = null
    }
  }, [isActiveHere, notifyActiveChange])

  // Report dirty state to parent
  const session = sessionRef.current
  const isDirty = session ? session.isDirty : false
  useEffect(() => {
    onDirtyChange?.(isDirty)
  }, [isDirty, onDirtyChange])

  const handleSave = useCallback(async () => {
    const s = sessionRef.current
    if (!onSave || !s || !s.isDirty) return
    const content = s.getText()
    lastSavedContentRef.current = content
    setSaveError(null)
    try {
      await onSave(content)
    } catch (e) {
      const msg = e instanceof Error ? e.message : SAVE_ERROR_BANNER
      setSaveError(msg)
      lastSavedContentRef.current = null
    }
  }, [onSave])

  useEffect(() => {
    if (saveRef) saveRef.current = handleSave
    return () => { if (saveRef) saveRef.current = null }
  }, [saveRef, handleSave])

  // Fire-and-forget save on unmount
  useEffect(() => {
    const noteId = note.id
    return () => {
      const s = sessionRef.current
      const save = onSaveRef.current
      if (save && s?.isDirty) {
        void save(s.getText()).catch((err) => {
          const msg = err instanceof Error ? err.message : SAVE_ERROR_BANNER
          try { sessionStorage.setItem(PENDING_VIEW_SAVE_ERROR_KEY, JSON.stringify({ noteId, message: msg })) } catch { /* ignore */ }
        })
      }
    }
  }, [note.id])

  // --- Shared editor interactions (gutter selection, drag, move) ---
  const dropCursorRef = useRef<HTMLDivElement>(null)
  const getState = useCallback(() => sessionRef.current?.editorState ?? null, [])
  const getController = useCallback(() => sessionRef.current?.controller ?? null, [])

  const {
    handleDragStart, handleMoveStart,
    handleGutterDragStart, handleGutterDragUpdate,
  } = useEditorInteractions(
    containerRef, dropCursorRef, getState, getController, 'data-view-line-index',
  )

  // Focus management: activate session on focus, save+deactivate on blur
  const handleContainerFocus = useCallback(() => {
    const s = getOrCreateSession()
    activateSession(s)
  }, [getOrCreateSession, activateSession])

  const handleContainerBlur = useCallback((e: React.FocusEvent) => {
    // relatedTarget is null/undefined when focus is lost transiently (e.g., during
    // React re-render or when no element receives focus). Don't deactivate in that
    // case — only deactivate when focus explicitly moved to an element outside.
    if (!e.relatedTarget) return
    if (containerRef.current?.contains(e.relatedTarget as Node)) return
    const prev = deactivateSession()
    if (prev?.isDirty && onSave) {
      void onSave(prev.getText()).catch(() => { /* handled by unmount fallback */ })
    }
  }, [deactivateSession, onSave])

  const activeOrFallback = (isActiveHere ? session : null) ?? getOrCreateSession()
  const displayLines = activeOrFallback.editorState.lines
  const displayController = activeOrFallback.controller
  const displayState = activeOrFallback.editorState

  return (
    <div
      ref={containerRef}
      className={styles.noteSection}
      onFocus={handleContainerFocus}
      onBlur={handleContainerBlur}
    >
      <div ref={dropCursorRef} className={styles.dropCursor} style={{ display: 'none' }} />
      {saveError && (
        <div className={styles.inlineSaveError}>
          <span>{SAVE_ERROR_BANNER}</span>
          <button className={styles.inlineSaveErrorDismiss} onClick={() => setSaveError(null)}>{SAVE_ERROR_DISMISS}</button>
        </div>
      )}
      {displayLines.map((line, i) => (
        <div key={i} data-view-line-index={i} data-view-note-id={note.id} className={styles.viewLineRow}>
          <div className={styles.viewNoteIdCell}>{line.noteIds.join(', ') || '\u00A0'}</div>
          <div className={styles.viewLineContent}>
            <EditorLine
              lineIndex={i}
              controller={displayController}
              editorState={displayState}
              onDragStart={handleDragStart}
              onGutterDragStart={handleGutterDragStart}
              onGutterDragUpdate={handleGutterDragUpdate}
              onMoveStart={handleMoveStart}
              hideNoteId
            />
          </div>
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
