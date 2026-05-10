import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from 'react'
import { EditorState } from '@/editor/EditorState'
import { EditorController } from '@/editor/EditorController'
import { UndoManager, type UndoManagerState } from '@/editor/UndoManager'
import { toEditorLines, type EditorLineInput, type NoteLine } from '@/data/Note'
import { NoteRepository, type SaveResult } from '@/data/NoteRepository'
import { DeletionSource } from '@/data/DeletionSource'
import { noteStatsRepo } from '@/data/NoteStatsRepository'
import { noteStore } from '@/data/NoteStore'
import { resolveNoteIds } from '@/editor/resolveNoteIds'
import { getDb, auth } from '@/firebase/config'
import { ERROR_LOAD, ERROR_SAVE, SAVE_ERROR_BANNER } from '@/strings'

const PENDING_SAVE_ERROR_KEY = 'pendingSaveError'
const VIEW_DWELL_MS = 1500
const VIEW_COOLDOWN_MS = 5 * 60 * 1000
const lastViewWriteMs = new Map<string, number>()

/**
 * Editor-specific state cache for instant tab switching.
 * Stores the editor's lines (text + noteIds together, sourced from a single
 * `editorState.lines` snapshot) so a cached restore can't drift into id/text
 * misalignment after edits that add, remove, or reorder lines.
 */
interface EditorCacheEntry {
  editorLines: EditorLineInput[]
  dirty: boolean
}
const editorStateCache = new Map<string, EditorCacheEntry>()

export function useEditor(noteId: string | undefined) {
  const [editorState] = useState(() => new EditorState())
  const [undoManager] = useState(() => new UndoManager())
  const [controller] = useState(() => new EditorController(editorState, undoManager))

  const [loading, setLoading] = useState(true)
  const [loadedNoteId, setLoadedNoteId] = useState<string | null>(null)
  const [showLoading, setShowLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [saveError, setSaveError] = useState<string | null>(null)
  const [dirty, setDirty] = useState(false)
  const [showCompleted, setShowCompleted] = useState(true)

  // Check for save errors persisted from a previous unmount/beforeunload
  useEffect(() => {
    try {
      const raw = sessionStorage.getItem(PENDING_SAVE_ERROR_KEY)
      if (raw) {
        sessionStorage.removeItem(PENDING_SAVE_ERROR_KEY)
        const { message } = JSON.parse(raw) as { message: string }
        setSaveError(message)
      }
    } catch { /* sessionStorage may be unavailable */ }
  }, [])

  // Force re-render when editor state changes
  const [, setRenderVersion] = useState(0)

  useEffect(() => {
    editorState.onTextChange = () => {
      setDirty(true)
      setRenderVersion((v) => v + 1)
    }
    editorState.onSelectionChange = () => {
      setRenderVersion((v) => v + 1)
    }
    return () => {
      editorState.onTextChange = null
      editorState.onSelectionChange = null
    }
  }, [editorState])

  // Persist undo state to localStorage
  const undoStorageKey = noteId ? `undo:${noteId}` : null

  useEffect(() => {
    if (!undoStorageKey) return
    // Save undo state periodically and on unmount
    const saveUndoState = () => {
      try {
        const state = undoManager.exportState()
        localStorage.setItem(undoStorageKey, JSON.stringify(state))
      } catch { /* localStorage may be full or unavailable */ }
    }
    const interval = setInterval(saveUndoState, 5000)
    return () => {
      clearInterval(interval)
      saveUndoState()
    }
  }, [undoStorageKey, undoManager])

  // Cache editor state when switching away from a note
  const currentNoteIdRef = useRef<string | undefined>(undefined)
  useEffect(() => {
    return () => {
      const prevId = currentNoteIdRef.current
      if (prevId && editorState.lines.length > 0) {
        // Snapshot text + noteIds together from `editorState.lines` so cached
        // restores can't drift into id/text misalignment after edits.
        editorStateCache.set(prevId, {
          editorLines: editorState.lines.map((l) => ({
            text: l.text,
            noteIds: [...l.noteIds],
          })),
          dirty: dirtyRef.current,
        })
      }
    }
  }, [noteId, editorState])

  const dirtyRef = useRef(dirty)
  dirtyRef.current = dirty

  // Snapshot of `containedNotes` at edit-session start (or last save), keyed
  // by id for the root and every live descendant. Anchors the 3-way merge
  // in NoteRepository.planSave at every depth. Refreshed on load, on
  // external-change reload, and after a successful save.
  const localBasesRef = useRef<Map<string, string[]> | null>(null)
  const captureLocalBase = useCallback((targetNoteId: string) => {
    localBasesRef.current = noteStore.snapshotLocalBases(targetNoteId)
  }, [])

  // Load note
  useEffect(() => {
    if (!noteId) return
    currentNoteIdRef.current = noteId

    let cancelled = false

    const populateEditor = (
      editorLines: EditorLineInput[],
      initialDirty: boolean,
    ) => {
      if (cancelled) return
      editorState.initFromNoteLines(editorLines)
      editorState.requestFocusUpdate()

      // Restore persisted undo state or set fresh baseline
      controller.resetUndoHistory()
      const savedUndoKey = `undo:${noteId}`
      try {
        const savedUndo = localStorage.getItem(savedUndoKey)
        if (savedUndo) {
          const state = JSON.parse(savedUndo) as UndoManagerState
          undoManager.importState(state)
        } else {
          undoManager.setBaseline(editorState.lines, editorState.focusedLineIndex)
        }
      } catch {
        undoManager.setBaseline(editorState.lines, editorState.focusedLineIndex)
      }

      setDirty(initialDirty)
      setLoading(false)
      setLoadedNoteId(noteId)
    }

    // Check editor state cache for instant tab switch with unsaved edits
    const cached = editorStateCache.get(noteId)
    editorStateCache.delete(noteId)

    let recordViewTimer: ReturnType<typeof setTimeout> | null = null
    const scheduleRecordView = () => {
      const last = lastViewWriteMs.get(noteId)
      if (last !== undefined && Date.now() - last < VIEW_COOLDOWN_MS) return
      recordViewTimer = setTimeout(() => {
        if (cancelled) return
        noteStatsRepo.recordView(noteId)
          .then(() => { lastViewWriteMs.set(noteId, Date.now()) })
          .catch((e) => { console.error('recordView failed', e) })
      }, VIEW_DWELL_MS)
    }
    const cleanup = () => {
      cancelled = true
      if (recordViewTimer) clearTimeout(recordViewTimer)
    }

    if (cached?.dirty) {
      // Restore unsaved edits immediately for instant display
      populateEditor(cached.editorLines, true)
      captureLocalBase(noteId)
      setShowLoading(false)
      scheduleRecordView()

      // Refresh editor line IDs from NoteStore once any inflight save echoes
      // through (the fire-and-forget switch-away save may have created new
      // child notes with IDs we don't have yet). If NoteStore is stale, we
      // skip silently — the next save's reconcile path recovers IDs.
      void (async () => {
        await noteStore.awaitPendingSave(noteId)
        if (cancelled) return
        const storeLines = noteStore.getNoteLinesById(noteId)
        if (!storeLines) {
          // We have cached unsaved edits for this note, so we expected
          // NoteStore to know about it. Anomalous; log so a future bug
          // surface can be traced. Not user-facing — the next save's
          // reconcile path recovers IDs regardless.
          if (noteStore.isLoaded()) {
            console.warn(
              `[useEditor] cached-dirty refresh: note ${noteId} missing from ` +
              `loaded NoteStore. Editor line IDs will be stale until next save.\n` +
              `Stack:\n${new Error().stack ?? '(unavailable)'}`,
            )
          }
          return
        }
        const freshContent = storeLines.map((l) => l.content).join('\n')
        const currentContent = editorState.lines.map((l) => l.text).join('\n')
        if (freshContent === currentContent) {
          editorState.updateNoteIds(storeLines.map((l) => [l.noteId]))
          setDirty(false)
        }
      })()

      return cleanup
    }

    // Canonical clean-load path: prefer the live NoteStore snapshot; fall back
    // to Firestore only when the store hasn't loaded yet or doesn't have the
    // note (e.g., immediately after createNote, before the listener echo).
    setLoading(true)
    const loadNote = async () => {
      try {
        setError(null)
        await noteStore.awaitPendingSave(noteId)
        if (cancelled) return

        if (noteStore.isLoaded()) {
          const rawNote = noteStore.getRawNoteById(noteId)
          const storeLines = rawNote ? noteStore.getNoteLinesById(noteId) : undefined
          if (rawNote && storeLines) {
            setShowCompleted(rawNote.showCompleted ?? true)
            populateEditor(toEditorLines(storeLines), false)
            captureLocalBase(noteId)
            setShowLoading(false)
            scheduleRecordView()
            return
          }
        }

        const { lines, showCompleted } = await new NoteRepository(getDb(), auth).loadNoteWithChildren(noteId)
        setShowCompleted(showCompleted)
        populateEditor(toEditorLines(lines), false)
        captureLocalBase(noteId)
        setShowLoading(false)
        scheduleRecordView()
      } catch (e) {
        if (!cancelled) {
          setError(e instanceof Error ? e.message : ERROR_LOAD)
          setLoading(false)
          setShowLoading(false)
        }
      }
    }

    void loadNote()
    return cleanup
  }, [noteId, editorState, controller, undoManager])

  // Detect external changes via NoteStore's changedNoteIds subscription.
  // Only fires on incremental Firestore snapshots (never on first load),
  // and provides the set of changed note IDs for targeted checking.
  // Mirrors Android's NoteStore.changedNoteIds → applyNoteContent flow.
  const savingRef = useRef(false)
  useEffect(() => {
    if (!noteId) return

    return noteStore.subscribeChangedNoteIds((changedIds) => {
      if (!changedIds.has(noteId)) return
      if (dirtyRef.current) return
      if (savingRef.current) return

      const storeNote = noteStore.getNoteById(noteId)
      if (!storeNote) return

      const currentContent = editorState.lines.map((l) => l.text).join('\n')
      if (storeNote.content === currentContent) return

      // External change detected — use NoteStore's in-memory tree for noteId mappings
      const storeLines = noteStore.getNoteLinesById(noteId)
      if (!storeLines) return

      // External reload supersedes any local removals: their noteIds are
      // about to be replaced by whatever Firestore says lives on this
      // note. Drop the tracker so we don't carry stale source tags
      // forward into the next save.
      controller.resetPendingSoftDeletes()
      editorState.initFromNoteLines(toEditorLines(storeLines), true)
      editorState.requestFocusUpdate()
      setShowCompleted(storeNote.showCompleted ?? true)
      captureLocalBase(noteId)
      setDirty(false)
      setRenderVersion((v) => v + 1)
    })
  }, [noteId, editorState, controller, captureLocalBase])

  /**
   * Builds the main editor's tracked lines for inclusion in a multi-note
   * save. The returned [applyResult] writes created ids back to the original
   * EditorState — guarded so a mid-save tab switch doesn't cross-pollinate.
   * [localBases] anchors the 3-way merge in planSave at every depth.
   */
  const prepareMainSaveItem = useCallback((targetNoteId: string): {
    trackedLines: NoteLine[]
    localBases: Map<string, string[]> | null
    /** Per-line deletion sources recorded by editor removal sites; passed
     *  through to `saveNoteWithChildren` so it can stamp source-tagged
     *  deletionBatchIds. */
    deletionSources: Map<string, DeletionSource>
    /** Joined text of the editor's current lines — used by the save
     *  coordinator for optimistic NoteStore updates. */
    text: string
    applyResult: (result: SaveResult) => void
  } => {
    controller.sortCompletedToBottom()
    controller.commitUndoState(true)

    const currentLines = editorState.lines.map((l) => l.text)
    const currentNoteIds = editorState.lines.map((l) => l.noteIds)
    let newTracked = resolveNoteIds(currentLines, currentNoteIds)
    if (newTracked.length > 0 && newTracked[0]!.noteId !== targetNoteId) {
      newTracked = [{ ...newTracked[0]!, noteId: targetNoteId }, ...newTracked.slice(1)]
    }

    const localBases = localBasesRef.current
    const deletionSources = controller.consumePendingSoftDeletes()
    const text = editorState.text

    const applyResult = (result: SaveResult) => {
      if (currentNoteIdRef.current !== targetNoteId) return
      editorState.updateNoteIds(
        newTracked.map((line, index) => [result.createdIds.get(index) ?? line.noteId]),
      )
      // See InlineEditSession.refreshLocalBase for why we use the save's
      // own post-write state instead of NoteStore.snapshotLocalBases.
      localBasesRef.current = result.postSaveContainedNotes
      setDirty(false)
    }

    return { trackedLines: newTracked, localBases, deletionSources, text, applyResult }
  }, [editorState, controller])

  // Core save logic — accepts noteId to avoid stale closure bugs. Used by
  // unmount/beforeunload paths.
  const saveNoteById = useCallback(async (targetNoteId: string) => {
    try {
      setSaving(true)
      savingRef.current = true

      const { trackedLines, localBases, deletionSources, applyResult } = prepareMainSaveItem(targetNoteId)
      const result = await noteStore.enqueueSave(() =>
        new NoteRepository(getDb(), auth).saveNoteWithChildren(targetNoteId, trackedLines, localBases, deletionSources),
      )
      applyResult(result)
    } catch (e) {
      setSaveError(e instanceof Error ? e.message : ERROR_SAVE)
      throw e // Re-throw so unmount/beforeunload callers can persist the error
    } finally {
      setSaving(false)
      savingRef.current = false
    }
  }, [prepareMainSaveItem])

  const toggleShowCompleted = useCallback(async () => {
    if (!noteId) return
    const newValue = !showCompleted
    setShowCompleted(newValue)
    try {
      await new NoteRepository(getDb(), auth).updateShowCompleted(noteId, newValue)
    } catch (e) {
      console.error('Failed to persist showCompleted:', e)
    }
  }, [noteId, showCompleted])

  // Ref for beforeunload — must always point to the latest noteId
  const noteIdRef = useRef(noteId)
  noteIdRef.current = noteId

  // Auto-save on beforeunload (tab close/refresh)
  useEffect(() => {
    const handler = (e: BeforeUnloadEvent) => {
      if (dirtyRef.current && noteIdRef.current) {
        const targetId = noteIdRef.current
        void saveNoteById(targetId).catch((err) => {
          const msg = err instanceof Error ? err.message : SAVE_ERROR_BANNER
          try { sessionStorage.setItem(PENDING_SAVE_ERROR_KEY, JSON.stringify({ noteId: targetId, message: msg })) } catch { /* ignore */ }
        })
        e.preventDefault()
      }
    }
    window.addEventListener('beforeunload', handler)
    return () => window.removeEventListener('beforeunload', handler)
  }, [saveNoteById])

  // Auto-save on note switch — captures noteId in closure so cleanup
  // saves to the CORRECT note, not the one we're switching TO.
  // (Using saveRef.current here would be a bug: the ref gets updated
  // to the new noteId during render, before this cleanup runs.)
  useEffect(() => {
    const capturedNoteId = noteId
    return () => {
      if (dirtyRef.current && capturedNoteId) {
        // Optimistic update: push dirty content into NoteStore immediately
        // so view directives on the destination note see the edit before
        // the async save completes. Use silent update to avoid triggering
        // a re-render of the outgoing screen (the destination note's render
        // will pick up the new snapshot via getSnapshot()).
        const existing = noteStore.getNoteById(capturedNoteId)
        if (existing) {
          const dirtyContent = editorState.lines.map((l) => l.text).join('\n')
          noteStore.updateNoteSilently(capturedNoteId, { ...existing, content: dirtyContent })
        }

        void saveNoteById(capturedNoteId).catch((err) => {
          const msg = err instanceof Error ? err.message : SAVE_ERROR_BANNER
          try { sessionStorage.setItem(PENDING_SAVE_ERROR_KEY, JSON.stringify({ noteId: capturedNoteId, message: msg })) } catch { /* ignore */ }
        })
      }
    }
  }, [noteId, editorState, saveNoteById])

  const clearSaveError = useCallback(() => setSaveError(null), [])

  // Subscribe to the needs-fix set so the command bar can switch the save
  // button to the warning color when the current note is in it.
  const notesNeedingFix = useSyncExternalStore(
    noteStore.subscribeNotesNeedingFix,
    noteStore.getNotesNeedingFixSnapshot,
  )
  const needsFix = !!(noteId && notesNeedingFix.has(noteId))

  return {
    controller,
    editorState,
    loading,
    loadedNoteId,
    showLoading,
    saving,
    setSaveError,
    error,
    saveError,
    clearSaveError,
    dirty,
    prepareMainSaveItem,
    showCompleted,
    toggleShowCompleted,
    needsFix,
    notesNeedingFix,
  }
}
