import { describe, it, expect } from 'vitest'
import { analyze, analysisToPartialDependencies } from '../../../dsl/cache/DependencyAnalyzer'
import type { Expression } from '../../../dsl/language/Expression'

function selfRef(position = 0): Expression {
  return { kind: 'CurrentNoteRef', position }
}

function prop(target: Expression, property: string, position = 0): Expression {
  return { kind: 'PropertyAccess', target, property, position }
}

function call(name: string, args: Expression[] = [], namedArgs: { name: string; value: Expression; position: number }[] = [], position = 0): Expression {
  return { kind: 'CallExpr', name, args, namedArgs, position }
}

function methodCall(target: Expression, methodName: string, args: Expression[] = [], namedArgs: { name: string; value: Expression; position: number }[] = [], position = 0): Expression {
  return { kind: 'MethodCall', target, methodName, args, namedArgs, position }
}

function numLit(value: number, position = 0): Expression {
  return { kind: 'NumberLiteral', value, position }
}

function strLit(value: string, position = 0): Expression {
  return { kind: 'StringLiteral', value, position }
}

function varRef(name: string, position = 0): Expression {
  return { kind: 'VariableRef', name, position }
}

function lambda(params: string[], body: Expression, position = 0): Expression {
  return { kind: 'LambdaExpr', params, body, position }
}

describe('DependencyAnalyzer', () => {
  describe('field dependency detection', () => {
    it('detects path dependency', () => {
      const result = analyze(prop(selfRef(), 'path'))
      expect(result.dependsOnPath).toBe(true)
      expect(result.dependsOnModified).toBe(false)
    })

    it('detects modified dependency', () => {
      const result = analyze(prop(selfRef(), 'modified'))
      expect(result.dependsOnModified).toBe(true)
    })

    it('detects created dependency', () => {
      const result = analyze(prop(selfRef(), 'created'))
      expect(result.dependsOnCreated).toBe(true)
    })

    it('detects name access (first line)', () => {
      const result = analyze(prop(selfRef(), 'name'))
      expect(result.accessesFirstLine).toBe(true)
    })

    it('detects content access (first and non-first line)', () => {
      const result = analyze(prop(selfRef(), 'content'))
      expect(result.accessesFirstLine).toBe(true)
      expect(result.accessesNonFirstLine).toBe(true)
    })
  })

  describe('find predicates', () => {
    it('find with path depends on note existence and path', () => {
      const expr = call('find', [], [
        { name: 'path', value: strLit('a/b'), position: 0 },
      ])
      const result = analyze(expr)
      expect(result.dependsOnNoteExistence).toBe(true)
      expect(result.dependsOnPath).toBe(true)
    })

    it('find with name depends on note existence and all names', () => {
      const expr = call('find', [], [
        { name: 'name', value: strLit('test'), position: 0 },
      ])
      const result = analyze(expr)
      expect(result.dependsOnNoteExistence).toBe(true)
      expect(result.dependsOnAllNames).toBe(true)
      expect(result.accessesFirstLine).toBe(true)
    })

    it('find with where analyzes the where expression', () => {
      const whereBody = prop(varRef('note'), 'path')
      const expr = call('find', [], [
        { name: 'where', value: lambda(['note'], whereBody), position: 0 },
      ])
      const result = analyze(expr)
      expect(result.dependsOnNoteExistence).toBe(true)
    })
  })

  describe('mutation detection', () => {
    it('detects assignment as mutating', () => {
      const expr: Expression = {
        kind: 'Assignment',
        target: varRef('x'),
        value: numLit(1),
        position: 0,
      }
      const result = analyze(expr)
      expect(result.isMutating).toBe(true)
    })

    it('non-mutating expression is not flagged', () => {
      const result = analyze(prop(selfRef(), 'name'))
      expect(result.isMutating).toBe(false)
    })
  })

  describe('hierarchy access detection', () => {
    it('detects up property access on self', () => {
      const expr = prop(selfRef(), 'up')
      const result = analyze(expr)
      expect(result.hierarchyAccesses.length).toBeGreaterThan(0)
      expect(result.hierarchyAccesses[0]!.path.kind).toBe('Up')
    })

    it('detects root property access on self', () => {
      const expr = prop(selfRef(), 'root')
      const result = analyze(expr)
      expect(result.hierarchyAccesses.length).toBeGreaterThan(0)
      expect(result.hierarchyAccesses[0]!.path.kind).toBe('Root')
    })

    it('detects up method call with levels', () => {
      const expr = methodCall(selfRef(), 'up', [numLit(3)])
      const result = analyze(expr)
      expect(result.hierarchyAccesses.length).toBeGreaterThan(0)
      const access = result.hierarchyAccesses[0]!
      expect(access.path.kind).toBe('UpN')
      if (access.path.kind === 'UpN') {
        expect(access.path.levels).toBe(3)
      }
    })

    it('detects field access on hierarchy path', () => {
      // .up.name
      const expr = prop(prop(selfRef(), 'up'), 'name')
      const result = analyze(expr)
      const fieldAccesses = result.hierarchyAccesses.filter((a) => a.field !== null)
      expect(fieldAccesses.length).toBeGreaterThan(0)
    })

    it('detects chained up access', () => {
      // .up.up -> UpN(2)
      const expr = prop(prop(selfRef(), 'up'), 'up')
      const result = analyze(expr)
      const upAccesses = result.hierarchyAccesses.filter((a) => a.path.kind === 'UpN')
      expect(upAccesses.length).toBeGreaterThan(0)
      if (upAccesses[0]!.path.kind === 'UpN') {
        expect(upAccesses[0]!.path.levels).toBe(2)
      }
    })
  })

  describe('analysisToPartialDependencies', () => {
    it('converts analysis to dependencies', () => {
      const analysis = analyze(call('find', [], [
        { name: 'path', value: strLit('x'), position: 0 },
      ]))
      const deps = analysisToPartialDependencies(analysis)
      expect(deps.dependsOnPath).toBe(true)
      expect(deps.dependsOnNoteExistence).toBe(true)
    })
  })

  describe('statement list analysis', () => {
    it('combines dependencies from multiple statements', () => {
      const expr: Expression = {
        kind: 'StatementList',
        statements: [
          prop(selfRef(), 'path'),
          prop(selfRef(), 'modified'),
        ],
        position: 0,
      }
      const result = analyze(expr)
      expect(result.dependsOnPath).toBe(true)
      expect(result.dependsOnModified).toBe(true)
    })
  })

  describe('lambda analysis', () => {
    it('analyzes lambda body for dependencies', () => {
      const expr = lambda(['x'], prop(selfRef(), 'modified'))
      const result = analyze(expr)
      expect(result.dependsOnModified).toBe(true)
    })
  })
})
