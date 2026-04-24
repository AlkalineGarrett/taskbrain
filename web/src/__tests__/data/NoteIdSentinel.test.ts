import { describe, it, expect } from 'vitest'
import {
  newSentinelNoteId,
  isSentinelNoteId,
  originOfSentinel,
  NOTE_ID_SENTINEL_PREFIX,
} from '../../data/NoteIdSentinel'

describe('NoteIdSentinel', () => {
  it('newSentinelNoteId produces sentinel with correct prefix and origin code', () => {
    const id = newSentinelNoteId('paste')
    expect(id.startsWith(NOTE_ID_SENTINEL_PREFIX)).toBe(true)
    expect(id).toMatch(/^@paste_[a-f0-9]+$/i)
  })

  it('isSentinelNoteId recognizes sentinels and rejects real ids', () => {
    expect(isSentinelNoteId('@paste_abc123')).toBe(true)
    expect(isSentinelNoteId(newSentinelNoteId('split'))).toBe(true)
    expect(isSentinelNoteId('aE5eVp2p2uiusUXqVa8T')).toBe(false)
    expect(isSentinelNoteId(null)).toBe(false)
    expect(isSentinelNoteId(undefined)).toBe(false)
    expect(isSentinelNoteId('')).toBe(false)
  })

  it('originOfSentinel extracts origin code', () => {
    expect(originOfSentinel(newSentinelNoteId('paste'))).toBe('paste')
    expect(originOfSentinel(newSentinelNoteId('split'))).toBe('split')
    expect(originOfSentinel('@agent_xyz')).toBe('agent')
    expect(originOfSentinel('aE5eVp2p2uiusUXqVa8T')).toBeNull()
    expect(originOfSentinel(null)).toBeNull()
  })

  it('newSentinelNoteId generates distinct ids across calls', () => {
    const ids = Array.from({ length: 20 }, () => newSentinelNoteId('paste'))
    expect(new Set(ids).size).toBe(ids.length)
  })

  const origins: Array<'typed' | 'paste' | 'split' | 'agent' | 'directive' | 'surgical'> = [
    'typed', 'paste', 'split', 'agent', 'directive', 'surgical',
  ]
  it.each(origins)('origin %s round-trips through originOfSentinel', (origin) => {
    const id = newSentinelNoteId(origin)
    expect(originOfSentinel(id)).toBe(origin)
  })
})
