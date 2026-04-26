import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from 'react'
import { EditorState } from '@/editor/EditorState'
import { EditorController } from '@/editor/EditorController'
import { UndoManager, type UndoManagerState } from '@/editor/UndoManager'
import type { NoteLine } from '@/data/Note'
import { NoteRepository } from '@/data/NoteRepository'
import { NoteStatsRepository } from '@/data/NoteStatsRepository'
import { noteStore } from '@/data/NoteStore'
import { resolveNoteIds } from '@/editor/resolveNoteIds'
import { db, auth } from '@/firebase/config'
import { ERROR_LOAD, ERROR_SAVE, SAVE_ERROR_BANNER } from '@/strings'

const PENDING_SAVE_ERROR_KEY = 'pendingSaveError'
const VIEW_DWELL_MS = 1500
const VIEW_COOLDOWN_MS = 5 * 60 * 1000
const lastViewWriteMs = new Map<string, number>()

const repo = new NoteRepository(db, auth)
const statsRepo = new NoteStatsRepository(db, auth)

/**
 * Editor-specific state cache for instant tab switching.
 * Only stores unsaved edits and noteId mappings — canonical content
 * comes from NoteStore (always fresh via collection listener).
 */
interface EditorCacheEntry {
  trackedLines: NoteLine[]
  editorTexts: string[]
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

  // Track line IDs for Firestore mapping
  const trackedLinesRef = useRef<NoteLine[]>([])

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
      if (prevId && trackedLinesRef.current.length > 0) {
        editorStateCache.set(prevId, {
          trackedLines: trackedLinesRef.current,
          editorTexts: editorState.lines.map((l) => l.text),
          dirty: dirtyRef.current,
        })
      }
    }
  }, [noteId, editorState])

  const dirtyRef = useRef(dirty)
  dirtyRef.current = dirty

  // Load note
  useEffect(() => {
    if (!noteId) return
    currentNoteIdRef.current = noteId

    let cancelled = false

    const populateEditor = (lines: NoteLine[], wasCached: boolean, cachedDirty: boolean, cachedTexts?: string[]) => {
      if (cancelled) return
      if (cachedTexts) {
        // Use cached editor texts (preserves unsaved edits) — noteIds come from tracked lines
        const noteLines = cachedTexts.map((text, i) => ({
          text,
          noteIds: lines[i]?.noteId ? [lines[i]!.noteId!] : [],
        }))
        editorState.initFromNoteLines(noteLines)
      } else {
        // Fresh load — noteIds come from loaded NoteLine data
        const noteLines = lines.map((l) => ({
          text: l.content,
          noteIds: l.noteId ? [l.noteId] : [],
        }))
        editorState.initFromNoteLines(noteLines)
      }
      editorState.requestFocusUpdate()

      trackedLinesRef.current = lines

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

      setDirty(wasCached ? cachedDirty : false)
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
        statsRepo.recordView(noteId)
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
      populateEditor(cached.trackedLines, true, true, cached.editorTexts)
      setShowLoading(false)
      scheduleRecordView()

      // Refresh tracked line IDs from NoteStore once any inflight save echoes
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
              `loaded NoteStore. Tracked line IDs will be stale until next save.\n` +
              `Stack:\n${new Error().stack ?? '(unavailable)'}`,
            )
          }
          return
        }
        const freshContent = storeLines.map((l) => l.content).join('\n')
        const currentContent = editorState.lines.map((l) => l.text).join('\n')
        if (freshContent === currentContent) {
          trackedLinesRef.current = storeLines
          editorState.updateNoteIds(
            storeLines.map((l) => (l.noteId ? [l.noteId] : [])),
          )
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
            populateEditor(storeLines, false, false)
            setShowLoading(false)
            scheduleRecordView()
            return
          }
        }

        const { lines, showCompleted } = await repo.loadNoteWithChildren(noteId)
        setShowCompleted(showCompleted)
        populateEditor(lines, false, false)
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

      const noteLines = storeLines.map((l) => ({
        text: l.content,
        noteIds: l.noteId ? [l.noteId] : [],
      }))
      editorState.initFromNoteLines(noteLines, true)
      editorState.requestFocusUpdate()
      trackedLinesRef.current = storeLines
      setShowCompleted(storeNote.showCompleted ?? true)
      setDirty(false)
      setRenderVersion((v) => v + 1)
    })
  }, [noteId, editorState])

  // Core save logic — accepts noteId as parameter to avoid stale closure bugs
  const saveNoteById = useCallback(async (targetNoteId: string) => {
    try {
      setSaving(true)
      savingRef.current = true
      controller.sortCompletedToBottom()
      controller.commitUndoState(true)

      // Build tracked lines from editor state's noteIds (already kept in sync
      // through all operations: indent, split, merge, paste, move, etc.)
      // resolveNoteIds deduplicates when multiple lines claim the same noteId.
      const currentLines = editorState.lines.map((l) => l.text)
      const currentNoteIds = editorState.lines.map((l) => l.noteIds)
      let newTracked = resolveNoteIds(currentLines, currentNoteIds)
      // Ensure first line always maps to the parent noteId
      if (newTracked.length > 0 && newTracked[0]!.noteId !== targetNoteId) {
        newTracked = [{ ...newTracked[0]!, noteId: targetNoteId }, ...newTracked.slice(1)]
      }

      const createdIds = await repo.saveNoteWithChildren(targetNoteId, newTracked)

      // Update tracked lines with newly created IDs
      const updatedTracked = newTracked.map((line, index) => {
        const newId = createdIds.get(index)
        return newId ? { ...line, noteId: newId } : line
      })
      trackedLinesRef.current = updatedTracked

      // Push updated noteIds back into editor state so the UI reflects new IDs
      editorState.updateNoteIds(
        updatedTracked.map((l) => (l.noteId ? [l.noteId] : []))
      )

      setDirty(false)
    } catch (e) {
      setSaveError(e instanceof Error ? e.message : ERROR_SAVE)
      throw e // Re-throw so unmount/beforeunload callers can persist the error
    } finally {
      setSaving(false)
      savingRef.current = false
    }
  }, [editorState, controller])

  const toggleShowCompleted = useCallback(async () => {
    if (!noteId) return
    const newValue = !showCompleted
    setShowCompleted(newValue)
    try {
      await repo.updateShowCompleted(noteId, newValue)
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

  // Override `save` so it also fires when only needsFix is true (not just dirty).
  const saveForFixOrEdit = useCallback(async () => {
    if (!noteId) return
    if (!dirty && !needsFix) return
    await saveNoteById(noteId)
    if (needsFix) noteStore.markNoteFixed(noteId)
  }, [noteId, dirty, needsFix, saveNoteById])

  return {
    controller,
    editorState,
    loading,
    loadedNoteId,
    showLoading,
    saving,
    error,
    saveError,
    clearSaveError,
    dirty,
    save: saveForFixOrEdit,
    showCompleted,
    toggleShowCompleted,
    needsFix,
    notesNeedingFix,
  }
}
