import { describe, it, expect } from 'vitest'
import { isStale, shouldReExecute } from '../../../dsl/cache/StalenessChecker'
import { cachedResultSuccess, cachedResultError } from '../../../dsl/cache/CachedDirectiveResult'
import { EMPTY_DEPENDENCIES, type DirectiveDependencies } from '../../../dsl/cache/DirectiveDependencies'
import { EMPTY_METADATA_HASHES } from '../../../dsl/cache/MetadataHasher'
import { computePathHash, computeExistenceHash, computeModifiedHash, computeAllNamesHash } from '../../../dsl/cache/MetadataHasher'
import { hashFirstLine, hashNonFirstLine } from '../../../dsl/cache/ContentHasher'
import { numberVal } from '../../../dsl/runtime/DslValue'
import type { Note } from '../../../data/Note'
import { Timestamp } from 'firebase/firestore'

function makeNote(id: string, content = 'Note content', path = `path/${id}`): Note {
  return {
    id,
    userId: 'user1',
    parentNoteId: null,
    content,
    createdAt: Timestamp.fromMillis(1000),
    updatedAt: Timestamp.fromMillis(2000),
    lastAccessedAt: Timestamp.fromMillis(3000),
    tags: [],
    containedNotes: [],
    state: null,
    path,
    rootNoteId: null,
  }
}

describe('StalenessChecker', () => {
  describe('isStale with no dependencies', () => {
    it('is not stale when no dependencies', () => {
      const cached = cachedResultSuccess(numberVal(42), EMPTY_DEPENDENCIES)
      const notes = [makeNote('a')]
      expect(isStale(cached, notes)).toBe(false)
    })
  })

  describe('isStale with metadata dependencies', () => {
    it('detects staleness when existence changes', () => {
      const notes = [makeNote('a'), makeNote('b')]
      const deps: DirectiveDependencies = { ...EMPTY_DEPENDENCIES, dependsOnNoteExistence: true }
      const metadataHashes = { ...EMPTY_METADATA_HASHES, existenceHash: computeExistenceHash(notes) }
      const cached = cachedResultSuccess(numberVal(1), deps, new Map(), metadataHashes)

      // Same notes - not stale
      expect(isStale(cached, notes)).toBe(false)

      // Add a note - stale
      const moreNotes = [...notes, makeNote('c')]
      expect(isStale(cached, moreNotes)).toBe(true)
    })

    it('detects staleness when path changes', () => {
      const notes = [makeNote('a', 'content', 'old/path')]
      const deps: DirectiveDependencies = { ...EMPTY_DEPENDENCIES, dependsOnPath: true }
      const metadataHashes = { ...EMPTY_METADATA_HASHES, pathHash: computePathHash(notes) }
      const cached = cachedResultSuccess(numberVal(1), deps, new Map(), metadataHashes)

      const modified = [makeNote('a', 'content', 'new/path')]
      expect(isStale(cached, modified)).toBe(true)
    })

    it('detects staleness when modified timestamp changes', () => {
      const notes = [makeNote('a')]
      const deps: DirectiveDependencies = { ...EMPTY_DEPENDENCIES, dependsOnModified: true }
      const metadataHashes = { ...EMPTY_METADATA_HASHES, modifiedHash: computeModifiedHash(notes) }
      const cached = cachedResultSuccess(numberVal(1), deps, new Map(), metadataHashes)

      const modified = [{ ...notes[0]!, updatedAt: Timestamp.fromMillis(99999) }]
      expect(isStale(cached, modified)).toBe(true)
    })

    it('detects staleness when all names change', () => {
      const notes = [makeNote('a', 'Original Name\nBody')]
      const deps: DirectiveDependencies = { ...EMPTY_DEPENDENCIES, dependsOnAllNames: true }
      const metadataHashes = { ...EMPTY_METADATA_HASHES, allNamesHash: computeAllNamesHash(notes) }
      const cached = cachedResultSuccess(numberVal(1), deps, new Map(), metadataHashes)

      const modified = [makeNote('a', 'Changed Name\nBody')]
      expect(isStale(cached, modified)).toBe(true)
    })
  })

  describe('isStale with content dependencies', () => {
    it('detects staleness when first line changes', () => {
      const note = makeNote('a', 'First line\nSecond line')
      const deps: DirectiveDependencies = { ...EMPTY_DEPENDENCIES, firstLineNotes: new Set(['a']) }
      const contentHashes = new Map([['a', { firstLineHash: hashFirstLine(note.content), nonFirstLineHash: null }]])
      const cached = cachedResultSuccess(numberVal(1), deps, contentHashes)

      // Change first line
      const modified = [makeNote('a', 'Different first\nSecond line')]
      expect(isStale(cached, modified)).toBe(true)
    })

    it('not stale when first line is same', () => {
      const note = makeNote('a', 'Same first\nSecond line')
      const deps: DirectiveDependencies = { ...EMPTY_DEPENDENCIES, firstLineNotes: new Set(['a']) }
      const contentHashes = new Map([['a', { firstLineHash: hashFirstLine(note.content), nonFirstLineHash: null }]])
      const cached = cachedResultSuccess(numberVal(1), deps, contentHashes)

      // Same first line, different body
      const modified = [makeNote('a', 'Same first\nDifferent body')]
      expect(isStale(cached, modified)).toBe(false)
    })

    it('detects staleness when non-first line changes', () => {
      const note = makeNote('a', 'First line\nSecond line')
      const deps: DirectiveDependencies = { ...EMPTY_DEPENDENCIES, nonFirstLineNotes: new Set(['a']) }
      const contentHashes = new Map([['a', { firstLineHash: null, nonFirstLineHash: hashNonFirstLine(note.content) }]])
      const cached = cachedResultSuccess(numberVal(1), deps, contentHashes)

      const modified = [makeNote('a', 'First line\nChanged second')]
      expect(isStale(cached, modified)).toBe(true)
    })

    it('detects staleness when tracked note is deleted', () => {
      const note = makeNote('a', 'Content')
      const deps: DirectiveDependencies = { ...EMPTY_DEPENDENCIES, firstLineNotes: new Set(['a']) }
      const contentHashes = new Map([['a', { firstLineHash: hashFirstLine(note.content), nonFirstLineHash: null }]])
      const cached = cachedResultSuccess(numberVal(1), deps, contentHashes)

      // Note 'a' is gone
      const modified = [makeNote('b', 'Other')]
      expect(isStale(cached, modified)).toBe(true)
    })
  })

  describe('shouldReExecute', () => {
    it('returns false for fresh non-stale result', () => {
      const cached = cachedResultSuccess(numberVal(42), EMPTY_DEPENDENCIES)
      expect(shouldReExecute(cached, [])).toBe(false)
    })

    it('returns true for non-deterministic error', () => {
      const error = { kind: 'NetworkError' as const, message: 'Network error', isDeterministic: false }
      const cached = cachedResultError(error)
      expect(shouldReExecute(cached, [])).toBe(true)
    })

    it('returns false for deterministic error', () => {
      const error = { kind: 'TypeError' as const, message: 'Type error', isDeterministic: true }
      const cached = cachedResultError(error)
      expect(shouldReExecute(cached, [])).toBe(false)
    })

    it('returns true when dependencies are stale', () => {
      const notes = [makeNote('a')]
      const deps: DirectiveDependencies = { ...EMPTY_DEPENDENCIES, dependsOnNoteExistence: true }
      const metadataHashes = { ...EMPTY_METADATA_HASHES, existenceHash: computeExistenceHash(notes) }
      const cached = cachedResultSuccess(numberVal(1), deps, new Map(), metadataHashes)

      const moreNotes = [...notes, makeNote('b')]
      expect(shouldReExecute(cached, moreNotes)).toBe(true)
    })
  })
})
