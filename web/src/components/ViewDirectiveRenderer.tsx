import { useState, useCallback, useContext, useRef, useEffect, useMemo } from 'react'
import type { Note } from '@/data/Note'
import type { ViewVal } from '@/dsl/runtime/DslValue'
import type { DirectiveResult } from '@/dsl/directives/DirectiveResult'
import { findDirectives, directiveHash } from '@/dsl/directives/DirectiveFinder'
import { executeDirectiveWithMutations } from '@/dsl/directives/DirectiveExecutor'
import { noteStore } from '@/data/NoteStore'
import { NoteRepository } from '@/data/NoteRepository'
import { db, auth } from '@/firebase/config'
import { InlineEditSession } from '@/editor/InlineEditSession'
import { useActiveEditor } from '@/editor/ActiveEditorContext'
import { useEditorInteractions } from '@/editor/useEditorInteractions'
import { ParentShowCompletedContext } from '@/editor/ParentShowCompletedContext'
import { computeDisplayItemsFromHidden } from '@/editor/CompletedLineUtils'
import { EditorLine } from './EditorLine'
import { CompletedPlaceholderRow } from './CompletedPlaceholderRow'
import { SAVE_ERROR_BANNER, SAVE_ERROR_DISMISS } from '@/strings'
import styles from './ViewDirectiveRenderer.module.css'

const noteRepo = new NoteRepository(db, auth)

interface ViewDirectiveRendererProps {
  viewVal: ViewVal
  /** Click handler for the gear icon overlaid at the top-right of the
   *  embedded view. Used to navigate the user to where they can edit
   *  the directive that produced this view (typically focuses the
   *  parent line). Omit to suppress the gear. */
  onEditDirective?: () => void
  /** Floated to the left of the first embedded note section as a small
   *  marker. Used when the parent directive line is hidden (pure
   *  view-host) so its noteId still has a visible home. Omit when the
   *  directive line is rendered normally. */
  parentNoteIdText?: string
}

/**
 * Renders the embedded notes from a `[view find(...)]` directive as flat
 * siblings of the parent's directive line — direct grid items of the
 * editor's column grid (via `ViewNoteSection`'s subgrid). The container
 * `<div display: contents>` exists only to group sessions for ensureSessions
 * and the per-note directive-results memo; it adds nothing to layout.
 */
export function ViewDirectiveRenderer({ viewVal, onEditDirective, parentNoteIdText }: ViewDirectiveRendererProps) {
  const { notes } = viewVal
  const { sessionManager } = useActiveEditor()

  // Eagerly create sessions for all notes in this view directive. The
  // setter triggers a re-render once sessions are populated; the value
  // is unused. We fire it unconditionally rather than gating on
  // "created" because in StrictMode the first mount's effect creates
  // the sessions but its cleanup sets cancelled=true, and the second
  // mount's effect sees `created=false` (sessions already exist) — the
  // original gate left setReadyTick never firing, so the first render's
  // placeholders stuck.
  const [, setReadyTick] = useState(0)
  useEffect(() => {
    let cancelled = false
    void sessionManager
      .ensureSessions(notes, (id) => noteRepo.loadNoteLinesAwait(id))
      .then(() => { if (!cancelled) setReadyTick((t) => t + 1) })
      .catch((err) => {
        if (!cancelled) console.error('ensureSessions failed:', err)
      })
    return () => { cancelled = true }
  }, [notes, sessionManager])

  // Per-viewed-note directive results.
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

  if (notes.length === 0) return null

  // `display: contents` so each ViewNoteSection becomes a direct child
  // of the editor grid (where this whole tree is rendered as a sibling
  // of the directive line in NoteEditorScreen). The first note
  // section gets the `onEditDirective` callback so its gear button
  // renders at the top-right of the (combined) embedded view area.
  return (
    <div style={{ display: 'contents' }}>
      {notes.map((note, idx) => {
        const session = sessionManager.getSession(note.id)
        if (!session) return null
        return (
          <ViewNoteSection
            key={note.id}
            note={note}
            session={session}
            directiveResults={viewedNoteDirectiveResults.get(note.id)}
            onEditDirective={idx === 0 ? onEditDirective : undefined}
            parentNoteIdText={idx === 0 ? parentNoteIdText : undefined}
          />
        )
      })}
    </div>
  )
}

interface ViewNoteSectionProps {
  note: Note
  session: InlineEditSession
  directiveResults?: Map<string, DirectiveResult>
  /** When provided, this section renders the gear icon (set on only
   *  one section per ViewDirectiveRenderer — typically the first). */
  onEditDirective?: () => void
  /** When provided, this section renders the parent directive line's
   *  noteId as a small marker floated to the left of the section.
   *  Used when the directive line itself isn't rendered. */
  parentNoteIdText?: string
}

const PENDING_VIEW_SAVE_ERROR_KEY = 'pendingViewSaveError'

function ViewNoteSection({
  note,
  session: preCreatedSession,
  directiveResults: viewDirectiveResults,
  onEditDirective,
  parentNoteIdText,
}: ViewNoteSectionProps) {
  const { activateSession, deactivateSession, activeSession, notifyActiveChange, sessionManager, unifiedUndoManager } = useActiveEditor()
  const parentShowCompleted = useContext(ParentShowCompletedContext)
  const [saveError, setSaveError] = useState<string | null>(null)
  const [, setRenderVersion] = useState(0)
  const containerRef = useRef<HTMLDivElement>(null)
  const sessionRef = useRef<InlineEditSession | null>(preCreatedSession)
  sessionRef.current = preCreatedSession
  const isActiveHere = activeSession?.noteId === note.id
  const isActiveHereRef = useRef(isActiveHere)
  isActiveHereRef.current = isActiveHere

  const parentShowCompletedArg = parentShowCompleted ?? undefined

  useEffect(() => {
    preCreatedSession.editorState.onTextChange = () => {
      preCreatedSession.updateHiddenIndices(parentShowCompletedArg)
      setRenderVersion(v => v + 1)
      // Bump the parent's active-session version too, so `NoteEditorScreen`
      // re-renders and `useSaveCoordinator` re-evaluates `anyDirty` —
      // otherwise typing into an embedded line doesn't flip the Save
      // button until focus moves elsewhere.
      notifyActiveChange()
    }
    preCreatedSession.editorState.onSelectionChange = () => {
      setRenderVersion(v => v + 1)
      notifyActiveChange()
    }
  }, [preCreatedSession, notifyActiveChange, parentShowCompletedArg])

  useEffect(() => {
    preCreatedSession.updateHiddenIndices(parentShowCompletedArg)
    setRenderVersion(v => v + 1)
  }, [parentShowCompletedArg, preCreatedSession])

  useEffect(() => {
    sessionManager.syncExternalChanges(note.id, note.content)
    setRenderVersion(v => v + 1)
  }, [note.content, note.id, sessionManager])

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
    null, unifiedUndoManager,
  )

  const handleActivate = useCallback(() => {
    activateSession(preCreatedSession)
  }, [preCreatedSession, activateSession])

  const handleContainerBlur = useCallback((e: React.FocusEvent) => {
    if (!e.relatedTarget) return
    if (containerRef.current?.contains(e.relatedTarget as Node)) return
    if (preCreatedSession.isDirty) {
      const existing = noteStore.getNoteById(preCreatedSession.noteId)
      if (existing) {
        noteStore.updateNoteSilently(preCreatedSession.noteId, {
          ...existing,
          content: preCreatedSession.getText(),
        })
      }
    }
    deactivateSession(preCreatedSession)
  }, [deactivateSession, preCreatedSession])

  const displayLines = preCreatedSession.editorState.lines
  const displayController = preCreatedSession.controller
  const displayState = preCreatedSession.editorState
  const displayItems = computeDisplayItemsFromHidden(
    displayLines.map((l) => l.text),
    displayController.hiddenIndices,
  )

  // The container is a subgrid that spans the editor's column tracks,
  // so its rows place into [note-id]/[gutter]/[content]/[right-margin]
  // exactly like main-editor rows, while still being a real DOM box —
  // the dropTargetRegistry uses its bounding rect to decide which
  // editor a cross-editor drop lands in.
  return (
    <div
      ref={containerRef}
      className={styles.noteSection}
      onMouseDownCapture={handleActivate}
      onFocus={handleActivate}
      onBlur={handleContainerBlur}
    >
      <div ref={dropCursorRef} className={styles.dropCursor} style={{ display: 'none' }} />
      {parentNoteIdText !== undefined && (
        <div className={styles.parentNoteIdMarker}>{parentNoteIdText || ' '}</div>
      )}
      {onEditDirective && (
        <button
          type="button"
          className={styles.editButton}
          onClick={(e) => { e.stopPropagation(); onEditDirective() }}
          title="Edit directive"
          aria-label="Edit directive"
        >
          ⚙
        </button>
      )}
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
          <div
            key={item.realIndex}
            data-view-line-index={item.realIndex}
            data-view-note-id={note.id}
            className={styles.editorRow}
          >
            <EditorLine
              lineIndex={item.realIndex}
              controller={displayController}
              editorState={displayState}
              directiveResults={viewDirectiveResults}
              onDragStart={handleDragStart}
              onGutterDragStart={handleGutterDragStart}
              onGutterDragUpdate={handleGutterDragUpdate}
              onMoveStart={handleMoveStart}
              allowAutoFocus={isActiveHere}
            />
          </div>
        ),
      )}
    </div>
  )
}
