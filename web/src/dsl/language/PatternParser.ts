import { TokenType } from './Token'
import { CharClassType, type PatternElement, type PatternExpr, type Quantifier } from './Expression'
import { ParseException } from './ParseException'
import { TokenStream } from './TokenStream'

const CHAR_CLASS_NAMES: Record<string, CharClassType> = {
  digit: CharClassType.DIGIT,
  letter: CharClassType.LETTER,
  space: CharClassType.SPACE,
  punct: CharClassType.PUNCT,
  any: CharClassType.ANY,
}

/**
 * Parses pattern expressions: `pattern(digit*4 "-" digit*2 "-" digit*2)`.
 *
 * Pattern syntax:
 * - Character classes: `digit`, `letter`, `space`, `punct`, `any`
 * - Quantifiers: `*4`, `*any`, `*(0..5)`, `*(1..)`
 * - Literals: `"string"`
 *
 * Caller has already consumed `pattern(`. This parser consumes through the
 * closing `)` and returns the `PatternExpr`.
 */
export class PatternParser {
  constructor(private readonly stream: TokenStream) {}

  parsePatternExpression(position: number): PatternExpr {
    const elements: PatternElement[] = []

    while (!this.stream.check(TokenType.RPAREN)) {
      elements.push(this.parsePatternElement())
    }

    this.stream.consume(TokenType.RPAREN, "Expected ')' after pattern elements")

    if (elements.length === 0) {
      throw new ParseException('Pattern cannot be empty', position)
    }

    return { kind: 'PatternExpr', elements, position }
  }

  private parsePatternElement(): PatternElement {
    const base = this.parseBasePatternElement()
    return this.stream.check(TokenType.STAR) ? this.parseQuantifiedElement(base) : base
  }

  private parseBasePatternElement(): PatternElement {
    if (this.stream.match(TokenType.STRING)) {
      const token = this.stream.previous()
      return { kind: 'PatternLiteral', value: token.literal as string, position: token.position }
    }
    if (this.stream.match(TokenType.IDENTIFIER)) {
      const token = this.stream.previous()
      const name = token.literal as string
      const charClassType = CHAR_CLASS_NAMES[name]
      if (charClassType == null) {
        throw new ParseException(
          `Unknown pattern character class '${name}'. Valid classes are: ${Object.keys(CHAR_CLASS_NAMES).join(', ')}`,
          token.position,
        )
      }
      return { kind: 'CharClass', type: charClassType, position: token.position }
    }
    throw new ParseException('Expected pattern element (character class or string literal)', this.stream.peek().position)
  }

  private parseQuantifiedElement(element: PatternElement): PatternElement {
    this.stream.advance()
    const quantifier = this.parseQuantifier()
    return { kind: 'Quantified', element, quantifier, position: element.position }
  }

  private parseQuantifier(): Quantifier {
    if (this.stream.match(TokenType.NUMBER)) {
      const token = this.stream.previous()
      const count = Math.floor(token.literal as number)
      if (count < 0) throw new ParseException('Quantifier count must be non-negative', token.position)
      return { kind: 'Exact', n: count }
    }
    if (this.stream.match(TokenType.IDENTIFIER)) {
      const token = this.stream.previous()
      const name = token.literal as string
      if (name !== 'any') {
        throw new ParseException(`Expected quantifier: number, 'any', or '(min..max)'. Got '${name}'`, token.position)
      }
      return { kind: 'Any' }
    }
    if (this.stream.match(TokenType.LPAREN)) {
      return this.parseRangeQuantifier()
    }
    throw new ParseException("Expected quantifier after '*': number, 'any', or '(min..max)'", this.stream.peek().position)
  }

  private parseRangeQuantifier(): Quantifier {
    const minToken = this.stream.consume(TokenType.NUMBER, 'Expected minimum in range quantifier')
    const min = Math.floor(minToken.literal as number)
    if (min < 0) throw new ParseException('Range minimum must be non-negative', minToken.position)

    this.stream.consume(TokenType.DOTDOT, "Expected '..' in range quantifier")

    let max: number | null = null
    if (this.stream.check(TokenType.NUMBER)) {
      const maxToken = this.stream.advance()
      const maxVal = Math.floor(maxToken.literal as number)
      if (maxVal < min) {
        throw new ParseException(`Range maximum (${maxVal}) must be >= minimum (${min})`, maxToken.position)
      }
      max = maxVal
    }

    this.stream.consume(TokenType.RPAREN, "Expected ')' after range quantifier")
    return { kind: 'Range', min, max }
  }
}
