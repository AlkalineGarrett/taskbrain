import { describe, it, expect } from 'vitest'
import { findDirectives, containsDirectives, directiveKey, hashDirective } from '../../../dsl/directives/DirectiveFinder'

describe('directiveKey', () => {
  it('creates key from line index and offset', () => {
    expect(directiveKey(0, 5)).toBe('0:5')
    expect(directiveKey(3, 12)).toBe('3:12')
  })
})

describe('findDirectives', () => {
  it('finds single directive', () => {
    const result = findDirectives('hello [world] there')
    expect(result).toHaveLength(1)
    expect(result[0]!.sourceText).toBe('[world]')
    expect(result[0]!.startOffset).toBe(6)
    expect(result[0]!.endOffset).toBe(13)
  })

  it('finds multiple directives', () => {
    const result = findDirectives('[foo] and [bar]')
    expect(result).toHaveLength(2)
    expect(result[0]!.sourceText).toBe('[foo]')
    expect(result[1]!.sourceText).toBe('[bar]')
  })

  it('returns empty for no directives', () => {
    const result = findDirectives('no directives here')
    expect(result).toHaveLength(0)
  })

  it('returns empty for empty content', () => {
    const result = findDirectives('')
    expect(result).toHaveLength(0)
  })

  it('handles nested brackets for lambda syntax', () => {
    const result = findDirectives('[lambda[inner]]')
    expect(result).toHaveLength(1)
    expect(result[0]!.sourceText).toBe('[lambda[inner]]')
    expect(result[0]!.startOffset).toBe(0)
    expect(result[0]!.endOffset).toBe(15)
  })

  it('handles deeply nested brackets', () => {
    const result = findDirectives('[a[b[c]]]')
    expect(result).toHaveLength(1)
    expect(result[0]!.sourceText).toBe('[a[b[c]]]')
  })

  it('tracks correct offsets', () => {
    const result = findDirectives('abc [dir1] def [dir2]')
    expect(result).toHaveLength(2)
    expect(result[0]!.startOffset).toBe(4)
    expect(result[0]!.endOffset).toBe(10)
    expect(result[1]!.startOffset).toBe(15)
    expect(result[1]!.endOffset).toBe(21)
  })

  it('handles directive at start of line', () => {
    const result = findDirectives('[start] rest')
    expect(result).toHaveLength(1)
    expect(result[0]!.startOffset).toBe(0)
  })

  it('handles directive at end of line', () => {
    const result = findDirectives('rest [end]')
    expect(result).toHaveLength(1)
    expect(result[0]!.endOffset).toBe(10)
  })

  it('handles adjacent directives', () => {
    const result = findDirectives('[first][second]')
    expect(result).toHaveLength(2)
    expect(result[0]!.sourceText).toBe('[first]')
    expect(result[1]!.sourceText).toBe('[second]')
  })

  it('ignores unclosed bracket', () => {
    const result = findDirectives('[unclosed')
    expect(result).toHaveLength(0)
  })

  it('handles multiple directives on same line with text between', () => {
    const result = findDirectives('text [a] more [b] end')
    expect(result).toHaveLength(2)
    expect(result[0]!.sourceText).toBe('[a]')
    expect(result[0]!.startOffset).toBe(5)
    expect(result[1]!.sourceText).toBe('[b]')
    expect(result[1]!.startOffset).toBe(14)
  })

  it('handles directives spanning bracket patterns', () => {
    const result = findDirectives('[outer [inner] more]')
    expect(result).toHaveLength(1)
    expect(result[0]!.sourceText).toBe('[outer [inner] more]')
  })
})

describe('containsDirectives', () => {
  it('returns true when content has directives', () => {
    expect(containsDirectives('[hello]')).toBe(true)
    expect(containsDirectives('before [dir] after')).toBe(true)
  })

  it('returns false when content has no directives', () => {
    expect(containsDirectives('no brackets here')).toBe(false)
    expect(containsDirectives('')).toBe(false)
  })

  it('returns false for unclosed brackets', () => {
    expect(containsDirectives('[unclosed')).toBe(false)
  })
})

describe('hashDirective', () => {
  it('produces consistent hash for same input', async () => {
    const hash1 = await hashDirective('[test]')
    const hash2 = await hashDirective('[test]')
    expect(hash1).toBe(hash2)
  })

  it('produces different hash for different input', async () => {
    const hash1 = await hashDirective('[test1]')
    const hash2 = await hashDirective('[test2]')
    expect(hash1).not.toBe(hash2)
  })

  it('returns hex string', async () => {
    const hash = await hashDirective('[test]')
    expect(hash).toMatch(/^[0-9a-f]+$/)
    expect(hash.length).toBe(64) // SHA-256 = 32 bytes = 64 hex chars
  })
})
