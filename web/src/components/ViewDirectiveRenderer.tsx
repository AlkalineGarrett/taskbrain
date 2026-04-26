import { useState, useCallback, useContext, useRef, useEffect, useMemo } from 'react'
import type { Note } from '@/data/Note'
import type { ViewVal } from '@/dsl/runtime/DslValue'
import type { DirectiveResult } from '@/dsl/directives/DirectiveResult'
import { findDirectives, directiveHash } from '@/dsl/directives/DirectiveFinder'
import { executeDirectiveWithMutations } from '@/dsl/directives/DirectiveExecutor'
import { noteStore } from '@/data/NoteStore'
import { InlineEditSession } from '@/editor/InlineEditSession'
import { useActiveEditor } from '@/editor/ActiveEditorContext'
import { useEditorInteractions } from '@/editor/useEditorInteractions'
import { ParentShowCompletedContext } from '@/editor/ParentShowCompletedContext'
import { computeDisplayItemsFromHidden } from '@/editor/CompletedLineUtils'
import { EditorLine } from './EditorLine'
import { CompletedPlaceholderRow } from './CompletedPlaceholderRow'
import { EMPTY_VIEW, SAVE_ERROR_BANNER, SAVE_ERROR_DISMISS } from '@/strings'
import styles from './ViewDirectiveRenderer.module.css'

interface ViewDirectiveRendererProps {
  viewVal: ViewVal
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
  onEditDirective,
}: ViewDirectiveRendererProps) {
  const { notes } = viewVal
  const { sessionManager } = useActiveEditor()

  // Eagerly create sessions for all notes in this view directive
  useMemo(() => sessionManager.ensureSessions(notes), [notes, sessionManager])

  // Compute directive results for each viewed note's content
  const viewedNoteDirectiveResults = useMemo(() => {
    const allNotes = noteStore.getSnapshot()
    const resultsByNoteId = new Map<string, Map<string, DirectiveResult>>()
    for (const note of notes) {
      const lines = note.content.split('\n')
      const results = new Map<string, DirectiveResult>()
      for (const line of lines) {
        for (const directive of findDirectives(line)) {
          const hash = directiveHash(directive.sourceText)
          if (results.has(hash)) continue
          const { result } = executeDirectiveWithMutations(
            directive.sourceText, allNotes, note,
          )
          results.set(hash, result)
        }
      }
      if (results.size > 0) {
        resultsByNoteId.set(note.id, results)
      }
    }
    return resultsByNoteId
  }, [notes])

  const gearButton = onEditDirective ? (
    <button
      className={styles.editButton}
      onClick={(e) => { e.stopPropagation(); onEditDirective() }}
      title="Edit directive"
      aria-label="Edit directive"
    >
      ⚙
    </button>
  ) : null

  if (notes.length === 0) {
    return (
      <div className={styles.viewWrapper}>
        {gearButton}
        <div className={styles.emptyView}>{EMPTY_VIEW}</div>
      </div>
    )
  }

  return (
    <div className={styles.viewWrapper}>
      {gearButton}
      <div className={styles.viewContainer}>
      {notes.map((note, noteIndex) => (
        <div key={note.id}>
          {noteIndex > 0 && <hr className={styles.separator} />}
          <ViewNoteSection
            note={note}
            session={sessionManager.getSession(note.id)!}
            directiveResults={viewedNoteDirectiveResults.get(note.id)}
          />
        </div>
      ))}
      </div>
    </div>
  )
}

interface ViewNoteSectionProps {
  note: Note
  session: InlineEditSession
  directiveResults?: Map<string, DirectiveResult>
}

const PENDING_VIEW_SAVE_ERROR_KEY = 'pendingViewSaveError'

function ViewNoteSection({
  note,
  session: preCreatedSession,
  directiveResults: viewDirectiveResults,
}: ViewNoteSectionProps) {
  const { activateSession, deactivateSession, activeSession, notifyActiveChange, sessionManager } = useActiveEditor()
  const parentShowCompleted = useContext(ParentShowCompletedContext)
  const [saveError, setSaveError] = useState<string | null>(null)
  const [, setRenderVersion] = useState(0)
  const containerRef = useRef<HTMLDivElement>(null)
  const sessionRef = useRef<InlineEditSession | null>(preCreatedSession)
  sessionRef.current = preCreatedSession
  const isActiveHere = activeSession?.noteId === note.id
  const isActiveHereRef = useRef(isActiveHere)
  isActiveHereRef.current = isActiveHere

  // The parent editor's showCompleted overrides the embedded note's own setting.
  // Pass it to updateHiddenIndices so checked subtrees collapse into placeholders
  // when the parent says hide.
  const parentShowCompletedArg = parentShowCompleted ?? undefined

  // Connect change handlers to the session
  useEffect(() => {
    preCreatedSession.editorState.onTextChange = () => {
      preCreatedSession.updateHiddenIndices(parentShowCompletedArg)
      setRenderVersion(v => v + 1)
    }
    preCreatedSession.editorState.onSelectionChange = () => {
      setRenderVersion(v => v + 1)
      notifyActiveChange()
    }
  }, [preCreatedSession, notifyActiveChange, parentShowCompletedArg])

  // Recompute hidden indices when the parent's showCompleted toggles.
  useEffect(() => {
    preCreatedSession.updateHiddenIndices(parentShowCompletedArg)
    setRenderVersion(v => v + 1)
  }, [parentShowCompletedArg, preCreatedSession])

  // Sync external content changes via the centralized session manager
  useEffect(() => {
    sessionManager.syncExternalChanges(note.id, note.content)
    setRenderVersion(v => v + 1)
  }, [note.content, note.id, sessionManager])

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

  // --- Shared editor interactions (gutter selection, drag, move) ---
  const dropCursorRef = useRef<HTMLDivElement>(null)
  const getState = useCallback(() => sessionRef.current?.editorState ?? null, [])
  const getController = useCallback(() => sessionRef.current?.controller ?? null, [])

  const {
    handleDragStart, handleMoveStart,
    handleGutterDragStart, handleGutterDragUpdate,
    selectLineRange, gutterAnchorRef,
  } = useEditorInteractions(
    containerRef, dropCursorRef, getState, getController, 'data-view-line-index',
  )

  // Focus management: activate session on mouseDown (capture phase fires before
  // EditorLine's mouseDown, ensuring the session is active when setCursorFromGlobalOffset
  // runs), on focus (fallback for keyboard/Tab navigation), save+deactivate on blur.
  const handleActivate = useCallback(() => {
    activateSession(preCreatedSession)
  }, [preCreatedSession, activateSession])

  const handleContainerBlur = useCallback((e: React.FocusEvent) => {
    // relatedTarget is null/undefined when focus is lost transiently (e.g., during
    // React re-render or when no element receives focus). Don't deactivate in that
    // case — only deactivate when focus explicitly moved to an element outside.
    if (!e.relatedTarget) return
    if (containerRef.current?.contains(e.relatedTarget as Node)) return
    // On blur: push optimistic update to NoteStore (no Firestore write).
    // This lets other view directives referencing the same note see edits.
    if (preCreatedSession.isDirty) {
      const existing = noteStore.getNoteById(preCreatedSession.noteId)
      if (existing) {
        noteStore.updateNoteSilently(preCreatedSession.noteId, {
          ...existing,
          content: preCreatedSession.getText(),
        })
      }
    }
    // Only deactivate if this section's session is still the active one —
    // a sibling ViewNoteSection may have already activated its own session.
    deactivateSession(preCreatedSession)
  }, [deactivateSession, preCreatedSession])

  const displayLines = preCreatedSession.editorState.lines
  const displayController = preCreatedSession.controller
  const displayState = preCreatedSession.editorState
  const displayItems = computeDisplayItemsFromHidden(
    displayLines.map((l) => l.text),
    displayController.hiddenIndices,
  )

  return (
    <div
      ref={containerRef}
      className={styles.noteSection}
      onMouseDownCapture={handleActivate}
      onFocus={handleActivate}
      onBlur={handleContainerBlur}
    >
      <div ref={dropCursorRef} className={styles.dropCursor} style={{ display: 'none' }} />
      {saveError && (
        <div className={styles.inlineSaveError}>
          <span>{SAVE_ERROR_BANNER}</span>
          <button className={styles.inlineSaveErrorDismiss} onClick={() => setSaveError(null)}>{SAVE_ERROR_DISMISS}</button>
        </div>
      )}
      {displayItems.map((item, i) =>
        item.type === 'placeholder' ? (
          <CompletedPlaceholderRow
            key={`ph-${i}`}
            count={item.count}
            indentLevel={item.indentLevel}
            noteIdText={Array.from({ length: item.count }, (_, j) => displayLines[item.startIndex + j]?.noteIds ?? []).flat().join(', ')}
            isSelected={displayState.hasSelection && (() => {
              const [selFirst, selLast] = displayState.getSelectedLineRange()
              return item.startIndex <= selLast && item.endIndex >= selFirst
            })()}
            onGutterDragStart={() => {
              gutterAnchorRef.current = [item.startIndex, item.endIndex]
              selectLineRange(item.startIndex, item.endIndex)
            }}
            onGutterDragUpdate={() => {
              const [anchorStart, anchorEnd] = gutterAnchorRef.current
              if (anchorStart < 0) return
              selectLineRange(Math.min(anchorStart, item.startIndex), Math.max(anchorEnd, item.endIndex))
            }}
          />
        ) : (
          <div key={item.realIndex} data-view-line-index={item.realIndex} data-view-note-id={note.id} className={styles.viewLineRow}>
            <div className={styles.viewNoteIdCell}>{displayLines[item.realIndex]?.noteIds.join(', ') || '\u00A0'}</div>
            <div className={styles.viewLineContent}>
              <EditorLine
                lineIndex={item.realIndex}
                controller={displayController}
                editorState={displayState}
                directiveResults={viewDirectiveResults}
                onDragStart={handleDragStart}
                onGutterDragStart={handleGutterDragStart}
                onGutterDragUpdate={handleGutterDragUpdate}
                onMoveStart={handleMoveStart}
                hideNoteId
                allowAutoFocus={isActiveHere}
              />
            </div>
          </div>
        ),
      )}
    </div>
  )
}
