import { describe, it, expect } from 'vitest'
import { Lexer } from '../../../dsl/language/Lexer'
import { Parser } from '../../../dsl/language/Parser'
import type { Expression } from '../../../dsl/language/Expression'

function expr(source: string): Expression {
  const tokens = new Lexer(source).tokenize()
  return new Parser(tokens, source).parseDirective().expression
}

describe('refresh[...] expression', () => {
  describe('parsing', () => {
    it('parses refresh with number body', () => {
      const e = expr('[refresh[42]]')
      expect(e.kind).toBe('RefreshExpr')
      if (e.kind === 'RefreshExpr') {
        expect(e.body.kind).toBe('NumberLiteral')
        if (e.body.kind === 'NumberLiteral') {
          expect(e.body.value).toBe(42)
        }
      }
    })

    it('parses refresh with function call body', () => {
      const e = expr('[refresh[foo]]')
      expect(e.kind).toBe('RefreshExpr')
      if (e.kind === 'RefreshExpr') {
        expect(e.body.kind).toBe('CallExpr')
        if (e.body.kind === 'CallExpr') {
          expect(e.body.name).toBe('foo')
        }
      }
    })

    it('parses refresh with string body', () => {
      const e = expr('[refresh["hello"]]')
      expect(e.kind).toBe('RefreshExpr')
      if (e.kind === 'RefreshExpr') {
        expect(e.body.kind).toBe('StringLiteral')
      }
    })
  })

  describe('evaluation', () => {
    it('refresh wraps body in RefreshExpr node', () => {
      const e = expr('[refresh[date]]')
      expect(e.kind).toBe('RefreshExpr')
      if (e.kind === 'RefreshExpr') {
        expect(e.body.kind).toBe('CallExpr')
        if (e.body.kind === 'CallExpr') {
          expect(e.body.name).toBe('date')
        }
      }
    })

    it('refresh with method call body', () => {
      const e = expr('[refresh[foo.bar()]]')
      expect(e.kind).toBe('RefreshExpr')
      if (e.kind === 'RefreshExpr') {
        expect(e.body.kind).toBe('MethodCall')
      }
    })
  })

  describe('temporal value wrapping', () => {
    it('refresh around dynamic call remains dynamic', () => {
      // At parse level, refresh[] just wraps the body
      // The DynamicCallAnalyzer treats refresh[] as dynamic
      const e = expr('[refresh[datetime]]')
      expect(e.kind).toBe('RefreshExpr')
      if (e.kind === 'RefreshExpr') {
        expect(e.body.kind).toBe('CallExpr')
      }
    })
  })
})
