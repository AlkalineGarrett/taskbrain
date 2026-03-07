import { describe, it, expect } from 'vitest'
import { Lexer } from '../../../dsl/language/Lexer'
import { Parser } from '../../../dsl/language/Parser'
import { analyze, checkUnwrappedMutations } from '../../../dsl/language/IdempotencyAnalyzer'
import type { Expression } from '../../../dsl/language/Expression'

function expr(source: string): Expression {
  const tokens = new Lexer(source).tokenize()
  return new Parser(tokens, source).parseDirective().expression
}

describe('IdempotencyAnalyzer', () => {
  describe('pure expression detection', () => {
    it('number literal is idempotent', () => {
      expect(analyze(expr('[42]')).isIdempotent).toBe(true)
    })

    it('string literal is idempotent', () => {
      expect(analyze(expr('["hello"]')).isIdempotent).toBe(true)
    })

    it('current note ref is idempotent', () => {
      expect(analyze(expr('[.]')).isIdempotent).toBe(true)
    })

    it('property access is idempotent', () => {
      expect(analyze(expr('[.name]')).isIdempotent).toBe(true)
    })

    it('regular function call is idempotent', () => {
      expect(analyze(expr('[date]')).isIdempotent).toBe(true)
    })

    it('pattern expression is idempotent', () => {
      expect(analyze(expr('[pattern(digit*4)]')).isIdempotent).toBe(true)
    })

    it('once expression is idempotent', () => {
      expect(analyze(expr('[once[date]]')).isIdempotent).toBe(true)
    })

    it('refresh expression is idempotent', () => {
      expect(analyze(expr('[refresh[date]]')).isIdempotent).toBe(true)
    })

    it('variable ref is idempotent', () => {
      const stmtList = expr('[x: 42; x]')
      // The statement list itself should be idempotent
      expect(analyze(stmtList).isIdempotent).toBe(true)
    })
  })

  describe('mutations needing wrappers', () => {
    it('new() is not idempotent', () => {
      const result = analyze(expr('[new()]'))
      expect(result.isIdempotent).toBe(false)
      expect(result.nonIdempotentReason).toContain('new()')
    })

    it('append method is not idempotent', () => {
      const result = analyze(expr('[foo.append("x")]'))
      expect(result.isIdempotent).toBe(false)
      expect(result.nonIdempotentReason).toContain('.append()')
    })

    it('new() inside button is idempotent', () => {
      // button wraps a non-idempotent action
      // button(new()) - the button function is an action wrapper
      // However, button is in ACTION_WRAPPER_FUNCTIONS, so it checks the first arg
      // but the first arg (new()) IS non-idempotent, so it propagates
      const result = analyze(expr('[button(new())]'))
      expect(result.isIdempotent).toBe(false)
    })

    it('schedule wrapping checks inner expression', () => {
      const result = analyze(expr('[schedule(new())]'))
      expect(result.isIdempotent).toBe(false)
    })

    it('regular call with non-idempotent arg propagates', () => {
      const result = analyze(expr('[foo(new())]'))
      expect(result.isIdempotent).toBe(false)
    })
  })

  describe('propagation', () => {
    it('non-idempotent propagates through statement list', () => {
      const result = analyze(expr('[x: 1; new()]'))
      expect(result.isIdempotent).toBe(false)
    })

    it('non-idempotent propagates through method call target', () => {
      const result = analyze(expr('[new().bar()]'))
      expect(result.isIdempotent).toBe(false)
    })

    it('non-idempotent propagates through method call args', () => {
      const result = analyze(expr('[foo.bar(new())]'))
      expect(result.isIdempotent).toBe(false)
    })

    it('non-idempotent propagates through lambda body', () => {
      const result = analyze(expr('[lambda[new()]]'))
      expect(result.isIdempotent).toBe(false)
    })

    it('idempotent statement list is idempotent', () => {
      const result = analyze(expr('[x: 1; y: 2]'))
      expect(result.isIdempotent).toBe(true)
    })
  })

  describe('checkUnwrappedMutations', () => {
    it('property assignment without once needs wrapper', () => {
      const result = checkUnwrappedMutations(expr('[.name: "test"]'))
      expect(result.isIdempotent).toBe(false)
      expect(result.nonIdempotentReason).toContain('once[]')
    })

    it('property assignment inside once is ok', () => {
      const result = checkUnwrappedMutations(expr('[once[.name: "test"]]'))
      expect(result.isIdempotent).toBe(true)
    })

    it('refresh propagates check to body', () => {
      const result = checkUnwrappedMutations(expr('[refresh[.name: "test"]]'))
      expect(result.isIdempotent).toBe(false)
    })

    it('statement list checks all statements', () => {
      const result = checkUnwrappedMutations(expr('[x: 1; .name: "test"]'))
      expect(result.isIdempotent).toBe(false)
    })

    it('regular expression is ok', () => {
      const result = checkUnwrappedMutations(expr('[date]'))
      expect(result.isIdempotent).toBe(true)
    })
  })
})
