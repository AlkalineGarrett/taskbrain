import { describe, it, expect } from 'vitest'
import { findDirectives, containsDirectives, directiveKey, startOffsetFromKey, hashDirective } from '../../../dsl/directives/DirectiveFinder'
import { executeDirective, executeAllDirectives } from '../../../dsl/directives/DirectiveExecutor'
import { DirectiveWarningType, directiveResultToValue, directiveResultToDisplayString, directiveResultWarning } from '../../../dsl/directives/DirectiveResult'
import { numberVal, stringVal } from '../../../dsl/runtime/DslValue'

describe('directiveKey', () => {
  it('creates key from lineId and offset', () => {
    expect(directiveKey('test-line', 5)).toBe('test-line:5')
    expect(directiveKey('test-line', 12)).toBe('test-line:12')
  })

  it('creates key with provided lineId', () => {
    expect(directiveKey('abc123', 5)).toBe('abc123:5')
    expect(directiveKey('note-xyz', 12)).toBe('note-xyz:12')
  })
})

describe('startOffsetFromKey', () => {
  it('extracts offset from noteId-based key', () => {
    expect(startOffsetFromKey('abc123:5')).toBe(5)
  })

  it('extracts offset from lineId-based key', () => {
    expect(startOffsetFromKey('test-line:12')).toBe(12)
  })

  it('returns undefined for invalid key', () => {
    expect(startOffsetFromKey('nocolon')).toBeUndefined()
  })

  it('returns undefined for non-numeric offset', () => {
    expect(startOffsetFromKey('abc:xyz')).toBeUndefined()
  })

  it('handles zero offset', () => {
    expect(startOffsetFromKey('test-line:0')).toBe(0)
    expect(startOffsetFromKey('note:0')).toBe(0)
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

  it('handles nested brackets for bracket body syntax', () => {
    const result = findDirectives('[once[inner]]')
    expect(result).toHaveLength(1)
    expect(result[0]!.sourceText).toBe('[once[inner]]')
    expect(result[0]!.startOffset).toBe(0)
    expect(result[0]!.endOffset).toBe(13)
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

describe('executeDirective', () => {
  it('executes number directive successfully', () => {
    const result = executeDirective('[42]', [], null)
    expect(result.error).toBeNull()
    expect(result.result).not.toBeNull()
    const val = directiveResultToValue(result)
    expect(val).toEqual(numberVal(42))
  })

  it('executes string directive successfully', () => {
    const result = executeDirective('["hello"]', [], null)
    expect(result.error).toBeNull()
    const val = directiveResultToValue(result)
    expect(val).toEqual(stringVal('hello'))
  })

  it('returns error for invalid directive', () => {
    const result = executeDirective('[invalid@syntax]', [], null)
    expect(result.error).not.toBeNull()
    expect(result.error).toContain('error')
  })

  it('returns error for empty directive', () => {
    const result = executeDirective('[]', [], null)
    expect(result.error).not.toBeNull()
  })

  it('returns error for unclosed string', () => {
    const result = executeDirective('["unclosed]', [], null)
    expect(result.error).not.toBeNull()
  })
})

describe('executeAllDirectives', () => {
  it('returns results for all directives', () => {
    const { results } = executeAllDirectives('First [42] second ["hello"]', [], null)
    expect(results.size).toBe(2)
  })

  it('handles mixed success and error', () => {
    const { results } = executeAllDirectives('[42] and [invalid@]', [], null)
    expect(results.size).toBe(2)

    const values = [...results.values()]
    const success = values.find((r) => r.error === null)
    expect(success).toBeDefined()
    expect(directiveResultToValue(success!)).toEqual(numberVal(42))

    const error = values.find((r) => r.error !== null)
    expect(error).toBeDefined()
  })

  it('returns empty map for no directives', () => {
    const { results } = executeAllDirectives('No directives here', [], null)
    expect(results.size).toBe(0)
  })
})

describe('no-effect warnings', () => {
  it('implicit lambda at top level returns warning', () => {
    const result = executeDirective('[[i]]', [], null)
    expect(result.error).toBeNull()
    expect(result.warning).toBe(DirectiveWarningType.NO_EFFECT_LAMBDA)
  })

  it('implicit lambda with body at top level returns warning', () => {
    const result = executeDirective('[[i.path]]', [], null)
    expect(result.error).toBeNull()
    expect(result.warning).toBe(DirectiveWarningType.NO_EFFECT_LAMBDA)
  })

  it('pattern at top level returns warning', () => {
    const result = executeDirective('[pattern(digit*4)]', [], null)
    expect(result.error).toBeNull()
    expect(result.warning).toBe(DirectiveWarningType.NO_EFFECT_PATTERN)
  })

  it('warning display message is descriptive', () => {
    expect(DirectiveWarningType.NO_EFFECT_LAMBDA).toBe('Uncalled lambda has no effect')
    expect(DirectiveWarningType.NO_EFFECT_PATTERN).toBe('Unused pattern has no effect')
  })

  it('warning result toDisplayString shows warning message', () => {
    const result = directiveResultWarning(DirectiveWarningType.NO_EFFECT_LAMBDA)
    const display = directiveResultToDisplayString(result)
    expect(display).toContain('Warning')
    expect(display).toContain('Uncalled lambda')
  })
})
