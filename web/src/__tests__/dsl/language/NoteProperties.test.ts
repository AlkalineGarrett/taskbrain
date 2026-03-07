import { describe, it, expect } from 'vitest'
import { Lexer } from '../../../dsl/language/Lexer'
import { Parser } from '../../../dsl/language/Parser'
import type { Expression } from '../../../dsl/language/Expression'

function expr(source: string): Expression {
  const tokens = new Lexer(source).tokenize()
  return new Parser(tokens, source).parseDirective().expression
}

describe('Note property access', () => {
  describe('current note reference', () => {
    it('dot alone is CurrentNoteRef', () => {
      const e = expr('[.]')
      expect(e.kind).toBe('CurrentNoteRef')
    })
  })

  describe('property access', () => {
    it('parses .path property', () => {
      const e = expr('[.path]')
      expect(e.kind).toBe('PropertyAccess')
      if (e.kind === 'PropertyAccess') {
        expect(e.target.kind).toBe('CurrentNoteRef')
        expect(e.property).toBe('path')
      }
    })

    it('parses .name property', () => {
      const e = expr('[.name]')
      expect(e.kind).toBe('PropertyAccess')
      if (e.kind === 'PropertyAccess') {
        expect(e.target.kind).toBe('CurrentNoteRef')
        expect(e.property).toBe('name')
      }
    })

    it('parses .created property', () => {
      const e = expr('[.created]')
      expect(e.kind).toBe('PropertyAccess')
      if (e.kind === 'PropertyAccess') {
        expect(e.target.kind).toBe('CurrentNoteRef')
        expect(e.property).toBe('created')
      }
    })

    it('parses .modified property', () => {
      const e = expr('[.modified]')
      expect(e.kind).toBe('PropertyAccess')
      if (e.kind === 'PropertyAccess') {
        expect(e.target.kind).toBe('CurrentNoteRef')
        expect(e.property).toBe('modified')
      }
    })

    it('parses .viewed property', () => {
      const e = expr('[.viewed]')
      expect(e.kind).toBe('PropertyAccess')
      if (e.kind === 'PropertyAccess') {
        expect(e.target.kind).toBe('CurrentNoteRef')
        expect(e.property).toBe('viewed')
      }
    })
  })

  describe('hierarchy', () => {
    it('parses .up as property access', () => {
      const e = expr('[.up]')
      expect(e.kind).toBe('PropertyAccess')
      if (e.kind === 'PropertyAccess') {
        expect(e.target.kind).toBe('CurrentNoteRef')
        expect(e.property).toBe('up')
      }
    })

    it('parses .root as property access', () => {
      const e = expr('[.root]')
      expect(e.kind).toBe('PropertyAccess')
      if (e.kind === 'PropertyAccess') {
        expect(e.target.kind).toBe('CurrentNoteRef')
        expect(e.property).toBe('root')
      }
    })

    it('parses chained hierarchy .up.name', () => {
      const e = expr('[.up.name]')
      expect(e.kind).toBe('PropertyAccess')
      if (e.kind === 'PropertyAccess') {
        expect(e.property).toBe('name')
        expect(e.target.kind).toBe('PropertyAccess')
        if (e.target.kind === 'PropertyAccess') {
          expect(e.target.property).toBe('up')
          expect(e.target.target.kind).toBe('CurrentNoteRef')
        }
      }
    })

    it('parses .root.path', () => {
      const e = expr('[.root.path]')
      expect(e.kind).toBe('PropertyAccess')
      if (e.kind === 'PropertyAccess') {
        expect(e.property).toBe('path')
        expect(e.target.kind).toBe('PropertyAccess')
        if (e.target.kind === 'PropertyAccess') {
          expect(e.target.property).toBe('root')
          expect(e.target.target.kind).toBe('CurrentNoteRef')
        }
      }
    })
  })

  describe('method calls on current note', () => {
    it('parses method call on current note', () => {
      const e = expr('[.find("test")]')
      expect(e.kind).toBe('MethodCall')
      if (e.kind === 'MethodCall') {
        expect(e.target.kind).toBe('CurrentNoteRef')
        expect(e.methodName).toBe('find')
        expect(e.args).toHaveLength(1)
      }
    })
  })
})
