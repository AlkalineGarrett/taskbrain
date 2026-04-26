import { useCallback, useEffect, useState } from 'react'
import type { Note } from '@/data/Note'
import type { NoteStats } from '@/data/NoteStats'
import { NoteRepository } from '@/data/NoteRepository'
import { NoteStatsRepository } from '@/data/NoteStatsRepository'
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
  const [notes, setNotes] = useState<Note[]>([])
  const [stats, setStats] = useState<Map<string, NoteStats>>(new Map())
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [sortMode, setSortMode] = useState<NoteSortMode>('recent')

  const refresh = useCallback(async () => {
    try {
      setLoading(true)
      setError(null)
      const [allNotes, allStats] = await Promise.all([
        repo.loadAllUserNotes(),
        statsRepo.loadAllNoteStats(),
      ])
      setNotes(allNotes)
      setStats(allStats)
    } catch (e) {
      setError(e instanceof Error ? e.message : ERROR_LOAD)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void refresh()
  }, [refresh])

  const activeNotes = filterAndSortNotesByMode(notes, stats, sortMode, Date.now())
  const deletedNotes = filterAndSortDeletedNotes(notes)

  const createNote = useCallback(async (): Promise<string> => {
    const id = await repo.createNote()
    await refresh()
    return id
  }, [refresh])

  const deleteNote = useCallback(
    async (noteId: string) => {
      await repo.softDeleteNote(noteId)
      await refresh()
    },
    [refresh],
  )

  const undeleteNote = useCallback(
    async (noteId: string) => {
      await repo.undeleteNote(noteId)
      await refresh()
    },
    [refresh],
  )

  const clearDeleted = useCallback(async (): Promise<number> => {
    const count = await repo.hardDeleteAllSoftDeleted()
    await refresh()
    return count
  }, [refresh])

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
