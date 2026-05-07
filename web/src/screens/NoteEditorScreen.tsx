import { useParams } from 'react-router-dom'
import { Fragment, useEffect, useState, useMemo } from 'react'
import type { EditorState } from '@/editor/EditorState'
import type { DirectiveResult } from '@/dsl/directives/DirectiveResult'
import { noteStore } from '@/data/NoteStore'
import { isLive, NoteState } from '@/data/NoteState'
import { useAllNotes, useNoteStoreError } from '@/hooks/useNoteStore'
import { NoteRepositoryOperations } from '@/dsl/runtime/NoteRepositoryOperations'
import { useEditor } from '@/hooks/useEditor'
import { useDirectives } from '@/hooks/useDirectives'
import { useTabSync } from '@/hooks/useTabSync'
import { useDirectiveMutations } from '@/hooks/useDirectiveMutations'
import { useCompletedLineDisplay } from '@/hooks/useCompletedLineDisplay'
import { useActiveEditorSession } from '@/hooks/useActiveEditorSession'
import { useUnifiedUndo } from '@/hooks/useUnifiedUndo'
import { useSaveCoordinator } from '@/hooks/useSaveCoordinator'
import { useGlobalKeyboardShortcuts } from '@/hooks/useGlobalKeyboardShortcuts'
import { useGutterRouting } from '@/hooks/useGutterRouting'
import { useNoteDeletion } from '@/hooks/useNoteDeletion'
import { CommandBar } from '@/components/CommandBar'
import { ConfirmDialog } from '@/components/ConfirmDialog'
import { EditorLine } from '@/components/EditorLine'
import { CompletedPlaceholderRow } from '@/components/CompletedPlaceholderRow'
import { RecentTabsBar } from '@/components/RecentTabsBar'
import { extractDisplayText } from '@/data/TabState'
import { LOADING_NOTE, DELETE_NOTE, DELETE_NOTE_CONFIRM_TITLE, DELETE_NOTE_CONFIRM_MESSAGE, SAVE_ERROR_BANNER, SAVE_ERROR_DISMISS, SYNC_ERROR_BANNER } from '@/strings'
import { db, auth } from '@/firebase/config'
import { findDirectives } from '@/dsl/directives/DirectiveFinder'
import { segmentLine, isViewSegment } from '@/dsl/directives/DirectiveSegmenter'
import { directiveResultToValue } from '@/dsl/directives/DirectiveResult'
import type { ViewVal } from '@/dsl/runtime/DslValue'
import { ViewDirectiveRenderer } from '@/components/ViewDirectiveRenderer'
import { ActiveEditorContext } from '@/editor/ActiveEditorContext'
import { UndoActionsContext } from '@/editor/UndoActionsContext'
import { ParentShowCompletedContext } from '@/editor/ParentShowCompletedContext'
import { DirectiveEditingContext } from '@/editor/DirectiveEditingContext'
import { InlineSessionManager } from '@/editor/InlineSessionManager'
import { UnifiedUndoManager } from '@/editor/UnifiedUndoManager'
import styles from './NoteEditorScreen.module.css'

/** Hard cap on the on-tab-switch scroll-reset window. After this we trust
 *  layout has settled and stop fighting any further scrollTop changes,
 *  even if the user hasn't yet signalled scroll intent. */
const SCROLL_RESET_RELEASE_MS = 5000

interface LineViewState {
  /** The resolved ViewVals (paired with segment keys) for view directives
   *  on this line that have at least one matched note — used both to
   *  render flat sibling content rows and to wire the gear's onClick
   *  through to `DirectiveEditingContext`. */
  viewEntries: Array<{ viewVal: ViewVal; segmentKey: string }>
  /** True when the line's only meaningful segments are non-empty view
   *  directives. Such lines hide their own editor row — the view
   *  content occupies the slot and the line's noteId floats off to
   *  the left as a marker. Lines that mix a view directive with other
   *  content (text, prefix, another directive) render normally. */
  isPureViewHost: boolean
}

/** Single pass over `segmentLine` that yields everything the render
 *  loop needs to decide whether to draw the directive line as a row
 *  or hide it in favor of the embedded view's first row. */
function lineViewState(
  lineIndex: number,
  editorState: EditorState,
  directiveResults: Map<string, DirectiveResult>,
): LineViewState {
  const line = editorState.lines[lineIndex]
  if (!line) return { viewEntries: [], isPureViewHost: false }
  const segments = segmentLine(line.text, line.effectiveId, directiveResults, line.noteIds[0])
  const viewEntries: Array<{ viewVal: ViewVal; segmentKey: string }> = []
  let pure = true
  let sawViewDirective = false
  for (const s of segments) {
    if (s.kind === 'Text') {
      if (s.content.trim() !== '') pure = false
      continue
    }
    // Directive segment.
    if (!isViewSegment(s) || !s.result) {
      pure = false
      continue
    }
    const value = directiveResultToValue(s.result)
    if (value?.kind === 'ViewVal' && value.notes.length > 0) {
      viewEntries.push({ viewVal: value, segmentKey: s.key })
      sawViewDirective = true
    } else {
      pure = false
    }
  }
  return { viewEntries, isPureViewHost: pure && sawViewDirective }
}

export function NoteEditorScreen() {
  const { noteId: urlNoteId } = useParams<{ noteId: string }>()
  const { controller, editorState, loading, loadedNoteId, showLoading, error, saveError, clearSaveError, setSaveError, dirty, prepareMainSaveItem, showCompleted, toggleShowCompleted, needsFix, notesNeedingFix } = useEditor(urlNoteId)
  // Use loadedNoteId for all rendering — keeps showing the old note until
  // the new one is fully loaded, preventing transition flashes.
  const noteId = loadedNoteId ?? urlNoteId

  const [sessionManager] = useState(() => new InlineSessionManager())
  const [unifiedUndoManager] = useState(() => new UnifiedUndoManager())

  const allNotes = useAllNotes()
  const syncError = useNoteStoreError()
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false)
  const [directiveEditingKey, setDirectiveEditingKey] = useState<string | null>(null)
  const directiveEditingCtx = useMemo(
    () => ({ editingKey: directiveEditingKey, setEditingKey: setDirectiveEditingKey }),
    [directiveEditingKey],
  )

  // Start Firestore collection listener for real-time note sync
  useEffect(() => { noteStore.start(db, auth) }, [])

  const currentNote = useMemo(
    () => allNotes.find((n) => n.id === noteId) ?? null,
    [allNotes, noteId],
  )

  const noteOperations = useMemo(() => {
    const userId = auth.currentUser?.uid
    if (!userId) return undefined
    return new NoteRepositoryOperations(db, userId)
  }, [])

  const activeNotes = useMemo(
    () => allNotes.filter((n) => isLive(n.state)),
    [allNotes],
  )

  const { results: directiveResults, mutations: directiveMutations, pendingOnceCacheEntries, invalidateAndRecompute, refreshDirective } =
    useDirectives({
      noteId: loadedNoteId,
      editorState,
      notes: activeNotes,
      currentNote,
      noteOperations,
    })

  useDirectiveMutations(noteId, editorState, controller, directiveMutations)

  useTabSync(noteId, loading, editorState.lines[0]?.text ?? '')

  const {
    activeSession,
    activeSessionRef,
    activeController,
    activateSession,
    deactivateSession,
    activeEditorCtx,
  } = useActiveEditorSession(controller, editorState, sessionManager, unifiedUndoManager)

  const { handleUndo, handleRedo } = useUnifiedUndo({
    unifiedUndoManager,
    controller,
    sessionManager,
    activeSessionRef,
    activateSession,
    deactivateSession,
    invalidateAndRecompute,
  })

  const undoActionsValue = useMemo(() => ({ handleUndo, handleRedo }), [handleUndo, handleRedo])

  const { saveAll, saveStatus, anyDirty } = useSaveCoordinator({
    noteId,
    prepareMainSaveItem,
    setSaveError,
    dirty,
    needsFix,
    sessionManager,
    invalidateAndRecompute,
    pendingOnceCacheEntries,
  })

  useGlobalKeyboardShortcuts({
    saveAll,
    controller,
    editorState,
    activeSessionRef,
    handleUndo,
    handleRedo,
  })

  const {
    editorRef,
    dropCursorRef,
    handleDragStart,
    handleMoveStart,
    handleGutterDragStart,
    handleGutterDragUpdate,
    selectLineRange,
    gutterAnchorRef,
  } = useGutterRouting({
    controller,
    editorState,
    activeSessionRef,
    activateSession,
    deactivateSession,
    unifiedUndoManager,
    moveDraggingClassName: styles.moveDragging,
  })

  // Reset the scroll container to the top when the loaded note changes.
  // Embedded notes from `[view find(...)]` directives render across many
  // frames as their directive results compute (some via Firestore), and
  // their layout shifts (or focus/textarea-resize side-effects) can pull
  // scrollTop down between renders.
  //
  // Strategy: snap scrollTop back to 0 whenever the container fires a
  // `scroll` event from anything other than user input. Listening to
  // `scroll` is essentially free when nothing is scrolling — far better
  // than per-frame polling. Stop on first wheel/touch/keydown so the
  // user can scroll freely once async rendering has settled. The cap
  // bounds the worst case if a user neither scrolls nor types.
  useEffect(() => {
    const el = editorRef.current
    if (!el) return
    el.scrollTop = 0

    let releaseToUser = false
    const release = () => { releaseToUser = true }
    const onScroll = () => {
      if (releaseToUser) return
      if (el.scrollTop !== 0) el.scrollTop = 0
    }
    el.addEventListener('scroll', onScroll, { passive: true })
    el.addEventListener('wheel', release, { passive: true })
    el.addEventListener('touchstart', release, { passive: true })
    el.addEventListener('keydown', release, { passive: true })
    const cap = window.setTimeout(release, SCROLL_RESET_RELEASE_MS)

    return () => {
      release()
      window.clearTimeout(cap)
      el.removeEventListener('scroll', onScroll)
      el.removeEventListener('wheel', release)
      el.removeEventListener('touchstart', release)
      el.removeEventListener('keydown', release)
    }
  }, [loadedNoteId, editorRef])

  const { handleDeleteNote, handleRestoreNote } = useNoteDeletion(noteId)

  const { displayItems, fadedIndices } = useCompletedLineDisplay(editorState, controller, showCompleted)

  const handleDirectiveEdit = (oldSourceText: string, newSourceText: string) => {
    for (let lineIndex = 0; lineIndex < editorState.lines.length; lineIndex++) {
      const lineContent = editorState.lines[lineIndex]?.text ?? ''
      const directive = findDirectives(lineContent).find((d) => d.sourceText === oldSourceText)
      if (directive) {
        controller.confirmDirectiveEdit(lineIndex, directive.startOffset, directive.endOffset, newSourceText)
        invalidateAndRecompute()
        return
      }
    }
  }

  if (showLoading) {
    return <div className="loading">{LOADING_NOTE}</div>
  }

  if (error) {
    return (
      <div style={{ maxWidth: 800, margin: '0 auto', padding: '1rem' }}>
        <p style={{ color: 'var(--color-error-hover)' }}>{error}</p>
      </div>
    )
  }

  return (
    <div className={styles.container}>
      <RecentTabsBar
        notesNeedingFix={notesNeedingFix}
        currentTab={
          loadedNoteId
            ? {
                noteId: loadedNoteId,
                displayText: extractDisplayText(editorState.lines[0]?.text ?? ''),
              }
            : null
        }
      />

      <CommandBar
        controller={activeController}
        onSave={saveAll}
        onUndo={handleUndo}
        onRedo={handleRedo}
        canUndo={unifiedUndoManager.canUndo}
        canRedo={unifiedUndoManager.canRedo}
        onDelete={() => setShowDeleteConfirm(true)}
        onRestore={() => void handleRestoreNote()}
        isDeleted={currentNote?.state === NoteState.DELETED}
        anyDirty={anyDirty}
        saveStatus={saveStatus}
        needsFix={needsFix}
        showCompleted={showCompleted}
        onToggleShowCompleted={toggleShowCompleted}
      />

      <ConfirmDialog
        open={showDeleteConfirm}
        title={DELETE_NOTE_CONFIRM_TITLE}
        message={DELETE_NOTE_CONFIRM_MESSAGE}
        confirmLabel={DELETE_NOTE}
        danger
        onConfirm={() => { setShowDeleteConfirm(false); void handleDeleteNote() }}
        onCancel={() => setShowDeleteConfirm(false)}
      />

      {saveError && (
        <div className={styles.saveErrorBanner}>
          <span>{SAVE_ERROR_BANNER}: {saveError}</span>
          <button className={styles.saveErrorDismiss} onClick={clearSaveError}>{SAVE_ERROR_DISMISS}</button>
        </div>
      )}

      {syncError && (
        <div className={styles.saveErrorBanner}>
          <span>{SYNC_ERROR_BANNER}: {syncError}</span>
          <button className={styles.saveErrorDismiss} onClick={() => noteStore.clearError()}>{SAVE_ERROR_DISMISS}</button>
        </div>
      )}

      <UndoActionsContext.Provider value={undoActionsValue}>
      <ActiveEditorContext.Provider value={activeEditorCtx}>
      <ParentShowCompletedContext.Provider value={showCompleted}>
      <DirectiveEditingContext.Provider value={directiveEditingCtx}>
      <div className={styles.editorArea}>
        <div
          ref={editorRef}
          className={`${styles.editor} ${currentNote?.state === NoteState.DELETED ? styles.deletedEditor : ''}`}
        >
        <div ref={dropCursorRef} className={styles.dropCursor} style={{ display: 'none' }} />
        {displayItems.map((item, i) =>
          item.type === 'placeholder' ? (
            <CompletedPlaceholderRow
              key={`ph-${i}`}
              count={item.count}
              indentLevel={item.indentLevel}
              noteIdText={Array.from({ length: item.count }, (_, j) => editorState.lines[item.startIndex + j]?.noteIds ?? []).flat().join(', ')}
              isSelected={editorState.hasSelection && (() => {
                const [selFirst, selLast] = editorState.getSelectedLineRange()
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
          ) : (() => {
            const { viewEntries, isPureViewHost } = lineViewState(item.realIndex, editorState, directiveResults)
            const isEditingThisLine = isPureViewHost
              && directiveEditingKey != null
              && viewEntries.some((v) => v.segmentKey === directiveEditingKey)
            // Pure-view-host lines hide their own row to give the slot to
            // the embedded view content — except when the user has the
            // directive open for inline editing (clicked the gear): then
            // we re-render the row so `DirectiveLineContent` can mount
            // its `DirectiveEditRow`. The row pushes the view content
            // down, exactly mirroring how non-host directive lines
            // behave when their chip is clicked.
            const hideRow = isPureViewHost && !isEditingThisLine
            const parentNoteIdText = editorState.lines[item.realIndex]?.noteIds.join(', ') ?? ''
            return (
              <Fragment key={item.realIndex}>
                {!hideRow && (
                  <div data-line-index={item.realIndex} className={styles.editorRow} style={fadedIndices.has(item.realIndex) ? { opacity: 0.4 } : undefined}>
                    <EditorLine
                      lineIndex={item.realIndex}
                      controller={controller}
                      editorState={editorState}
                      directiveResults={directiveResults}
                      onDirectiveEdit={handleDirectiveEdit}
                      onDirectiveRefresh={refreshDirective}
                      onDragStart={handleDragStart}
                      onGutterDragStart={handleGutterDragStart}
                      onGutterDragUpdate={handleGutterDragUpdate}
                      onMoveStart={handleMoveStart}
                      showFocusHighlight={!activeSession}
                    />
                  </div>
                )}
                {viewEntries.map(({ viewVal, segmentKey }, idx) => (
                  <ViewDirectiveRenderer
                    key={`view-${item.realIndex}-${segmentKey}`}
                    viewVal={viewVal}
                    /* The parent's noteId floats to the left of the first
                     * embedded note section, but only when the directive
                     * line itself isn't rendered (pure-view-host case
                     * AND not currently being edited). */
                    parentNoteIdText={hideRow && idx === 0 ? parentNoteIdText : undefined}
                    onEditDirective={() => setDirectiveEditingKey(segmentKey)}
                  />
                ))}
              </Fragment>
            )
          })(),
        )}
      </div>
      </div>
      </DirectiveEditingContext.Provider>
      </ParentShowCompletedContext.Provider>
      </ActiveEditorContext.Provider>
      </UndoActionsContext.Provider>

    </div>
  )
}
