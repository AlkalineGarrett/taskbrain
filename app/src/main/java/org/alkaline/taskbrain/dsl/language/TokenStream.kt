package org.alkaline.taskbrain.dsl.language

/**
 * Cursor over a token list. Holds the position and provides the standard
 * recursive-descent-parser primitives (peek/check/match/consume).
 *
 * `consume` throws [ParseException] if the next token doesn't match.
 */
class TokenStream(private val tokens: List<Token>) {
    private var current = 0

    fun match(vararg types: TokenType): Boolean {
        for (type in types) {
            if (check(type)) {
                advance()
                return true
            }
        }
        return false
    }

    fun check(type: TokenType): Boolean {
        if (isAtEnd()) return false
        return peek().type == type
    }

    fun checkNext(type: TokenType): Boolean {
        if (current + 1 >= tokens.size) return false
        return tokens[current + 1].type == type
    }

    fun advance(): Token {
        if (!isAtEnd()) current++
        return previous()
    }

    fun isAtEnd(): Boolean = peek().type == TokenType.EOF

    fun peek(): Token = tokens[current]

    fun previous(): Token = tokens[current - 1]

    fun consume(type: TokenType, message: String): Token {
        if (check(type)) return advance()
        throw ParseException(message, peek().position)
    }

    /**
     * Token [offset] positions ahead of the current cursor, or null if past EOF.
     * `peekAt(0)` is equivalent to [peek]; `peekAt(1)` is one past the cursor, etc.
     */
    fun peekAt(offset: Int): Token? {
        val i = current + offset
        return if (i < 0 || i >= tokens.size) null else tokens[i]
    }
}
