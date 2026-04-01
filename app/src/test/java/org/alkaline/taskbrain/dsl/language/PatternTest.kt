package org.alkaline.taskbrain.dsl.language

import org.alkaline.taskbrain.dsl.runtime.values.BooleanVal
import org.alkaline.taskbrain.dsl.runtime.values.DslValue
import org.alkaline.taskbrain.dsl.runtime.ExecutionException
import org.alkaline.taskbrain.dsl.runtime.Executor
import org.alkaline.taskbrain.dsl.runtime.values.PatternVal
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for pattern parsing and matching.
 * Milestone 4: Patterns for mobile-friendly string matching.
 */
class PatternTest {

    private lateinit var executor: Executor

    @Before
    fun setUp() {
        executor = Executor()
    }

    private fun execute(source: String): DslValue {
        val tokens = Lexer(source).tokenize()
        val directive = Parser(tokens, source).parseDirective()
        return executor.execute(directive)
    }

    private fun parse(source: String): Directive {
        val tokens = Lexer(source).tokenize()
        return Parser(tokens, source).parseDirective()
    }

    // region Lexer tests for pattern tokens

    @Test
    fun `lexer tokenizes star`() {
        val tokens = Lexer("*").tokenize()
        assertEquals(2, tokens.size)
        assertEquals(TokenType.STAR, tokens[0].type)
        assertEquals(TokenType.EOF, tokens[1].type)
    }

    @Test
    fun `lexer tokenizes dotdot`() {
        val tokens = Lexer("..").tokenize()
        assertEquals(2, tokens.size)
        assertEquals(TokenType.DOTDOT, tokens[0].type)
        assertEquals(TokenType.EOF, tokens[1].type)
    }

    @Test
    fun `lexer accepts single dot for property access`() {
        // Milestone 6: Single dots are now supported for property access
        val tokens = Lexer(".").tokenize()
        assertEquals(2, tokens.size)
        assertEquals(TokenType.DOT, tokens[0].type)
        assertEquals(TokenType.EOF, tokens[1].type)
    }

    @Test
    fun `lexer handles pattern-like sequence`() {
        // digit*4 as individual tokens
        val tokens = Lexer("digit*4").tokenize()
        assertEquals(4, tokens.size)
        assertEquals(TokenType.IDENTIFIER, tokens[0].type)
        assertEquals("digit", tokens[0].literal)
        assertEquals(TokenType.STAR, tokens[1].type)
        assertEquals(TokenType.NUMBER, tokens[2].type)
        assertEquals(4.0, tokens[2].literal)
        assertEquals(TokenType.EOF, tokens[3].type)
    }

    @Test
    fun `lexer handles range quantifier tokens`() {
        // *(0..5) as individual tokens
        val tokens = Lexer("*(0..5)").tokenize()
        assertEquals(7, tokens.size)  // Including EOF
        assertEquals(TokenType.STAR, tokens[0].type)
        assertEquals(TokenType.LPAREN, tokens[1].type)
        assertEquals(TokenType.NUMBER, tokens[2].type)
        assertEquals(TokenType.DOTDOT, tokens[3].type)
        assertEquals(TokenType.NUMBER, tokens[4].type)
        assertEquals(TokenType.RPAREN, tokens[5].type)
        assertEquals(TokenType.EOF, tokens[6].type)
    }

    // endregion

    // region Parser tests for pattern expressions

    @Test
    fun `parses simple character class pattern`() {
        val directive = parse("[pattern(digit)]")
        assertTrue(directive.expression is PatternExpr)
        val pattern = directive.expression as PatternExpr
        assertEquals(1, pattern.elements.size)
        assertTrue(pattern.elements[0] is CharClass)
        assertEquals(CharClassType.DIGIT, (pattern.elements[0] as CharClass).type)
    }

    @Test
    fun `parses all character class types`() {
        val classes = listOf("digit", "letter", "space", "punct", "any")
        val expectedTypes = listOf(
            CharClassType.DIGIT,
            CharClassType.LETTER,
            CharClassType.SPACE,
            CharClassType.PUNCT,
            CharClassType.ANY
        )

        classes.zip(expectedTypes).forEach { (name, expectedType) ->
            val directive = parse("[pattern($name)]")
            val pattern = directive.expression as PatternExpr
            val charClass = pattern.elements[0] as CharClass
            assertEquals("Failed for $name", expectedType, charClass.type)
        }
    }

    @Test
    fun `parses pattern literal`() {
        val directive = parse("[pattern(\"-\")]")
        val pattern = directive.expression as PatternExpr
        assertEquals(1, pattern.elements.size)
        assertTrue(pattern.elements[0] is PatternLiteral)
        assertEquals("-", (pattern.elements[0] as PatternLiteral).value)
    }

    @Test
    fun `parses exact quantifier`() {
        val directive = parse("[pattern(digit*4)]")
        val pattern = directive.expression as PatternExpr
        assertEquals(1, pattern.elements.size)
        assertTrue(pattern.elements[0] is Quantified)
        val quantified = pattern.elements[0] as Quantified
        assertTrue(quantified.element is CharClass)
        assertTrue(quantified.quantifier is Quantifier.Exact)
        assertEquals(4, (quantified.quantifier as Quantifier.Exact).n)
    }

    @Test
    fun `parses any quantifier`() {
        val directive = parse("[pattern(letter*any)]")
        val pattern = directive.expression as PatternExpr
        val quantified = pattern.elements[0] as Quantified
        assertTrue(quantified.quantifier is Quantifier.Any)
    }

    @Test
    fun `parses range quantifier with max`() {
        val directive = parse("[pattern(digit*(0..5))]")
        val pattern = directive.expression as PatternExpr
        val quantified = pattern.elements[0] as Quantified
        assertTrue(quantified.quantifier is Quantifier.Range)
        val range = quantified.quantifier as Quantifier.Range
        assertEquals(0, range.min)
        assertEquals(5, range.max)
    }

    @Test
    fun `parses range quantifier without max`() {
        val directive = parse("[pattern(letter*(1..))]")
        val pattern = directive.expression as PatternExpr
        val quantified = pattern.elements[0] as Quantified
        assertTrue(quantified.quantifier is Quantifier.Range)
        val range = quantified.quantifier as Quantifier.Range
        assertEquals(1, range.min)
        assertNull(range.max)
    }

    @Test
    fun `parses complex ISO date pattern`() {
        val directive = parse("[pattern(digit*4 \"-\" digit*2 \"-\" digit*2)]")
        val pattern = directive.expression as PatternExpr
        assertEquals(5, pattern.elements.size)

        // digit*4
        assertTrue(pattern.elements[0] is Quantified)
        val q1 = pattern.elements[0] as Quantified
        assertEquals(CharClassType.DIGIT, (q1.element as CharClass).type)
        assertEquals(4, (q1.quantifier as Quantifier.Exact).n)

        // "-"
        assertTrue(pattern.elements[1] is PatternLiteral)
        assertEquals("-", (pattern.elements[1] as PatternLiteral).value)

        // digit*2
        assertTrue(pattern.elements[2] is Quantified)
        val q2 = pattern.elements[2] as Quantified
        assertEquals(2, (q2.quantifier as Quantifier.Exact).n)

        // "-"
        assertTrue(pattern.elements[3] is PatternLiteral)
        assertEquals("-", (pattern.elements[3] as PatternLiteral).value)

        // digit*2
        assertTrue(pattern.elements[4] is Quantified)
    }

    @Test
    fun `rejects unknown character class`() {
        val exception = assertThrows(ParseException::class.java) {
            parse("[pattern(unknown)]")
        }
        assertTrue(exception.message!!.contains("Unknown pattern character class"))
    }

    @Test
    fun `rejects empty pattern`() {
        val exception = assertThrows(ParseException::class.java) {
            parse("[pattern()]")
        }
        assertTrue(exception.message!!.contains("Pattern cannot be empty"))
    }

    @Test
    fun `rejects invalid quantifier identifier`() {
        val exception = assertThrows(ParseException::class.java) {
            parse("[pattern(digit*foo)]")
        }
        assertTrue(exception.message!!.contains("Expected quantifier"))
    }

    @Test
    fun `rejects range with max less than min`() {
        val exception = assertThrows(ParseException::class.java) {
            parse("[pattern(digit*(5..3))]")
        }
        assertTrue(exception.message!!.contains("must be >= minimum"))
    }

    // endregion

    // region Executor tests for pattern evaluation

    @Test
    fun `evaluates pattern to PatternVal`() {
        val result = execute("[pattern(digit*4)]")
        assertTrue(result is PatternVal)
    }

    @Test
    fun `pattern has correct regex`() {
        val result = execute("[pattern(digit*4)]") as PatternVal
        assertEquals("\\d{4}", result.compiledRegex.pattern)
    }

    @Test
    fun `ISO date pattern matches correct format`() {
        val result = execute("[pattern(digit*4 \"-\" digit*2 \"-\" digit*2)]") as PatternVal

        assertTrue(result.matches("2026-01-15"))
        assertTrue(result.matches("1999-12-31"))
        assertTrue(result.matches("0000-00-00"))
    }

    @Test
    fun `ISO date pattern rejects incorrect format`() {
        val result = execute("[pattern(digit*4 \"-\" digit*2 \"-\" digit*2)]") as PatternVal

        assertFalse(result.matches("26-01-15"))      // Year too short
        assertFalse(result.matches("2026-1-15"))    // Month too short
        assertFalse(result.matches("2026/01/15"))   // Wrong separator
        assertFalse(result.matches("2026-01-15 "))  // Trailing space
        assertFalse(result.matches(""))             // Empty
    }

    @Test
    fun `letter pattern matches correctly`() {
        val result = execute("[pattern(letter*3)]") as PatternVal

        assertTrue(result.matches("abc"))
        assertTrue(result.matches("XYZ"))
        assertTrue(result.matches("aBc"))
        assertFalse(result.matches("ab"))
        assertFalse(result.matches("abcd"))
        assertFalse(result.matches("ab1"))
    }

    @Test
    fun `any quantifier matches zero or more`() {
        val result = execute("[pattern(digit*any)]") as PatternVal

        assertTrue(result.matches(""))
        assertTrue(result.matches("1"))
        assertTrue(result.matches("123"))
        assertTrue(result.matches("999999999"))
    }

    @Test
    fun `range quantifier with bounds`() {
        val result = execute("[pattern(letter*(2..4))]") as PatternVal

        assertFalse(result.matches("a"))         // Too few
        assertTrue(result.matches("ab"))         // Min
        assertTrue(result.matches("abc"))        // Within range
        assertTrue(result.matches("abcd"))       // Max
        assertFalse(result.matches("abcde"))     // Too many
    }

    @Test
    fun `range quantifier unbounded`() {
        val result = execute("[pattern(digit*(1..))]") as PatternVal

        assertFalse(result.matches(""))          // Too few
        assertTrue(result.matches("1"))          // Min
        assertTrue(result.matches("123456789"))  // Many
    }

    @Test
    fun `space character class`() {
        val result = execute("[pattern(space*any)]") as PatternVal

        assertTrue(result.matches(""))
        assertTrue(result.matches(" "))
        assertTrue(result.matches("   "))
        assertTrue(result.matches("\t"))
        assertTrue(result.matches("\n"))
    }

    @Test
    fun `punct character class`() {
        val result = execute("[pattern(punct)]") as PatternVal

        assertTrue(result.matches("."))
        assertTrue(result.matches(","))
        assertTrue(result.matches("!"))
        assertTrue(result.matches("?"))
        assertFalse(result.matches("a"))
        assertFalse(result.matches("1"))
    }

    @Test
    fun `any character class`() {
        val result = execute("[pattern(any*3)]") as PatternVal

        assertTrue(result.matches("abc"))
        assertTrue(result.matches("123"))
        assertTrue(result.matches("!@#"))
        assertTrue(result.matches("a 1"))
        assertFalse(result.matches("ab"))
    }

    @Test
    fun `pattern literal with special regex chars`() {
        // Test that special regex characters are escaped
        val result = execute("[pattern(\"[test]\")]") as PatternVal

        assertTrue(result.matches("[test]"))
        assertFalse(result.matches("test"))
    }

    @Test
    fun `complex pattern with mixed elements`() {
        // Pattern: any letters, then a space, then 3 digits
        val result = execute("[pattern(letter*(1..) space digit*3)]") as PatternVal

        assertTrue(result.matches("hello 123"))
        assertTrue(result.matches("a 000"))
        assertFalse(result.matches("hello123"))     // Missing space
        assertFalse(result.matches("123 456"))      // Starts with digits
        assertFalse(result.matches("hello 12"))     // Too few digits
    }

    // endregion

    // region Serialization tests

    @Test
    fun `pattern serializes and deserializes`() {
        val original = execute("[pattern(digit*4 \"-\" digit*2)]") as PatternVal
        val serialized = original.serialize()
        val deserialized = DslValue.deserialize(serialized)

        assertTrue(deserialized is PatternVal)
        val restored = deserialized as PatternVal
        assertEquals(original.compiledRegex.pattern, restored.compiledRegex.pattern)
    }

    @Test
    fun `deserialized pattern matches correctly`() {
        val original = execute("[pattern(digit*4)]") as PatternVal
        val serialized = original.serialize()
        val restored = DslValue.deserialize(serialized) as PatternVal

        assertTrue(restored.matches("1234"))
        assertFalse(restored.matches("123"))
    }

    @Test
    fun `pattern display string shows regex`() {
        val result = execute("[pattern(digit*4)]") as PatternVal
        assertEquals("pattern(\\d{4})", result.toDisplayString())
    }

    // endregion

    // region Dynamic classification tests

    @Test
    fun `pattern is classified as static`() {
        val directive = parse("[pattern(digit*4)]")
        assertFalse(DynamicCallAnalyzer.containsDynamicCalls(directive.expression))
    }

    // endregion

    // region matches() function tests

    @Test
    fun `matches returns true for matching string`() {
        val result = execute("[matches(\"2026-01-15\", pattern(digit*4 \"-\" digit*2 \"-\" digit*2))]")

        assertTrue(result is BooleanVal)
        assertTrue((result as BooleanVal).value)
    }

    @Test
    fun `matches returns false for non-matching string`() {
        val result = execute("[matches(\"not-a-date\", pattern(digit*4 \"-\" digit*2 \"-\" digit*2))]")

        assertTrue(result is BooleanVal)
        assertFalse((result as BooleanVal).value)
    }

    @Test
    fun `matches with simple patterns`() {
        assertTrue((execute("[matches(\"abc\", pattern(letter*3))]") as BooleanVal).value)
        assertFalse((execute("[matches(\"ab1\", pattern(letter*3))]") as BooleanVal).value)
        assertTrue((execute("[matches(\"123\", pattern(digit*(1..)))]") as BooleanVal).value)
        assertFalse((execute("[matches(\"\", pattern(digit*(1..)))]") as BooleanVal).value)
    }

    @Test
    fun `matches with empty string`() {
        assertTrue((execute("[matches(\"\", pattern(digit*any))]") as BooleanVal).value)
        assertFalse((execute("[matches(\"\", pattern(digit*(1..)))]") as BooleanVal).value)
    }

    @Test
    fun `matches requires string as first argument`() {
        val exception = assertThrows(ExecutionException::class.java) {
            execute("[matches(123, pattern(digit*3))]")
        }
        assertTrue(exception.message!!.contains("first argument must be a string"))
    }

    @Test
    fun `matches requires pattern as second argument`() {
        val exception = assertThrows(ExecutionException::class.java) {
            execute("[matches(\"test\", \"test\")]")
        }
        assertTrue(exception.message!!.contains("second argument must be a pattern"))
    }

    @Test
    fun `matches requires exactly 2 arguments`() {
        val exception = assertThrows(ExecutionException::class.java) {
            execute("[matches(\"test\")]")
        }
        assertTrue(exception.message!!.contains("requires 2 arguments"))
    }

    @Test
    fun `matches is classified as static`() {
        assertFalse(org.alkaline.taskbrain.dsl.runtime.BuiltinRegistry.isDynamic("matches"))
    }

    // endregion

    // region BooleanVal tests

    @Test
    fun `BooleanVal displays as true or false`() {
        val trueVal = BooleanVal(true)
        val falseVal = BooleanVal(false)

        assertEquals("true", trueVal.toDisplayString())
        assertEquals("false", falseVal.toDisplayString())
    }

    @Test
    fun `BooleanVal serializes and deserializes`() {
        val originalTrue = BooleanVal(true)
        val originalFalse = BooleanVal(false)

        val deserializedTrue = DslValue.deserialize(originalTrue.serialize())
        val deserializedFalse = DslValue.deserialize(originalFalse.serialize())

        assertTrue(deserializedTrue is BooleanVal)
        assertTrue((deserializedTrue as BooleanVal).value)

        assertTrue(deserializedFalse is BooleanVal)
        assertFalse((deserializedFalse as BooleanVal).value)
    }

    @Test
    fun `BooleanVal has correct type name`() {
        assertEquals("boolean", BooleanVal(true).typeName)
    }

    // endregion
}
