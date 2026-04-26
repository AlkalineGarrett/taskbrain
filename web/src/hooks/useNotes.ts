import { useCallback, useEffect, useState } from 'react'
import type { NoteStats } from '@/data/NoteStats'
import { NoteRepository } from '@/data/NoteRepository'
import { NoteStatsRepository } from '@/data/NoteStatsRepository'
import { noteStore } from '@/data/NoteStore'
import { useAllNotes } from '@/hooks/useNoteStore'
import {
  filterAndSortNotesByMode,
  filterAndSortDeletedNotes,
  type NoteSortMode,
} from '@/data/NoteFilteringUtils'
import { db, auth } from '@/firebase/config'
import { ERROR_LOAD } from '@/strings'

const repo = new NoteRepository(db, auth)
const statsRepo = new NoteStatsRepository(db, auth)

export function useNotes() {
  // Notes come from the live NoteStore listener — no explicit Firestore read.
  // Mutations write only; the listener delivers the resulting state via docChanges.
  const allNotes = useAllNotes()
  const [stats, setStats] = useState<Map<string, NoteStats>>(new Map())
  const [storeLoaded, setStoreLoaded] = useState(noteStore.isLoaded())
  const [statsLoaded, setStatsLoaded] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [sortMode, setSortMode] = useState<NoteSortMode>('recent')

  useEffect(() => {
    if (storeLoaded) return
    let cancelled = false
    void noteStore.ensureLoaded().then(() => {
      if (!cancelled) setStoreLoaded(true)
    })
    return () => { cancelled = true }
  }, [storeLoaded])

  const loadStats = useCallback(async () => {
    try {
      setError(null)
      const allStats = await statsRepo.loadAllNoteStats()
      setStats(allStats)
    } catch (e) {
      setError(e instanceof Error ? e.message : ERROR_LOAD)
    } finally {
      setStatsLoaded(true)
    }
  }, [])

  useEffect(() => { void loadStats() }, [loadStats])

  // Notes are live; refresh re-pulls only stats (which has no listener).
  const refresh = useCallback(async () => {
    await loadStats()
  }, [loadStats])

  const loading = !storeLoaded || !statsLoaded

  const activeNotes = filterAndSortNotesByMode(allNotes, stats, sortMode, Date.now())
  const deletedNotes = filterAndSortDeletedNotes(allNotes)

  // Mutations propagate failures via setError so the user sees something
  // explicit (e.g., a NoteStoreNotLoadedError if a delete is clicked before
  // the listener has loaded). Re-throw so callers can chain on success.
  const surfaceMutationError = useCallback((e: unknown): never => {
    setError(e instanceof Error ? e.message : ERROR_LOAD)
    throw e
  }, [])

  const createNote = useCallback(async (): Promise<string> => {
    return repo.createNote().catch(surfaceMutationError)
  }, [surfaceMutationError])

  const deleteNote = useCallback(async (noteId: string) => {
    await repo.softDeleteNote(noteId).catch(surfaceMutationError)
  }, [surfaceMutationError])

  const undeleteNote = useCallback(async (noteId: string) => {
    await repo.undeleteNote(noteId).catch(surfaceMutationError)
  }, [surfaceMutationError])

  const clearDeleted = useCallback(async (): Promise<number> => {
    return repo.hardDeleteAllSoftDeleted().catch(surfaceMutationError)
  }, [surfaceMutationError])

  return {
    activeNotes,
    deletedNotes,
    stats,
    loading,
    error,
    sortMode,
    setSortMode,
    createNote,
    deleteNote,
    undeleteNote,
    clearDeleted,
    refresh,
  }
}

export { repo as noteRepository }
