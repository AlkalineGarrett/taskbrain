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
  prepareMainSaveItem: (targetNoteId: string) => {
    trackedLines: NoteLine[]
    applyResult: (createdIds: Map<number, string>) => void
  }
  setSaveError: (msg: string | null) => void
  dirty: boolean
  sessionManager: InlineSessionManager
  invalidateAndRecompute: () => void
  pendingOnceCacheEntries: Record<string, Record<string, unknown>> | null
}

interface SaveSlot {
  noteId: string
  trackedLines: NoteLine[]
  applyResult: (createdIds: Map<number, string>) => void
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
  prepareMainSaveItem,
  setSaveError,
  dirty,
  sessionManager,
  invalidateAndRecompute,
  pendingOnceCacheEntries,
}: UseSaveCoordinatorOptions) {
  /**
   * Single-session save used by unmount/beforeunload paths where the batched
   * saveAll isn't reachable. Partial failures here only ever affect one note.
   */
  const saveInlineSession = useCallback(async (session: InlineEditSession): Promise<boolean> => {
    session.controller.sortCompletedToBottom()
    const content = session.getText()
    noteStore.updateContentIfChanged(session.noteId, content)
    const savePromise = noteStore.enqueueSave(() =>
      noteRepo.saveNoteWithFullContent(session.noteId, content),
    )
    noteStore.trackSave(session.noteId, savePromise)
    const createdIds = await savePromise
    session.applyCreatedIds(createdIds)
    session.markSaved(content)
    invalidateAndRecompute()
    return true
  }, [invalidateAndRecompute])

  const flushOnceCacheEntries = useCallback(async (): Promise<void> => {
    if (!noteId || !pendingOnceCacheEntries) return
    const updates: Record<string, unknown> = {}
    for (const [cacheKey, value] of Object.entries(pendingOnceCacheEntries)) {
      updates[`onceCache.${cacheKey}`] = value
    }
    const { doc: docRef, updateDoc: updateDocFn, getFirestore: getDb } = await import('firebase/firestore')
    updateDocFn(docRef(getDb(), 'notes', noteId), updates).catch((e) => {
      console.error('Failed to persist once cache entries:', e)
    })
  }, [noteId, pendingOnceCacheEntries])

  const [saveStatus, setSaveStatus] = useState<SaveStatus>('idle')
  const anyDirty = dirty || sessionManager.getAllDirtySessions().length > 0

  /**
   * Combines the main editor save (if dirty) and every dirty inline session
   * into a single Firestore batch, so a partial failure can't leave stale
   * content in some notes and saved content in others.
   */
  const saveAll = useCallback(async () => {
    setSaveStatus('saving')
    const dirtySessions = sessionManager.getAllDirtySessions()
    if (!dirty && dirtySessions.length === 0) {
      setSaveStatus('saved')
      return
    }

    for (const session of dirtySessions) {
      session.controller.sortCompletedToBottom()
    }

    try {
      const slots: SaveSlot[] = []

      if (dirty && noteId) {
        const main = prepareMainSaveItem(noteId)
        slots.push({ noteId, trackedLines: main.trackedLines, applyResult: main.applyResult })
        noteStore.updateContentIfChanged(noteId, editorState.text)
      }

      // Resolve inline session tracked lines in parallel: a NoteStore miss
      // falls through to loadNoteWithChildren (Firestore round trip), so
      // serializing them would stack unrelated read latencies.
      const inlineSlots = await Promise.all(
        dirtySessions.map(async (session): Promise<SaveSlot> => {
          const content = session.getText()
          const tracked = await noteRepo.prepareInlineEditTrackedLines(
            session.noteId, content, 'saveAll',
          )
          return {
            noteId: session.noteId,
            trackedLines: tracked,
            applyResult: (createdIds) => {
              session.applyCreatedIds(createdIds)
              session.markSaved(content)
            },
          }
        }),
      )
      for (let i = 0; i < dirtySessions.length; i++) {
        const session = dirtySessions[i]!
        slots.push(inlineSlots[i]!)
        noteStore.updateContentIfChanged(session.noteId, session.getText())
      }

      const items = slots.map((s) => ({ noteId: s.noteId, trackedLines: s.trackedLines }))
      const savePromise = noteStore.enqueueSave(() => noteRepo.saveMultipleNotes(items))
      // Single-promise fan-out: every dirty noteId sees the batch in
      // awaitPendingSave, matching the all-or-nothing batch contract.
      for (const slot of slots) noteStore.trackSave(slot.noteId, savePromise)
      const idsByNote = await savePromise

      for (const slot of slots) {
        slot.applyResult(idsByNote.get(slot.noteId) ?? new Map<number, string>())
      }

      await flushOnceCacheEntries()
      invalidateAndRecompute()
      setSaveStatus('saved')
    } catch (e) {
      console.error('saveAll failed:', e)
      setSaveError(e instanceof Error ? e.message : 'Save failed')
      setSaveStatus('partial-error')
    }
  }, [noteId, editorState, dirty, sessionManager, prepareMainSaveItem, setSaveError, flushOnceCacheEntries, invalidateAndRecompute])

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
