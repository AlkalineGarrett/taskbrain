import { Timestamp } from 'firebase/firestore'

/** IMPORTANT: Keep in sync with Kotlin Note.kt and docs/schema.md */
export interface Note {
  id: string
  userId: string
  parentNoteId: string | null
  content: string
  createdAt: Timestamp | null
  updatedAt: Timestamp | null
  lastAccessedAt: Timestamp | null
  tags: string[]
  containedNotes: string[]
  state: string | null
  /** Unique path identifier for this note (URL-safe: alphanumeric, -, _, /). */
  path: string
  /** Root note ID for tree queries. Null for root notes, set on all descendants. */
  rootNoteId: string | null
  /** Whether completed (checked) lines are shown. Per-note toggle, defaults to true. */
  showCompleted: boolean
}

export interface NoteLine {
  content: string
  noteId: string | null
}

export function noteFromFirestore(id: string, data: Record<string, unknown>): Note {
  return {
    id,
    userId: (data.userId as string) ?? '',
    parentNoteId: (data.parentNoteId as string) ?? null,
    content: (data.content as string) ?? '',
    createdAt: (data.createdAt as Timestamp) ?? null,
    updatedAt: (data.updatedAt as Timestamp) ?? null,
    lastAccessedAt: (data.lastAccessedAt as Timestamp) ?? null,
    tags: (data.tags as string[]) ?? [],
    containedNotes: (data.containedNotes as string[]) ?? [],
    state: (data.state as string) ?? null,
    path: (data.path as string) ?? '',
    rootNoteId: (data.rootNoteId as string) ?? null,
    showCompleted: (data.showCompleted as boolean) ?? true,
  }
}
