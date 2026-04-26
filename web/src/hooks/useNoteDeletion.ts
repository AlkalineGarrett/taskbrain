import { useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import { NoteRepository } from '@/data/NoteRepository'
import { noteStore } from '@/data/NoteStore'
import { removeTab } from '@/components/RecentTabsBar'
import { db, auth } from '@/firebase/config'

const noteRepo = new NoteRepository(db, auth)

export function useNoteDeletion(noteId: string | null | undefined) {
  const navigate = useNavigate()

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

  return { handleDeleteNote, handleRestoreNote }
}
