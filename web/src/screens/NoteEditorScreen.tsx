import { useParams } from 'react-router-dom'
import { useEffect, useState, useMemo } from 'react'
import { noteStore } from '@/data/NoteStore'
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
import { LOADING_NOTE, DELETE_NOTE, DELETE_NOTE_CONFIRM_TITLE, DELETE_NOTE_CONFIRM_MESSAGE, SAVE_ERROR_BANNER, SAVE_ERROR_DISMISS, SYNC_ERROR_BANNER } from '@/strings'
import { db, auth } from '@/firebase/config'
import { findDirectives } from '@/dsl/directives/DirectiveFinder'
import { ActiveEditorContext } from '@/editor/ActiveEditorContext'
import { ParentShowCompletedContext } from '@/editor/ParentShowCompletedContext'
import { InlineSessionManager } from '@/editor/InlineSessionManager'
import styles from './NoteEditorScreen.module.css'

export function NoteEditorScreen() {
  const { noteId: urlNoteId } = useParams<{ noteId: string }>()
  const { controller, editorState, loading, loadedNoteId, showLoading, error, saveError, clearSaveError, dirty, save, showCompleted, toggleShowCompleted, needsFix, notesNeedingFix } = useEditor(urlNoteId)
  // Use loadedNoteId for all rendering — keeps showing the old note until
  // the new one is fully loaded, preventing transition flashes.
  const noteId = loadedNoteId ?? urlNoteId

  const [sessionManager] = useState(() => new InlineSessionManager())

  const allNotes = useAllNotes()
  const syncError = useNoteStoreError()
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false)

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
    () => allNotes.filter((n) => n.state !== 'deleted'),
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
  } = useActiveEditorSession(controller, editorState, sessionManager)

  const { unifiedUndoManager, handleUndo, handleRedo } = useUnifiedUndo({
    controller,
    sessionManager,
    activeSessionRef,
    activateSession,
    deactivateSession,
    invalidateAndRecompute,
  })

  const { saveAll, saveStatus, anyDirty } = useSaveCoordinator({
    noteId,
    editorState,
    save,
    dirty,
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
    moveDraggingClassName: styles.moveDragging,
  })

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
      <RecentTabsBar notesNeedingFix={notesNeedingFix} />

      <CommandBar
        controller={activeController}
        onSave={saveAll}
        onUndo={handleUndo}
        onRedo={handleRedo}
        canUndo={unifiedUndoManager.canUndo}
        canRedo={unifiedUndoManager.canRedo}
        onDelete={() => setShowDeleteConfirm(true)}
        onRestore={() => void handleRestoreNote()}
        isDeleted={currentNote?.state === 'deleted'}
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

      <ActiveEditorContext.Provider value={activeEditorCtx}>
      <ParentShowCompletedContext.Provider value={showCompleted}>
      <div className={styles.editorArea}>
        <div
          ref={editorRef}
          className={`${styles.editor} ${currentNote?.state === 'deleted' ? styles.deletedEditor : ''}`}
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
          ) : (
            <div key={item.realIndex} data-line-index={item.realIndex} style={fadedIndices.has(item.realIndex) ? { opacity: 0.4 } : undefined}>
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
          ),
        )}
      </div>
      </div>
      </ParentShowCompletedContext.Provider>
      </ActiveEditorContext.Provider>

    </div>
  )
}
