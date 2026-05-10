import { useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import { NoteRepository } from '@/data/NoteRepository'
import { noteStore } from '@/data/NoteStore'
import { removeTab } from '@/components/RecentTabsBar'
import { getDb, auth } from '@/firebase/config'

export function useNoteDeletion(noteId: string | null | undefined) {
  const navigate = useNavigate()

  const handleDeleteNote = useCallback(async () => {
    if (!noteId) return
    try {
      const noteRepo = new NoteRepository(getDb(), auth)
      await noteRepo.softDeleteNote(noteId)
      const nextNoteId = await removeTab(noteId)
      navigate(nextNoteId ? `/note/${nextNoteId}` : '/')
    } catch (e) {
      console.error('Failed to delete note:', e)
    }
  }, [noteId, navigate])

  const handleRestoreNote = useCallback(async () => {
    if (!noteId) return
    try {
      const noteRepo = new NoteRepository(getDb(), auth)
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
