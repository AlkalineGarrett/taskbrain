import { describe, it, expect } from 'vitest'
import { Lexer } from '../../../dsl/language/Lexer'
import { Parser } from '../../../dsl/language/Parser'
import type { Expression } from '../../../dsl/language/Expression'

function expr(source: string): Expression {
  const tokens = new Lexer(source).tokenize()
  return new Parser(tokens, source).parseDirective().expression
}

describe('once[...] expression', () => {
  describe('parsing', () => {
    it('parses once with number body', () => {
      const e = expr('[once[42]]')
      expect(e.kind).toBe('OnceExpr')
      if (e.kind === 'OnceExpr') {
        expect(e.body.kind).toBe('NumberLiteral')
        if (e.body.kind === 'NumberLiteral') {
          expect(e.body.value).toBe(42)
        }
      }
    })

    it('parses once with function call body', () => {
      const e = expr('[once[foo]]')
      expect(e.kind).toBe('OnceExpr')
      if (e.kind === 'OnceExpr') {
        expect(e.body.kind).toBe('CallExpr')
        if (e.body.kind === 'CallExpr') {
          expect(e.body.name).toBe('foo')
        }
      }
    })

    it('parses once with string body', () => {
      const e = expr('[once["hello"]]')
      expect(e.kind).toBe('OnceExpr')
      if (e.kind === 'OnceExpr') {
        expect(e.body.kind).toBe('StringLiteral')
      }
    })

    it('parses once with nested expression', () => {
      const e = expr('[once[foo.bar()]]')
      expect(e.kind).toBe('OnceExpr')
      if (e.kind === 'OnceExpr') {
        expect(e.body.kind).toBe('MethodCall')
      }
    })
  })

  describe('caching behavior', () => {
    it('once wraps body in OnceExpr node', () => {
      const e = expr('[once[date]]')
      expect(e.kind).toBe('OnceExpr')
      // The caching behavior is a runtime concern; at parse level,
      // we verify the AST structure
      if (e.kind === 'OnceExpr') {
        expect(e.body.kind).toBe('CallExpr')
        if (e.body.kind === 'CallExpr') {
          expect(e.body.name).toBe('date')
        }
      }
    })
  })

  describe('temporal value wrapping', () => {
    it('once around dynamic call creates static wrapper', () => {
      // At the parse level, once[] just wraps the body
      // The DynamicCallAnalyzer treats once[] as non-dynamic
      const e = expr('[once[datetime]]')
      expect(e.kind).toBe('OnceExpr')
      if (e.kind === 'OnceExpr') {
        expect(e.body.kind).toBe('CallExpr')
      }
    })
  })
})
