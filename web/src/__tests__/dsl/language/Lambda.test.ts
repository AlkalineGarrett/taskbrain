import { describe, it, expect } from 'vitest'
import { Lexer } from '../../../dsl/language/Lexer'
import { Parser } from '../../../dsl/language/Parser'
import type { Expression } from '../../../dsl/language/Expression'

function expr(source: string): Expression {
  const tokens = new Lexer(source).tokenize()
  return new Parser(tokens, source).parseDirective().expression
}

describe('Lambda expressions', () => {
  describe('parsing', () => {
    it('parses implicit lambda with bracket body', () => {
      const e = expr('[[42]]')
      expect(e.kind).toBe('LambdaExpr')
      if (e.kind === 'LambdaExpr') {
        expect(e.params).toEqual(['i'])
        expect(e.body.kind).toBe('NumberLiteral')
      }
    })

    it('implicit lambda body contains the expression', () => {
      const e = expr('[["hello"]]')
      expect(e.kind).toBe('LambdaExpr')
      if (e.kind === 'LambdaExpr') {
        expect(e.body.kind).toBe('StringLiteral')
        if (e.body.kind === 'StringLiteral') {
          expect(e.body.value).toBe('hello')
        }
      }
    })

    it('implicit lambda with function call body', () => {
      const e = expr('[[foo]]')
      expect(e.kind).toBe('LambdaExpr')
      if (e.kind === 'LambdaExpr') {
        expect(e.body.kind).toBe('CallExpr')
        if (e.body.kind === 'CallExpr') {
          expect(e.body.name).toBe('foo')
        }
      }
    })
  })

  describe('invocation', () => {
    it('parses implicit lambda invocation with parentheses', () => {
      const e = expr('[[42]()]')
      expect(e.kind).toBe('LambdaInvocation')
      if (e.kind === 'LambdaInvocation') {
        expect(e.lambda.kind).toBe('LambdaExpr')
        expect(e.args).toHaveLength(0)
      }
    })

    it('parses implicit lambda invocation with arguments', () => {
      const e = expr('[[i](10)]')
      expect(e.kind).toBe('LambdaInvocation')
      if (e.kind === 'LambdaInvocation') {
        expect(e.args).toHaveLength(1)
      }
    })
  })

  describe('find with lambdas', () => {
    it('parses find with lambda argument as implicit lambda', () => {
      const e = expr('[find[i]]')
      expect(e.kind).toBe('CallExpr')
      if (e.kind === 'CallExpr') {
        expect(e.name).toBe('find')
        expect(e.args).toHaveLength(1)
        const arg = e.args[0]!
        expect(arg.kind).toBe('LambdaExpr')
        if (arg.kind === 'LambdaExpr') {
          expect(arg.params).toEqual(['i'])
          expect(arg.body.kind).toBe('CallExpr')
        }
      }
    })
  })
})
