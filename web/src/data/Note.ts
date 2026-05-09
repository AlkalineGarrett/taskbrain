import { Timestamp } from 'firebase/firestore'

/** IMPORTANT: Keep in sync with Kotlin Note.kt and docs/schema.md */
export interface Note {
  id: string
  userId: string
  parentNoteId: string | null
  content: string
  createdAt: Timestamp | null
  updatedAt: Timestamp | null
  tags: string[]
  containedNotes: string[]
  state: string | null
  /** Unique path identifier for this note (URL-safe: alphanumeric, -, _, /). */
  path: string
  /** Root note ID for tree queries. Null for root notes, set on all descendants. */
  rootNoteId: string | null
  /** Whether completed (checked) lines are shown. Per-note toggle, defaults to true. */
  showCompleted: boolean
  /** Persistent cache for once[...] expression results. Keys are normalized AST strings, values are serialized DslValues. */
  onceCache: Record<string, Record<string, unknown>>
  /**
   * Snapshot of containedNotes as the saving client read it before applying
   * its diff. Receiving clients use this for 3-way merge: base = this field,
   * local = client's current view, remote = the new containedNotes. `null`
   * when the writing save didn't stage a merge-eligible change.
   */
  containedNotesBase: string[] | null
  /**
   * Source-tagged batch identifier stamped when this note's `state` flips
   * to a removed state (DELETED). Format: `<source>_<uuid>` — e.g.
   * `whole-note_<uuid>` for `softDeleteNote`, `selection-delete_<uuid>`
   * for an editor selection-delete. Notes deleted as part of the same
   * operation share the same id; later operations get fresh ids. Used by
   * reconstruction (children with matching id are part of the same delete
   * batch), by undelete (scope the restore set), and by forensics
   * (`unknown_*` flags an uninstrumented removal path). Cleared on
   * undelete so a re-delete starts a fresh batch.
   */
  deletionBatchId: string | null
}

/**
 * A line within a note tree at save time. [noteId] is always present —
 * either a real Firestore doc id or a sentinel ("new line, allocate fresh").
 * The save planner enforces this at runtime; the type system captures it
 * here so upstream paths can't silently produce nulls.
 */
export interface NoteLine {
  content: string
  noteId: string
}

/** Shape consumed by `EditorState.initFromNoteLines`. */
export interface EditorLineInput {
  text: string
  noteIds: string[]
}

export function toEditorLines(lines: NoteLine[]): EditorLineInput[] {
  return lines.map(l => ({ text: l.content, noteIds: [l.noteId] }))
}

export function firstLineOf(content: string): string {
  return content.split('\n', 1)[0] ?? ''
}

export function noteFromFirestore(id: string, data: Record<string, unknown>): Note {
  return {
    id,
    userId: (data.userId as string) ?? '',
    parentNoteId: (data.parentNoteId as string) ?? null,
    content: (data.content as string) ?? '',
    createdAt: (data.createdAt as Timestamp) ?? null,
    updatedAt: (data.updatedAt as Timestamp) ?? null,
    tags: (data.tags as string[]) ?? [],
    containedNotes: (data.containedNotes as string[]) ?? [],
    state: (data.state as string) ?? null,
    path: (data.path as string) ?? '',
    rootNoteId: (data.rootNoteId as string) ?? null,
    showCompleted: (data.showCompleted as boolean) ?? true,
    onceCache: (data.onceCache as Record<string, Record<string, unknown>>) ?? {},
    containedNotesBase: (data.containedNotesBase as string[]) ?? null,
    deletionBatchId: (data.deletionBatchId as string) ?? null,
  }
}
