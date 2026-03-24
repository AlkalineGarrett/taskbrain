import { describe, it, expect } from 'vitest'
import { directiveHash } from '../../../dsl/directives/DirectiveFinder'

describe('directiveHash', () => {
  it('produces consistent hash for same input', () => {
    const hash1 = directiveHash('some input text')
    const hash2 = directiveHash('some input text')
    expect(hash1).toBe(hash2)
  })

  it('produces different hashes for different inputs', () => {
    const hash1 = directiveHash('input one')
    const hash2 = directiveHash('input two')
    expect(hash1).not.toBe(hash2)
  })

  it('produces 16-character lowercase hex string', () => {
    const hash = directiveHash('test string')
    expect(hash).toMatch(/^[0-9a-f]{16}$/)
  })

  it('handles empty string', () => {
    const hash = directiveHash('')
    expect(hash).toMatch(/^[0-9a-f]{16}$/)
  })

  it('handles unicode', () => {
    const hash = directiveHash('\u{1F600} emoji and \u00E9 accents')
    expect(hash).toMatch(/^[0-9a-f]{16}$/)
  })

  it('matches Android hash for "hello"', () => {
    expect(directiveHash('hello')).toBe('a430d84680aabd0b')
  })

  it('matches Android hash for "[test]"', () => {
    expect(directiveHash('[test]')).toBe('11cfc5a73ae59ce5')
  })
})
