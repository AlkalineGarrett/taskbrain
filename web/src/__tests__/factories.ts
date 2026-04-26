import type { Note } from '@/data/Note'

/**
 * Build a fully-populated Note for tests, defaulting every required field so
 * call sites only specify what matters to their assertions.
 */
export function note(overrides: Partial<Note> & { id: string }): Note {
  return {
    userId: '',
    parentNoteId: null,
    content: '',
    createdAt: null,
    updatedAt: null,
    tags: [],
    containedNotes: [],
    state: null,
    path: '',
    rootNoteId: null,
    showCompleted: true,
    onceCache: {},
    ...overrides,
  }
}
