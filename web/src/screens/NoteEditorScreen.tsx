import { useParams, useNavigate } from 'react-router-dom'
import { useEffect, useCallback, useState, useRef, useMemo } from 'react'
import type { NoteLine } from '@/data/Note'
import { NoteRepository } from '@/data/NoteRepository'
import { noteStore } from '@/data/NoteStore'
import { useAllNotes, useNoteStoreError } from '@/hooks/useNoteStore'
import { MutationType, type NoteMutation } from '@/dsl/runtime/NoteMutation'
import { NoteRepositoryOperations } from '@/dsl/runtime/NoteRepositoryOperations'
import { useEditor } from '@/hooks/useEditor'
import { useDirectives } from '@/hooks/useDirectives'
import { CommandBar, type SaveStatus } from '@/components/CommandBar'
import { ConfirmDialog } from '@/components/ConfirmDialog'
import { EditorLine } from '@/components/EditorLine'
import { CompletedPlaceholderRow } from '@/components/CompletedPlaceholderRow'
import { RecentTabsBar, addOrUpdateTab, updateTabDisplayText, removeTab } from '@/components/RecentTabsBar'
import { extractDisplayText } from '@/data/TabState'
import { LOADING_NOTE, DELETE_NOTE, DELETE_NOTE_CONFIRM_TITLE, DELETE_NOTE_CONFIRM_MESSAGE, SAVE_ERROR_BANNER, SAVE_ERROR_DISMISS, SYNC_ERROR_BANNER } from '@/strings'
import { db, auth } from '@/firebase/config'
import { LineState } from '@/editor/LineState'
import { ActiveEditorContext, type ActiveEditorContextValue } from '@/editor/ActiveEditorContext'
import type { InlineEditSession } from '@/editor/InlineEditSession'
import { InlineSessionManager } from '@/editor/InlineSessionManager'
import { UnifiedUndoManager } from '@/editor/UnifiedUndoManager'
import { computeHiddenIndices, computeDisplayItemsFromHidden, computeEffectiveHidden, computeFadedIndices, nearestVisibleLine } from '@/editor/CompletedLineUtils'
import { findDirectives } from '@/dsl/directives/DirectiveFinder'
import { useEditorInteractions } from '@/editor/useEditorInteractions'
import styles from './NoteEditorScreen.module.css'

const noteRepo = new NoteRepository(db, auth)

export function NoteEditorScreen() {
  const { noteId: urlNoteId } = useParams<{ noteId: string }>()
  const navigate = useNavigate()
  const { controller, editorState, loading, loadedNoteId, showLoading, error, saveError, clearSaveError, dirty, save, showCompleted, toggleShowCompleted } = useEditor(urlNoteId)
  // Use loadedNoteId for all rendering — keeps showing the old note until
  // the new one is fully loaded, preventing transition flashes.
  const noteId = loadedNoteId ?? urlNoteId

  // Unified undo/redo across main editor + all inline sessions
  const [unifiedUndoManager] = useState(() => new UnifiedUndoManager())
  const [sessionManager] = useState(() => new InlineSessionManager())

  // All notes from singleton store — single source of truth for directive context
  const allNotes = useAllNotes()
  const syncError = useNoteStoreError()
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false)

  // Start Firestore collection listener for real-time note sync
  useEffect(() => { noteStore.start(db, auth) }, [])

  // Derive currentNote from allNotes — no separate async load, no race.
  const currentNote = useMemo(
    () => allNotes.find((n) => n.id === noteId) ?? null,
    [allNotes, noteId],
  )

  // Create NoteOperations for DSL mutations
  const noteOperations = useMemo(() => {
    const userId = auth.currentUser?.uid
    if (!userId) return undefined
    return new NoteRepositoryOperations(db, userId)
  }, [])

  // Handle mutations from directive execution
  const handleMutations = useCallback((mutations: NoteMutation[]) => {
    for (const mutation of mutations) {
      noteStore.updateNote(mutation.noteId, mutation.updatedNote)

      // Update editor content if the currently-edited note was mutated
      if (mutation.noteId === noteId) {
        switch (mutation.mutationType) {
          case MutationType.CONTENT_CHANGED: {
            // Update first line (content change = note name change)
            const currentLines = editorState.lines.map((l) => l.text)
            currentLines[0] = mutation.updatedNote.content
            editorState.lines = currentLines.map((t) => new LineState(t))
            editorState.requestFocusUpdate()
            editorState.notifyChange()
            controller.resetUndoHistory()
            break
          }
          case MutationType.CONTENT_APPENDED: {
            if (mutation.appendedText) {
              const newLines = mutation.appendedText.split('\n')
              for (const lineText of newLines) {
                editorState.lines.push(new LineState(lineText))
              }
              editorState.requestFocusUpdate()
              editorState.notifyChange()
              controller.resetUndoHistory()
            }
            break
          }
          case MutationType.PATH_CHANGED:
            // Path changes don't affect editor content
            break
        }
      }
    }
  }, [noteId, editorState, controller])

  // Register main editor with unified undo manager
  useEffect(() => {
    unifiedUndoManager.registerEditor('main', controller)
    return () => unifiedUndoManager.unregisterEditor('main')
  }, [unifiedUndoManager, controller])

  // Register inline sessions with unified undo manager when sessions change.
  // Key on allNotes (which drives directive results → ViewVal → ensureSessions).
  const inlineSessionIds = sessionManager.getAllSessions().map(s => s.noteId).join(',')
  useEffect(() => {
    for (const session of sessionManager.getAllSessions()) {
      unifiedUndoManager.registerEditor(`inline:${session.noteId}`, session.controller)
    }
    return () => {
      for (const session of sessionManager.getAllSessions()) {
        unifiedUndoManager.unregisterEditor(`inline:${session.noteId}`)
      }
    }
  }, [inlineSessionIds, sessionManager, unifiedUndoManager])

  const activeNotes = useMemo(
    () => allNotes.filter((n) => n.state !== 'deleted'),
    [allNotes],
  )

  const { results: directiveResults, mutations: directiveMutations, invalidateAndRecompute, refreshDirective } =
    useDirectives({
      noteId: loadedNoteId,
      editorState,
      notes: activeNotes,
      currentNote,
      noteOperations,
    })

  // Process mutations from directive execution (e.g., property assignments, .append())
  useEffect(() => {
    if (directiveMutations.length > 0) {
      handleMutations(directiveMutations)
    }
  }, [directiveMutations, handleMutations])

  const handleViewNoteSave = useCallback(async (viewedNoteId: string, trackedLines: NoteLine[]): Promise<Map<number, string>> => {
    // Update store optimistically so directives see the edit before Firestore confirms
    const newContent = trackedLines.map(l => l.content).join('\n')
    const existing = noteStore.getNoteById(viewedNoteId)
    if (existing) {
      noteStore.updateNote(viewedNoteId, { ...existing, content: newContent })
    }
    // Use saveNoteWithFullContent: the inline editor only has this note's
    // direct lines, not nested sub-trees from view directives.
    // saveNoteWithFullContent loads the existing tree and matches content
    // against it, preserving grandchild relationships.
    const savePromise = noteRepo.saveNoteWithFullContent(viewedNoteId, newContent)
    noteStore.trackSave(viewedNoteId, savePromise)
    const createdIds = await savePromise
    invalidateAndRecompute()
    return createdIds
  }, [invalidateAndRecompute])

  // Add/move tab to front when note first opens, and remember for nav
  useEffect(() => {
    if (!noteId || loading) return
    localStorage.setItem('lastNoteId', noteId)
    const displayText = extractDisplayText(editorState.lines[0]?.text ?? '')
    void addOrUpdateTab(noteId, displayText)
  }, [noteId, loading])

  // Update tab display text when title changes (without reordering)
  const firstLineText = editorState.lines[0]?.text ?? ''
  useEffect(() => {
    if (!noteId || loading) return
    const displayText = extractDisplayText(firstLineText)
    void updateTabDisplayText(noteId, displayText)
  }, [noteId, loading, firstLineText])

  // Save with directive execution — update note store so directives see fresh content
  const saveWithDirectives = useCallback(async () => {
    await save()
    if (noteId) {
      const existing = noteStore.getNoteById(noteId)
      if (existing) {
        noteStore.updateNote(noteId, { ...existing, content: editorState.text })
      }
    }
    invalidateAndRecompute()
  }, [save, noteId, editorState, invalidateAndRecompute])

  // Directive edit callback
  const handleDirectiveEdit = useCallback((oldSourceText: string, newSourceText: string) => {
    // Find the directive in the editor by matching its source text
    for (let lineIndex = 0; lineIndex < editorState.lines.length; lineIndex++) {
      const lineContent = editorState.lines[lineIndex]?.text ?? ''
      const directives = findDirectives(lineContent)
      const directive = directives.find((d) => d.sourceText === oldSourceText)
      if (directive) {
        controller.confirmDirectiveEdit(lineIndex, directive.startOffset, directive.endOffset, newSourceText)
        invalidateAndRecompute()
        return
      }
    }
  }, [editorState, controller, invalidateAndRecompute])

  const handleDeleteNote = useCallback(async () => {
    if (!noteId) return
    try {
      await noteRepo.softDeleteNote(noteId)
      const nextNoteId = await removeTab(noteId, noteId)
      navigate(nextNoteId ? `/note/${nextNoteId}` : '/')
    } catch (e) {
      console.error('Failed to delete note:', e)
    }
  }, [noteId, navigate])

  const handleRestoreNote = useCallback(async () => {
    if (!noteId) return
    try {
      await noteRepo.undeleteNote(noteId)
      const existing = noteStore.getNoteById(noteId)
      if (existing) {
        noteStore.updateNote(noteId, { ...existing, state: null })
      }
    } catch (e) {
      console.error('Failed to restore note:', e)
    }
  }, [noteId])

  // --- Active editor context (routes commands to parent or view controller) ---
  const [activeSession, setActiveSession] = useState<InlineEditSession | null>(null)
  const activeSessionRef = useRef<InlineEditSession | null>(null)
  // Force NoteEditorScreen re-render when the active session's state changes
  // (e.g., selection changes within the view). Without this, React skips the
  // re-render when setActiveSession is called with the same session object,
  // leaving CommandBar's disabled states stale.
  const [, setActiveSessionVersion] = useState(0)

  const activateSession = useCallback((session: InlineEditSession) => {
    const prev = activeSessionRef.current
    if (prev !== session) {
      // Commit pending undo state on the outgoing editor so edits become undo entries
      const outgoingCtrl = prev?.controller ?? controller
      outgoingCtrl.commitUndoState()
      // Mutual exclusivity: clear selections in all other editors when switching
      editorState.clearSelection()
      if (prev) prev.editorState.clearSelection()
    }
    activeSessionRef.current = session
    setActiveSession(session)
    // Bump version so CommandBar re-evaluates disabled states immediately
    setActiveSessionVersion(v => v + 1)
  }, [editorState, controller])

  const deactivateSession = useCallback((expectedSession?: InlineEditSession): InlineEditSession | null => {
    const prev = activeSessionRef.current
    // If caller specifies which session it expects to deactivate, only proceed if it matches.
    // This prevents a blurring ViewNoteSection from deactivating a sibling that just activated.
    if (expectedSession && prev !== expectedSession) return null
    // Clear the departing session's selection for mutual exclusivity
    if (prev) prev.editorState.clearSelection()
    activeSessionRef.current = null
    setActiveSession(null)
    return prev
  }, [])

  const activeController = activeSession?.controller ?? controller
  const activeState = activeSession?.editorState ?? editorState

  // Undo/redo routed through unified undo manager
  const getActiveContextId = useCallback(() => {
    const session = activeSessionRef.current
    return session ? `inline:${session.noteId}` : 'main'
  }, [])

  const activateEditorByContextId = useCallback((contextId: string) => {
    if (contextId === 'main') {
      if (activeSessionRef.current) deactivateSession()
    } else {
      const noteId = contextId.replace('inline:', '')
      const session = sessionManager.getSession(noteId)
      if (session) activateSession(session)
    }
  }, [sessionManager, activateSession, deactivateSession])

  const handleUndo = useCallback(() => {
    unifiedUndoManager.undo(getActiveContextId(), activateEditorByContextId)
    invalidateAndRecompute()
  }, [unifiedUndoManager, getActiveContextId, activateEditorByContextId, invalidateAndRecompute])

  const handleRedo = useCallback(() => {
    unifiedUndoManager.redo(getActiveContextId(), activateEditorByContextId)
    invalidateAndRecompute()
  }, [unifiedUndoManager, getActiveContextId, activateEditorByContextId, invalidateAndRecompute])

  const notifyActiveChange = useCallback(() => {
    setActiveSessionVersion(v => v + 1)
  }, [])

  const activeEditorCtx = useMemo<ActiveEditorContextValue>(() => ({
    activeController,
    activeState,
    activeSession,
    activateSession,
    deactivateSession,
    notifyActiveChange,
    sessionManager,
  }), [activeController, activeState, activeSession, activateSession, deactivateSession, notifyActiveChange, sessionManager])

  // --- Unified save: main note + all dirty inline sessions ---

  // Save a single inline session — returns true on success
  const saveInlineSession = useCallback(async (session: InlineEditSession): Promise<boolean> => {
    session.controller.sortCompletedToBottom()
    const trackedLines = session.getTrackedLines()
    const content = session.getText()
    const createdIds = await handleViewNoteSave(session.noteId, trackedLines)
    session.applyCreatedIds(createdIds)
    session.markSaved(content)
    return true
  }, [handleViewNoteSave])

  const [saveStatus, setSaveStatus] = useState<SaveStatus>('idle')
  const anyDirty = dirty || sessionManager.getAllDirtySessions().length > 0

  const saveAll = useCallback(async () => {
    setSaveStatus('saving')
    try {
      const mainSave = saveWithDirectives()
      const inlineSaves = sessionManager.getAllDirtySessions().map(session =>
        saveInlineSession(session).catch(e => {
          console.error(`Failed to save inline session ${session.noteId}:`, e)
          throw e
        })
      )
      const results = await Promise.allSettled([mainSave, ...inlineSaves])
      const failures = results.filter(r => r.status === 'rejected')
      if (failures.length > 0) {
        setSaveStatus('partial-error')
      } else {
        setSaveStatus('saved')
      }
    } catch {
      setSaveStatus('partial-error')
    }
  }, [saveWithDirectives, sessionManager, saveInlineSession])

  // Reset saveStatus to idle when any editor becomes dirty again
  useEffect(() => {
    if (anyDirty && (saveStatus === 'saved' || saveStatus === 'partial-error')) {
      setSaveStatus('idle')
    }
  }, [anyDirty, saveStatus])

  // Refs for cleanup callbacks (avoid stale closures)
  const saveInlineSessionRef = useRef(saveInlineSession)
  saveInlineSessionRef.current = saveInlineSession
  const sessionManagerRef = useRef(sessionManager)
  sessionManagerRef.current = sessionManager

  const saveAllDirtyInlineSessions = useCallback(() => {
    for (const session of sessionManagerRef.current.getAllDirtySessions()) {
      void saveInlineSessionRef.current(session).catch(() => {})
    }
  }, [])

  useEffect(() => {
    const handler = (e: BeforeUnloadEvent) => {
      if (sessionManagerRef.current.getAllDirtySessions().length > 0) {
        saveAllDirtyInlineSessions()
        e.preventDefault()
      }
    }
    window.addEventListener('beforeunload', handler)
    return () => window.removeEventListener('beforeunload', handler)
  }, [saveAllDirtyInlineSessions])

  // Auto-save dirty inline sessions on note switch (unmount)
  useEffect(() => {
    return () => saveAllDirtyInlineSessions()
  }, [noteId, saveAllDirtyInlineSessions])

  // Global keyboard shortcuts — Ctrl+S always, others when no textarea has focus
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key === 's') {
        e.preventDefault()
        void saveAll()
        return
      }

      // Remaining shortcuts only apply when no textarea has focus
      // (e.g. after gutter-selecting a placeholder)
      const active = document.activeElement
      if (active && active.tagName === 'TEXTAREA') return

      // Route through active controller/state (view or parent)
      const ctrl = activeSessionRef.current?.controller ?? controller
      const state = activeSessionRef.current?.editorState ?? editorState

      if (e.metaKey || e.ctrlKey) {
        if (e.key === 'z') {
          e.preventDefault()
          if (e.shiftKey) handleRedo()
          else handleUndo()
          return
        }
        if (e.key === 'y') {
          e.preventDefault()
          handleRedo()
          return
        }
        if (e.key === 'a') {
          e.preventDefault()
          state.selectAll()
          return
        }
        if (e.key === 'x' && state.hasSelection) {
          e.preventDefault()
          ctrl.cutSelection()
          return
        }
        if (e.key === 'c' && state.hasSelection) {
          e.preventDefault()
          ctrl.copySelection()
          return
        }
        return
      }

      // Non-modifier keys with selection
      if (state.hasSelection && (e.key === 'Backspace' || e.key === 'Delete')) {
        e.preventDefault()
        ctrl.deleteSelectionWithUndo()
      }
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [saveAll, editorState, controller, invalidateAndRecompute, handleUndo, handleRedo])

  // --- Shared editor interactions (gutter, drag, move) ---
  const editorRef = useRef<HTMLDivElement>(null)
  const dropCursorRef = useRef<HTMLDivElement>(null)
  const getParentState = useCallback(() => editorState, [editorState])
  const getParentController = useCallback(() => controller, [controller])

  const {
    handleDragStart,
    handleMoveStart: baseHandleMoveStart,
    handleGutterDragStart: baseGutterDragStart,
    handleGutterDragUpdate: baseGutterDragUpdate,
    selectLineRange,
    gutterAnchorRef,
  } = useEditorInteractions(editorRef, dropCursorRef, getParentState, getParentController)

  // Wrap moveStart to add the CSS class for the parent editor
  const handleMoveStart = useCallback(() => {
    baseHandleMoveStart()
    editorRef.current?.classList.add(styles.moveDragging!)
  }, [baseHandleMoveStart])

  // Remove moveDragging CSS class on mouseup (the hook handles the drop cursor but not CSS)
  useEffect(() => {
    const handleMouseUp = () => editorRef.current?.classList.remove(styles.moveDragging!)
    document.addEventListener('mouseup', handleMouseUp)
    return () => document.removeEventListener('mouseup', handleMouseUp)
  }, [])

  // Wrap gutter handlers to route clicks on view lines to the view's session
  const resolveViewLineAtY = useCallback((clientY: number): { viewLineIndex: number; session: InlineEditSession } | null => {
    const viewLineEl = document.elementsFromPoint(window.innerWidth / 2, clientY)
      .find(el => el.hasAttribute('data-view-line-index'))
    if (!viewLineEl) return null
    const viewLineIndex = parseInt(viewLineEl.getAttribute('data-view-line-index')!)
    const noteId = viewLineEl.getAttribute('data-view-note-id')
    const existing = activeSessionRef.current
    if (existing?.noteId === noteId) return { viewLineIndex, session: existing }
    return null
  }, [])

  const handleGutterDragStart = useCallback((lineIndex: number, clientY?: number) => {
    if (clientY != null) {
      const viewHit = resolveViewLineAtY(clientY)
      if (viewHit) {
        const { viewLineIndex, session } = viewHit
        activateSession(session)
        gutterAnchorRef.current = [viewLineIndex, viewLineIndex]
        // Use the view session's controller/state for gutter selection
        const state = session.editorState
        const ctrl = session.controller
        const start = state.getLineStartOffset(viewLineIndex)
        const lastLine = state.lines[viewLineIndex]
        let end = start + (lastLine?.text.length ?? 0)
        if ((lastLine?.text.length ?? 0) === 0 && viewLineIndex < state.lines.length - 1) end += 1
        ctrl.setSelection(start, end)
        return
      }
    }
    // Parent gutter: deactivate any active view session so commands route to parent
    if (activeSessionRef.current) deactivateSession()
    baseGutterDragStart(lineIndex)
  }, [baseGutterDragStart, resolveViewLineAtY, activateSession, deactivateSession, gutterAnchorRef])

  const handleGutterDragUpdate = useCallback((lineIndex: number, clientY?: number) => {
    if (clientY != null) {
      const viewHit = resolveViewLineAtY(clientY)
      if (viewHit) {
        const { viewLineIndex, session } = viewHit
        const [anchorStart, anchorEnd] = gutterAnchorRef.current
        if (anchorStart < 0) return
        const state = session.editorState
        const ctrl = session.controller
        const first = Math.max(0, Math.min(anchorStart, viewLineIndex))
        const last = Math.min(state.lines.length - 1, Math.max(anchorEnd, viewLineIndex))
        const start = state.getLineStartOffset(first)
        const lastLine = state.lines[last]
        let end = state.getLineStartOffset(last) + (lastLine?.text.length ?? 0)
        if ((lastLine?.text.length ?? 0) === 0 && last < state.lines.length - 1) end += 1
        ctrl.setSelection(start, end)
        return
      }
    }
    baseGutterDragUpdate(lineIndex)
  }, [baseGutterDragUpdate, resolveViewLineAtY, gutterAnchorRef])

  // Compute display items and hidden indices for show/hide completed lines
  const lineTexts = editorState.lines.map((l) => l.text)
  const lineTextsKey = lineTexts.join('\n')
  const hiddenIndices = useMemo(
    () => computeHiddenIndices(lineTexts, showCompleted),
    [lineTextsKey, showCompleted],
  )
  // Exclude recently-checked lines from hidden so they stay visible at reduced opacity.
  // Snapshot the set contents into a stable key — the Set is mutated in place so
  // reference/size comparison alone won't trigger useMemo recalculation.
  const recentlyChecked = controller.recentlyCheckedIndices
  const recentlyCheckedKey = [...recentlyChecked].join(',')
  const effectiveHidden = useMemo(
    () => computeEffectiveHidden(hiddenIndices, recentlyChecked, lineTexts),
    [hiddenIndices, recentlyCheckedKey],
  )
  const fadedIndices = useMemo(
    () => computeFadedIndices(hiddenIndices, recentlyChecked, lineTexts),
    [hiddenIndices, recentlyCheckedKey],
  )
  const displayItems = useMemo(
    () => computeDisplayItemsFromHidden(lineTexts, effectiveHidden),
    [lineTextsKey, effectiveHidden],
  )
  controller.hiddenIndices = effectiveHidden

  // Snap focus to nearest visible line when toggling showCompleted OFF
  const prevShowCompletedRef = useRef(showCompleted)
  useEffect(() => {
    if (prevShowCompletedRef.current && !showCompleted) {
      if (hiddenIndices.has(editorState.focusedLineIndex)) {
        const newFocus = nearestVisibleLine(lineTexts, editorState.focusedLineIndex, hiddenIndices)
        controller.setCursor(newFocus, editorState.lines[newFocus]?.cursorPosition ?? 0)
      }
      if (editorState.hasSelection) {
        editorState.clearSelection()
      }
    }
    prevShowCompletedRef.current = showCompleted
  }, [showCompleted])

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
      <RecentTabsBar />

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
          <span>{SAVE_ERROR_BANNER}</span>
          <button className={styles.saveErrorDismiss} onClick={clearSaveError}>{SAVE_ERROR_DISMISS}</button>
        </div>
      )}

      {syncError && (
        <div className={styles.saveErrorBanner}>
          <span>{SYNC_ERROR_BANNER}</span>
          <button className={styles.saveErrorDismiss} onClick={() => noteStore.clearError()}>{SAVE_ERROR_DISMISS}</button>
        </div>
      )}

      <ActiveEditorContext.Provider value={activeEditorCtx}>
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
                onViewNoteSave={handleViewNoteSave}
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
      </ActiveEditorContext.Provider>

    </div>
  )
}
