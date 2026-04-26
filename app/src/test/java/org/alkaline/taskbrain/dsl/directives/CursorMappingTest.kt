package org.alkaline.taskbrain.dsl.directives

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [mapSourceToDisplayOffset] and [mapDisplayToSourceOffset].
 *
 * These functions map cursor offsets between source text (which contains
 * directive syntax like `[add(1,1)]`) and display text (which contains the
 * rendered values like `2`). Source ↔ display can have very different lengths
 * per directive.
 */
class CursorMappingTest {

    private fun displayResult(displayText: String, vararg ranges: DirectiveDisplayRange) =
        DisplayTextResult(displayText, segments = emptyList(), directiveDisplayRanges = ranges.toList())

    private fun directiveRange(sourceRange: IntRange, displayRange: IntRange) =
        DirectiveDisplayRange(
            key = "k",
            sourceRange = sourceRange,
            displayRange = displayRange,
            sourceText = "",
            displayText = "",
            isComputed = true,
            hasError = false
        )

    // region empty/no-directive cases

    @Test
    fun `source-to-display passes through when no directives`() {
        val r = displayResult("hello")
        assertEquals(0, mapSourceToDisplayOffset(0, r))
        assertEquals(3, mapSourceToDisplayOffset(3, r))
        assertEquals(5, mapSourceToDisplayOffset(5, r))
    }

    @Test
    fun `display-to-source passes through when no directives`() {
        val r = displayResult("hello")
        assertEquals(0, mapDisplayToSourceOffset(0, r))
        assertEquals(3, mapDisplayToSourceOffset(3, r))
        assertEquals(5, mapDisplayToSourceOffset(5, r))
    }

    // endregion

    // region cursor before directive — no adjustment

    @Test
    fun `source-to-display with cursor before directive returns same offset`() {
        // Source: "ab[42]cd" → Display: "ab2cd"
        // [42] is at source 2..5, display 2..2
        val r = displayResult("ab2cd", directiveRange(2..5, 2..2))
        assertEquals(0, mapSourceToDisplayOffset(0, r))
        assertEquals(2, mapSourceToDisplayOffset(2, r))  // exactly at directive start
    }

    @Test
    fun `display-to-source with cursor before directive returns same offset`() {
        val r = displayResult("ab2cd", directiveRange(2..5, 2..2))
        assertEquals(0, mapDisplayToSourceOffset(0, r))
        assertEquals(2, mapDisplayToSourceOffset(2, r))
    }

    // endregion

    // region cursor after directive — length-difference adjustment

    @Test
    fun `source-to-display with cursor after shorter-display directive shrinks offset`() {
        // Source "ab[42]cd" (8 chars), display "ab2cd" (5 chars). After-directive
        // source positions shift left by 3 (4 source chars become 1 display char).
        val r = displayResult("ab2cd", directiveRange(2..5, 2..2))
        assertEquals(3, mapSourceToDisplayOffset(6, r))  // 'c'
        assertEquals(4, mapSourceToDisplayOffset(7, r))  // 'd'
        assertEquals(5, mapSourceToDisplayOffset(8, r))  // end
    }

    @Test
    fun `display-to-source with cursor after shorter-display directive grows offset`() {
        val r = displayResult("ab2cd", directiveRange(2..5, 2..2))
        assertEquals(6, mapDisplayToSourceOffset(3, r))  // 'c' in source
        assertEquals(7, mapDisplayToSourceOffset(4, r))  // 'd' in source
        assertEquals(8, mapDisplayToSourceOffset(5, r))  // end of source
    }

    @Test
    fun `source-to-display with cursor after longer-display directive grows offset`() {
        // Source "[a]b" (4 chars), display "longabc" (8 chars). [a] is 0..2 source, 0..4 display.
        val r = displayResult("longab", directiveRange(0..2, 0..4))
        assertEquals(5, mapSourceToDisplayOffset(3, r))   // 'b' shifts right by 2
    }

    // endregion

    // region cursor inside directive — snap to end

    @Test
    fun `source-to-display with cursor inside directive snaps to display end + 1`() {
        // Cursor inside [42] (source 3, 4, 5) all map to displayRange.last + 1 = 3
        val r = displayResult("ab2cd", directiveRange(2..5, 2..2))
        assertEquals(3, mapSourceToDisplayOffset(3, r))
        assertEquals(3, mapSourceToDisplayOffset(4, r))
        assertEquals(3, mapSourceToDisplayOffset(5, r))
    }

    @Test
    fun `source-to-display with cursor inside directive maps to display start when mapInsideDirective is false`() {
        val r = displayResult("ab2cd", directiveRange(2..5, 2..2))
        assertEquals(2, mapSourceToDisplayOffset(3, r, mapInsideDirective = false))
    }

    @Test
    fun `display-to-source with cursor inside directive snaps to source end + 1`() {
        val r = displayResult("ab2cd", directiveRange(2..5, 2..2))
        // Display position 2 is exactly at directive start — falls into the "before" branch.
        // Display positions inside the directive only exist when the display range spans
        // multiple chars; build a longer-display directive to test.
        val r2 = displayResult("XX longResultXX", directiveRange(0..3, 0..11))
        // Display offsets 1..11 are inside; should all map to source end+1 = 4
        assertEquals(4, mapDisplayToSourceOffset(1, r2))
        assertEquals(4, mapDisplayToSourceOffset(6, r2))
        assertEquals(4, mapDisplayToSourceOffset(11, r2))
    }

    // endregion

    // region multiple directives

    @Test
    fun `source-to-display accumulates adjustments across multiple directives`() {
        // Source "[42][7]x" (8 chars). Display "27x" (3 chars).
        // [42]: source 0..3 → display 0..0. [7]: source 4..6 → display 1..1.
        val r = displayResult(
            "27x",
            directiveRange(0..3, 0..0),
            directiveRange(4..6, 1..1)
        )
        assertEquals(0, mapSourceToDisplayOffset(0, r))   // before everything
        assertEquals(1, mapSourceToDisplayOffset(4, r))   // after first, before second
        assertEquals(2, mapSourceToDisplayOffset(7, r))   // after both, at 'x'
        assertEquals(3, mapSourceToDisplayOffset(8, r))   // end
    }

    @Test
    fun `display-to-source accumulates adjustments across multiple directives`() {
        val r = displayResult(
            "27x",
            directiveRange(0..3, 0..0),
            directiveRange(4..6, 1..1)
        )
        assertEquals(0, mapDisplayToSourceOffset(0, r))
        assertEquals(4, mapDisplayToSourceOffset(1, r))
        assertEquals(7, mapDisplayToSourceOffset(2, r))
        assertEquals(8, mapDisplayToSourceOffset(3, r))
    }

    // endregion

    // region clamping

    @Test
    fun `negative offsets clamp to zero`() {
        val r = displayResult("a")
        assertEquals(0, mapSourceToDisplayOffset(-5, r).coerceAtLeast(0))
        assertEquals(0, mapDisplayToSourceOffset(-5, r).coerceAtLeast(0))
    }

    // endregion
}
