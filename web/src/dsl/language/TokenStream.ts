import { TokenType, type Token } from './Token'
import { ParseException } from './ParseException'

/**
 * Cursor over a token list. Holds the position and provides the standard
 * recursive-descent-parser primitives (peek/check/match/consume).
 *
 * `consume` throws `ParseException` if the next token doesn't match.
 */
export class TokenStream {
  private current = 0

  constructor(private readonly tokens: Token[]) {}

  match(...types: TokenType[]): boolean {
    for (const type of types) {
      if (this.check(type)) {
        this.advance()
        return true
      }
    }
    return false
  }

  check(type: TokenType): boolean {
    if (this.isAtEnd()) return false
    return this.peek().type === type
  }

  checkNext(type: TokenType): boolean {
    if (this.current + 1 >= this.tokens.length) return false
    return this.tokens[this.current + 1]!.type === type
  }

  advance(): Token {
    if (!this.isAtEnd()) this.current++
    return this.previous()
  }

  isAtEnd(): boolean {
    return this.peek().type === TokenType.EOF
  }

  peek(): Token {
    return this.tokens[this.current]!
  }

  previous(): Token {
    return this.tokens[this.current - 1]!
  }

  consume(type: TokenType, message: string): Token {
    if (this.check(type)) return this.advance()
    throw new ParseException(message, this.peek().position)
  }

  /**
   * Token `offset` positions ahead of the current cursor, or null if past EOF.
   * `peekAt(0)` is equivalent to `peek()`; `peekAt(1)` is one past the cursor, etc.
   */
  peekAt(offset: number): Token | null {
    const i = this.current + offset
    if (i < 0 || i >= this.tokens.length) return null
    return this.tokens[i]!
  }
}
