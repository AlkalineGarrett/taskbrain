import type { Note } from '@/data/Note'
import { NoteField } from './DirectiveDependencies'

/**
 * Content hashes for a single note.
 */
export interface ContentHashes {
  firstLineHash: string | null
  nonFirstLineHash: string | null
}

export const EMPTY_CONTENT_HASHES: ContentHashes = { firstLineHash: null, nonFirstLineHash: null }

/**
 * Simple synchronous SHA-256 hash using a fast hash approximation.
 * For cache keys we use a fast string hash (not cryptographic).
 */
function simpleHash(input: string): string {
  let hash = 0
  for (let i = 0; i < input.length; i++) {
    const char = input.charCodeAt(i)
    hash = ((hash << 5) - hash + char) | 0
  }
  return (hash >>> 0).toString(16).padStart(8, '0')
}

export function hashFirstLine(content: string): string {
  const firstLine = content.split('\n')[0] ?? ''
  return simpleHash(firstLine)
}

export function hashNonFirstLine(content: string): string {
  const lines = content.split('\n')
  const nonFirstLines = lines.length > 1 ? lines.slice(1).join('\n') : ''
  return simpleHash(nonFirstLines)
}

export function hashField(note: Note, field: NoteField): string {
  let value: string
  switch (field) {
    case NoteField.NAME:
      value = note.content.split('\n')[0] ?? ''
      break
    case NoteField.PATH:
      value = note.path
      break
    case NoteField.MODIFIED:
      value = note.updatedAt?.toDate()?.getTime()?.toString() ?? ''
      break
    case NoteField.CREATED:
      value = note.createdAt?.toDate()?.getTime()?.toString() ?? ''
      break
    case NoteField.VIEWED:
      value = note.lastAccessedAt?.toDate()?.getTime()?.toString() ?? ''
      break
  }
  return simpleHash(value)
}

export function computeContentHashes(
  note: Note,
  needsFirstLine: boolean,
  needsNonFirstLine: boolean,
): ContentHashes {
  return {
    firstLineHash: needsFirstLine ? hashFirstLine(note.content) : null,
    nonFirstLineHash: needsNonFirstLine ? hashNonFirstLine(note.content) : null,
  }
}
