import { describe, it, expect, beforeEach } from 'vitest'
import { DirectiveCacheManager } from '../../../dsl/cache/DirectiveCache'
import { EditSessionManager, InvalidationReason } from '../../../dsl/cache/EditSessionManager'
import { cachedResultSuccess, cachedResultError, shouldRetryError } from '../../../dsl/cache/CachedDirectiveResult'
import { EMPTY_DEPENDENCIES, type DirectiveDependencies } from '../../../dsl/cache/DirectiveDependencies'
import { EMPTY_METADATA_HASHES } from '../../../dsl/cache/MetadataHasher'
import { computeExistenceHash } from '../../../dsl/cache/MetadataHasher'
import { numberVal, stringVal } from '../../../dsl/runtime/DslValue'
import type { Note } from '../../../data/Note'
import { Timestamp } from 'firebase/firestore'

function makeNote(id: string, content = 'Content', path = `path/${id}`): Note {
  return {
    id,
    userId: 'user1',
    parentNoteId: null,
    content,
    createdAt: Timestamp.fromMillis(1000),
    updatedAt: Timestamp.fromMillis(2000),
    tags: [],
    containedNotes: [],
    state: null,
    path,
    rootNoteId: null,
    showCompleted: true,
    onceCache: {},
  }
}

describe('CachingIntegration', () => {
  let cacheManager: DirectiveCacheManager

  beforeEach(() => {
    cacheManager = new DirectiveCacheManager()
  })

  describe('cache hit and miss', () => {
    it('returns undefined on cache miss', () => {
      const result = cacheManager.get('nonexistent', 'note1')
      expect(result).toBeUndefined()
    })

    it('returns cached result on cache hit', () => {
      const cached = cachedResultSuccess(numberVal(42), EMPTY_DEPENDENCIES)
      cacheManager.put('key1', 'note1', cached)

      const result = cacheManager.get('key1', 'note1')
      expect(result).toBeDefined()
      expect(result!.result).toEqual(numberVal(42))
    })
  })

  describe('staleness detection', () => {
    it('getIfValid returns result when not stale', () => {
      const cached = cachedResultSuccess(numberVal(1), EMPTY_DEPENDENCIES)
      cacheManager.put('key1', 'note1', cached)

      const result = cacheManager.getIfValid('key1', 'note1', [], null)
      expect(result).toBeDefined()
    })

    it('getIfValid returns undefined when stale', () => {
      const notes = [makeNote('a')]
      const deps: DirectiveDependencies = { ...EMPTY_DEPENDENCIES, dependsOnNoteExistence: true }
      const metadataHashes = { ...EMPTY_METADATA_HASHES, existenceHash: computeExistenceHash(notes) }
      const cached = cachedResultSuccess(numberVal(1), deps, new Map(), metadataHashes)
      cacheManager.put('key1', 'note1', cached)

      // Add new note to make it stale
      const newNotes = [...notes, makeNote('b')]
      const result = cacheManager.getIfValid('key1', 'note1', newNotes, null)
      expect(result).toBeUndefined()
    })
  })

  describe('current note exclusion (per-note cache)', () => {
    it('per-note cache entries are isolated by note ID', () => {
      const cached = cachedResultSuccess(stringVal('per-note'), EMPTY_DEPENDENCIES)
      cacheManager.put('key1', 'note1', cached)

      // Should find for note1
      expect(cacheManager.get('key1', 'note1')).toBeDefined()

      // Should not find for note2
      expect(cacheManager.get('key1', 'note2')).toBeUndefined()
    })

    it('cache entries are scoped per note', () => {
      const cached = cachedResultSuccess(stringVal('global'), EMPTY_DEPENDENCIES)
      cacheManager.put('key1', 'note1', cached)

      // Should only find from same note context
      expect(cacheManager.get('key1', 'note1')).toBeDefined()
      expect(cacheManager.get('key1', 'note2')).toBeUndefined()
    })
  })

  describe('invalidation', () => {
    it('clearNote removes per-note entries', () => {
      const cached = cachedResultSuccess(numberVal(1), EMPTY_DEPENDENCIES)
      cacheManager.put('key1', 'note1', cached)
      cacheManager.put('key2', 'note1', cachedResultSuccess(numberVal(2), EMPTY_DEPENDENCIES))

      cacheManager.clearNote('note1')

      expect(cacheManager.get('key1', 'note1')).toBeUndefined()
      expect(cacheManager.get('key2', 'note1')).toBeUndefined()
    })

    it('clearNote does not affect other notes', () => {
      cacheManager.put('key1', 'note1', cachedResultSuccess(numberVal(1), EMPTY_DEPENDENCIES))
      cacheManager.put('key1', 'note2', cachedResultSuccess(numberVal(2), EMPTY_DEPENDENCIES))

      cacheManager.clearNote('note1')

      expect(cacheManager.get('key1', 'note1')).toBeUndefined()
      expect(cacheManager.get('key1', 'note2')).toBeDefined()
    })

    it('clearAll removes everything', () => {
      cacheManager.put('gkey', 'n1', cachedResultSuccess(numberVal(1), EMPTY_DEPENDENCIES))
      cacheManager.put('pkey', 'n1', cachedResultSuccess(numberVal(2), EMPTY_DEPENDENCIES))
      cacheManager.put('pkey', 'n2', cachedResultSuccess(numberVal(3), EMPTY_DEPENDENCIES))

      cacheManager.clearAll()

      expect(cacheManager.get('gkey', 'n1')).toBeUndefined()
      expect(cacheManager.get('pkey', 'n1')).toBeUndefined()
      expect(cacheManager.get('pkey', 'n2')).toBeUndefined()
    })
  })

  describe('inline editing suppression', () => {
    it('suppresses invalidation during edit session for originating note', () => {
      const editSessionManager = new EditSessionManager(cacheManager)

      cacheManager.put('key1', 'originNote', cachedResultSuccess(numberVal(1), EMPTY_DEPENDENCIES))

      editSessionManager.startEditSession('editNote', 'originNote')
      const invalidated = editSessionManager.requestInvalidation('originNote', InvalidationReason.CONTENT_CHANGED)

      expect(invalidated).toBe(false)
      // Cache entry is still there
      expect(cacheManager.get('key1', 'originNote')).toBeDefined()
    })

    it('processes deferred invalidations on session end', () => {
      const editSessionManager = new EditSessionManager(cacheManager)

      cacheManager.put('key1', 'originNote', cachedResultSuccess(numberVal(1), EMPTY_DEPENDENCIES))

      editSessionManager.startEditSession('editNote', 'originNote')
      editSessionManager.requestInvalidation('originNote', InvalidationReason.CONTENT_CHANGED)

      editSessionManager.endEditSession()

      // Now it should be invalidated
      expect(cacheManager.get('key1', 'originNote')).toBeUndefined()
    })

    it('discards deferred invalidations on abort', () => {
      const editSessionManager = new EditSessionManager(cacheManager)

      cacheManager.put('key1', 'originNote', cachedResultSuccess(numberVal(1), EMPTY_DEPENDENCIES))

      editSessionManager.startEditSession('editNote', 'originNote')
      editSessionManager.requestInvalidation('originNote', InvalidationReason.CONTENT_CHANGED)

      editSessionManager.abortEditSession()

      // Cache entry should still be there (invalidation was discarded)
      expect(cacheManager.get('key1', 'originNote')).toBeDefined()
    })
  })

  describe('error caching', () => {
    it('caches deterministic errors', () => {
      const error = { kind: 'TypeError' as const, message: 'Type error', isDeterministic: true }
      const cached = cachedResultError(error)
      cacheManager.put('key1', 'note1', cached)

      const retrieved = cacheManager.get('key1', 'note1')
      expect(retrieved).toBeDefined()
      expect(retrieved!.error?.isDeterministic).toBe(true)
      expect(shouldRetryError(retrieved!)).toBe(false)
    })

    it('non-deterministic errors should be retried', () => {
      const error = { kind: 'NetworkError' as const, message: 'Network failure', isDeterministic: false }
      const cached = cachedResultError(error)

      expect(shouldRetryError(cached)).toBe(true)
    })

    it('successful results should not be retried', () => {
      const cached = cachedResultSuccess(numberVal(42), EMPTY_DEPENDENCIES)
      expect(shouldRetryError(cached)).toBe(false)
    })
  })
})
