import { describe, it, expect } from 'vitest'
import { Lexer } from '../../../dsl/language/Lexer'
import { Parser } from '../../../dsl/language/Parser'
import type { Expression } from '../../../dsl/language/Expression'

function expr(source: string): Expression {
  const tokens = new Lexer(source).tokenize()
  return new Parser(tokens, source).parseDirective().expression
}

describe('Method-style syntax', () => {
  it('parses method call with gt', () => {
    const e = expr('[foo.gt(10)]')
    expect(e.kind).toBe('MethodCall')
    if (e.kind === 'MethodCall') {
      expect(e.methodName).toBe('gt')
      expect(e.args).toHaveLength(1)
      expect(e.args[0]!.kind).toBe('NumberLiteral')
      expect(e.target.kind).toBe('CallExpr')
      if (e.target.kind === 'CallExpr') {
        expect(e.target.name).toBe('foo')
      }
    }
  })

  it('parses method call with startsWith', () => {
    const e = expr('[foo.startsWith("bar")]')
    expect(e.kind).toBe('MethodCall')
    if (e.kind === 'MethodCall') {
      expect(e.methodName).toBe('startsWith')
      expect(e.args).toHaveLength(1)
      expect(e.args[0]!.kind).toBe('StringLiteral')
    }
  })

  it('parses method call with plus', () => {
    const e = expr('[foo.plus(1)]')
    expect(e.kind).toBe('MethodCall')
    if (e.kind === 'MethodCall') {
      expect(e.methodName).toBe('plus')
      expect(e.args).toHaveLength(1)
    }
  })

  it('parses chained method calls', () => {
    const e = expr('[foo.bar().baz()]')
    expect(e.kind).toBe('MethodCall')
    if (e.kind === 'MethodCall') {
      expect(e.methodName).toBe('baz')
      expect(e.target.kind).toBe('MethodCall')
      if (e.target.kind === 'MethodCall') {
        expect(e.target.methodName).toBe('bar')
      }
    }
  })

  it('parses method call without args', () => {
    const e = expr('[foo.bar()]')
    expect(e.kind).toBe('MethodCall')
    if (e.kind === 'MethodCall') {
      expect(e.methodName).toBe('bar')
      expect(e.args).toHaveLength(0)
    }
  })

  it('parses property access (no parens) as PropertyAccess', () => {
    const e = expr('[foo.bar]')
    expect(e.kind).toBe('PropertyAccess')
    if (e.kind === 'PropertyAccess') {
      expect(e.property).toBe('bar')
    }
  })
})
