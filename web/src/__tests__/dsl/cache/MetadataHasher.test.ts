import { describe, it, expect } from 'vitest'
import {
  computeMetadataHashes,
  computePathHash,
  computeModifiedHash,
  computeCreatedHash,
  computeViewedHash,
  computeExistenceHash,
  computeAllNamesHash,
  EMPTY_METADATA_HASHES,
} from '../../../dsl/cache/MetadataHasher'
import { EMPTY_DEPENDENCIES } from '../../../dsl/cache/DirectiveDependencies'
import type { Note } from '../../../data/Note'
import { Timestamp } from 'firebase/firestore'

function makeNote(id: string, overrides: Partial<Note> = {}): Note {
  return {
    id,
    userId: 'user1',
    parentNoteId: null,
    content: `Note ${id}\nBody`,
    createdAt: Timestamp.fromMillis(1000),
    updatedAt: Timestamp.fromMillis(2000),
    lastAccessedAt: Timestamp.fromMillis(3000),
    tags: [],
    containedNotes: [],
    state: null,
    path: `path/${id}`,
    rootNoteId: null,
    ...overrides,
  }
}

describe('MetadataHasher', () => {
  const notes = [makeNote('a'), makeNote('b'), makeNote('c')]

  describe('computePathHash', () => {
    it('produces a consistent hash', () => {
      const hash1 = computePathHash(notes)
      const hash2 = computePathHash(notes)
      expect(hash1).toBe(hash2)
    })

    it('produces 8-char hex string', () => {
      expect(computePathHash(notes)).toMatch(/^[0-9a-f]{8}$/)
    })

    it('changes when a path changes', () => {
      const modified = [makeNote('a', { path: 'different/path' }), makeNote('b'), makeNote('c')]
      expect(computePathHash(modified)).not.toBe(computePathHash(notes))
    })

    it('is order-independent (sorted by id)', () => {
      const reversed = [...notes].reverse()
      expect(computePathHash(reversed)).toBe(computePathHash(notes))
    })
  })

  describe('computeModifiedHash', () => {
    it('changes when modification timestamp changes', () => {
      const modified = [makeNote('a', { updatedAt: Timestamp.fromMillis(99999) }), makeNote('b'), makeNote('c')]
      expect(computeModifiedHash(modified)).not.toBe(computeModifiedHash(notes))
    })

    it('handles null timestamps', () => {
      const nullTs = [makeNote('a', { updatedAt: null })]
      expect(computeModifiedHash(nullTs)).toMatch(/^[0-9a-f]{8}$/)
    })
  })

  describe('computeCreatedHash', () => {
    it('changes when created timestamp changes', () => {
      const modified = [makeNote('a', { createdAt: Timestamp.fromMillis(99999) }), makeNote('b'), makeNote('c')]
      expect(computeCreatedHash(modified)).not.toBe(computeCreatedHash(notes))
    })
  })

  describe('computeViewedHash', () => {
    it('changes when viewed timestamp changes', () => {
      const modified = [makeNote('a', { lastAccessedAt: Timestamp.fromMillis(99999) }), makeNote('b'), makeNote('c')]
      expect(computeViewedHash(modified)).not.toBe(computeViewedHash(notes))
    })
  })

  describe('computeExistenceHash', () => {
    it('changes when notes are added', () => {
      const withExtra = [...notes, makeNote('d')]
      expect(computeExistenceHash(withExtra)).not.toBe(computeExistenceHash(notes))
    })

    it('changes when notes are removed', () => {
      const fewer = notes.slice(0, 2)
      expect(computeExistenceHash(fewer)).not.toBe(computeExistenceHash(notes))
    })

    it('same notes produce same hash regardless of order', () => {
      const reversed = [...notes].reverse()
      expect(computeExistenceHash(reversed)).toBe(computeExistenceHash(notes))
    })
  })

  describe('computeAllNamesHash', () => {
    it('changes when first line content changes', () => {
      const modified = [makeNote('a', { content: 'Different Name\nBody' }), makeNote('b'), makeNote('c')]
      expect(computeAllNamesHash(modified)).not.toBe(computeAllNamesHash(notes))
    })

    it('does not change when non-first line changes', () => {
      const modified = [makeNote('a', { content: 'Note a\nDifferent body' }), makeNote('b'), makeNote('c')]
      // The first line is still "Note a" so hash should be same
      expect(computeAllNamesHash(modified)).toBe(computeAllNamesHash(notes))
    })
  })

  describe('computeMetadataHashes', () => {
    it('returns EMPTY when no dependencies', () => {
      const hashes = computeMetadataHashes(notes, EMPTY_DEPENDENCIES)
      expect(hashes).toEqual(EMPTY_METADATA_HASHES)
    })

    it('computes path hash when dependsOnPath', () => {
      const deps = { ...EMPTY_DEPENDENCIES, dependsOnPath: true }
      const hashes = computeMetadataHashes(notes, deps)
      expect(hashes.pathHash).not.toBeNull()
      expect(hashes.modifiedHash).toBeNull()
    })

    it('computes modified hash when dependsOnModified', () => {
      const deps = { ...EMPTY_DEPENDENCIES, dependsOnModified: true }
      const hashes = computeMetadataHashes(notes, deps)
      expect(hashes.modifiedHash).not.toBeNull()
    })

    it('computes created hash when dependsOnCreated', () => {
      const deps = { ...EMPTY_DEPENDENCIES, dependsOnCreated: true }
      const hashes = computeMetadataHashes(notes, deps)
      expect(hashes.createdHash).not.toBeNull()
    })

    it('computes viewed hash when dependsOnViewed', () => {
      const deps = { ...EMPTY_DEPENDENCIES, dependsOnViewed: true }
      const hashes = computeMetadataHashes(notes, deps)
      expect(hashes.viewedHash).not.toBeNull()
    })

    it('computes existence hash when dependsOnNoteExistence', () => {
      const deps = { ...EMPTY_DEPENDENCIES, dependsOnNoteExistence: true }
      const hashes = computeMetadataHashes(notes, deps)
      expect(hashes.existenceHash).not.toBeNull()
    })

    it('computes allNames hash when dependsOnAllNames', () => {
      const deps = { ...EMPTY_DEPENDENCIES, dependsOnAllNames: true }
      const hashes = computeMetadataHashes(notes, deps)
      expect(hashes.allNamesHash).not.toBeNull()
    })

    it('computes multiple hashes for multiple dependencies', () => {
      const deps = { ...EMPTY_DEPENDENCIES, dependsOnPath: true, dependsOnModified: true, dependsOnNoteExistence: true }
      const hashes = computeMetadataHashes(notes, deps)
      expect(hashes.pathHash).not.toBeNull()
      expect(hashes.modifiedHash).not.toBeNull()
      expect(hashes.existenceHash).not.toBeNull()
      expect(hashes.createdHash).toBeNull()
    })
  })
})
