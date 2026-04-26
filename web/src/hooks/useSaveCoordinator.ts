import { useCallback, useEffect, useRef, useState } from 'react'
import type { NoteLine } from '@/data/Note'
import { NoteRepository } from '@/data/NoteRepository'
import { noteStore } from '@/data/NoteStore'
import type { EditorState } from '@/editor/EditorState'
import type { InlineEditSession } from '@/editor/InlineEditSession'
import type { InlineSessionManager } from '@/editor/InlineSessionManager'
import type { SaveStatus } from '@/components/CommandBar'
import { db, auth } from '@/firebase/config'

const noteRepo = new NoteRepository(db, auth)

interface UseSaveCoordinatorOptions {
  noteId: string | null | undefined
  editorState: EditorState
  save: () => Promise<void>
  dirty: boolean
  sessionManager: InlineSessionManager
  invalidateAndRecompute: () => void
  pendingOnceCacheEntries: Record<string, Record<string, unknown>> | null
}

/**
 * Coordinates saving across the main note and all dirty inline sessions.
 *
 * Owns: saveStatus state, the unified saveAll entry point, the
 * inline-session save path, beforeunload + on-unmount auto-save, and the
 * once-cache flush on the main save path.
 */
export function useSaveCoordinator({
  noteId,
  editorState,
  save,
  dirty,
  sessionManager,
  invalidateAndRecompute,
  pendingOnceCacheEntries,
}: UseSaveCoordinatorOptions) {
  const handleViewNoteSave = useCallback(async (
    viewedNoteId: string,
    trackedLines: NoteLine[],
  ): Promise<Map<number, string>> => {
    // Optimistic store update so directives see the edit before Firestore confirms
    const newContent = trackedLines.map(l => l.content).join('\n')
    const existing = noteStore.getNoteById(viewedNoteId)
    if (existing) {
      noteStore.updateNote(viewedNoteId, { ...existing, content: newContent })
    }
    // The inline editor only has this note's direct lines, not nested sub-trees
    // from view directives. saveNoteWithFullContent loads the existing tree and
    // matches by content, preserving grandchild relationships.
    const savePromise = noteRepo.saveNoteWithFullContent(viewedNoteId, newContent)
    noteStore.trackSave(viewedNoteId, savePromise)
    const createdIds = await savePromise
    invalidateAndRecompute()
    return createdIds
  }, [invalidateAndRecompute])

  const saveWithDirectives = useCallback(async () => {
    await save()
    if (noteId) {
      const existing = noteStore.getNoteById(noteId)
      if (existing) {
        noteStore.updateNote(noteId, { ...existing, content: editorState.text })
      }
      // Flush staged once cache entries (safe here — main save already wrote the doc)
      if (pendingOnceCacheEntries) {
        const updates: Record<string, unknown> = {}
        for (const [cacheKey, value] of Object.entries(pendingOnceCacheEntries)) {
          updates[`onceCache.${cacheKey}`] = value
        }
        const { doc: docRef, updateDoc: updateDocFn, getFirestore: getDb } = await import('firebase/firestore')
        updateDocFn(docRef(getDb(), 'notes', noteId), updates).catch((e) => {
          console.error('Failed to persist once cache entries:', e)
        })
      }
    }
    invalidateAndRecompute()
  }, [save, noteId, editorState, invalidateAndRecompute, pendingOnceCacheEntries])

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
        }),
      )
      const results = await Promise.allSettled([mainSave, ...inlineSaves])
      const failures = results.filter(r => r.status === 'rejected')
      setSaveStatus(failures.length > 0 ? 'partial-error' : 'saved')
    } catch {
      setSaveStatus('partial-error')
    }
  }, [saveWithDirectives, sessionManager, saveInlineSession])

  useEffect(() => {
    if (anyDirty && (saveStatus === 'saved' || saveStatus === 'partial-error')) {
      setSaveStatus('idle')
    }
  }, [anyDirty, saveStatus])

  // Refs for cleanup callbacks (avoid stale closures on unmount/beforeunload)
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

  return { saveAll, saveStatus, anyDirty }
}
