import { describe, it, expect } from 'vitest'
import { Lexer, LexerException } from '../../../dsl/language/Lexer'
import { TokenType } from '../../../dsl/language/Token'
import type { Token } from '../../../dsl/language/Token'

function tokenize(source: string): Token[] {
  return new Lexer(source).tokenize()
}

function tokenTypes(source: string): TokenType[] {
  return tokenize(source).map((t) => t.type)
}

describe('Lexer', () => {
  describe('brackets', () => {
    it('tokenizes left and right brackets', () => {
      const tokens = tokenize('[]')
      expect(tokens[0]!.type).toBe(TokenType.LBRACKET)
      expect(tokens[1]!.type).toBe(TokenType.RBRACKET)
      expect(tokens[2]!.type).toBe(TokenType.EOF)
    })

    it('tokenizes nested brackets', () => {
      expect(tokenTypes('[[]]')).toEqual([
        TokenType.LBRACKET,
        TokenType.LBRACKET,
        TokenType.RBRACKET,
        TokenType.RBRACKET,
        TokenType.EOF,
      ])
    })
  })

  describe('numbers', () => {
    it('tokenizes integer', () => {
      const tokens = tokenize('42')
      expect(tokens[0]!.type).toBe(TokenType.NUMBER)
      expect(tokens[0]!.literal).toBe(42)
      expect(tokens[0]!.lexeme).toBe('42')
    })

    it('tokenizes decimal number', () => {
      const tokens = tokenize('3.14')
      expect(tokens[0]!.type).toBe(TokenType.NUMBER)
      expect(tokens[0]!.literal).toBe(3.14)
    })

    it('tokenizes zero', () => {
      const tokens = tokenize('0')
      expect(tokens[0]!.type).toBe(TokenType.NUMBER)
      expect(tokens[0]!.literal).toBe(0)
    })

    it('tokenizes decimal with leading zero', () => {
      const tokens = tokenize('0.5')
      expect(tokens[0]!.type).toBe(TokenType.NUMBER)
      expect(tokens[0]!.literal).toBe(0.5)
    })

    it('tokenizes large number', () => {
      const tokens = tokenize('999999')
      expect(tokens[0]!.type).toBe(TokenType.NUMBER)
      expect(tokens[0]!.literal).toBe(999999)
    })

    it('tokenizes number with leading zeros as number', () => {
      const tokens = tokenize('007')
      expect(tokens[0]!.type).toBe(TokenType.NUMBER)
      expect(tokens[0]!.literal).toBe(7)
    })
  })

  describe('strings', () => {
    it('tokenizes simple string', () => {
      const tokens = tokenize('"hello"')
      expect(tokens[0]!.type).toBe(TokenType.STRING)
      expect(tokens[0]!.literal).toBe('hello')
    })

    it('tokenizes empty string', () => {
      const tokens = tokenize('""')
      expect(tokens[0]!.type).toBe(TokenType.STRING)
      expect(tokens[0]!.literal).toBe('')
    })

    it('tokenizes string with spaces', () => {
      const tokens = tokenize('"hello world"')
      expect(tokens[0]!.type).toBe(TokenType.STRING)
      expect(tokens[0]!.literal).toBe('hello world')
    })

    it('tokenizes string with special characters', () => {
      const tokens = tokenize('"!@#$%"')
      expect(tokens[0]!.type).toBe(TokenType.STRING)
      expect(tokens[0]!.literal).toBe('!@#$%')
    })
  })

  describe('whitespace handling', () => {
    it('skips spaces between tokens', () => {
      const tokens = tokenize('42 "hello"')
      expect(tokens[0]!.type).toBe(TokenType.NUMBER)
      expect(tokens[1]!.type).toBe(TokenType.STRING)
    })

    it('skips tabs', () => {
      const tokens = tokenize('42\t"hello"')
      expect(tokens[0]!.type).toBe(TokenType.NUMBER)
      expect(tokens[1]!.type).toBe(TokenType.STRING)
    })

    it('skips newlines', () => {
      const tokens = tokenize('42\n"hello"')
      expect(tokens[0]!.type).toBe(TokenType.NUMBER)
      expect(tokens[1]!.type).toBe(TokenType.STRING)
    })

    it('skips mixed whitespace', () => {
      const tokens = tokenize('  42  \t\n  "hello"  ')
      expect(tokens[0]!.type).toBe(TokenType.NUMBER)
      expect(tokens[1]!.type).toBe(TokenType.STRING)
      expect(tokens[2]!.type).toBe(TokenType.EOF)
    })
  })

  describe('position tracking', () => {
    it('tracks position for first token', () => {
      const tokens = tokenize('42')
      expect(tokens[0]!.position).toBe(0)
    })

    it('tracks position after whitespace', () => {
      const tokens = tokenize('  42')
      expect(tokens[0]!.position).toBe(2)
    })

    it('tracks position of multiple tokens', () => {
      const tokens = tokenize('[42]')
      expect(tokens[0]!.position).toBe(0) // [
      expect(tokens[1]!.position).toBe(1) // 42
      expect(tokens[2]!.position).toBe(3) // ]
    })

    it('EOF position is at end of source', () => {
      const tokens = tokenize('abc')
      const eof = tokens[tokens.length - 1]!
      expect(eof.type).toBe(TokenType.EOF)
      expect(eof.position).toBe(3)
    })
  })

  describe('error cases', () => {
    it('throws on unterminated string', () => {
      expect(() => tokenize('"hello')).toThrow(LexerException)
      expect(() => tokenize('"hello')).toThrow('Unterminated string')
    })

    it('throws on unexpected character', () => {
      expect(() => tokenize('~')).toThrow(LexerException)
      expect(() => tokenize('~')).toThrow("Unexpected character '~'")
    })

    it('reports position on unterminated string', () => {
      try {
        tokenize('"hello')
        expect.fail('should have thrown')
      } catch (e) {
        expect(e).toBeInstanceOf(LexerException)
        expect((e as LexerException).position).toBe(0)
      }
    })

    it('reports position on unexpected character', () => {
      try {
        tokenize('abc ~')
        expect.fail('should have thrown')
      } catch (e) {
        expect(e).toBeInstanceOf(LexerException)
        expect((e as LexerException).position).toBe(4)
      }
    })
  })

  describe('identifiers', () => {
    it('tokenizes simple identifier', () => {
      const tokens = tokenize('foo')
      expect(tokens[0]!.type).toBe(TokenType.IDENTIFIER)
      expect(tokens[0]!.literal).toBe('foo')
    })

    it('tokenizes identifier with underscore', () => {
      const tokens = tokenize('foo_bar')
      expect(tokens[0]!.type).toBe(TokenType.IDENTIFIER)
      expect(tokens[0]!.literal).toBe('foo_bar')
    })

    it('tokenizes identifier starting with underscore', () => {
      const tokens = tokenize('_foo')
      expect(tokens[0]!.type).toBe(TokenType.IDENTIFIER)
      expect(tokens[0]!.literal).toBe('_foo')
    })

    it('tokenizes identifier with digits', () => {
      const tokens = tokenize('foo123')
      expect(tokens[0]!.type).toBe(TokenType.IDENTIFIER)
      expect(tokens[0]!.literal).toBe('foo123')
    })

    it('tokenizes multiple identifiers', () => {
      const tokens = tokenize('foo bar')
      expect(tokens[0]!.type).toBe(TokenType.IDENTIFIER)
      expect(tokens[0]!.literal).toBe('foo')
      expect(tokens[1]!.type).toBe(TokenType.IDENTIFIER)
      expect(tokens[1]!.literal).toBe('bar')
    })
  })

  describe('semicolons', () => {
    it('tokenizes semicolons', () => {
      const tokens = tokenize('a;b')
      expect(tokens[0]!.type).toBe(TokenType.IDENTIFIER)
      expect(tokens[1]!.type).toBe(TokenType.SEMICOLON)
      expect(tokens[2]!.type).toBe(TokenType.IDENTIFIER)
    })
  })

  describe('dots', () => {
    it('tokenizes single dot', () => {
      const tokens = tokenize('.')
      expect(tokens[0]!.type).toBe(TokenType.DOT)
    })

    it('tokenizes dot-dot', () => {
      const tokens = tokenize('..')
      expect(tokens[0]!.type).toBe(TokenType.DOTDOT)
    })
  })

  describe('punctuation', () => {
    it('tokenizes parentheses', () => {
      expect(tokenTypes('()')).toEqual([TokenType.LPAREN, TokenType.RPAREN, TokenType.EOF])
    })

    it('tokenizes comma', () => {
      const tokens = tokenize(',')
      expect(tokens[0]!.type).toBe(TokenType.COMMA)
    })

    it('tokenizes colon', () => {
      const tokens = tokenize(':')
      expect(tokens[0]!.type).toBe(TokenType.COLON)
    })

    it('tokenizes star', () => {
      const tokens = tokenize('*')
      expect(tokens[0]!.type).toBe(TokenType.STAR)
    })
  })
})
