import { describe, it, expect } from 'vitest'
import { Lexer } from '../../../dsl/language/Lexer'
import { Parser } from '../../../dsl/language/Parser'
import type { Expression } from '../../../dsl/language/Expression'

function expr(source: string): Expression {
  const tokens = new Lexer(source).tokenize()
  return new Parser(tokens, source).parseDirective().expression
}

describe('later keyword', () => {
  it('later creates implicit lambda from next primary', () => {
    const e = expr('[later foo]')
    // "later foo" => the 'later' consumes 'foo' as the body of a LambdaExpr
    // Then the outer parseCallChain does NOT see a CallExpr (it sees LambdaExpr),
    // so there is no nested call chain wrapping
    expect(e.kind).toBe('LambdaExpr')
    if (e.kind === 'LambdaExpr') {
      expect(e.params).toEqual(['i'])
      expect(e.body.kind).toBe('CallExpr')
      if (e.body.kind === 'CallExpr') {
        expect(e.body.name).toBe('foo')
      }
    }
  })

  it('later with bracket block creates deferred block', () => {
    const e = expr('[later[42]]')
    expect(e.kind).toBe('LambdaExpr')
    if (e.kind === 'LambdaExpr') {
      expect(e.params).toEqual(['i'])
      expect(e.body.kind).toBe('NumberLiteral')
    }
  })

  it('later wraps number literal in lambda', () => {
    const e = expr('[later 42]')
    expect(e.kind).toBe('LambdaExpr')
    if (e.kind === 'LambdaExpr') {
      expect(e.params).toEqual(['i'])
      expect(e.body.kind).toBe('NumberLiteral')
      if (e.body.kind === 'NumberLiteral') {
        expect(e.body.value).toBe(42)
      }
    }
  })

  it('later wraps string literal in lambda', () => {
    const e = expr('[later "hello"]')
    expect(e.kind).toBe('LambdaExpr')
    if (e.kind === 'LambdaExpr') {
      expect(e.params).toEqual(['i'])
      expect(e.body.kind).toBe('StringLiteral')
      if (e.body.kind === 'StringLiteral') {
        expect(e.body.value).toBe('hello')
      }
    }
  })
})
