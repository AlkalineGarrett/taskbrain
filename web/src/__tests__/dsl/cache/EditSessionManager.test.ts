import { describe, it, expect, vi } from 'vitest'
import { EditSessionManager, InvalidationReason } from '../../../dsl/cache/EditSessionManager'
import { DirectiveCacheManager } from '../../../dsl/cache/DirectiveCache'
import { cachedResultSuccess } from '../../../dsl/cache/CachedDirectiveResult'
import { EMPTY_DEPENDENCIES } from '../../../dsl/cache/DirectiveDependencies'
import { numberVal } from '../../../dsl/runtime/DslValue'

describe('EditSessionManager', () => {
  describe('start and end sessions', () => {
    it('starts an edit session', () => {
      const manager = new EditSessionManager()
      manager.startEditSession('editNote', 'originNote')
      expect(manager.isEditSessionActive()).toBe(true)
    })

    it('ends an edit session', () => {
      const manager = new EditSessionManager()
      manager.startEditSession('editNote', 'originNote')
      manager.endEditSession()
      expect(manager.isEditSessionActive()).toBe(false)
    })

    it('starting a new session ends the previous one', () => {
      const cacheManager = new DirectiveCacheManager()
      const manager = new EditSessionManager(cacheManager)

      // Put something in cache for the first origin note
      cacheManager.put('key1', 'origin1', true, cachedResultSuccess(numberVal(1), EMPTY_DEPENDENCIES))

      manager.startEditSession('edit1', 'origin1')
      // Suppress invalidation for origin1
      manager.requestInvalidation('origin1', InvalidationReason.CONTENT_CHANGED)

      // Starting a new session should end the first and flush pending invalidations
      manager.startEditSession('edit2', 'origin2')

      // The pending invalidation from origin1 should have been flushed
      expect(cacheManager.get('key1', 'origin1', true)).toBeUndefined()
    })
  })

  describe('invalidation suppression', () => {
    it('suppresses invalidation for originating note during edit', () => {
      const manager = new EditSessionManager()
      manager.startEditSession('editNote', 'originNote')

      expect(manager.shouldSuppressInvalidation('originNote')).toBe(true)
    })

    it('does not suppress invalidation for other notes', () => {
      const manager = new EditSessionManager()
      manager.startEditSession('editNote', 'originNote')

      expect(manager.shouldSuppressInvalidation('otherNote')).toBe(false)
    })

    it('does not suppress when no session is active', () => {
      const manager = new EditSessionManager()
      expect(manager.shouldSuppressInvalidation('anyNote')).toBe(false)
    })
  })

  describe('request invalidation', () => {
    it('defers invalidation for originating note during edit session', () => {
      const cacheManager = new DirectiveCacheManager()
      const manager = new EditSessionManager(cacheManager)

      cacheManager.put('key1', 'originNote', true, cachedResultSuccess(numberVal(1), EMPTY_DEPENDENCIES))

      manager.startEditSession('editNote', 'originNote')
      const invalidated = manager.requestInvalidation('originNote', InvalidationReason.CONTENT_CHANGED)

      // Should NOT have been invalidated immediately
      expect(invalidated).toBe(false)
      // Cache should still have the entry
      expect(cacheManager.get('key1', 'originNote', true)).toBeDefined()
    })

    it('immediately invalidates non-originating notes', () => {
      const cacheManager = new DirectiveCacheManager()
      const manager = new EditSessionManager(cacheManager)

      cacheManager.put('key1', 'otherNote', true, cachedResultSuccess(numberVal(1), EMPTY_DEPENDENCIES))

      manager.startEditSession('editNote', 'originNote')
      const invalidated = manager.requestInvalidation('otherNote', InvalidationReason.CONTENT_CHANGED)

      expect(invalidated).toBe(true)
      expect(cacheManager.get('key1', 'otherNote', true)).toBeUndefined()
    })

    it('flushes pending invalidations on session end', () => {
      const cacheManager = new DirectiveCacheManager()
      const manager = new EditSessionManager(cacheManager)

      cacheManager.put('key1', 'originNote', true, cachedResultSuccess(numberVal(1), EMPTY_DEPENDENCIES))

      manager.startEditSession('editNote', 'originNote')
      manager.requestInvalidation('originNote', InvalidationReason.CONTENT_CHANGED)

      // Cache still has it
      expect(cacheManager.get('key1', 'originNote', true)).toBeDefined()

      // End session should flush
      manager.endEditSession()
      expect(cacheManager.get('key1', 'originNote', true)).toBeUndefined()
    })
  })

  describe('abort session', () => {
    it('aborts session and discards pending invalidations', () => {
      const cacheManager = new DirectiveCacheManager()
      const manager = new EditSessionManager(cacheManager)

      cacheManager.put('key1', 'originNote', true, cachedResultSuccess(numberVal(1), EMPTY_DEPENDENCIES))

      manager.startEditSession('editNote', 'originNote')
      manager.requestInvalidation('originNote', InvalidationReason.CONTENT_CHANGED)

      manager.abortEditSession()

      // Session should be ended
      expect(manager.isEditSessionActive()).toBe(false)
      // Cache should still have the entry (invalidation was discarded)
      expect(cacheManager.get('key1', 'originNote', true)).toBeDefined()
    })
  })

  describe('session end listeners', () => {
    it('calls listeners on session end', () => {
      const manager = new EditSessionManager()
      const listener = vi.fn()

      manager.addSessionEndListener(listener)
      manager.startEditSession('editNote', 'originNote')
      manager.endEditSession()

      expect(listener).toHaveBeenCalledOnce()
    })

    it('removes listener', () => {
      const manager = new EditSessionManager()
      const listener = vi.fn()

      manager.addSessionEndListener(listener)
      manager.removeSessionEndListener(listener)
      manager.startEditSession('editNote', 'originNote')
      manager.endEditSession()

      expect(listener).not.toHaveBeenCalled()
    })

    it('does not call listeners on abort', () => {
      const manager = new EditSessionManager()
      const listener = vi.fn()

      manager.addSessionEndListener(listener)
      manager.startEditSession('editNote', 'originNote')
      manager.abortEditSession()

      expect(listener).not.toHaveBeenCalled()
    })
  })

  describe('end without active session', () => {
    it('endEditSession is a no-op when no session is active', () => {
      const manager = new EditSessionManager()
      // Should not throw
      manager.endEditSession()
      expect(manager.isEditSessionActive()).toBe(false)
    })
  })
})
