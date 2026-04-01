import { describe, it, expect } from 'vitest'
import { findDirectives } from '../../../dsl/directives/DirectiveFinder'

describe('findDirectives - bracket escaping', () => {
  it('should skip escaped opening brackets [[', () => {
    const result = findDirectives('text [[ more')
    expect(result).toHaveLength(0)
  })

  it('should skip escaped closing brackets ]]', () => {
    const result = findDirectives('text ]] more')
    expect(result).toHaveLength(0)
  })

  it('should find directive after escaped brackets', () => {
    const result = findDirectives('[[ [real] ]]')
    expect(result).toHaveLength(1)
    expect(result[0]!.sourceText).toBe('[real]')
  })

  it('should handle escaped brackets at start', () => {
    const result = findDirectives('[[escaped]] [real]')
    expect(result).toHaveLength(1)
    expect(result[0]!.sourceText).toBe('[real]')
  })

  it('should handle only escaped brackets', () => {
    const result = findDirectives('[[hello]]')
    expect(result).toHaveLength(0)
  })

  it('should not confuse single bracket with escaped', () => {
    const result = findDirectives('[normal]')
    expect(result).toHaveLength(1)
    expect(result[0]!.sourceText).toBe('[normal]')
  })

  it('should handle mixed escaped and real directives', () => {
    const result = findDirectives('[[literal]] [dir1] text [dir2]')
    expect(result).toHaveLength(2)
    expect(result[0]!.sourceText).toBe('[dir1]')
    expect(result[1]!.sourceText).toBe('[dir2]')
  })

  it('should handle nested brackets inside directive normally', () => {
    // Inside a directive, brackets are tracked normally (no escaping)
    const result = findDirectives('[once[inner]]')
    expect(result).toHaveLength(1)
    expect(result[0]!.sourceText).toBe('[once[inner]]')
  })
})
