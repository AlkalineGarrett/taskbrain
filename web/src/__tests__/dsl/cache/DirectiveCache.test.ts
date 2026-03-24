import { describe, it, expect } from 'vitest'
import { DirectiveCacheManager } from '../../../dsl/cache/DirectiveCache'
import { cachedResultSuccess, cachedResultError } from '../../../dsl/cache/CachedDirectiveResult'
import { EMPTY_DEPENDENCIES } from '../../../dsl/cache/DirectiveDependencies'
import { numberVal, stringVal } from '../../../dsl/runtime/DslValue'

describe('DirectiveCacheManager', () => {
  describe('global cache (non-self-access)', () => {
    it('stores and retrieves a result', () => {
      const cache = new DirectiveCacheManager()
      const result = cachedResultSuccess(numberVal(42), EMPTY_DEPENDENCIES)
      cache.put('key1', 'note1', false, result)

      const retrieved = cache.get('key1', 'note1', false)
      expect(retrieved).toBeDefined()
      expect(retrieved!.result).toEqual(numberVal(42))
    })

    it('returns undefined for missing key', () => {
      const cache = new DirectiveCacheManager()
      expect(cache.get('missing', 'note1', false)).toBeUndefined()
    })

    it('cache is per-note even for non-self-access directives', () => {
      const cache = new DirectiveCacheManager()
      const result = cachedResultSuccess(numberVal(1), EMPTY_DEPENDENCIES)
      cache.put('key1', 'noteA', false, result)

      // Same key, different note should NOT find it — cache is always per-note
      const retrieved = cache.get('key1', 'noteB', false)
      expect(retrieved).toBeUndefined()
      // Same note should find it
      expect(cache.get('key1', 'noteA', false)).toBeDefined()
    })
  })

  describe('per-note cache (self-access)', () => {
    it('stores and retrieves per-note result', () => {
      const cache = new DirectiveCacheManager()
      const result = cachedResultSuccess(stringVal('hello'), EMPTY_DEPENDENCIES)
      cache.put('key1', 'note1', true, result)

      const retrieved = cache.get('key1', 'note1', true)
      expect(retrieved).toBeDefined()
      expect(retrieved!.result).toEqual(stringVal('hello'))
    })

    it('per-note results are isolated by note ID', () => {
      const cache = new DirectiveCacheManager()
      const result = cachedResultSuccess(numberVal(1), EMPTY_DEPENDENCIES)
      cache.put('key1', 'note1', true, result)

      // Same key, different note should not find it
      expect(cache.get('key1', 'note2', true)).toBeUndefined()
    })

    it('clearNote removes per-note cache', () => {
      const cache = new DirectiveCacheManager()
      const result = cachedResultSuccess(numberVal(1), EMPTY_DEPENDENCIES)
      cache.put('key1', 'note1', true, result)

      cache.clearNote('note1')
      expect(cache.get('key1', 'note1', true)).toBeUndefined()
    })
  })

  describe('clearAll', () => {
    it('clears both global and per-note caches', () => {
      const cache = new DirectiveCacheManager()
      cache.put('gkey', 'n1', false, cachedResultSuccess(numberVal(1), EMPTY_DEPENDENCIES))
      cache.put('pkey', 'n1', true, cachedResultSuccess(numberVal(2), EMPTY_DEPENDENCIES))

      cache.clearAll()

      expect(cache.get('gkey', 'n1', false)).toBeUndefined()
      expect(cache.get('pkey', 'n1', true)).toBeUndefined()
    })
  })

  describe('getIfValid with staleness', () => {
    it('returns cached result when not stale', () => {
      const cache = new DirectiveCacheManager()
      // A result with no dependencies is never stale
      const result = cachedResultSuccess(numberVal(42), EMPTY_DEPENDENCIES)
      cache.put('key1', 'note1', false, result)

      const retrieved = cache.getIfValid('key1', 'note1', false, [], null)
      expect(retrieved).toBeDefined()
      expect(retrieved!.result).toEqual(numberVal(42))
    })

    it('returns undefined when key not found', () => {
      const cache = new DirectiveCacheManager()
      expect(cache.getIfValid('missing', 'note1', false, [], null)).toBeUndefined()
    })
  })

  describe('error caching', () => {
    it('stores and retrieves error results', () => {
      const cache = new DirectiveCacheManager()
      const error = { kind: 'TypeError' as const, message: 'bad type', isDeterministic: true }
      const result = cachedResultError(error, EMPTY_DEPENDENCIES)
      cache.put('key1', 'note1', false, result)

      const retrieved = cache.get('key1', 'note1', false)
      expect(retrieved).toBeDefined()
      expect(retrieved!.error).toEqual(error)
      expect(retrieved!.result).toBeNull()
    })
  })

  describe('overwriting entries', () => {
    it('overwrites existing entry with same key', () => {
      const cache = new DirectiveCacheManager()
      cache.put('key1', 'n1', false, cachedResultSuccess(numberVal(1), EMPTY_DEPENDENCIES))
      cache.put('key1', 'n1', false, cachedResultSuccess(numberVal(2), EMPTY_DEPENDENCIES))

      const retrieved = cache.get('key1', 'n1', false)
      expect(retrieved!.result).toEqual(numberVal(2))
    })
  })
})
