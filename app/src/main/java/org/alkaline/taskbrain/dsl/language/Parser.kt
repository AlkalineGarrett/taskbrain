package org.alkaline.taskbrain.dsl.language

/**
 * Recursive descent parser for Mindl (the TaskBrain DSL).
 * Produces an AST from a sequence of tokens.
 *
 * Adds parenthesized calls with named arguments.
 * Supports two calling styles:
 * 1. Space-separated: [a b c] -> a(b(c)) (right-to-left nesting)
 * 2. Parenthesized: [add(1, 2)] or [foo(bar: "baz")]
 *
 * Space-separated nesting still works inside parens: [a(b c, d)] -> a(b(c), d)
 *
 * Adds pattern(...) special parsing for mobile-friendly pattern matching.
 * Adds dot operator for current note reference and property access.
 * Adds assignment syntax and statement separation.
 */
class Parser(private val tokens: List<Token>, private val source: String) {

    companion object {
        /** Valid character class names for pattern matching. */
        private val CHAR_CLASS_NAMES = mapOf(
            "digit" to CharClassType.DIGIT,
            "letter" to CharClassType.LETTER,
            "space" to CharClassType.SPACE,
            "punct" to CharClassType.PUNCT,
            "any" to CharClassType.ANY
        )
    }
    private var current = 0

    /**
     * Parse a complete directive.
     * @return The parsed Directive
     * @throws ParseException on syntax error
     */
    fun parseDirective(): Directive {
        val startToken = consume(TokenType.LBRACKET, "Expected '[' to start directive")
        val startPos = startToken.position
        val expression = parseExpression()
        consume(TokenType.RBRACKET, "Expected ']' to close directive")

        val endPos = previous().position + previous().lexeme.length
        val sourceText = source.substring(startPos, endPos)

        return Directive(expression, sourceText, startPos)
    }

    /**
     * Parse an expression.
     * Handles statement lists (semicolon-separated), assignments, and call chains.
     *
     * Adds statement list and assignment support.
     */
    private fun parseExpression(): Expression {
        return parseStatementList()
    }

    /**
     * Parse a list of statements separated by semicolons.
     * Returns a StatementList if multiple statements, otherwise the single statement.
     */
    private fun parseStatementList(): Expression {
        val position = peek().position
        val statements = mutableListOf<Expression>()

        statements.add(parseStatement())

        while (match(TokenType.SEMICOLON)) {
            statements.add(parseStatement())
        }

        return if (statements.size == 1) {
            statements[0]
        } else {
            StatementList(statements, position)
        }
    }

    /**
     * Parse a single statement (assignment or expression).
     * Assignment has the form: target : value
     * where target is an identifier (variable) or property access.
     */
    private fun parseStatement(): Expression {
        val position = peek().position

        // Special case: [x: value] where x is a variable being defined
        // Need to look ahead for IDENTIFIER followed by COLON (but not inside a call)
        if (check(TokenType.IDENTIFIER) && checkNext(TokenType.COLON)) {
            val nameToken = advance()
            val name = nameToken.literal as String
            advance() // consume COLON
            val value = parseCallChain()
            return Assignment(VariableRef(name, nameToken.position), value, position)
        }

        // Parse the expression (could be an assignment target or a regular expression)
        val expr = parseCallChain()

        // Check if this is an assignment (expression followed by COLON)
        if (match(TokenType.COLON)) {
            // Validate that the target is assignable (property access or current note ref)
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

    /**
     * Check if an expression can be the target of an assignment.
     */
    private fun isAssignableTarget(expr: Expression): Boolean {
        return when (expr) {
            is PropertyAccess -> true  // .path, note.path
            is CurrentNoteRef -> true  // . (whole note)
            is VariableRef -> true     // x
            else -> false
        }
    }

    /**
     * Parse a chain of space-separated calls.
     * Uses right-to-left nesting: [a b c] -> a(b(c))
     */
    private fun parseCallChain(): Expression {
        val first = parsePostfix(parsePrimary())

        // If the first element is not an identifier, it can't start a call chain
        if (first !is CallExpr) {
            return first
        }

        // Check if there are more expressions to nest (not at end-of-argument boundary)
        if (!isAtExpressionStart()) {
            return first
        }

        // Parse the rest of the chain recursively
        val rest = parseCallChain()

        // Nest right-to-left: the rest becomes the argument to first
        return CallExpr(first.name, listOf(rest), first.position, first.namedArgs)
    }

    /**
     * Parse postfix operators (property access chains, method calls, and immediate invocation).
     * Example: expr.path.name -> PropertyAccess(PropertyAccess(expr, "path"), "name")
     * Example: expr.append("x") -> MethodCall(expr, "append", ["x"], [])
     * Example: [[i.path](.)] -> LambdaExpr invoked with current note
     *
     * Property access chains.
     * Method calls on expressions.
     * Immediate lambda invocation with (args).
     */
    private fun parsePostfix(expr: Expression): Expression {
        var result = expr

        // Keep consuming postfix operators
        while (true) {
            when {
                match(TokenType.DOT) -> {
                    val dotPosition = previous().position
                    val propToken = consume(TokenType.IDENTIFIER, "Expected property or method name after '.'")
                    val propName = propToken.literal as String

                    // Check if this is a method call (followed by parentheses)
                    if (match(TokenType.LPAREN)) {
                        val (positionalArgs, namedArgs) = parseMethodArguments()
                        result = MethodCall(result, propName, positionalArgs, namedArgs, dotPosition)
                    } else {
                        result = PropertyAccess(result, propName, dotPosition)
                    }
                }
                // Immediate invocation - expr(args) where expr is a lambda
                result is LambdaExpr && match(TokenType.LPAREN) -> {
                    val (positionalArgs, namedArgs) = parseMethodArguments()
                    result = LambdaInvocation(result, positionalArgs, namedArgs, result.position)
                }
                else -> break
            }
        }

        return result
    }

    /**
     * Parse method arguments inside parentheses.
     * Called after LPAREN has been consumed.
     */
    private fun parseMethodArguments(): Pair<List<Expression>, List<NamedArg>> {
        val positionalArgs = mutableListOf<Expression>()
        val namedArgs = mutableListOf<NamedArg>()

        // Check for empty argument list
        if (!check(TokenType.RPAREN)) {
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
                    is ParsedNamedArg -> {
                        namedArgs.add(arg.namedArg)
                    }
                }
            } while (match(TokenType.COMMA))
        }

        consume(TokenType.RPAREN, "Expected ')' after method arguments")

        return positionalArgs to namedArgs
    }

    /**
     * Check if we're at the start of another expression.
     * Not at: ], ), ,, :, ;, or EOF
     *
     * Added SEMICOLON as a boundary.
     */
    private fun isAtExpressionStart(): Boolean {
        return !check(TokenType.RBRACKET) &&
               !check(TokenType.RPAREN) &&
               !check(TokenType.COMMA) &&
               !check(TokenType.COLON) &&
               !check(TokenType.SEMICOLON) &&
               !isAtEnd()
    }

    /**
     * Parse a primary expression (literal, identifier, current note ref, deferred block, or parenthesized call).
     *
     * Added support for:
     * - `[...]` as implicit lambda with parameter `i`
     * - `func[x]` as equivalent to `func([x])`
     * - `(a, b)[expr]` for multi-arg lambdas
     */
    private fun parsePrimary(): Expression {
        return when {
            match(TokenType.NUMBER) -> {
                val token = previous()
                NumberLiteral(token.literal as Double, token.position)
            }
            match(TokenType.STRING) -> {
                val token = previous()
                StringLiteral(token.literal as String, token.position)
            }
            match(TokenType.DOT) -> {
                // Current note reference: [.] or [.path] or [.method(...)]
                val position = previous().position
                val currentNoteRef = CurrentNoteRef(position)

                // Check if followed by identifier for property access or method call
                if (check(TokenType.IDENTIFIER)) {
                    val propToken = advance()
                    val propName = propToken.literal as String

                    // Check if this is a method call (followed by parentheses)
                    if (match(TokenType.LPAREN)) {
                        val (positionalArgs, namedArgs) = parseMethodArguments()
                        MethodCall(currentNoteRef, propName, positionalArgs, namedArgs, position)
                    } else {
                        PropertyAccess(currentNoteRef, propName, position)
                    }
                } else {
                    // Just [.] - return current note reference
                    currentNoteRef
                }
            }
            match(TokenType.LBRACKET) -> {
                // `[...]` in expression position is an implicit lambda with parameter `i`
                parseDeferredBlock(previous().position)
            }
            check(TokenType.LPAREN) && isMultiArgLambdaStart() -> {
                // `(a, b)[expr]` multi-arg lambda syntax
                parseMultiArgLambda()
            }
            match(TokenType.IDENTIFIER) -> {
                val token = previous()
                val name = token.literal as String
                val position = token.position

                if (name == "once" && check(TokenType.LBRACKET)) {
                    // once[...] creates a cached execution block
                    parseOnceExpression(position)
                } else if (name == "refresh" && check(TokenType.LBRACKET)) {
                    // refresh[...] creates a time-triggered refresh block
                    parseRefreshExpression(position)
                } else if (name == "later") {
                    // later creates a deferred reference
                    parseLaterExpression(position)
                } else if (check(TokenType.LBRACKET)) {
                    // func[x] → func([x])
                    advance() // consume LBRACKET
                    val lambdaBody = parseDeferredBlock(previous().position)
                    CallExpr(name, listOf(lambdaBody), position)
                } else if (match(TokenType.LPAREN)) {
                    // Special case: pattern(...) has its own parsing mode
                    if (name == "pattern") {
                        parsePatternExpression(position)
                    } else {
                        parseParenthesizedCall(name, position)
                    }
                } else {
                    // An identifier alone becomes a zero-arg function call
                    CallExpr(name, emptyList(), position)
                }
            }
            else -> throw ParseException(
                "Expected expression",
                peek().position
            )
        }
    }

    /**
     * Check if we're at the start of a multi-arg lambda: (a, b)[expr]
     * Looks ahead to see if there's a pattern of identifiers, commas, RPAREN, LBRACKET.
     */
    private fun isMultiArgLambdaStart(): Boolean {
        if (!check(TokenType.LPAREN)) return false

        // Save position and scan ahead
        var lookahead = current + 1
        val maxLookahead = minOf(current + 20, tokens.size) // reasonable limit

        // Skip past LPAREN
        // Check for pattern: identifier (comma identifier)* RPAREN LBRACKET
        var seenIdentifier = false
        while (lookahead < maxLookahead) {
            val token = tokens[lookahead]
            when (token.type) {
                TokenType.IDENTIFIER -> {
                    seenIdentifier = true
                    lookahead++
                }
                TokenType.COMMA -> {
                    if (!seenIdentifier) return false
                    lookahead++
                    seenIdentifier = false
                }
                TokenType.RPAREN -> {
                    if (!seenIdentifier) return false
                    // Check if followed by LBRACKET
                    return lookahead + 1 < tokens.size &&
                           tokens[lookahead + 1].type == TokenType.LBRACKET
                }
                else -> return false
            }
        }
        return false
    }

    /**
     * Parse a multi-arg lambda: (a, b)[expr]
     * Called when LPAREN is current and we've verified it's a multi-arg lambda.
     */
    private fun parseMultiArgLambda(): LambdaExpr {
        val position = peek().position
        consume(TokenType.LPAREN, "Expected '(' for multi-arg lambda")

        val params = mutableListOf<String>()

        // Parse parameter names
        if (!check(TokenType.RPAREN)) {
            do {
                val paramToken = consume(TokenType.IDENTIFIER, "Expected parameter name")
                params.add(paramToken.literal as String)
            } while (match(TokenType.COMMA))
        }

        consume(TokenType.RPAREN, "Expected ')' after lambda parameters")
        consume(TokenType.LBRACKET, "Expected '[' after lambda parameters")

        val body = parseExpression()
        consume(TokenType.RBRACKET, "Expected ']' to close lambda body")

        return LambdaExpr(params = params, body = body, position = position)
    }

    /**
     * Parse a deferred block: [expr] where expr can be multi-statement.
     * Returns a LambdaExpr with implicit parameter `i`.
     * Called after LBRACKET has been consumed.
     */
    private fun parseDeferredBlock(position: Int): LambdaExpr {
        val body = parseExpression()
        consume(TokenType.RBRACKET, "Expected ']' to close deferred block")
        return LambdaExpr(params = listOf("i"), body = body, position = position)
    }

    /**
     * Parse parenthesized arguments: name(arg1, arg2, key: value, ...)
     * Called after LPAREN has been consumed.
     */
    private fun parseParenthesizedCall(name: String, position: Int): CallExpr {
        val positionalArgs = mutableListOf<Expression>()
        val namedArgs = mutableListOf<NamedArg>()

        // Check for empty argument list
        if (!check(TokenType.RPAREN)) {
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
                    is ParsedNamedArg -> {
                        namedArgs.add(arg.namedArg)
                    }
                }
            } while (match(TokenType.COMMA))
        }

        consume(TokenType.RPAREN, "Expected ')' after arguments")

        return CallExpr(name, positionalArgs, position, namedArgs)
    }

    /**
     * Result of parsing a single argument - either positional or named.
     */
    private sealed class ParsedArg
    private data class ParsedPositionalArg(val expr: Expression) : ParsedArg()
    private data class ParsedNamedArg(val namedArg: NamedArg) : ParsedArg()

    /**
     * Parse a single argument (positional or named).
     * Named arguments have the form: identifier: expression
     * Positional arguments are just expressions.
     */
    private fun parseArgument(): ParsedArg {
        // Look ahead: if we see IDENTIFIER followed by COLON, it's a named argument
        if (check(TokenType.IDENTIFIER) && checkNext(TokenType.COLON)) {
            val nameToken = advance()
            val argName = nameToken.literal as String
            val argPosition = nameToken.position
            consume(TokenType.COLON, "Expected ':' after parameter name")
            val value = parseCallChain()
            return ParsedNamedArg(NamedArg(argName, value, argPosition))
        }

        // Otherwise it's a positional argument
        val expr = parseCallChain()
        return ParsedPositionalArg(expr)
    }

    /**
     * Check the next token (one ahead) without consuming.
     */
    private fun checkNext(type: TokenType): Boolean {
        if (current + 1 >= tokens.size) return false
        return tokens[current + 1].type == type
    }

    private fun match(vararg types: TokenType): Boolean {
        for (type in types) {
            if (check(type)) {
                advance()
                return true
            }
        }
        return false
    }

    private fun check(type: TokenType): Boolean {
        if (isAtEnd()) return false
        return peek().type == type
    }

    private fun advance(): Token {
        if (!isAtEnd()) current++
        return previous()
    }

    private fun isAtEnd(): Boolean = peek().type == TokenType.EOF

    private fun peek(): Token = tokens[current]

    private fun previous(): Token = tokens[current - 1]

    private fun consume(type: TokenType, message: String): Token {
        if (check(type)) return advance()
        throw ParseException(message, peek().position)
    }

    // ========================================================================
    // Pattern Parsing
    // ========================================================================

    /**
     * Parse a pattern expression: pattern(digit*4 "-" digit*2 "-" digit*2)
     * Called after 'pattern(' has been consumed.
     *
     * Pattern syntax:
     * - Character classes: digit, letter, space, punct, any
     * - Quantifiers: *4, *any, *(0..5), *(1..)
     * - Literals: "string"
     */
    private fun parsePatternExpression(position: Int): PatternExpr {
        val elements = mutableListOf<PatternElement>()

        // Parse pattern elements until we hit RPAREN
        while (!check(TokenType.RPAREN)) {
            elements.add(parsePatternElement())
        }

        consume(TokenType.RPAREN, "Expected ')' after pattern elements")

        if (elements.isEmpty()) {
            throw ParseException("Pattern cannot be empty", position)
        }

        return PatternExpr(elements, position)
    }

    /**
     * Parse a single pattern element (possibly with a quantifier).
     */
    private fun parsePatternElement(): PatternElement {
        val baseElement = parseBasePatternElement()

        // Check for optional quantifier
        return if (check(TokenType.STAR)) {
            parseQuantifiedElement(baseElement)
        } else {
            baseElement
        }
    }

    /**
     * Parse a base pattern element (before quantifier).
     */
    private fun parseBasePatternElement(): PatternElement {
        return when {
            match(TokenType.STRING) -> {
                val token = previous()
                PatternLiteral(token.literal as String, token.position)
            }
            match(TokenType.IDENTIFIER) -> {
                val token = previous()
                val name = token.literal as String
                val charClassType = CHAR_CLASS_NAMES[name]
                    ?: throw ParseException(
                        "Unknown pattern character class '$name'. " +
                        "Valid classes are: ${CHAR_CLASS_NAMES.keys.joinToString()}",
                        token.position
                    )
                CharClass(charClassType, token.position)
            }
            else -> throw ParseException(
                "Expected pattern element (character class or string literal)",
                peek().position
            )
        }
    }

    /**
     * Parse a quantified pattern element: element*4, element*any, element*(0..5)
     * Called when STAR is the current token.
     */
    private fun parseQuantifiedElement(element: PatternElement): Quantified {
        val starToken = advance() // consume STAR
        val quantifier = parseQuantifier()
        return Quantified(element, quantifier, element.position)
    }

    /**
     * Parse a quantifier after the STAR token.
     * Forms: *4, *any, *(0..5), *(1..)
     */
    private fun parseQuantifier(): Quantifier {
        return when {
            match(TokenType.NUMBER) -> {
                // *4 - exact count
                val token = previous()
                val count = (token.literal as Double).toInt()
                if (count < 0) {
                    throw ParseException("Quantifier count must be non-negative", token.position)
                }
                Quantifier.Exact(count)
            }
            match(TokenType.IDENTIFIER) -> {
                // *any - match any number of times
                val token = previous()
                val name = token.literal as String
                if (name != "any") {
                    throw ParseException(
                        "Expected quantifier: number, 'any', or '(min..max)'. Got '$name'",
                        token.position
                    )
                }
                Quantifier.Any
            }
            match(TokenType.LPAREN) -> {
                // *(0..5) or *(1..) - range quantifier
                parseRangeQuantifier()
            }
            else -> throw ParseException(
                "Expected quantifier after '*': number, 'any', or '(min..max)'",
                peek().position
            )
        }
    }

    /**
     * Parse a range quantifier: (0..5) or (1..)
     * Called after LPAREN has been consumed.
     */
    private fun parseRangeQuantifier(): Quantifier.Range {
        // Parse minimum
        val minToken = consume(TokenType.NUMBER, "Expected minimum in range quantifier")
        val min = (minToken.literal as Double).toInt()
        if (min < 0) {
            throw ParseException("Range minimum must be non-negative", minToken.position)
        }

        // Expect ..
        consume(TokenType.DOTDOT, "Expected '..' in range quantifier")

        // Parse maximum (optional - if missing, it's unbounded)
        val max: Int? = if (check(TokenType.NUMBER)) {
            val maxToken = advance()
            val maxVal = (maxToken.literal as Double).toInt()
            if (maxVal < min) {
                throw ParseException(
                    "Range maximum ($maxVal) must be >= minimum ($min)",
                    maxToken.position
                )
            }
            maxVal
        } else {
            null // unbounded
        }

        consume(TokenType.RPAREN, "Expected ')' after range quantifier")

        return Quantifier.Range(min, max)
    }

    // ========================================================================
    // Execution Block Parsing
    // ========================================================================

    /**
     * Parse a once expression: once[body]
     * Called when 'once' identifier has been consumed and '[' is next.
     *
     * The body is evaluated once and the result is cached permanently.
     * Example: once[datetime] creates OnceExpr(CallExpr("datetime", []))
     */
    private fun parseOnceExpression(position: Int): OnceExpr {
        consume(TokenType.LBRACKET, "Expected '[' after 'once'")
        val body = parseExpression()
        consume(TokenType.RBRACKET, "Expected ']' to close once block")
        return OnceExpr(body = body, position = position)
    }

    /**
     * Parse a refresh expression: refresh[body]
     * Called when 'refresh' identifier has been consumed and '[' is next.
     *
     * The body is re-evaluated at analyzed trigger times based on time comparisons.
     * Example: refresh[if(time.gt("12:00"), X, Y)] creates RefreshExpr
     */
    private fun parseRefreshExpression(position: Int): RefreshExpr {
        consume(TokenType.LBRACKET, "Expected '[' after 'refresh'")
        val body = parseExpression()
        consume(TokenType.RBRACKET, "Expected ']' to close refresh block")
        return RefreshExpr(body = body, position = position)
    }

    /**
     * Parse a later expression: later expr or later[...]
     * Called when 'later' identifier has been consumed.
     *
     * `later expr` creates a deferred reference (lambda with parameter i).
     * `later[...]` is redundant - just parses the bracket block (later is ignored).
     */
    private fun parseLaterExpression(position: Int): LambdaExpr {
        // Check if followed by bracket - if so, later is redundant
        if (check(TokenType.LBRACKET)) {
            advance() // consume LBRACKET
            return parseDeferredBlock(previous().position)
        }

        // Otherwise, parse the next primary expression and wrap it in a lambda
        val body = parsePrimary()
        return LambdaExpr(params = listOf("i"), body = body, position = position)
    }
}

/**
 * Exception thrown when the parser encounters a syntax error.
 */
class ParseException(
    message: String,
    val position: Int
) : RuntimeException("Parse error at position $position: $message")
