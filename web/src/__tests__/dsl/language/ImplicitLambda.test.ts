import { describe, it, expect } from 'vitest'
import { Lexer } from '../../../dsl/language/Lexer'
import { Parser } from '../../../dsl/language/Parser'
import type { Expression } from '../../../dsl/language/Expression'

function expr(source: string): Expression {
  const tokens = new Lexer(source).tokenize()
  return new Parser(tokens, source).parseDirective().expression
}

describe('Implicit lambda expressions', () => {
  it('parses bracket body after identifier as implicit lambda arg', () => {
    // foo[expr] => CallExpr(foo, [LambdaExpr(body=expr)])
    const e = expr('[foo[42]]')
    expect(e.kind).toBe('CallExpr')
    if (e.kind === 'CallExpr') {
      expect(e.name).toBe('foo')
      expect(e.args).toHaveLength(1)
      const arg = e.args[0]!
      expect(arg.kind).toBe('LambdaExpr')
      if (arg.kind === 'LambdaExpr') {
        expect(arg.params).toEqual(['i'])
        expect(arg.body.kind).toBe('NumberLiteral')
      }
    }
  })

  it('implicit lambda has default param i', () => {
    const e = expr('[filter[i.gt(0)]]')
    expect(e.kind).toBe('CallExpr')
    if (e.kind === 'CallExpr') {
      expect(e.args).toHaveLength(1)
      const lambda = e.args[0]!
      expect(lambda.kind).toBe('LambdaExpr')
      if (lambda.kind === 'LambdaExpr') {
        expect(lambda.params).toEqual(['i'])
      }
    }
  })

  describe('multi-arg lambda', () => {
    it('parses multi-arg lambda with two params', () => {
      const e = expr('[(a,b)[a]]')
      expect(e.kind).toBe('LambdaExpr')
      if (e.kind === 'LambdaExpr') {
        expect(e.params).toEqual(['a', 'b'])
        expect(e.body.kind).toBe('CallExpr')
        if (e.body.kind === 'CallExpr') {
          expect(e.body.name).toBe('a')
        }
      }
    })

    it('parses multi-arg lambda with three params', () => {
      const e = expr('[(x,y,z)[x]]')
      expect(e.kind).toBe('LambdaExpr')
      if (e.kind === 'LambdaExpr') {
        expect(e.params).toEqual(['x', 'y', 'z'])
      }
    })
  })

  describe('immediate invocation', () => {
    it('lambda invocation with parens after implicit lambda', () => {
      const e = expr('[[42]()]')
      expect(e.kind).toBe('LambdaInvocation')
      if (e.kind === 'LambdaInvocation') {
        expect(e.lambda.kind).toBe('LambdaExpr')
        expect(e.args).toHaveLength(0)
      }
    })

    it('multi-arg lambda invocation', () => {
      const e = expr('[(a,b)[a](1, 2)]')
      expect(e.kind).toBe('LambdaInvocation')
      if (e.kind === 'LambdaInvocation') {
        expect(e.lambda.kind).toBe('LambdaExpr')
        if (e.lambda.kind === 'LambdaExpr') {
          expect(e.lambda.params).toEqual(['a', 'b'])
        }
        expect(e.args).toHaveLength(2)
      }
    })
  })
})
