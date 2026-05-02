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
   * Monotonically-increasing per-note write counter. Bumped on every successful save.
   * Used by listeners to drop out-of-order/stale echoes (apply only if
   * version > lastAppliedVersion). Advisory — not enforced via transactions;
   * concurrent writes from two clients can both produce the same version, in
   * which case last-write-wins at Firestore. Optional; absent means 0
   * (legacy docs predating this field). Read sites should default to 0.
   */
  version?: number
  /**
   * Identity stamp written by the save that produced this revision. The local
   * listener checks each echo's lastWriterOpId against an in-memory set of
   * in-flight saves; matches are treated as our own echo (raw cache update
   * only, no editor reload). Distinct UUID per save batch. Optional; absent
   * on legacy docs and on writes from clients predating this field.
   */
  lastWriterOpId?: string | null
  /**
   * Snapshot of containedNotes as the saving client read it before applying
   * its diff. Receiving clients use this for 3-way merge: base = this field,
   * local = client's current view, remote = the new containedNotes. Only
   * present on writes that staged a 3-way-merge-eligible change.
   */
  containedNotesBase?: string[] | null
}

export interface NoteLine {
  content: string
  noteId: string | null
}

/** Shape consumed by `EditorState.initFromNoteLines`. */
export interface EditorLineInput {
  text: string
  noteIds: string[]
}

export function toEditorLines(lines: NoteLine[]): EditorLineInput[] {
  return lines.map(l => ({
    text: l.content,
    noteIds: l.noteId ? [l.noteId] : [],
  }))
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
    version: (data.version as number) ?? 0,
    lastWriterOpId: (data.lastWriterOpId as string) ?? null,
    containedNotesBase: (data.containedNotesBase as string[]) ?? null,
  }
}
