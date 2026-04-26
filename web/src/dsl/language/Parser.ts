import { TokenType, type Token } from './Token'
import type {
  Expression,
  NamedArg,
  Directive,
  LambdaExpr,
} from './Expression'
import { ParseException } from './ParseException'
import { TokenStream } from './TokenStream'
import { PatternParser } from './PatternParser'

type ParsedArg =
  | { kind: 'positional'; expr: Expression }
  | { kind: 'named'; namedArg: NamedArg }

const MULTI_ARG_LAMBDA_MAX_LOOKAHEAD = 20

/**
 * Recursive-descent parser for Mindl directives. Produces an AST from a
 * token list. Token cursor management lives in `TokenStream`; pattern-
 * grammar parsing in `PatternParser`. This class owns directive, statement,
 * expression, and primary parsing plus the special-form helpers.
 */
export class Parser {
  private readonly stream: TokenStream
  private readonly patternParser: PatternParser

  constructor(tokens: Token[], private readonly source: string) {
    this.stream = new TokenStream(tokens)
    this.patternParser = new PatternParser(this.stream)
  }

  parseDirective(): Directive {
    const startToken = this.stream.consume(TokenType.LBRACKET, "Expected '[' to start directive")
    const startPos = startToken.position
    const expression = this.parseExpression()
    this.stream.consume(TokenType.RBRACKET, "Expected ']' to close directive")

    const prev = this.stream.previous()
    const endPos = prev.position + prev.lexeme.length
    const sourceText = this.source.substring(startPos, endPos)

    return { expression, sourceText, startPosition: startPos }
  }

  private parseExpression(): Expression {
    return this.parseStatementList()
  }

  private parseStatementList(): Expression {
    const position = this.stream.peek().position
    const statements: Expression[] = [this.parseStatement()]

    while (this.stream.match(TokenType.SEMICOLON)) {
      statements.push(this.parseStatement())
    }

    return statements.length === 1
      ? statements[0]!
      : { kind: 'StatementList', statements, position }
  }

  private parseStatement(): Expression {
    const position = this.stream.peek().position

    // Variable assignment: x: value
    if (this.stream.check(TokenType.IDENTIFIER) && this.stream.checkNext(TokenType.COLON)) {
      const nameToken = this.stream.advance()
      const name = nameToken.literal as string
      this.stream.advance() // consume COLON
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
    if (this.stream.match(TokenType.COLON)) {
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
      if (this.stream.match(TokenType.DOT)) {
        const dotPosition = this.stream.previous().position
        const propToken = this.stream.consume(TokenType.IDENTIFIER, "Expected property or method name after '.'")
        const propName = propToken.literal as string

        if (this.stream.match(TokenType.LPAREN)) {
          const { positional, named } = this.parseMethodArguments()
          result = { kind: 'MethodCall', target: result, methodName: propName, args: positional, namedArgs: named, position: dotPosition }
        } else {
          result = { kind: 'PropertyAccess', target: result, property: propName, position: dotPosition }
        }
      } else if (result.kind === 'LambdaExpr' && this.stream.match(TokenType.LPAREN)) {
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

    if (!this.stream.check(TokenType.RPAREN)) {
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
      } while (this.stream.match(TokenType.COMMA))
    }

    this.stream.consume(TokenType.RPAREN, "Expected ')' after arguments")
    return { positional, named }
  }

  private isAtExpressionStart(): boolean {
    return (
      !this.stream.check(TokenType.RBRACKET) &&
      !this.stream.check(TokenType.RPAREN) &&
      !this.stream.check(TokenType.COMMA) &&
      !this.stream.check(TokenType.COLON) &&
      !this.stream.check(TokenType.SEMICOLON) &&
      !this.stream.isAtEnd()
    )
  }

  private parsePrimary(): Expression {
    if (this.stream.match(TokenType.NUMBER)) {
      const token = this.stream.previous()
      return { kind: 'NumberLiteral', value: token.literal as number, position: token.position }
    }

    if (this.stream.match(TokenType.STRING)) {
      const token = this.stream.previous()
      return { kind: 'StringLiteral', value: token.literal as string, position: token.position }
    }

    if (this.stream.match(TokenType.DOT)) {
      const position = this.stream.previous().position
      const currentNoteRef: Expression = { kind: 'CurrentNoteRef', position }

      if (this.stream.check(TokenType.IDENTIFIER)) {
        const propToken = this.stream.advance()
        const propName = propToken.literal as string

        if (this.stream.match(TokenType.LPAREN)) {
          const { positional, named } = this.parseMethodArguments()
          return { kind: 'MethodCall', target: currentNoteRef, methodName: propName, args: positional, namedArgs: named, position }
        }
        return { kind: 'PropertyAccess', target: currentNoteRef, property: propName, position }
      }
      return currentNoteRef
    }

    if (this.stream.match(TokenType.LBRACKET)) {
      return this.parseDeferredBlock(this.stream.previous().position)
    }

    if (this.stream.check(TokenType.LPAREN) && this.isMultiArgLambdaStart()) {
      return this.parseMultiArgLambda()
    }

    if (this.stream.match(TokenType.IDENTIFIER)) {
      const token = this.stream.previous()
      const name = token.literal as string
      const position = token.position

      if (name === 'once' && this.stream.check(TokenType.LBRACKET)) {
        return this.parseOnceExpression(position)
      }
      if (name === 'refresh' && this.stream.check(TokenType.LBRACKET)) {
        return this.parseRefreshExpression(position)
      }
      if (name === 'later') {
        return this.parseLaterExpression(position)
      }
      if (this.stream.check(TokenType.LBRACKET)) {
        this.stream.advance() // consume LBRACKET
        const lambdaBody = this.parseDeferredBlock(this.stream.previous().position)
        return { kind: 'CallExpr', name, args: [lambdaBody], namedArgs: [], position }
      }
      if (this.stream.match(TokenType.LPAREN)) {
        if (name === 'pattern') {
          return this.patternParser.parsePatternExpression(position)
        }
        if (name === 'string') {
          return this.parseFlatArgCall(name, position)
        }
        return this.parseParenthesizedCall(name, position)
      }
      return { kind: 'CallExpr', name, args: [], namedArgs: [], position }
    }

    throw new ParseException('Expected expression', this.stream.peek().position)
  }

  private isMultiArgLambdaStart(): boolean {
    if (!this.stream.check(TokenType.LPAREN)) return false

    let offset = 1
    let seenIdentifier = false

    while (offset < MULTI_ARG_LAMBDA_MAX_LOOKAHEAD) {
      const token = this.stream.peekAt(offset)
      if (token == null) return false
      switch (token.type) {
        case TokenType.IDENTIFIER:
          seenIdentifier = true
          offset++
          break
        case TokenType.COMMA:
          if (!seenIdentifier) return false
          offset++
          seenIdentifier = false
          break
        case TokenType.RPAREN: {
          if (!seenIdentifier) return false
          const next = this.stream.peekAt(offset + 1)
          return next != null && next.type === TokenType.LBRACKET
        }
        default:
          return false
      }
    }
    return false
  }

  private parseMultiArgLambda(): LambdaExpr {
    const position = this.stream.peek().position
    this.stream.consume(TokenType.LPAREN, "Expected '(' for multi-arg lambda")

    const params: string[] = []
    if (!this.stream.check(TokenType.RPAREN)) {
      do {
        const paramToken = this.stream.consume(TokenType.IDENTIFIER, 'Expected parameter name')
        params.push(paramToken.literal as string)
      } while (this.stream.match(TokenType.COMMA))
    }

    this.stream.consume(TokenType.RPAREN, "Expected ')' after lambda parameters")
    this.stream.consume(TokenType.LBRACKET, "Expected '[' after lambda parameters")

    const body = this.parseExpression()
    this.stream.consume(TokenType.RBRACKET, "Expected ']' to close lambda body")

    return { kind: 'LambdaExpr', params, body, position }
  }

  private parseDeferredBlock(position: number): LambdaExpr {
    const body = this.parseExpression()
    this.stream.consume(TokenType.RBRACKET, "Expected ']' to close deferred block")
    return { kind: 'LambdaExpr', params: ['i'], body, position }
  }

  private parseParenthesizedCall(name: string, position: number): Expression {
    const positional: Expression[] = []
    const named: NamedArg[] = []

    if (!this.stream.check(TokenType.RPAREN)) {
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
      } while (this.stream.match(TokenType.COMMA))
    }

    this.stream.consume(TokenType.RPAREN, "Expected ')' after arguments")
    return { kind: 'CallExpr', name, args: positional, namedArgs: named, position }
  }

  /**
   * Parse a call where args are flat (no right-to-left nesting from spaces).
   * Each primary expression becomes a separate positional arg. Commas are optional separators.
   */
  private parseFlatArgCall(name: string, position: number): Expression {
    const args: Expression[] = []

    while (!this.stream.check(TokenType.RPAREN) && !this.stream.isAtEnd()) {
      this.stream.match(TokenType.COMMA) // skip optional commas
      if (this.stream.check(TokenType.RPAREN)) break
      args.push(this.parsePostfix(this.parsePrimary()))
    }

    this.stream.consume(TokenType.RPAREN, "Expected ')' after arguments")
    return { kind: 'CallExpr', name, args, namedArgs: [], position }
  }

  private parseArgument(): ParsedArg {
    if (this.stream.check(TokenType.IDENTIFIER) && this.stream.checkNext(TokenType.COLON)) {
      const nameToken = this.stream.advance()
      const argName = nameToken.literal as string
      const argPosition = nameToken.position
      this.stream.consume(TokenType.COLON, "Expected ':' after parameter name")
      const value = this.parseCallChain()
      return { kind: 'named', namedArg: { name: argName, value, position: argPosition } }
    }
    return { kind: 'positional', expr: this.parseCallChain() }
  }

  private parseOnceExpression(position: number): Expression {
    this.stream.consume(TokenType.LBRACKET, "Expected '[' after 'once'")
    const body = this.parseExpression()
    this.stream.consume(TokenType.RBRACKET, "Expected ']' to close once block")
    return { kind: 'OnceExpr', body, position }
  }

  private parseRefreshExpression(position: number): Expression {
    this.stream.consume(TokenType.LBRACKET, "Expected '[' after 'refresh'")
    const body = this.parseExpression()
    this.stream.consume(TokenType.RBRACKET, "Expected ']' to close refresh block")
    return { kind: 'RefreshExpr', body, position }
  }

  private parseLaterExpression(position: number): LambdaExpr {
    if (this.stream.check(TokenType.LBRACKET)) {
      this.stream.advance()
      return this.parseDeferredBlock(this.stream.previous().position)
    }
    const body = this.parsePrimary()
    return { kind: 'LambdaExpr', params: ['i'], body, position }
  }
}

function isAssignableTarget(expr: Expression): boolean {
  return expr.kind === 'PropertyAccess' || expr.kind === 'CurrentNoteRef' || expr.kind === 'VariableRef'
}
