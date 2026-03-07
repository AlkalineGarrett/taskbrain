import { describe, it, expect } from 'vitest'
import { Lexer } from '../../../dsl/language/Lexer'
import { Parser } from '../../../dsl/language/Parser'
import type { Expression, LambdaExpr } from '../../../dsl/language/Expression'

function expr(source: string): Expression {
  const tokens = new Lexer(source).tokenize()
  return new Parser(tokens, source).parseDirective().expression
}

describe('Lambda expressions', () => {
  describe('parsing', () => {
    it('parses lambda keyword with bracket body', () => {
      const e = expr('[lambda[42]]')
      expect(e.kind).toBe('LambdaExpr')
      if (e.kind === 'LambdaExpr') {
        expect(e.params).toEqual(['i'])
        expect(e.body.kind).toBe('NumberLiteral')
      }
    })

    it('lambda body contains the expression', () => {
      const e = expr('[lambda["hello"]]')
      expect(e.kind).toBe('LambdaExpr')
      if (e.kind === 'LambdaExpr') {
        expect(e.body.kind).toBe('StringLiteral')
        if (e.body.kind === 'StringLiteral') {
          expect(e.body.value).toBe('hello')
        }
      }
    })

    it('lambda with function call body', () => {
      const e = expr('[lambda[foo]]')
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
    it('parses lambda invocation with parentheses', () => {
      const e = expr('[lambda[42]()]')
      expect(e.kind).toBe('LambdaInvocation')
      if (e.kind === 'LambdaInvocation') {
        expect(e.lambda.kind).toBe('LambdaExpr')
        expect(e.args).toHaveLength(0)
      }
    })

    it('parses lambda invocation with arguments', () => {
      const e = expr('[lambda[i](10)]')
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
