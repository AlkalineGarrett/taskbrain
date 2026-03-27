/**
 * Pure functions for reconstructing top-level note content from Firestore's
 * tree structure. Extracted from NoteStore for testability.
 */

import type { Note } from './Note'
import { flattenTreeToLines } from './NoteTree'

/**
 * Rebuild all reconstructed notes from a raw notes map.
 * Returns top-level notes with content reconstructed from their tree descendants.
 */
export function rebuildAllNotes(rawNotes: Map<string, Note>): Note[] {
  const topLevel: Note[] = []
  const descendantsByRoot = new Map<string, Note[]>()

  for (const note of rawNotes.values()) {
    if (note.parentNoteId == null) {
      topLevel.push(note)
    }
    if (note.rootNoteId != null) {
      const list = descendantsByRoot.get(note.rootNoteId)
      if (list) list.push(note)
      else descendantsByRoot.set(note.rootNoteId, [note])
    }
  }

  return topLevel.map(note =>
    reconstructNoteContent(note, descendantsByRoot.get(note.id), rawNotes)
  )
}

/**
 * Rebuild only the top-level notes whose trees were affected by changes.
 * Returns a new array with affected notes updated in place.
 */
export function rebuildAffectedNotes(
  currentReconstructed: Note[],
  affectedRootIds: Set<string>,
  rawNotes: Map<string, Note>
): Note[] {
  const descendantsByRoot = new Map<string, Note[]>()
  for (const note of rawNotes.values()) {
    if (note.rootNoteId != null && affectedRootIds.has(note.rootNoteId)) {
      const list = descendantsByRoot.get(note.rootNoteId)
      if (list) list.push(note)
      else descendantsByRoot.set(note.rootNoteId, [note])
    }
  }

  let changed = false
  const newNotes = [...currentReconstructed]

  for (const rootId of affectedRootIds) {
    const rootNote = rawNotes.get(rootId)
    if (!rootNote || rootNote.parentNoteId != null) {
      const idx = newNotes.findIndex(n => n.id === rootId)
      if (idx >= 0) {
        newNotes.splice(idx, 1)
        changed = true
      }
      continue
    }

    const reconstructed = reconstructNoteContent(rootNote, descendantsByRoot.get(rootId), rawNotes)
    const idx = newNotes.findIndex(n => n.id === rootId)
    if (idx >= 0) {
      newNotes[idx] = reconstructed
      changed = true
    } else {
      newNotes.push(reconstructed)
      changed = true
    }
  }

  return changed ? newNotes : currentReconstructed
}

/**
 * Reconstruct a single note's content from its descendants.
 */
export function reconstructNoteContent(
  note: Note,
  descendants: Note[] | undefined,
  rawNotes: Map<string, Note>
): Note {
  if (note.containedNotes.length === 0) return note

  if (descendants && descendants.length > 0) {
    const lines = flattenTreeToLines(note, descendants)
    return { ...note, content: lines.map(l => l.content).join('\n') }
  }

  // Old format fallback
  const parts = [note.content]
  for (const childId of note.containedNotes) {
    if (childId !== '') {
      parts.push(rawNotes.get(childId)?.content ?? '')
    } else {
      parts.push('')
    }
  }
  return { ...note, content: parts.join('\n') }
}
