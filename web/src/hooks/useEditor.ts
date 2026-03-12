import { useCallback, useEffect, useRef, useState } from 'react'
import { EditorState } from '@/editor/EditorState'
import { EditorController } from '@/editor/EditorController'
import { UndoManager, type UndoManagerState } from '@/editor/UndoManager'
import { LineState } from '@/editor/LineState'
import type { NoteLine } from '@/data/Note'
import { NoteRepository, matchLinesToIds } from '@/data/NoteRepository'
import { db, auth } from '@/firebase/config'

const repo = new NoteRepository(db, auth)

/** In-memory cache for instant tab switching. */
interface CachedNoteContent {
  lines: NoteLine[]
  editorTexts: string[]
  dirty: boolean
}
const contentCache = new Map<string, CachedNoteContent>()

export function useEditor(noteId: string | undefined) {
  const [editorState] = useState(() => new EditorState())
  const [undoManager] = useState(() => new UndoManager())
  const [controller] = useState(() => new EditorController(editorState, undoManager))

  const [loading, setLoading] = useState(true)
  const [showLoading, setShowLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [dirty, setDirty] = useState(false)

  // Track line IDs for Firestore mapping
  const trackedLinesRef = useRef<NoteLine[]>([])

  // Force re-render when editor state changes
  const [, setRenderVersion] = useState(0)

  useEffect(() => {
    editorState.onTextChange = () => {
      setDirty(true)
      setRenderVersion((v) => v + 1)
    }
    return () => {
      editorState.onTextChange = null
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

  // Cache content when switching away from a note
  const currentNoteIdRef = useRef<string | undefined>(undefined)
  useEffect(() => {
    return () => {
      const prevId = currentNoteIdRef.current
      if (prevId && trackedLinesRef.current.length > 0) {
        contentCache.set(prevId, {
          lines: trackedLinesRef.current,
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
      // Use cached editor texts if available (preserves unsaved edits)
      const lineTexts = cachedTexts ?? lines.map((l) => l.content)
      editorState.lines = lineTexts.map((t) => new LineState(t))
      editorState.focusedLineIndex = 0
      editorState.clearSelection()
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
    }

    // Try cache first for instant display
    const cached = contentCache.get(noteId)
    if (cached) {
      populateEditor(cached.lines, true, cached.dirty, cached.editorTexts)
      contentCache.delete(noteId)
      void repo.updateLastAccessed(noteId)
      return () => { cancelled = true }
    }

    // Load from Firestore
    setLoading(true)
    const loadNote = async () => {
      try {
        setError(null)
        const lines = await repo.loadNoteWithChildren(noteId)

        populateEditor(lines, false, false)
        setShowLoading(false)

        // Update last accessed
        void repo.updateLastAccessed(noteId)
      } catch (e) {
        if (!cancelled) {
          setError(e instanceof Error ? e.message : 'Failed to load note')
          setLoading(false)
        }
      }
    }

    void loadNote()
    return () => { cancelled = true }
  }, [noteId, editorState, controller, undoManager])

  // Save
  const save = useCallback(async () => {
    if (!noteId || !dirty) return

    try {
      setSaving(true)
      controller.commitUndoState(true)

      // Build tracked lines from current editor state + tracked IDs
      const currentLines = editorState.lines.map((l) => l.text)
      const existingTracked = trackedLinesRef.current

      // Re-match lines to IDs using the same algorithm as Android
      const newTracked = matchLinesToIds(
        noteId,
        existingTracked,
        currentLines,
      )

      const createdIds = await repo.saveNoteWithChildren(noteId, newTracked)

      // Update tracked lines with newly created IDs
      const updatedTracked = newTracked.map((line, index) => {
        const newId = createdIds.get(index)
        return newId ? { ...line, noteId: newId } : line
      })
      trackedLinesRef.current = updatedTracked

      setDirty(false)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to save')
    } finally {
      setSaving(false)
    }
  }, [noteId, dirty, editorState, controller])

  return {
    controller,
    editorState,
    loading,
    showLoading,
    saving,
    error,
    dirty,
    save,
  }
}
