import { useSyncExternalStore } from 'react'
import { noteStore } from '@/data/NoteStore'

/**
 * Subscribe to the full notes array from the NoteStore singleton.
 * Re-renders when any note is added, updated, or removed.
 */
export function useAllNotes() {
  return useSyncExternalStore(noteStore.subscribe, noteStore.getSnapshot)
}

/**
 * Subscribe to NoteStore sync errors (e.g., Firestore listener failures).
 */
export function useNoteStoreError() {
  return useSyncExternalStore(noteStore.subscribeError, noteStore.getErrorSnapshot)
}
