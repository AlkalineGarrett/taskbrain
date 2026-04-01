import { TokenType, type Token } from './Token'
import type {
  Expression,
  NamedArg,
  Directive,
  PatternElement,
  Quantifier,
  LambdaExpr,
} from './Expression'
import { CharClassType } from './Expression'

export class ParseException extends Error {
  constructor(
    message: string,
    public readonly position: number,
  ) {
    super(`Parse error at position ${position}: ${message}`)
  }
}

const CHAR_CLASS_NAMES: Record<string, CharClassType> = {
  digit: CharClassType.DIGIT,
  letter: CharClassType.LETTER,
  space: CharClassType.SPACE,
  punct: CharClassType.PUNCT,
  any: CharClassType.ANY,
}

type ParsedArg =
  | { kind: 'positional'; expr: Expression }
  | { kind: 'named'; namedArg: NamedArg }

export class Parser {
  private current = 0

  constructor(
    private readonly tokens: Token[],
    private readonly source: string,
  ) {}

  parseDirective(): Directive {
    const startToken = this.consume(TokenType.LBRACKET, "Expected '[' to start directive")
    const startPos = startToken.position
    const expression = this.parseExpression()
    this.consume(TokenType.RBRACKET, "Expected ']' to close directive")

    const prev = this.previous()
    const endPos = prev.position + prev.lexeme.length
    const sourceText = this.source.substring(startPos, endPos)

    return { expression, sourceText, startPosition: startPos }
  }

  private parseExpression(): Expression {
    return this.parseStatementList()
  }

  private parseStatementList(): Expression {
    const position = this.peek().position
    const statements: Expression[] = [this.parseStatement()]

    while (this.match(TokenType.SEMICOLON)) {
      statements.push(this.parseStatement())
    }

    return statements.length === 1
      ? statements[0]!
      : { kind: 'StatementList', statements, position }
  }

  private parseStatement(): Expression {
    const position = this.peek().position

    // Variable assignment: x: value
    if (this.check(TokenType.IDENTIFIER) && this.checkNext(TokenType.COLON)) {
      const nameToken = this.advance()
      const name = nameToken.literal as string
      this.advance() // consume COLON
      const value = this.parseCallChain()
      return {
        kind: 'Assignment',
        target: { kind: 'VariableRef', name, position: nameToken.position },
        value,
        position,
      }
    }

    const expr = this.parseCallChain()

    // Property assignment: expr: value
    if (this.match(TokenType.COLON)) {
      if (!isAssignableTarget(expr)) {
        throw new ParseException(
          'Invalid assignment target. Expected variable, property access, or current note reference.',
          position,
        )
      }
      const value = this.parseCallChain()
      return { kind: 'Assignment', target: expr, value, position }
    }

    return expr
  }

  private parseCallChain(): Expression {
    const first = this.parsePostfix(this.parsePrimary())

    if (first.kind !== 'CallExpr') return first
    if (!this.isAtExpressionStart()) return first

    const rest = this.parseCallChain()
    return {
      kind: 'CallExpr',
      name: first.name,
      args: [rest],
      namedArgs: first.namedArgs,
      position: first.position,
    }
  }

  private parsePostfix(expr: Expression): Expression {
    let result = expr

    while (true) {
      if (this.match(TokenType.DOT)) {
        const dotPosition = this.previous().position
        const propToken = this.consume(TokenType.IDENTIFIER, "Expected property or method name after '.'")
        const propName = propToken.literal as string

        if (this.match(TokenType.LPAREN)) {
          const { positional, named } = this.parseMethodArguments()
          result = { kind: 'MethodCall', target: result, methodName: propName, args: positional, namedArgs: named, position: dotPosition }
        } else {
          result = { kind: 'PropertyAccess', target: result, property: propName, position: dotPosition }
        }
      } else if (result.kind === 'LambdaExpr' && this.match(TokenType.LPAREN)) {
        const { positional, named } = this.parseMethodArguments()
        result = { kind: 'LambdaInvocation', lambda: result, args: positional, namedArgs: named, position: result.position }
      } else {
        break
      }
    }

    return result
  }

  private parseMethodArguments(): { positional: Expression[]; named: NamedArg[] } {
    const positional: Expression[] = []
    const named: NamedArg[] = []

    if (!this.check(TokenType.RPAREN)) {
      do {
        const arg = this.parseArgument()
        if (arg.kind === 'positional') {
          if (named.length > 0) {
            throw new ParseException('Positional argument cannot follow named argument', arg.expr.position)
          }
          positional.push(arg.expr)
        } else {
          named.push(arg.namedArg)
        }
      } while (this.match(TokenType.COMMA))
    }

    this.consume(TokenType.RPAREN, "Expected ')' after arguments")
    return { positional, named }
  }

  private isAtExpressionStart(): boolean {
    return (
      !this.check(TokenType.RBRACKET) &&
      !this.check(TokenType.RPAREN) &&
      !this.check(TokenType.COMMA) &&
      !this.check(TokenType.COLON) &&
      !this.check(TokenType.SEMICOLON) &&
      !this.isAtEnd()
    )
  }

  private parsePrimary(): Expression {
    if (this.match(TokenType.NUMBER)) {
      const token = this.previous()
      return { kind: 'NumberLiteral', value: token.literal as number, position: token.position }
    }

    if (this.match(TokenType.STRING)) {
      const token = this.previous()
      return { kind: 'StringLiteral', value: token.literal as string, position: token.position }
    }

    if (this.match(TokenType.DOT)) {
      const position = this.previous().position
      const currentNoteRef: Expression = { kind: 'CurrentNoteRef', position }

      if (this.check(TokenType.IDENTIFIER)) {
        const propToken = this.advance()
        const propName = propToken.literal as string

        if (this.match(TokenType.LPAREN)) {
          const { positional, named } = this.parseMethodArguments()
          return { kind: 'MethodCall', target: currentNoteRef, methodName: propName, args: positional, namedArgs: named, position }
        }
        return { kind: 'PropertyAccess', target: currentNoteRef, property: propName, position }
      }
      return currentNoteRef
    }

    if (this.match(TokenType.LBRACKET)) {
      return this.parseDeferredBlock(this.previous().position)
    }

    if (this.check(TokenType.LPAREN) && this.isMultiArgLambdaStart()) {
      return this.parseMultiArgLambda()
    }

    if (this.match(TokenType.IDENTIFIER)) {
      const token = this.previous()
      const name = token.literal as string
      const position = token.position

      if (name === 'once' && this.check(TokenType.LBRACKET)) {
        return this.parseOnceExpression(position)
      }
      if (name === 'refresh' && this.check(TokenType.LBRACKET)) {
        return this.parseRefreshExpression(position)
      }
      if (name === 'later') {
        return this.parseLaterExpression(position)
      }
      if (this.check(TokenType.LBRACKET)) {
        this.advance() // consume LBRACKET
        const lambdaBody = this.parseDeferredBlock(this.previous().position)
        return { kind: 'CallExpr', name, args: [lambdaBody], namedArgs: [], position }
      }
      if (this.match(TokenType.LPAREN)) {
        if (name === 'pattern') {
          return this.parsePatternExpression(position)
        }
        if (name === 'string') {
          return this.parseFlatArgCall(name, position)
        }
        return this.parseParenthesizedCall(name, position)
      }
      return { kind: 'CallExpr', name, args: [], namedArgs: [], position }
    }

    throw new ParseException('Expected expression', this.peek().position)
  }

  private isMultiArgLambdaStart(): boolean {
    if (!this.check(TokenType.LPAREN)) return false

    let lookahead = this.current + 1
    const maxLookahead = Math.min(this.current + 20, this.tokens.length)
    let seenIdentifier = false

    while (lookahead < maxLookahead) {
      const token = this.tokens[lookahead]!
      switch (token.type) {
        case TokenType.IDENTIFIER:
          seenIdentifier = true
          lookahead++
          break
        case TokenType.COMMA:
          if (!seenIdentifier) return false
          lookahead++
          seenIdentifier = false
          break
        case TokenType.RPAREN:
          if (!seenIdentifier) return false
          return lookahead + 1 < this.tokens.length &&
            this.tokens[lookahead + 1]!.type === TokenType.LBRACKET
        default:
          return false
      }
    }
    return false
  }

  private parseMultiArgLambda(): LambdaExpr {
    const position = this.peek().position
    this.consume(TokenType.LPAREN, "Expected '(' for multi-arg lambda")

    const params: string[] = []
    if (!this.check(TokenType.RPAREN)) {
      do {
        const paramToken = this.consume(TokenType.IDENTIFIER, 'Expected parameter name')
        params.push(paramToken.literal as string)
      } while (this.match(TokenType.COMMA))
    }

    this.consume(TokenType.RPAREN, "Expected ')' after lambda parameters")
    this.consume(TokenType.LBRACKET, "Expected '[' after lambda parameters")

    const body = this.parseExpression()
    this.consume(TokenType.RBRACKET, "Expected ']' to close lambda body")

    return { kind: 'LambdaExpr', params, body, position }
  }

  private parseDeferredBlock(position: number): LambdaExpr {
    const body = this.parseExpression()
    this.consume(TokenType.RBRACKET, "Expected ']' to close deferred block")
    return { kind: 'LambdaExpr', params: ['i'], body, position }
  }

  private parseParenthesizedCall(name: string, position: number): Expression {
    const positional: Expression[] = []
    const named: NamedArg[] = []

    if (!this.check(TokenType.RPAREN)) {
      do {
        const arg = this.parseArgument()
        if (arg.kind === 'positional') {
          if (named.length > 0) {
            throw new ParseException('Positional argument cannot follow named argument', arg.expr.position)
          }
          positional.push(arg.expr)
        } else {
          named.push(arg.namedArg)
        }
      } while (this.match(TokenType.COMMA))
    }

    this.consume(TokenType.RPAREN, "Expected ')' after arguments")
    return { kind: 'CallExpr', name, args: positional, namedArgs: named, position }
  }

  /**
   * Parse a call where args are flat (no right-to-left nesting from spaces).
   * Each primary expression becomes a separate positional arg. Commas are optional separators.
   */
  private parseFlatArgCall(name: string, position: number): Expression {
    const args: Expression[] = []

    while (!this.check(TokenType.RPAREN) && !this.isAtEnd()) {
      this.match(TokenType.COMMA) // skip optional commas
      if (this.check(TokenType.RPAREN)) break
      args.push(this.parsePostfix(this.parsePrimary()))
    }

    this.consume(TokenType.RPAREN, "Expected ')' after arguments")
    return { kind: 'CallExpr', name, args, namedArgs: [], position }
  }

  private parseArgument(): ParsedArg {
    if (this.check(TokenType.IDENTIFIER) && this.checkNext(TokenType.COLON)) {
      const nameToken = this.advance()
      const argName = nameToken.literal as string
      const argPosition = nameToken.position
      this.consume(TokenType.COLON, "Expected ':' after parameter name")
      const value = this.parseCallChain()
      return { kind: 'named', namedArg: { name: argName, value, position: argPosition } }
    }
    return { kind: 'positional', expr: this.parseCallChain() }
  }

  private parseOnceExpression(position: number): Expression {
    this.consume(TokenType.LBRACKET, "Expected '[' after 'once'")
    const body = this.parseExpression()
    this.consume(TokenType.RBRACKET, "Expected ']' to close once block")
    return { kind: 'OnceExpr', body, position }
  }

  private parseRefreshExpression(position: number): Expression {
    this.consume(TokenType.LBRACKET, "Expected '[' after 'refresh'")
    const body = this.parseExpression()
    this.consume(TokenType.RBRACKET, "Expected ']' to close refresh block")
    return { kind: 'RefreshExpr', body, position }
  }

  private parseLaterExpression(position: number): LambdaExpr {
    if (this.check(TokenType.LBRACKET)) {
      this.advance()
      return this.parseDeferredBlock(this.previous().position)
    }
    const body = this.parsePrimary()
    return { kind: 'LambdaExpr', params: ['i'], body, position }
  }

  // --- Pattern parsing ---

  private parsePatternExpression(position: number): Expression {
    const elements: PatternElement[] = []

    while (!this.check(TokenType.RPAREN)) {
      elements.push(this.parsePatternElement())
    }

    this.consume(TokenType.RPAREN, "Expected ')' after pattern elements")

    if (elements.length === 0) {
      throw new ParseException('Pattern cannot be empty', position)
    }

    return { kind: 'PatternExpr', elements, position }
  }

  private parsePatternElement(): PatternElement {
    const base = this.parseBasePatternElement()
    return this.check(TokenType.STAR) ? this.parseQuantifiedElement(base) : base
  }

  private parseBasePatternElement(): PatternElement {
    if (this.match(TokenType.STRING)) {
      const token = this.previous()
      return { kind: 'PatternLiteral', value: token.literal as string, position: token.position }
    }
    if (this.match(TokenType.IDENTIFIER)) {
      const token = this.previous()
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
    throw new ParseException('Expected pattern element (character class or string literal)', this.peek().position)
  }

  private parseQuantifiedElement(element: PatternElement): PatternElement {
    this.advance() // consume STAR
    const quantifier = this.parseQuantifier()
    return { kind: 'Quantified', element, quantifier, position: element.position }
  }

  private parseQuantifier(): Quantifier {
    if (this.match(TokenType.NUMBER)) {
      const token = this.previous()
      const count = Math.floor(token.literal as number)
      if (count < 0) throw new ParseException('Quantifier count must be non-negative', token.position)
      return { kind: 'Exact', n: count }
    }
    if (this.match(TokenType.IDENTIFIER)) {
      const token = this.previous()
      const name = token.literal as string
      if (name !== 'any') {
        throw new ParseException(`Expected quantifier: number, 'any', or '(min..max)'. Got '${name}'`, token.position)
      }
      return { kind: 'Any' }
    }
    if (this.match(TokenType.LPAREN)) {
      return this.parseRangeQuantifier()
    }
    throw new ParseException("Expected quantifier after '*': number, 'any', or '(min..max)'", this.peek().position)
  }

  private parseRangeQuantifier(): Quantifier {
    const minToken = this.consume(TokenType.NUMBER, 'Expected minimum in range quantifier')
    const min = Math.floor(minToken.literal as number)
    if (min < 0) throw new ParseException('Range minimum must be non-negative', minToken.position)

    this.consume(TokenType.DOTDOT, "Expected '..' in range quantifier")

    let max: number | null = null
    if (this.check(TokenType.NUMBER)) {
      const maxToken = this.advance()
      const maxVal = Math.floor(maxToken.literal as number)
      if (maxVal < min) {
        throw new ParseException(`Range maximum (${maxVal}) must be >= minimum (${min})`, maxToken.position)
      }
      max = maxVal
    }

    this.consume(TokenType.RPAREN, "Expected ')' after range quantifier")
    return { kind: 'Range', min, max }
  }

  // --- Token helpers ---

  private match(...types: TokenType[]): boolean {
    for (const type of types) {
      if (this.check(type)) {
        this.advance()
        return true
      }
    }
    return false
  }

  private check(type: TokenType): boolean {
    if (this.isAtEnd()) return false
    return this.peek().type === type
  }

  private checkNext(type: TokenType): boolean {
    if (this.current + 1 >= this.tokens.length) return false
    return this.tokens[this.current + 1]!.type === type
  }

  private advance(): Token {
    if (!this.isAtEnd()) this.current++
    return this.previous()
  }

  private isAtEnd(): boolean { return this.peek().type === TokenType.EOF }
  private peek(): Token { return this.tokens[this.current]! }
  private previous(): Token { return this.tokens[this.current - 1]! }

  private consume(type: TokenType, message: string): Token {
    if (this.check(type)) return this.advance()
    throw new ParseException(message, this.peek().position)
  }
}

function isAssignableTarget(expr: Expression): boolean {
  return expr.kind === 'PropertyAccess' || expr.kind === 'CurrentNoteRef' || expr.kind === 'VariableRef'
}
