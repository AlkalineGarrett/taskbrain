import { useCallback, useEffect, useRef, useState } from 'react'
import { doc, updateDoc } from 'firebase/firestore'
import type { NoteLine } from '@/data/Note'
import { NoteRepository, withStampedWrite, type SaveResult } from '@/data/NoteRepository'
import { noteStore } from '@/data/NoteStore'
import type { InlineEditSession } from '@/editor/InlineEditSession'
import type { InlineSessionManager } from '@/editor/InlineSessionManager'
import type { SaveStatus } from '@/components/CommandBar'
import { db, auth } from '@/firebase/config'

const noteRepo = new NoteRepository(db, auth)

interface UseSaveCoordinatorOptions {
  noteId: string | null | undefined
  prepareMainSaveItem: (targetNoteId: string) => {
    trackedLines: NoteLine[]
    localBases: Map<string, string[]> | null
    text: string
    applyResult: (result: SaveResult) => void
  }
  setSaveError: (msg: string | null) => void
  dirty: boolean
  /** True when the current note is flagged as needing a structural fix
   *  (e.g., orphan refs in `containedNotes`). Triggers a main-note save
   *  even when not dirty so the editor's filtered view (which already
   *  drops the orphan) is written through to Firestore. */
  needsFix: boolean
  sessionManager: InlineSessionManager
  invalidateAndRecompute: () => void
  pendingOnceCacheEntries: Record<string, Record<string, unknown>> | null
}

interface SaveSlot {
  noteId: string
  trackedLines: NoteLine[]
  localBases: Map<string, string[]> | null
  applyResult: (result: SaveResult) => void
}

const EMPTY_SAVE_RESULT: SaveResult = {
  createdIds: new Map(),
  postSaveContainedNotes: new Map(),
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
  prepareMainSaveItem,
  setSaveError,
  dirty,
  needsFix,
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
    // saveNoteWithFullContent is a legacy unmount/beforeunload path that
    // doesn't track the edit-session's localBases (`null` is passed into
    // saveNoteWithChildren), so there's no localBase to refresh here. The
    // next full session load will populate it from rawNotes (which by then
    // will have caught up via the listener).
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
    // Stamp + register so the listener treats this as our own echo —
    // otherwise toggling once-cache fires a spurious editor reload.
    await withStampedWrite(async (_, stamp) => {
      await updateDoc(doc(db, 'notes', noteId), { ...updates, ...stamp })
    }, noteStore.getRawNoteById(noteId) ?? undefined).catch((e) => {
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
    // needsFix forces a main-note save even when not dirty: the editor's
    // reconstructed view already drops the orphan ref, so writing the
    // current containedNotes through to Firestore heals the doc.
    const willSaveMain = !!noteId && (dirty || needsFix)
    if (!willSaveMain && dirtySessions.length === 0) {
      setSaveStatus('saved')
      return
    }

    for (const session of dirtySessions) {
      session.controller.sortCompletedToBottom()
    }

    try {
      const slots: SaveSlot[] = []

      if (willSaveMain && noteId) {
        const main = prepareMainSaveItem(noteId)
        slots.push({
          noteId,
          trackedLines: main.trackedLines,
          localBases: main.localBases,
          applyResult: main.applyResult,
        })
        noteStore.updateContentIfChanged(noteId, main.text)
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
            localBases: session.getLocalBases(),
            applyResult: (result) => {
              session.applyCreatedIds(result.createdIds)
              session.markSaved(content)
              session.refreshLocalBase(result.postSaveContainedNotes)
            },
          }
        }),
      )
      for (let i = 0; i < dirtySessions.length; i++) {
        const session = dirtySessions[i]!
        slots.push(inlineSlots[i]!)
        noteStore.updateContentIfChanged(session.noteId, session.getText())
      }

      const items = slots.map((s) => ({
        noteId: s.noteId,
        trackedLines: s.trackedLines,
        localBases: s.localBases,
      }))
      const savePromise = noteStore.enqueueSave(() => noteRepo.saveMultipleNotes(items))
      // Single-promise fan-out: every dirty noteId sees the batch in
      // awaitPendingSave, matching the all-or-nothing batch contract.
      for (const slot of slots) noteStore.trackSave(slot.noteId, savePromise)
      const resultsByNote = await savePromise

      for (const slot of slots) {
        slot.applyResult(resultsByNote.get(slot.noteId) ?? EMPTY_SAVE_RESULT)
      }
      // Optimistic UI flip: the save wrote the editor's filtered view as
      // the new containedNotes, so the parent no longer references the
      // orphan. Clear before the listener echo arrives.
      if (needsFix && noteId) noteStore.markNoteFixed(noteId)

      await flushOnceCacheEntries()
      invalidateAndRecompute()
      setSaveStatus('saved')
    } catch (e) {
      console.error('saveAll failed:', e)
      setSaveError(e instanceof Error ? e.message : 'Save failed')
      setSaveStatus('partial-error')
    }
  }, [noteId, dirty, needsFix, sessionManager, prepareMainSaveItem, setSaveError, flushOnceCacheEntries, invalidateAndRecompute])

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
