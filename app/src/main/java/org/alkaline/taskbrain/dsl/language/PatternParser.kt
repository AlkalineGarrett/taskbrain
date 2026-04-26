package org.alkaline.taskbrain.dsl.language

/**
 * Parses pattern expressions: `pattern(digit*4 "-" digit*2 "-" digit*2)`.
 *
 * Pattern syntax:
 * - Character classes: `digit`, `letter`, `space`, `punct`, `any`
 * - Quantifiers: `*4`, `*any`, `*(0..5)`, `*(1..)`
 * - Literals: `"string"`
 *
 * Caller has already consumed `pattern(`. This parser consumes through the
 * closing `)` and returns the [PatternExpr].
 */
class PatternParser(private val stream: TokenStream) {

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

    fun parsePatternExpression(position: Int): PatternExpr {
        val elements = mutableListOf<PatternElement>()

        while (!stream.check(TokenType.RPAREN)) {
            elements.add(parsePatternElement())
        }

        stream.consume(TokenType.RPAREN, "Expected ')' after pattern elements")

        if (elements.isEmpty()) {
            throw ParseException("Pattern cannot be empty", position)
        }

        return PatternExpr(elements, position)
    }

    private fun parsePatternElement(): PatternElement {
        val baseElement = parseBasePatternElement()
        return if (stream.check(TokenType.STAR)) parseQuantifiedElement(baseElement) else baseElement
    }

    private fun parseBasePatternElement(): PatternElement {
        return when {
            stream.match(TokenType.STRING) -> {
                val token = stream.previous()
                PatternLiteral(token.literal as String, token.position)
            }
            stream.match(TokenType.IDENTIFIER) -> {
                val token = stream.previous()
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
                stream.peek().position
            )
        }
    }

    private fun parseQuantifiedElement(element: PatternElement): Quantified {
        stream.advance()
        val quantifier = parseQuantifier()
        return Quantified(element, quantifier, element.position)
    }

    private fun parseQuantifier(): Quantifier {
        return when {
            stream.match(TokenType.NUMBER) -> {
                val token = stream.previous()
                val count = (token.literal as Double).toInt()
                if (count < 0) {
                    throw ParseException("Quantifier count must be non-negative", token.position)
                }
                Quantifier.Exact(count)
            }
            stream.match(TokenType.IDENTIFIER) -> {
                val token = stream.previous()
                val name = token.literal as String
                if (name != "any") {
                    throw ParseException(
                        "Expected quantifier: number, 'any', or '(min..max)'. Got '$name'",
                        token.position
                    )
                }
                Quantifier.Any
            }
            stream.match(TokenType.LPAREN) -> {
                parseRangeQuantifier()
            }
            else -> throw ParseException(
                "Expected quantifier after '*': number, 'any', or '(min..max)'",
                stream.peek().position
            )
        }
    }

    private fun parseRangeQuantifier(): Quantifier.Range {
        val minToken = stream.consume(TokenType.NUMBER, "Expected minimum in range quantifier")
        val min = (minToken.literal as Double).toInt()
        if (min < 0) {
            throw ParseException("Range minimum must be non-negative", minToken.position)
        }

        stream.consume(TokenType.DOTDOT, "Expected '..' in range quantifier")

        val max: Int? = if (stream.check(TokenType.NUMBER)) {
            val maxToken = stream.advance()
            val maxVal = (maxToken.literal as Double).toInt()
            if (maxVal < min) {
                throw ParseException(
                    "Range maximum ($maxVal) must be >= minimum ($min)",
                    maxToken.position
                )
            }
            maxVal
        } else {
            null
        }

        stream.consume(TokenType.RPAREN, "Expected ')' after range quantifier")

        return Quantifier.Range(min, max)
    }
}
