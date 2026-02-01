package org.alkaline.taskbrain.dsl.directives

import android.util.Log
import org.alkaline.taskbrain.dsl.runtime.values.ButtonVal
import org.alkaline.taskbrain.dsl.runtime.values.ViewVal

private const val TAG = "DirectiveSegmenter"

/**
 * Represents a segment of line content - either plain text or a directive.
 * Used for rendering lines with computed directive results.
 */
sealed class DirectiveSegment {
    /** The character range in the original line content */
    abstract val range: IntRange

    /**
     * Plain text segment (not a directive).
     */
    data class Text(
        val content: String,
        override val range: IntRange
    ) : DirectiveSegment()

    /**
     * A directive segment with its computed result.
     */
    data class Directive(
        val sourceText: String,      // Original text, e.g., "[42]"
        val key: String,             // Position-based key (e.g., "3:15" for line 3, offset 15)
        val result: DirectiveResult?, // Computed result, null if not yet computed
        override val range: IntRange
    ) : DirectiveSegment() {
        /** Display text - result value if computed, source if not */
        val displayText: String
            get() = result?.let {
                if (it.isComputed) it.toValue()?.toDisplayString() ?: sourceText else sourceText
            } ?: sourceText

        /** Whether this directive has been computed */
        val isComputed: Boolean
            get() = result?.isComputed ?: false
    }
}

/**
 * Splits line content into segments of text and directives.
 */
object DirectiveSegmenter {

    /**
     * Split a line into segments.
     *
     * @param content The line content
     * @param lineIndex The line number (0-indexed) - used for position-based directive keys
     * @param results Map of directive key to result (keys are position-based: "lineIndex:startOffset")
     * @return List of segments in order
     */
    fun segmentLine(content: String, lineIndex: Int, results: Map<String, DirectiveResult>): List<DirectiveSegment> {
        val directives = DirectiveFinder.findDirectives(content)

        if (directives.isEmpty()) {
            return if (content.isEmpty()) {
                emptyList()
            } else {
                listOf(DirectiveSegment.Text(content, 0 until content.length))
            }
        }

        val segments = mutableListOf<DirectiveSegment>()
        var lastEnd = 0

        for (directive in directives) {
            // Add text before this directive
            if (directive.startOffset > lastEnd) {
                segments.add(
                    DirectiveSegment.Text(
                        content = content.substring(lastEnd, directive.startOffset),
                        range = lastEnd until directive.startOffset
                    )
                )
            }

            // Add the directive segment with position-based key
            val key = DirectiveFinder.directiveKey(lineIndex, directive.startOffset)
            segments.add(
                DirectiveSegment.Directive(
                    sourceText = directive.sourceText,
                    key = key,
                    result = results[key],
                    range = directive.startOffset until directive.endOffset
                )
            )

            lastEnd = directive.endOffset
        }

        // Add remaining text after last directive
        if (lastEnd < content.length) {
            segments.add(
                DirectiveSegment.Text(
                    content = content.substring(lastEnd),
                    range = lastEnd until content.length
                )
            )
        }

        return segments
    }

    /**
     * Check if a line contains any directives.
     */
    fun hasDirectives(content: String): Boolean {
        return DirectiveFinder.containsDirectives(content)
    }

    /**
     * Check if a line has any computed directives (results available).
     */
    fun hasComputedDirectives(content: String, lineIndex: Int, results: Map<String, DirectiveResult>): Boolean {
        val directives = DirectiveFinder.findDirectives(content)
        return directives.any { directive ->
            val key = DirectiveFinder.directiveKey(lineIndex, directive.startOffset)
            results[key]?.isComputed ?: false
        }
    }

    /**
     * Build the display text for a line, replacing directive source with results.
     * Also returns offset mapping from display to source positions.
     *
     * @param content The source line content
     * @param lineIndex The line number (0-indexed) - used for position-based directive keys
     * @param results Map of directive key to result (keys are position-based: "lineIndex:startOffset")
     * @return Pair of (displayText, sourceToDisplayMapping)
     */
    fun buildDisplayText(
        content: String,
        lineIndex: Int,
        results: Map<String, DirectiveResult>
    ): DisplayTextResult {
        val segments = segmentLine(content, lineIndex, results)

        if (segments.isEmpty()) {
            return DisplayTextResult(
                displayText = "",
                segments = emptyList(),
                directiveDisplayRanges = emptyList()
            )
        }

        val displayBuilder = StringBuilder()
        val directiveRanges = mutableListOf<DirectiveDisplayRange>()

        for (segment in segments) {
            when (segment) {
                is DirectiveSegment.Text -> {
                    displayBuilder.append(segment.content)
                }
                is DirectiveSegment.Directive -> {
                    val displayStart = displayBuilder.length
                    val displayText = segment.displayText
                    displayBuilder.append(displayText)
                    val displayEnd = displayBuilder.length

                    // Check if the result is a ViewVal or ButtonVal for special UI handling
                    val resultValue = segment.result?.toValue()
                    val isViewResult = resultValue is ViewVal
                    val isButtonResult = resultValue is ButtonVal
                    Log.d(TAG, "buildDisplayText: key=${segment.key}, resultValue=${resultValue?.javaClass?.simpleName}, isButton=$isButtonResult, isView=$isViewResult")

                    directiveRanges.add(
                        DirectiveDisplayRange(
                            key = segment.key,
                            sourceRange = segment.range,
                            displayRange = displayStart until displayEnd,
                            sourceText = segment.sourceText,
                            displayText = displayText,
                            isComputed = segment.isComputed,
                            hasError = segment.result?.error != null,
                            hasWarning = segment.result?.hasWarning ?: false,
                            isView = isViewResult,
                            isButton = isButtonResult
                        )
                    )
                }
            }
        }

        return DisplayTextResult(
            displayText = displayBuilder.toString(),
            segments = segments,
            directiveDisplayRanges = directiveRanges
        )
    }
}

/**
 * Result of building display text from source content.
 */
data class DisplayTextResult(
    val displayText: String,
    val segments: List<DirectiveSegment>,
    val directiveDisplayRanges: List<DirectiveDisplayRange>
)

/**
 * Tracks where a directive appears in both source and display text.
 *
 * Milestone 8: Added hasWarning for no-effect warnings.
 * Milestone 10: Added isView for view directive special rendering.
 * Button UI: Added isButton for button directive special rendering.
 */
data class DirectiveDisplayRange(
    val key: String,             // Position-based key (e.g., "3:15" for line 3, offset 15)
    val sourceRange: IntRange,
    val displayRange: IntRange,
    val sourceText: String,
    val displayText: String,
    val isComputed: Boolean,
    val hasError: Boolean,
    val hasWarning: Boolean = false,
    val isView: Boolean = false,  // True if result is a ViewVal
    val isButton: Boolean = false // True if result is a ButtonVal
)
