import { useCallback, useEffect, useRef, useState } from 'react'
import { EditorState } from '@/editor/EditorState'
import { EditorController } from '@/editor/EditorController'
import { UndoManager, type UndoManagerState } from '@/editor/UndoManager'
import type { NoteLine } from '@/data/Note'
import { NoteRepository } from '@/data/NoteRepository'
import { noteStore } from '@/data/NoteStore'
import { resolveNoteIds } from '@/editor/resolveNoteIds'
import { db, auth } from '@/firebase/config'
import { ERROR_LOAD, ERROR_SAVE, SAVE_ERROR_BANNER } from '@/strings'

const PENDING_SAVE_ERROR_KEY = 'pendingSaveError'

const repo = new NoteRepository(db, auth)

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

    if (cached?.dirty) {
      // Restore unsaved edits immediately for instant display
      populateEditor(cached.trackedLines, true, true, cached.editorTexts)
      setShowLoading(false)
      void repo.updateLastAccessed(noteId)

      // Refresh tracked line IDs from Firestore (the fire-and-forget save
      // may have created new child notes with IDs we don't have yet)
      void repo.loadNoteWithChildren(noteId).then((freshLines) => {
        if (cancelled) return
        const freshContent = freshLines.map((l) => l.content).join('\n')
        const currentContent = editorState.lines.map((l) => l.text).join('\n')
        if (freshContent === currentContent) {
          trackedLinesRef.current = freshLines
          editorState.updateNoteIds(
            freshLines.map((l) => (l.noteId ? [l.noteId] : [])),
          )
          setDirty(false)
        }
      }).catch(() => { /* editor state is still usable */ })

      return () => { cancelled = true }
    }

    // Load from Firestore — canonical path for clean notes
    setLoading(true)
    const loadNote = async () => {
      try {
        setError(null)
        // Await any pending inline-edit saves for this note to avoid reading stale data
        await noteStore.awaitPendingSave(noteId)
        const [lines, note] = await Promise.all([
          repo.loadNoteWithChildren(noteId),
          repo.loadNoteById(noteId),
        ])

        setShowCompleted(note?.showCompleted ?? true)
        // If we had a clean cache entry, use its tracked lines for noteId mappings
        // but content from Firestore (always fresh)
        populateEditor(lines, false, false)
        setShowLoading(false)

        // Update last accessed
        void repo.updateLastAccessed(noteId)
      } catch (e) {
        if (!cancelled) {
          setError(e instanceof Error ? e.message : ERROR_LOAD)
          setLoading(false)
          setShowLoading(false)
        }
      }
    }

    void loadNote()
    return () => { cancelled = true }
  }, [noteId, editorState, controller, undoManager])

  // Detect external changes via NoteStore's collection listener.
  // When the store's content for the current note changes and the editor isn't dirty,
  // reload the editor to show the external update.
  const suppressStoreReloadRef = useRef(false)
  useEffect(() => {
    if (!noteId) return
    // Suppress the first notification (initial load — editor already has this content)
    suppressStoreReloadRef.current = true

    const unsub = noteStore.subscribe(() => {
      if (suppressStoreReloadRef.current) {
        suppressStoreReloadRef.current = false
        return
      }
      if (dirtyRef.current) return

      const storeNote = noteStore.getNoteById(noteId)
      if (!storeNote) return

      const currentContent = editorState.lines.map((l) => l.text).join('\n')
      if (storeNote.content === currentContent) return

      // External change detected — reload from Firestore for proper noteId mappings
      void (async () => {
        try {
          const freshLines = await repo.loadNoteWithChildren(noteId)
          const freshContent = freshLines.map((l) => l.content).join('\n')
          if (freshContent === editorState.lines.map((l) => l.text).join('\n')) return

          const noteLines = freshLines.map((l) => ({
            text: l.content,
            noteIds: l.noteId ? [l.noteId] : [],
          }))
          editorState.initFromNoteLines(noteLines)
          editorState.requestFocusUpdate()
          trackedLinesRef.current = freshLines
          setDirty(false)
          setRenderVersion((v) => v + 1)
        } catch (e) {
          console.error('NoteStore-triggered reload failed:', e)
        }
      })()
    })

    return unsub
  }, [noteId, editorState])

  // Core save logic — accepts noteId as parameter to avoid stale closure bugs
  const saveNoteById = useCallback(async (targetNoteId: string) => {
    try {
      setSaving(true)
      suppressStoreReloadRef.current = true
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
    }
  }, [editorState, controller])

  // Save — convenience wrapper using current noteId
  const save = useCallback(async () => {
    if (!noteId || !dirty) return
    await saveNoteById(noteId)
  }, [noteId, dirty, saveNoteById])

  const toggleShowCompleted = useCallback(async () => {
    if (!noteId) return
    const newValue = !showCompleted
    setShowCompleted(newValue)
    suppressStoreReloadRef.current = true
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
    save,
    showCompleted,
    toggleShowCompleted,
  }
}
