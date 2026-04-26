import { describe, it, expect } from 'vitest'
import { hashFirstLine, hashNonFirstLine, hashField, computeContentHashes } from '../../../dsl/cache/ContentHasher'
import { NoteField } from '../../../dsl/cache/DirectiveDependencies'
import type { Note } from '../../../data/Note'
import { Timestamp } from 'firebase/firestore'

function makeNote(overrides: Partial<Note> = {}): Note {
  return {
    id: 'note1',
    userId: 'user1',
    parentNoteId: null,
    content: 'First line\nSecond line\nThird line',
    createdAt: Timestamp.fromMillis(1000000),
    updatedAt: Timestamp.fromMillis(2000000),
    tags: [],
    containedNotes: [],
    state: null,
    path: 'a/b/c',
    rootNoteId: null,
    showCompleted: true,
    onceCache: {},
    ...overrides,
  }
}

describe('ContentHasher', () => {
  describe('hashFirstLine', () => {
    it('hashes the first line of content', () => {
      const hash = hashFirstLine('First line\nSecond line')
      expect(hash).toMatch(/^[0-9a-f]{8}$/)
    })

    it('returns same hash for same first line', () => {
      const hash1 = hashFirstLine('Hello\nWorld')
      const hash2 = hashFirstLine('Hello\nDifferent')
      expect(hash1).toBe(hash2)
    })

    it('returns different hash for different first line', () => {
      const hash1 = hashFirstLine('Hello\nWorld')
      const hash2 = hashFirstLine('Goodbye\nWorld')
      expect(hash1).not.toBe(hash2)
    })

    it('handles single line content', () => {
      const hash = hashFirstLine('Only one line')
      expect(hash).toMatch(/^[0-9a-f]{8}$/)
    })

    it('handles empty content', () => {
      const hash = hashFirstLine('')
      expect(hash).toMatch(/^[0-9a-f]{8}$/)
    })
  })

  describe('hashNonFirstLine', () => {
    it('hashes lines after the first', () => {
      const hash = hashNonFirstLine('First\nSecond\nThird')
      expect(hash).toMatch(/^[0-9a-f]{8}$/)
    })

    it('returns same hash when non-first lines are same', () => {
      const hash1 = hashNonFirstLine('Different\nSecond\nThird')
      const hash2 = hashNonFirstLine('Other\nSecond\nThird')
      expect(hash1).toBe(hash2)
    })

    it('returns different hash when non-first lines differ', () => {
      const hash1 = hashNonFirstLine('Same\nSecond')
      const hash2 = hashNonFirstLine('Same\nThird')
      expect(hash1).not.toBe(hash2)
    })

    it('handles single line content (no non-first lines)', () => {
      const hash = hashNonFirstLine('Only one line')
      expect(hash).toMatch(/^[0-9a-f]{8}$/)
    })
  })

  describe('hashField', () => {
    it('hashes NAME field (first line of content)', () => {
      const note = makeNote({ content: 'My Note Title\nBody text' })
      const hash = hashField(note, NoteField.NAME)
      expect(hash).toMatch(/^[0-9a-f]{8}$/)

      // Same first line should produce same hash
      const note2 = makeNote({ content: 'My Note Title\nDifferent body' })
      expect(hashField(note2, NoteField.NAME)).toBe(hash)
    })

    it('hashes PATH field', () => {
      const note = makeNote({ path: 'a/b/c' })
      const hash = hashField(note, NoteField.PATH)
      expect(hash).toMatch(/^[0-9a-f]{8}$/)

      const note2 = makeNote({ path: 'x/y/z' })
      expect(hashField(note2, NoteField.PATH)).not.toBe(hash)
    })

    it('hashes MODIFIED field', () => {
      const note = makeNote({ updatedAt: Timestamp.fromMillis(1000000) })
      const hash = hashField(note, NoteField.MODIFIED)
      expect(hash).toMatch(/^[0-9a-f]{8}$/)

      const note2 = makeNote({ updatedAt: Timestamp.fromMillis(2000000) })
      expect(hashField(note2, NoteField.MODIFIED)).not.toBe(hash)
    })

    it('hashes CREATED field', () => {
      const note = makeNote({ createdAt: Timestamp.fromMillis(5000000) })
      const hash = hashField(note, NoteField.CREATED)
      expect(hash).toMatch(/^[0-9a-f]{8}$/)
    })

    it('handles null timestamps', () => {
      const note = makeNote({ updatedAt: null, createdAt: null })
      // Should not throw
      expect(hashField(note, NoteField.MODIFIED)).toMatch(/^[0-9a-f]{8}$/)
      expect(hashField(note, NoteField.CREATED)).toMatch(/^[0-9a-f]{8}$/)
    })
  })

  describe('computeContentHashes', () => {
    it('computes both hashes when both are needed', () => {
      const note = makeNote()
      const hashes = computeContentHashes(note, true, true)
      expect(hashes.firstLineHash).not.toBeNull()
      expect(hashes.nonFirstLineHash).not.toBeNull()
    })

    it('computes only first line hash when only that is needed', () => {
      const note = makeNote()
      const hashes = computeContentHashes(note, true, false)
      expect(hashes.firstLineHash).not.toBeNull()
      expect(hashes.nonFirstLineHash).toBeNull()
    })

    it('computes only non-first line hash when only that is needed', () => {
      const note = makeNote()
      const hashes = computeContentHashes(note, false, true)
      expect(hashes.firstLineHash).toBeNull()
      expect(hashes.nonFirstLineHash).not.toBeNull()
    })

    it('returns null for both when neither is needed', () => {
      const note = makeNote()
      const hashes = computeContentHashes(note, false, false)
      expect(hashes.firstLineHash).toBeNull()
      expect(hashes.nonFirstLineHash).toBeNull()
    })
  })
})
