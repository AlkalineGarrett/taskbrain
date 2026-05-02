import { toEditorLines, type Note, type NoteLine } from '@/data/Note'
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
   * Existing sessions (including dirty ones) are preserved. Returns true
   * if any new sessions were created.
   *
   * [loadLines] resolves a note's tracked lines (root + descendants with
   * real noteIds) — typically backed by [NoteRepository.loadNoteLinesAwait],
   * which awaits the listener and falls back to a Firestore read so we
   * never initialize a session from synthesized null-id lines.
   */
  async ensureSessions(
    notes: Note[],
    loadLines: (noteId: string) => Promise<NoteLine[]>,
  ): Promise<boolean> {
    const toCreate = notes.filter((n) => !this.sessions.has(n.id))
    if (toCreate.length === 0) return false
    const resolved = await Promise.all(
      toCreate.map(async (note) => [note, await loadLines(note.id)] as const),
    )
    let created = false
    for (const [note, lines] of resolved) {
      if (this.sessions.has(note.id)) continue
      this.sessions.set(note.id, new InlineEditSession(note.id, lines))
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
    if (!storeLines) {
      // No structured lines available — the note vanished from the store
      // (mid-save echo gap, delete, etc.). Skip the sync; the prior session
      // state is closer to truth than what updateFromText would produce,
      // since that path reconciles by content match and silently drops
      // noteIds whose lines no longer match. A subsequent snapshot that
      // restores the note will trigger another syncExternalChanges call.
      // Benign during mid-save echo / just-deleted-note races. The next
      // snapshot that restores structured lines will trigger another sync.
      console.debug(
        `InlineSessionManager.syncExternalChanges(${noteId}): skipping — ` +
        `NoteStore has no structured lines.`,
      )
      return
    }

    session.editorState.initFromNoteLines(toEditorLines(storeLines), true)
    session.syncOriginalContent(newContent)
    session.updateHiddenIndices()
  }

  has(noteId: string): boolean {
    return this.sessions.has(noteId)
  }

  clear(): void {
    this.sessions.clear()
  }
}
