package org.alkaline.taskbrain.dsl.language

/**
 * Recursive-descent parser for Mindl directives. Produces an AST from a
 * token list.
 *
 * Token cursor management lives in [TokenStream]; pattern-grammar parsing
 * in [PatternParser]. This class owns directive, statement, expression,
 * and primary parsing plus the special-form helpers.
 *
 * Calling styles supported:
 * 1. Space-separated: `[a b c]` -> `a(b(c))` (right-to-left nesting)
 * 2. Parenthesized: `[add(1, 2)]` or `[foo(bar: "baz")]`
 *
 * Space-separated nesting still works inside parens: `[a(b c, d)]` -> `a(b(c), d)`.
 */
class Parser(tokens: List<Token>, private val source: String) {

    companion object {
        private const val MULTI_ARG_LAMBDA_MAX_LOOKAHEAD = 20
    }

    private val stream = TokenStream(tokens)
    private val patternParser = PatternParser(stream)

    /**
     * Parse a complete directive.
     * @return The parsed [Directive]
     * @throws ParseException on syntax error
     */
    fun parseDirective(): Directive {
        val startToken = stream.consume(TokenType.LBRACKET, "Expected '[' to start directive")
        val startPos = startToken.position
        val expression = parseExpression()
        stream.consume(TokenType.RBRACKET, "Expected ']' to close directive")

        val endPos = stream.previous().position + stream.previous().lexeme.length
        val sourceText = source.substring(startPos, endPos)

        return Directive(expression, sourceText, startPos)
    }

    private fun parseExpression(): Expression = parseStatementList()

    private fun parseStatementList(): Expression {
        val position = stream.peek().position
        val statements = mutableListOf<Expression>()

        statements.add(parseStatement())

        while (stream.match(TokenType.SEMICOLON)) {
            statements.add(parseStatement())
        }

        return if (statements.size == 1) statements[0] else StatementList(statements, position)
    }

    private fun parseStatement(): Expression {
        val position = stream.peek().position

        // Variable assignment: x: value
        if (stream.check(TokenType.IDENTIFIER) && stream.checkNext(TokenType.COLON)) {
            val nameToken = stream.advance()
            val name = nameToken.literal as String
            stream.advance() // consume COLON
            val value = parseCallChain()
            return Assignment(VariableRef(name, nameToken.position), value, position)
        }

        val expr = parseCallChain()

        // Property assignment: expr: value
        if (stream.match(TokenType.COLON)) {
            if (!isAssignableTarget(expr)) {
                throw ParseException(
                    "Invalid assignment target. Expected variable, property access, or current note reference.",
                    position
                )
            }
            val value = parseCallChain()
            return Assignment(expr, value, position)
        }

        return expr
    }

    private fun isAssignableTarget(expr: Expression): Boolean = when (expr) {
        is PropertyAccess -> true
        is CurrentNoteRef -> true
        is VariableRef -> true
        else -> false
    }

    /**
     * Parse a chain of space-separated calls.
     * Right-to-left nesting: `[a b c]` -> `a(b(c))`.
     */
    private fun parseCallChain(): Expression {
        val first = parsePostfix(parsePrimary())

        if (first !is CallExpr) return first
        if (!isAtExpressionStart()) return first

        val rest = parseCallChain()
        return CallExpr(first.name, listOf(rest), first.position, first.namedArgs)
    }

    /**
     * Parse postfix operators (property access chains, method calls, immediate invocation).
     */
    private fun parsePostfix(expr: Expression): Expression {
        var result = expr

        while (true) {
            when {
                stream.match(TokenType.DOT) -> {
                    val dotPosition = stream.previous().position
                    val propToken = stream.consume(TokenType.IDENTIFIER, "Expected property or method name after '.'")
                    val propName = propToken.literal as String

                    result = if (stream.match(TokenType.LPAREN)) {
                        val (positionalArgs, namedArgs) = parseMethodArguments()
                        MethodCall(result, propName, positionalArgs, namedArgs, dotPosition)
                    } else {
                        PropertyAccess(result, propName, dotPosition)
                    }
                }
                result is LambdaExpr && stream.match(TokenType.LPAREN) -> {
                    val (positionalArgs, namedArgs) = parseMethodArguments()
                    result = LambdaInvocation(result, positionalArgs, namedArgs, result.position)
                }
                else -> break
            }
        }

        return result
    }

    private fun parseMethodArguments(): Pair<List<Expression>, List<NamedArg>> {
        val positionalArgs = mutableListOf<Expression>()
        val namedArgs = mutableListOf<NamedArg>()

        if (!stream.check(TokenType.RPAREN)) {
            do {
                val arg = parseArgument()
                when (arg) {
                    is ParsedPositionalArg -> {
                        if (namedArgs.isNotEmpty()) {
                            throw ParseException(
                                "Positional argument cannot follow named argument",
                                arg.expr.position
                            )
                        }
                        positionalArgs.add(arg.expr)
                    }
                    is ParsedNamedArg -> namedArgs.add(arg.namedArg)
                }
            } while (stream.match(TokenType.COMMA))
        }

        stream.consume(TokenType.RPAREN, "Expected ')' after method arguments")

        return positionalArgs to namedArgs
    }

    private fun isAtExpressionStart(): Boolean =
        !stream.check(TokenType.RBRACKET) &&
        !stream.check(TokenType.RPAREN) &&
        !stream.check(TokenType.COMMA) &&
        !stream.check(TokenType.COLON) &&
        !stream.check(TokenType.SEMICOLON) &&
        !stream.isAtEnd()

    private fun parsePrimary(): Expression {
        return when {
            stream.match(TokenType.NUMBER) -> {
                val token = stream.previous()
                NumberLiteral(token.literal as Double, token.position)
            }
            stream.match(TokenType.STRING) -> {
                val token = stream.previous()
                StringLiteral(token.literal as String, token.position)
            }
            stream.match(TokenType.DOT) -> {
                val position = stream.previous().position
                val currentNoteRef = CurrentNoteRef(position)

                if (stream.check(TokenType.IDENTIFIER)) {
                    val propToken = stream.advance()
                    val propName = propToken.literal as String

                    if (stream.match(TokenType.LPAREN)) {
                        val (positionalArgs, namedArgs) = parseMethodArguments()
                        MethodCall(currentNoteRef, propName, positionalArgs, namedArgs, position)
                    } else {
                        PropertyAccess(currentNoteRef, propName, position)
                    }
                } else {
                    currentNoteRef
                }
            }
            stream.match(TokenType.LBRACKET) -> {
                parseDeferredBlock(stream.previous().position)
            }
            stream.check(TokenType.LPAREN) && isMultiArgLambdaStart() -> {
                parseMultiArgLambda()
            }
            stream.match(TokenType.IDENTIFIER) -> {
                val token = stream.previous()
                val name = token.literal as String
                val position = token.position

                if (name == "once" && stream.check(TokenType.LBRACKET)) {
                    parseOnceExpression(position)
                } else if (name == "refresh" && stream.check(TokenType.LBRACKET)) {
                    parseRefreshExpression(position)
                } else if (name == "later") {
                    parseLaterExpression(position)
                } else if (stream.check(TokenType.LBRACKET)) {
                    stream.advance() // consume LBRACKET
                    val lambdaBody = parseDeferredBlock(stream.previous().position)
                    CallExpr(name, listOf(lambdaBody), position)
                } else if (stream.match(TokenType.LPAREN)) {
                    if (name == "pattern") {
                        patternParser.parsePatternExpression(position)
                    } else {
                        parseParenthesizedCall(name, position)
                    }
                } else {
                    CallExpr(name, emptyList(), position)
                }
            }
            else -> throw ParseException("Expected expression", stream.peek().position)
        }
    }

    /**
     * Check if we're at the start of a multi-arg lambda: `(a, b)[expr]`.
     * Looks ahead for the pattern: identifier (comma identifier)* RPAREN LBRACKET.
     */
    private fun isMultiArgLambdaStart(): Boolean {
        if (!stream.check(TokenType.LPAREN)) return false

        var offset = 1
        var seenIdentifier = false
        while (offset < MULTI_ARG_LAMBDA_MAX_LOOKAHEAD) {
            val token = stream.peekAt(offset) ?: return false
            when (token.type) {
                TokenType.IDENTIFIER -> {
                    seenIdentifier = true
                    offset++
                }
                TokenType.COMMA -> {
                    if (!seenIdentifier) return false
                    offset++
                    seenIdentifier = false
                }
                TokenType.RPAREN -> {
                    if (!seenIdentifier) return false
                    val next = stream.peekAt(offset + 1)
                    return next != null && next.type == TokenType.LBRACKET
                }
                else -> return false
            }
        }
        return false
    }

    private fun parseMultiArgLambda(): LambdaExpr {
        val position = stream.peek().position
        stream.consume(TokenType.LPAREN, "Expected '(' for multi-arg lambda")

        val params = mutableListOf<String>()
        if (!stream.check(TokenType.RPAREN)) {
            do {
                val paramToken = stream.consume(TokenType.IDENTIFIER, "Expected parameter name")
                params.add(paramToken.literal as String)
            } while (stream.match(TokenType.COMMA))
        }

        stream.consume(TokenType.RPAREN, "Expected ')' after lambda parameters")
        stream.consume(TokenType.LBRACKET, "Expected '[' after lambda parameters")

        val body = parseExpression()
        stream.consume(TokenType.RBRACKET, "Expected ']' to close lambda body")

        return LambdaExpr(params = params, body = body, position = position)
    }

    /**
     * Parse a deferred block: `[expr]` (caller has consumed the LBRACKET).
     * Returns a [LambdaExpr] with implicit parameter `i`.
     */
    private fun parseDeferredBlock(position: Int): LambdaExpr {
        val body = parseExpression()
        stream.consume(TokenType.RBRACKET, "Expected ']' to close deferred block")
        return LambdaExpr(params = listOf("i"), body = body, position = position)
    }

    /**
     * Parse parenthesized arguments: `name(arg1, arg2, key: value, ...)`.
     * Caller has consumed the LPAREN.
     */
    private fun parseParenthesizedCall(name: String, position: Int): CallExpr {
        val positionalArgs = mutableListOf<Expression>()
        val namedArgs = mutableListOf<NamedArg>()

        if (!stream.check(TokenType.RPAREN)) {
            do {
                val arg = parseArgument()
                when (arg) {
                    is ParsedPositionalArg -> {
                        if (namedArgs.isNotEmpty()) {
                            throw ParseException(
                                "Positional argument cannot follow named argument",
                                arg.expr.position
                            )
                        }
                        positionalArgs.add(arg.expr)
                    }
                    is ParsedNamedArg -> namedArgs.add(arg.namedArg)
                }
            } while (stream.match(TokenType.COMMA))
        }

        stream.consume(TokenType.RPAREN, "Expected ')' after arguments")

        return CallExpr(name, positionalArgs, position, namedArgs)
    }

    private sealed class ParsedArg
    private data class ParsedPositionalArg(val expr: Expression) : ParsedArg()
    private data class ParsedNamedArg(val namedArg: NamedArg) : ParsedArg()

    private fun parseArgument(): ParsedArg {
        if (stream.check(TokenType.IDENTIFIER) && stream.checkNext(TokenType.COLON)) {
            val nameToken = stream.advance()
            val argName = nameToken.literal as String
            val argPosition = nameToken.position
            stream.consume(TokenType.COLON, "Expected ':' after parameter name")
            val value = parseCallChain()
            return ParsedNamedArg(NamedArg(argName, value, argPosition))
        }

        val expr = parseCallChain()
        return ParsedPositionalArg(expr)
    }

    /**
     * Parse a once expression: `once[body]` (caller has consumed the `once` identifier).
     * The body is evaluated once and the result is cached permanently.
     */
    private fun parseOnceExpression(position: Int): OnceExpr {
        stream.consume(TokenType.LBRACKET, "Expected '[' after 'once'")
        val body = parseExpression()
        stream.consume(TokenType.RBRACKET, "Expected ']' to close once block")
        return OnceExpr(body = body, position = position)
    }

    /**
     * Parse a refresh expression: `refresh[body]` (caller has consumed the `refresh` identifier).
     * The body is re-evaluated at analyzed trigger times based on time comparisons.
     */
    private fun parseRefreshExpression(position: Int): RefreshExpr {
        stream.consume(TokenType.LBRACKET, "Expected '[' after 'refresh'")
        val body = parseExpression()
        stream.consume(TokenType.RBRACKET, "Expected ']' to close refresh block")
        return RefreshExpr(body = body, position = position)
    }

    /**
     * Parse a later expression: `later expr` or `later[...]` (caller has consumed `later`).
     * `later expr` creates a deferred reference (lambda with parameter `i`);
     * `later[...]` is redundant (the bracket block alone is already a lambda).
     */
    private fun parseLaterExpression(position: Int): LambdaExpr {
        if (stream.check(TokenType.LBRACKET)) {
            stream.advance() // consume LBRACKET
            return parseDeferredBlock(stream.previous().position)
        }

        val body = parsePrimary()
        return LambdaExpr(params = listOf("i"), body = body, position = position)
    }
}
