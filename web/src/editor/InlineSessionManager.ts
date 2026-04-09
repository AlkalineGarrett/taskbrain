import type { Note } from '@/data/Note'
import { noteStore } from '@/data/NoteStore'
import { InlineEditSession } from './InlineEditSession'

/**
 * Centralized manager for inline edit sessions.
 *
 * Creates sessions eagerly when notes are discovered in view directives,
 * so that clicking into any embedded note is instant (no lazy creation).
 * Sessions survive across re-renders and are only removed when their
 * note disappears from the directive results.
 */
export class InlineSessionManager {
  private readonly sessions = new Map<string, InlineEditSession>()

  /**
   * Ensure sessions exist for the given notes, creating new ones as needed.
   * Existing sessions (including dirty ones) are preserved.
   * Returns true if any new sessions were created.
   */
  ensureSessions(notes: Note[]): boolean {
    let created = false
    for (const note of notes) {
      if (this.sessions.has(note.id)) continue
      const lineNoteIds = this.deriveLineNoteIds(note.id)
      const session = new InlineEditSession(note.id, note.content, lineNoteIds)
      this.sessions.set(note.id, session)
      created = true
    }
    return created
  }

  /**
   * Remove sessions for notes no longer in view directives.
   * Returns any dirty sessions that were removed (caller may want to save them).
   */
  removeStale(activeNoteIds: Set<string>): InlineEditSession[] {
    const removed: InlineEditSession[] = []
    for (const [noteId, session] of this.sessions) {
      if (!activeNoteIds.has(noteId)) {
        if (session.isDirty) removed.push(session)
        this.sessions.delete(noteId)
      }
    }
    return removed
  }

  getSession(noteId: string): InlineEditSession | undefined {
    return this.sessions.get(noteId)
  }

  getAllSessions(): InlineEditSession[] {
    return [...this.sessions.values()]
  }

  getAllDirtySessions(): InlineEditSession[] {
    return [...this.sessions.values()].filter(s => s.isDirty)
  }

  /**
   * Sync external content changes into non-dirty sessions.
   * Call this when note content changes from Firestore.
   */
  syncExternalChanges(noteId: string, newContent: string): void {
    const session = this.sessions.get(noteId)
    if (!session || session.isDirty) return
    if (session.getText() === newContent) return

    const storeLines = noteStore.getNoteLinesById(noteId)
    if (storeLines) {
      const noteLines = storeLines.map(nl => ({
        text: nl.content,
        noteIds: nl.noteId ? [nl.noteId] : [],
      }))
      session.editorState.initFromNoteLines(noteLines, true)
    } else {
      session.editorState.updateFromText(newContent)
    }
    session.syncOriginalContent(newContent)
    session.updateHiddenIndices()
  }

  has(noteId: string): boolean {
    return this.sessions.has(noteId)
  }

  clear(): void {
    this.sessions.clear()
  }

  private deriveLineNoteIds(noteId: string): string[][] | undefined {
    const noteLines = noteStore.getNoteLinesById(noteId)
    if (!noteLines) return undefined
    return noteLines.map(nl => nl.noteId ? [nl.noteId] : [])
  }
}
