import { describe, it, expect } from 'vitest'
import { validate, containsMutations, validationErrorMessage } from '../../../dsl/cache/MutationValidator'
import type { Expression } from '../../../dsl/language/Expression'

function selfRef(position = 0): Expression {
  return { kind: 'CurrentNoteRef', position }
}

function strLit(value: string, position = 0): Expression {
  return { kind: 'StringLiteral', value, position }
}

function numLit(value: number, position = 0): Expression {
  return { kind: 'NumberLiteral', value, position }
}

function varRef(name: string, position = 0): Expression {
  return { kind: 'VariableRef', name, position }
}

function call(name: string, args: Expression[] = [], namedArgs: { name: string; value: Expression; position: number }[] = [], position = 0): Expression {
  return { kind: 'CallExpr', name, args, namedArgs, position }
}

function methodCall(target: Expression, methodName: string, args: Expression[] = [], namedArgs: { name: string; value: Expression; position: number }[] = [], position = 0): Expression {
  return { kind: 'MethodCall', target, methodName, args, namedArgs, position }
}

function lambda(params: string[], body: Expression, position = 0): Expression {
  return { kind: 'LambdaExpr', params, body, position }
}

function prop(target: Expression, property: string, position = 0): Expression {
  return { kind: 'PropertyAccess', target, property, position }
}

describe('MutationValidator', () => {
  describe('bare mutations', () => {
    it('rejects bare new() call', () => {
      const expr = call('new', [strLit('test')])
      const result = validate(expr)
      expect(result.kind).toBe('BareMutation')
      if (result.kind === 'BareMutation') {
        expect(result.mutationType).toBe('new()')
      }
    })

    it('rejects bare maybe_new() call', () => {
      const expr = call('maybe_new', [strLit('test')])
      const result = validate(expr)
      expect(result.kind).toBe('BareMutation')
    })

    it('rejects bare .append() method', () => {
      const expr = methodCall(selfRef(), 'append', [strLit('text')])
      const result = validate(expr)
      expect(result.kind).toBe('BareMutation')
      if (result.kind === 'BareMutation') {
        expect(result.mutationType).toBe('.append()')
      }
    })
  })

  describe('mutations wrapped in button', () => {
    it('allows new() inside button', () => {
      const expr = call('button', [
        strLit('Create'),
        lambda([], call('new', [strLit('test')])),
      ])
      const result = validate(expr)
      expect(result.kind).toBe('Valid')
    })

    it('allows append inside button', () => {
      const expr = call('button', [
        strLit('Append'),
        lambda([], methodCall(selfRef(), 'append', [strLit('text')])),
      ])
      const result = validate(expr)
      expect(result.kind).toBe('Valid')
    })
  })

  describe('mutations wrapped in schedule', () => {
    it('allows new() inside schedule', () => {
      const expr = call('schedule', [
        strLit('daily'),
        lambda([], call('new', [strLit('test')])),
      ])
      const result = validate(expr)
      expect(result.kind).toBe('Valid')
    })
  })

  describe('bare time values', () => {
    it('rejects bare date() call', () => {
      const expr = call('date')
      const result = validate(expr)
      expect(result.kind).toBe('BareTimeValue')
      if (result.kind === 'BareTimeValue') {
        expect(result.functionName).toBe('date()')
      }
    })

    it('rejects bare time() call', () => {
      const expr = call('time')
      const result = validate(expr)
      expect(result.kind).toBe('BareTimeValue')
    })

    it('rejects bare datetime() call', () => {
      const expr = call('datetime')
      const result = validate(expr)
      expect(result.kind).toBe('BareTimeValue')
    })

    it('allows date() inside once expression', () => {
      const expr: Expression = { kind: 'OnceExpr', body: call('date'), position: 0 }
      const result = validate(expr)
      expect(result.kind).toBe('Valid')
    })

    it('allows time() inside refresh expression', () => {
      const expr: Expression = { kind: 'RefreshExpr', body: call('time'), position: 0 }
      const result = validate(expr)
      expect(result.kind).toBe('Valid')
    })
  })

  describe('non-mutating expressions', () => {
    it('accepts simple property access', () => {
      const expr = prop(selfRef(), 'name')
      const result = validate(expr)
      expect(result.kind).toBe('Valid')
    })

    it('accepts find() call', () => {
      const expr = call('find', [], [
        { name: 'path', value: strLit('test'), position: 0 },
      ])
      const result = validate(expr)
      expect(result.kind).toBe('Valid')
    })

    it('accepts number literal', () => {
      expect(validate(numLit(42)).kind).toBe('Valid')
    })

    it('accepts string literal', () => {
      expect(validate(strLit('hello')).kind).toBe('Valid')
    })

    it('accepts variable ref', () => {
      expect(validate(varRef('x')).kind).toBe('Valid')
    })
  })

  describe('containsMutations', () => {
    it('returns true for new() call', () => {
      expect(containsMutations(call('new', [strLit('test')]))).toBe(true)
    })

    it('returns true for maybe_new() call', () => {
      expect(containsMutations(call('maybe_new', [strLit('test')]))).toBe(true)
    })

    it('returns true for .append() method', () => {
      expect(containsMutations(methodCall(selfRef(), 'append', [strLit('text')]))).toBe(true)
    })

    it('returns true for button() call', () => {
      expect(containsMutations(call('button', [strLit('Click')]))).toBe(true)
    })

    it('returns true for schedule() call', () => {
      expect(containsMutations(call('schedule', [strLit('daily')]))).toBe(true)
    })

    it('returns false for non-mutating expressions', () => {
      expect(containsMutations(prop(selfRef(), 'name'))).toBe(false)
      expect(containsMutations(numLit(42))).toBe(false)
      expect(containsMutations(strLit('hello'))).toBe(false)
      expect(containsMutations(call('find'))).toBe(false)
    })

    it('detects mutations nested in statement list', () => {
      const expr: Expression = {
        kind: 'StatementList',
        statements: [numLit(1), call('new', [strLit('test')])],
        position: 0,
      }
      expect(containsMutations(expr)).toBe(true)
    })

    it('detects mutations nested in lambda', () => {
      const expr = lambda([], call('new', [strLit('test')]))
      expect(containsMutations(expr)).toBe(true)
    })
  })

  describe('validationErrorMessage', () => {
    it('returns null for valid result', () => {
      expect(validationErrorMessage({ kind: 'Valid' })).toBeNull()
    })

    it('returns message for bare mutation', () => {
      const result = validate(call('new', [strLit('test')]))
      const msg = validationErrorMessage(result)
      expect(msg).not.toBeNull()
      expect(msg).toContain('new()')
      expect(msg).toContain('requires explicit trigger')
    })

    it('returns message for bare time value', () => {
      const result = validate(call('date'))
      const msg = validationErrorMessage(result)
      expect(msg).not.toBeNull()
      expect(msg).toContain('date()')
      expect(msg).toContain('time value')
    })
  })
})
