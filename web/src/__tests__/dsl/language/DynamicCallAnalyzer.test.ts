import { describe, it, expect, vi, beforeEach } from 'vitest'
import { Lexer } from '../../../dsl/language/Lexer'
import { Parser } from '../../../dsl/language/Parser'
import type { Expression } from '../../../dsl/language/Expression'

// Mock the BuiltinRegistry to avoid requiring the full builtins module tree
vi.mock('../../../dsl/runtime/BuiltinRegistry', () => {
  const DYNAMIC_FUNCTIONS = new Set(['date', 'datetime', 'time'])
  return {
    BuiltinRegistry: {
      isDynamic: (name: string) => DYNAMIC_FUNCTIONS.has(name),
    },
  }
})

// Import after mock is set up
import { containsDynamicCalls } from '../../../dsl/language/DynamicCallAnalyzer'

function expr(source: string): Expression {
  const tokens = new Lexer(source).tokenize()
  return new Parser(tokens, source).parseDirective().expression
}

describe('DynamicCallAnalyzer', () => {
  describe('dynamic call detection', () => {
    it('date is dynamic', () => {
      expect(containsDynamicCalls(expr('[date]'))).toBe(true)
    })

    it('datetime is dynamic', () => {
      expect(containsDynamicCalls(expr('[datetime]'))).toBe(true)
    })

    it('time is dynamic', () => {
      expect(containsDynamicCalls(expr('[time]'))).toBe(true)
    })

    it('qt is static (not dynamic)', () => {
      expect(containsDynamicCalls(expr('[qt]'))).toBe(false)
    })

    it('number literal is not dynamic', () => {
      expect(containsDynamicCalls(expr('[42]'))).toBe(false)
    })

    it('string literal is not dynamic', () => {
      expect(containsDynamicCalls(expr('["hello"]'))).toBe(false)
    })

    it('current note ref is not dynamic', () => {
      expect(containsDynamicCalls(expr('[.]'))).toBe(false)
    })

    it('pattern expression is not dynamic', () => {
      expect(containsDynamicCalls(expr('[pattern(digit*4)]'))).toBe(false)
    })

    it('property access on static is not dynamic', () => {
      expect(containsDynamicCalls(expr('[.name]'))).toBe(false)
    })

    it('variable ref is not dynamic', () => {
      const e = expr('[x: 42; x]')
      expect(containsDynamicCalls(e)).toBe(false)
    })
  })

  describe('refresh is dynamic', () => {
    it('refresh expression is always dynamic', () => {
      expect(containsDynamicCalls(expr('[refresh[42]]'))).toBe(true)
    })

    it('refresh with static body is still dynamic', () => {
      expect(containsDynamicCalls(expr('[refresh["hello"]]'))).toBe(true)
    })
  })

  describe('once wraps dynamic to static', () => {
    it('once around dynamic call is not dynamic', () => {
      expect(containsDynamicCalls(expr('[once[date]]'))).toBe(false)
    })

    it('once around datetime is not dynamic', () => {
      expect(containsDynamicCalls(expr('[once[datetime]]'))).toBe(false)
    })

    it('once around static remains static', () => {
      expect(containsDynamicCalls(expr('[once[42]]'))).toBe(false)
    })
  })

  describe('propagation', () => {
    it('dynamic call in function arg propagates', () => {
      expect(containsDynamicCalls(expr('[foo(date)]'))).toBe(true)
    })

    it('dynamic call in method target propagates', () => {
      expect(containsDynamicCalls(expr('[date.format()]'))).toBe(true)
    })

    it('dynamic call in method arg propagates', () => {
      expect(containsDynamicCalls(expr('[foo.bar(date)]'))).toBe(true)
    })

    it('dynamic call in statement list propagates', () => {
      expect(containsDynamicCalls(expr('[x: 1; date]'))).toBe(true)
    })

    it('dynamic call in assignment value propagates', () => {
      expect(containsDynamicCalls(expr('[x: date]'))).toBe(true)
    })

    it('dynamic call in lambda body propagates', () => {
      expect(containsDynamicCalls(expr('[lambda[date]]'))).toBe(true)
    })

    it('static lambda body is not dynamic', () => {
      expect(containsDynamicCalls(expr('[lambda[42]]'))).toBe(false)
    })
  })
})
