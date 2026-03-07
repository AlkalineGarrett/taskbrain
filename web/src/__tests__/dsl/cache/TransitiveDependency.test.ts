import { describe, it, expect } from 'vitest'
import { mergeDependencies, EMPTY_DEPENDENCIES, isDependenciesEmpty, type DirectiveDependencies } from '../../../dsl/cache/DirectiveDependencies'

describe('TransitiveDependency', () => {
  describe('mergeDependencies', () => {
    it('merging two empty dependencies produces empty', () => {
      const result = mergeDependencies(EMPTY_DEPENDENCIES, EMPTY_DEPENDENCIES)
      expect(isDependenciesEmpty(result)).toBe(true)
    })

    it('merges boolean flags with OR', () => {
      const a: DirectiveDependencies = { ...EMPTY_DEPENDENCIES, dependsOnPath: true }
      const b: DirectiveDependencies = { ...EMPTY_DEPENDENCIES, dependsOnModified: true }
      const result = mergeDependencies(a, b)

      expect(result.dependsOnPath).toBe(true)
      expect(result.dependsOnModified).toBe(true)
      expect(result.dependsOnCreated).toBe(false)
    })

    it('merges first line note sets', () => {
      const a: DirectiveDependencies = { ...EMPTY_DEPENDENCIES, firstLineNotes: new Set(['a', 'b']) }
      const b: DirectiveDependencies = { ...EMPTY_DEPENDENCIES, firstLineNotes: new Set(['b', 'c']) }
      const result = mergeDependencies(a, b)

      expect(result.firstLineNotes).toEqual(new Set(['a', 'b', 'c']))
    })

    it('merges non-first line note sets', () => {
      const a: DirectiveDependencies = { ...EMPTY_DEPENDENCIES, nonFirstLineNotes: new Set(['x']) }
      const b: DirectiveDependencies = { ...EMPTY_DEPENDENCIES, nonFirstLineNotes: new Set(['y']) }
      const result = mergeDependencies(a, b)

      expect(result.nonFirstLineNotes).toEqual(new Set(['x', 'y']))
    })

    it('merges hierarchy deps by concatenation', () => {
      const a: DirectiveDependencies = {
        ...EMPTY_DEPENDENCIES,
        hierarchyDeps: [{ path: { kind: 'Up' }, resolvedNoteId: 'p1', field: null, fieldHash: null }],
      }
      const b: DirectiveDependencies = {
        ...EMPTY_DEPENDENCIES,
        hierarchyDeps: [{ path: { kind: 'Root' }, resolvedNoteId: 'r1', field: null, fieldHash: null }],
      }
      const result = mergeDependencies(a, b)

      expect(result.hierarchyDeps).toHaveLength(2)
      expect(result.hierarchyDeps[0]!.path.kind).toBe('Up')
      expect(result.hierarchyDeps[1]!.path.kind).toBe('Root')
    })

    it('merges usesSelfAccess with OR', () => {
      const a: DirectiveDependencies = { ...EMPTY_DEPENDENCIES, usesSelfAccess: false }
      const b: DirectiveDependencies = { ...EMPTY_DEPENDENCIES, usesSelfAccess: true }
      const result = mergeDependencies(a, b)

      expect(result.usesSelfAccess).toBe(true)
    })

    it('merges all flags correctly', () => {
      const a: DirectiveDependencies = {
        ...EMPTY_DEPENDENCIES,
        dependsOnPath: true,
        dependsOnCreated: true,
        dependsOnNoteExistence: true,
        firstLineNotes: new Set(['n1']),
      }
      const b: DirectiveDependencies = {
        ...EMPTY_DEPENDENCIES,
        dependsOnModified: true,
        dependsOnViewed: true,
        dependsOnAllNames: true,
        nonFirstLineNotes: new Set(['n2']),
        usesSelfAccess: true,
      }
      const result = mergeDependencies(a, b)

      expect(result.dependsOnPath).toBe(true)
      expect(result.dependsOnModified).toBe(true)
      expect(result.dependsOnCreated).toBe(true)
      expect(result.dependsOnViewed).toBe(true)
      expect(result.dependsOnNoteExistence).toBe(true)
      expect(result.dependsOnAllNames).toBe(true)
      expect(result.usesSelfAccess).toBe(true)
      expect(result.firstLineNotes).toEqual(new Set(['n1']))
      expect(result.nonFirstLineNotes).toEqual(new Set(['n2']))
    })
  })

  describe('isDependenciesEmpty', () => {
    it('returns true for EMPTY_DEPENDENCIES', () => {
      expect(isDependenciesEmpty(EMPTY_DEPENDENCIES)).toBe(true)
    })

    it('returns false when any flag is set', () => {
      expect(isDependenciesEmpty({ ...EMPTY_DEPENDENCIES, dependsOnPath: true })).toBe(false)
      expect(isDependenciesEmpty({ ...EMPTY_DEPENDENCIES, usesSelfAccess: true })).toBe(false)
    })

    it('returns false when note sets are non-empty', () => {
      expect(isDependenciesEmpty({ ...EMPTY_DEPENDENCIES, firstLineNotes: new Set(['a']) })).toBe(false)
      expect(isDependenciesEmpty({ ...EMPTY_DEPENDENCIES, nonFirstLineNotes: new Set(['a']) })).toBe(false)
    })

    it('returns false when hierarchy deps exist', () => {
      expect(isDependenciesEmpty({
        ...EMPTY_DEPENDENCIES,
        hierarchyDeps: [{ path: { kind: 'Up' }, resolvedNoteId: null, field: null, fieldHash: null }],
      })).toBe(false)
    })
  })
})
