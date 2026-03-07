import { describe, it, expect } from 'vitest'
import { Lexer } from '../../../dsl/language/Lexer'
import { Parser } from '../../../dsl/language/Parser'
import type { Expression, PatternExpr, Quantified } from '../../../dsl/language/Expression'
import { CharClassType } from '../../../dsl/language/Expression'

function expr(source: string): Expression {
  const tokens = new Lexer(source).tokenize()
  return new Parser(tokens, source).parseDirective().expression
}

function patternExpr(source: string): PatternExpr {
  const e = expr(source)
  expect(e.kind).toBe('PatternExpr')
  return e as PatternExpr
}

describe('Pattern parsing', () => {
  it('parses digit with exact quantifier', () => {
    const p = patternExpr('[pattern(digit*4)]')
    expect(p.elements).toHaveLength(1)
    const el = p.elements[0]!
    expect(el.kind).toBe('Quantified')
    if (el.kind === 'Quantified') {
      expect(el.element.kind).toBe('CharClass')
      if (el.element.kind === 'CharClass') {
        expect(el.element.type).toBe(CharClassType.DIGIT)
      }
      expect(el.quantifier).toEqual({ kind: 'Exact', n: 4 })
    }
  })

  it('parses letter with open-ended range', () => {
    const p = patternExpr('[pattern(letter*(1..))]')
    expect(p.elements).toHaveLength(1)
    const el = p.elements[0]!
    expect(el.kind).toBe('Quantified')
    if (el.kind === 'Quantified') {
      expect(el.element.kind).toBe('CharClass')
      if (el.element.kind === 'CharClass') {
        expect(el.element.type).toBe(CharClassType.LETTER)
      }
      expect(el.quantifier).toEqual({ kind: 'Range', min: 1, max: null })
    }
  })

  it('parses bounded range quantifier', () => {
    const p = patternExpr('[pattern(digit*(2..5))]')
    const el = p.elements[0]! as Quantified
    expect(el.quantifier).toEqual({ kind: 'Range', min: 2, max: 5 })
  })

  it('parses any quantifier', () => {
    const p = patternExpr('[pattern(letter*any)]')
    const el = p.elements[0]! as Quantified
    expect(el.quantifier).toEqual({ kind: 'Any' })
  })

  it('parses literal string in pattern', () => {
    const p = patternExpr('[pattern("-" digit*4)]')
    expect(p.elements).toHaveLength(2)
    expect(p.elements[0]!.kind).toBe('PatternLiteral')
    if (p.elements[0]!.kind === 'PatternLiteral') {
      expect(p.elements[0]!.value).toBe('-')
    }
    expect(p.elements[1]!.kind).toBe('Quantified')
  })

  it('parses complex pattern with multiple elements', () => {
    // e.g. digit*4 "-" digit*2 "-" digit*2
    const p = patternExpr('[pattern(digit*4 "-" digit*2 "-" digit*2)]')
    expect(p.elements).toHaveLength(5)
    expect(p.elements[0]!.kind).toBe('Quantified')
    expect(p.elements[1]!.kind).toBe('PatternLiteral')
    expect(p.elements[2]!.kind).toBe('Quantified')
    expect(p.elements[3]!.kind).toBe('PatternLiteral')
    expect(p.elements[4]!.kind).toBe('Quantified')
  })

  it('parses unquantified char class', () => {
    const p = patternExpr('[pattern(digit)]')
    expect(p.elements).toHaveLength(1)
    expect(p.elements[0]!.kind).toBe('CharClass')
    if (p.elements[0]!.kind === 'CharClass') {
      expect(p.elements[0]!.type).toBe(CharClassType.DIGIT)
    }
  })

  it('supports space char class', () => {
    const p = patternExpr('[pattern(space*1)]')
    const el = p.elements[0]! as Quantified
    expect(el.element.kind).toBe('CharClass')
    if (el.element.kind === 'CharClass') {
      expect(el.element.type).toBe(CharClassType.SPACE)
    }
  })

  it('supports punct char class', () => {
    const p = patternExpr('[pattern(punct)]')
    expect(p.elements[0]!.kind).toBe('CharClass')
    if (p.elements[0]!.kind === 'CharClass') {
      expect(p.elements[0]!.type).toBe(CharClassType.PUNCT)
    }
  })

  it('supports any char class', () => {
    const p = patternExpr('[pattern(any*3)]')
    const el = p.elements[0]! as Quantified
    expect(el.element.kind).toBe('CharClass')
    if (el.element.kind === 'CharClass') {
      expect(el.element.type).toBe(CharClassType.ANY)
    }
  })

  it('rejects empty pattern', () => {
    expect(() => expr('[pattern()]')).toThrow('Pattern cannot be empty')
  })

  it('rejects unknown char class', () => {
    expect(() => expr('[pattern(foobar)]')).toThrow("Unknown pattern character class 'foobar'")
  })
})
