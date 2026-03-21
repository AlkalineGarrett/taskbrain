import { describe, it, expect } from 'vitest'
import { shouldSyncCursorForKey } from '../../components/EditorLine'

describe('shouldSyncCursorForKey', () => {
  describe('typing characters should NOT trigger sync', () => {
    it('rejects lowercase letters', () => {
      for (const ch of 'abcdefghijklmnopqrstuvwxyz') {
        expect(shouldSyncCursorForKey(ch), `key '${ch}'`).toBe(false)
      }
    })

    it('rejects uppercase letters', () => {
      for (const ch of 'ABCDEFGHIJKLMNOPQRSTUVWXYZ') {
        expect(shouldSyncCursorForKey(ch), `key '${ch}'`).toBe(false)
      }
    })

    it('rejects digits', () => {
      for (const ch of '0123456789') {
        expect(shouldSyncCursorForKey(ch), `key '${ch}'`).toBe(false)
      }
    })

    it('rejects punctuation and symbols', () => {
      for (const ch of '!@#$%^&*()-_=+[]{}|;:\'",.<>?/`~') {
        expect(shouldSyncCursorForKey(ch), `key '${ch}'`).toBe(false)
      }
    })

    it('rejects space', () => {
      expect(shouldSyncCursorForKey(' ')).toBe(false)
    })
  })

  describe('navigation keys SHOULD trigger sync', () => {
    it('accepts Home', () => {
      expect(shouldSyncCursorForKey('Home')).toBe(true)
    })

    it('accepts End', () => {
      expect(shouldSyncCursorForKey('End')).toBe(true)
    })

    it('accepts PageUp', () => {
      expect(shouldSyncCursorForKey('PageUp')).toBe(true)
    })

    it('accepts PageDown', () => {
      expect(shouldSyncCursorForKey('PageDown')).toBe(true)
    })

    it('accepts function keys', () => {
      expect(shouldSyncCursorForKey('F1')).toBe(true)
      expect(shouldSyncCursorForKey('F12')).toBe(true)
    })

    it('accepts Insert', () => {
      expect(shouldSyncCursorForKey('Insert')).toBe(true)
    })
  })
})
