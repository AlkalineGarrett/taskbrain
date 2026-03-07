import { describe, it, expect } from 'vitest'
import { Lexer } from '../../../dsl/language/Lexer'
import { Parser, ParseException } from '../../../dsl/language/Parser'
import type { Directive } from '../../../dsl/language/Expression'
import type { Expression } from '../../../dsl/language/Expression'

function parse(source: string): Directive {
  const tokens = new Lexer(source).tokenize()
  return new Parser(tokens, source).parseDirective()
}

function expr(source: string): Expression {
  return parse(source).expression
}

describe('Parser', () => {
  describe('number literals', () => {
    it('parses integer literal', () => {
      const e = expr('[42]')
      expect(e.kind).toBe('NumberLiteral')
      if (e.kind === 'NumberLiteral') {
        expect(e.value).toBe(42)
      }
    })

    it('parses decimal literal', () => {
      const e = expr('[3.14]')
      expect(e.kind).toBe('NumberLiteral')
      if (e.kind === 'NumberLiteral') {
        expect(e.value).toBe(3.14)
      }
    })
  })

  describe('string literals', () => {
    it('parses simple string', () => {
      const e = expr('["hello"]')
      expect(e.kind).toBe('StringLiteral')
      if (e.kind === 'StringLiteral') {
        expect(e.value).toBe('hello')
      }
    })

    it('parses empty string', () => {
      const e = expr('[""]')
      expect(e.kind).toBe('StringLiteral')
      if (e.kind === 'StringLiteral') {
        expect(e.value).toBe('')
      }
    })
  })

  describe('directive metadata', () => {
    it('captures source text', () => {
      const d = parse('[foo]')
      expect(d.sourceText).toBe('[foo]')
    })

    it('captures source text with complex expression', () => {
      const d = parse('[foo(42, "hello")]')
      expect(d.sourceText).toBe('[foo(42, "hello")]')
    })

    it('captures start position', () => {
      const d = parse('[foo]')
      expect(d.startPosition).toBe(0)
    })

    it('captures position with leading whitespace', () => {
      // Note: the lexer skips whitespace, but the Parser captures position from the LBRACKET token
      const d = parse('  [foo]')
      expect(d.startPosition).toBe(2)
      expect(d.sourceText).toBe('[foo]')
    })
  })

  describe('error cases', () => {
    it('throws on missing opening bracket', () => {
      expect(() => parse('foo]')).toThrow(ParseException)
    })

    it('throws on missing closing bracket', () => {
      expect(() => parse('[foo')).toThrow(ParseException)
    })

    it('throws on empty directive', () => {
      expect(() => parse('[]')).toThrow(ParseException)
    })
  })

  describe('function calls', () => {
    it('parses single identifier as function call', () => {
      const e = expr('[foo]')
      expect(e.kind).toBe('CallExpr')
      if (e.kind === 'CallExpr') {
        expect(e.name).toBe('foo')
        expect(e.args).toHaveLength(0)
      }
    })

    it('parses function call with parenthesized args', () => {
      const e = expr('[foo(42)]')
      expect(e.kind).toBe('CallExpr')
      if (e.kind === 'CallExpr') {
        expect(e.name).toBe('foo')
        expect(e.args).toHaveLength(1)
        expect(e.args[0]!.kind).toBe('NumberLiteral')
      }
    })

    it('parses nested function calls', () => {
      const e = expr('[foo bar]')
      expect(e.kind).toBe('CallExpr')
      if (e.kind === 'CallExpr') {
        expect(e.name).toBe('foo')
        expect(e.args).toHaveLength(1)
        const inner = e.args[0]!
        expect(inner.kind).toBe('CallExpr')
        if (inner.kind === 'CallExpr') {
          expect(inner.name).toBe('bar')
        }
      }
    })

    it('parses three identifiers as nested calls', () => {
      const e = expr('[foo bar baz]')
      expect(e.kind).toBe('CallExpr')
      if (e.kind === 'CallExpr') {
        expect(e.name).toBe('foo')
        expect(e.args).toHaveLength(1)
        const middle = e.args[0]!
        expect(middle.kind).toBe('CallExpr')
        if (middle.kind === 'CallExpr') {
          expect(middle.name).toBe('bar')
          expect(middle.args).toHaveLength(1)
          const inner = middle.args[0]!
          expect(inner.kind).toBe('CallExpr')
          if (inner.kind === 'CallExpr') {
            expect(inner.name).toBe('baz')
          }
        }
      }
    })

    it('parses function call with no args', () => {
      const e = expr('[foo()]')
      expect(e.kind).toBe('CallExpr')
      if (e.kind === 'CallExpr') {
        expect(e.name).toBe('foo')
        expect(e.args).toHaveLength(0)
      }
    })

    it('parses function call with multiple args', () => {
      const e = expr('[foo(1, 2, 3)]')
      expect(e.kind).toBe('CallExpr')
      if (e.kind === 'CallExpr') {
        expect(e.name).toBe('foo')
        expect(e.args).toHaveLength(3)
      }
    })
  })

  describe('variable assignment', () => {
    it('parses variable assignment', () => {
      const e = expr('[x: 42]')
      expect(e.kind).toBe('Assignment')
      if (e.kind === 'Assignment') {
        expect(e.target.kind).toBe('VariableRef')
        if (e.target.kind === 'VariableRef') {
          expect(e.target.name).toBe('x')
        }
        expect(e.value.kind).toBe('NumberLiteral')
      }
    })
  })

  describe('property assignment', () => {
    it('parses property assignment on current note', () => {
      const e = expr('[.name: "test"]')
      expect(e.kind).toBe('Assignment')
      if (e.kind === 'Assignment') {
        expect(e.target.kind).toBe('PropertyAccess')
        if (e.target.kind === 'PropertyAccess') {
          expect(e.target.property).toBe('name')
          expect(e.target.target.kind).toBe('CurrentNoteRef')
        }
      }
    })
  })

  describe('statement lists', () => {
    it('parses statement list with semicolons', () => {
      const e = expr('[x: 1; y: 2]')
      expect(e.kind).toBe('StatementList')
      if (e.kind === 'StatementList') {
        expect(e.statements).toHaveLength(2)
        expect(e.statements[0]!.kind).toBe('Assignment')
        expect(e.statements[1]!.kind).toBe('Assignment')
      }
    })

    it('single statement is not wrapped in StatementList', () => {
      const e = expr('[foo]')
      expect(e.kind).toBe('CallExpr')
    })
  })

  describe('method calls', () => {
    it('parses method call with args', () => {
      const e = expr('[foo.bar(42)]')
      expect(e.kind).toBe('MethodCall')
      if (e.kind === 'MethodCall') {
        expect(e.methodName).toBe('bar')
        expect(e.args).toHaveLength(1)
        expect(e.target.kind).toBe('CallExpr')
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
  })

  describe('chained property access', () => {
    it('parses chained property access', () => {
      const e = expr('[foo.bar.baz]')
      expect(e.kind).toBe('PropertyAccess')
      if (e.kind === 'PropertyAccess') {
        expect(e.property).toBe('baz')
        const inner = e.target
        expect(inner.kind).toBe('PropertyAccess')
        if (inner.kind === 'PropertyAccess') {
          expect(inner.property).toBe('bar')
          expect(inner.target.kind).toBe('CallExpr')
        }
      }
    })
  })

  describe('named arguments', () => {
    it('parses named argument in function call', () => {
      const e = expr('[foo(name: "bar")]')
      expect(e.kind).toBe('CallExpr')
      if (e.kind === 'CallExpr') {
        expect(e.args).toHaveLength(0)
        expect(e.namedArgs).toHaveLength(1)
        expect(e.namedArgs[0]!.name).toBe('name')
        expect(e.namedArgs[0]!.value.kind).toBe('StringLiteral')
      }
    })

    it('parses mixed positional and named args', () => {
      const e = expr('[foo(42, name: "bar")]')
      expect(e.kind).toBe('CallExpr')
      if (e.kind === 'CallExpr') {
        expect(e.args).toHaveLength(1)
        expect(e.namedArgs).toHaveLength(1)
      }
    })
  })
})
