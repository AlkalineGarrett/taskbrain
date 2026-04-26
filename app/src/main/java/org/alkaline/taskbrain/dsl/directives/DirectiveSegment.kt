package org.alkaline.taskbrain.dsl.directives

import org.alkaline.taskbrain.data.AlarmMarkers
import org.alkaline.taskbrain.dsl.runtime.values.AlarmVal
import org.alkaline.taskbrain.dsl.runtime.values.ButtonVal
import org.alkaline.taskbrain.dsl.runtime.values.ViewVal

/**
 * Compute the result-map key for a directive. For directives containing once[...],
 * the key is scoped per-line ({lineNoteId}:{hash}) so different lines can hold
 * independent once values. For regular directives, the key is just the hash.
 */
fun onceAwareKey(sourceText: String, lineNoteId: String?): String {
    val hash = DirectiveResult.hashDirective(sourceText)
    return if (lineNoteId != null && sourceText.contains("once[")) {
        "$lineNoteId:$hash"
    } else {
        hash
    }
}

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
        val key: String,             // Directive key (e.g., "noteId:15")
        val result: DirectiveResult?, // Computed result, null if not yet computed
        override val range: IntRange
    ) : DirectiveSegment() {
        /** Display text - result value if computed, source if not */
        val displayText: String
            get() {
                val r = result
                if (r != null && r.isComputed) {
                    val value = r.toValue() ?: return sourceText
                    return value.toDisplayString()
                }
                // Alarm directives are trivial pure functions — render the icon
                // even without a computed result to avoid flicker from key mismatches
                if (isAlarmDirective(sourceText)) return ALARM_SYMBOL
                return sourceText
            }

        /** Whether this directive has been computed */
        val isComputed: Boolean
            get() = result?.isComputed ?: false || isAlarmDirective(sourceText)

        /**
         * The authoritative result for this directive.
         * For alarm directives, always parses the ID from the source text rather than
         * relying on the result cache, which can return wrong results after line reordering
         * (the cache is keyed by noteId + offset, so a stale noteId can collide with a
         * different alarm directive at the same offset).
         */
        val effectiveResult: DirectiveResult?
            get() {
                alarmIdFromSource(sourceText)?.let { return DirectiveResult.success(AlarmVal(it)) }
                recurringAlarmIdFromSource(sourceText)?.let { return DirectiveResult.success(AlarmVal(it)) }
                return result
            }

        companion object {
            private const val ALARM_SYMBOL = "⏰"

            /** Extracts the alarm ID if this is an alarm directive, null otherwise. */
            fun alarmIdFromSource(sourceText: String): String? =
                AlarmMarkers.ALARM_DIRECTIVE_REGEX.matchEntire(sourceText)?.groupValues?.get(1)

            /** Extracts the recurring alarm ID if this is a recurringAlarm directive, null otherwise. */
            fun recurringAlarmIdFromSource(sourceText: String): String? =
                AlarmMarkers.RECURRING_ALARM_DIRECTIVE_REGEX.matchEntire(sourceText)?.groupValues?.get(1)

            /** Returns true if this is any alarm-type directive (alarm or recurringAlarm). */
            fun isAlarmDirective(sourceText: String): Boolean =
                alarmIdFromSource(sourceText) != null || recurringAlarmIdFromSource(sourceText) != null

            /** Returns true if the source text is a view directive, regardless of parse/execution result. */
            fun isViewDirective(sourceText: String): Boolean =
                sourceText.startsWith("[view(")
        }
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
     * @param results Map of directive hash to result
     * @return List of segments in order
     */
    fun segmentLine(content: String, results: Map<String, DirectiveResult>, lineNoteId: String? = null): List<DirectiveSegment> {
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

            val key = onceAwareKey(directive.sourceText, lineNoteId)
            val lookupResult = results[key]
            segments.add(
                DirectiveSegment.Directive(
                    sourceText = directive.sourceText,
                    key = key,
                    result = lookupResult,
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
    fun hasComputedDirectives(content: String, results: Map<String, DirectiveResult>, lineNoteId: String? = null): Boolean {
        val directives = DirectiveFinder.findDirectives(content)
        return directives.any { directive ->
            val key = onceAwareKey(directive.sourceText, lineNoteId)
            results[key]?.isComputed ?: false
        }
    }

    /**
     * Build the display text for a line, replacing directive source with results.
     * Also returns offset mapping from display to source positions.
     *
     * @param content The source line content
     * @param results Map of directive hash to result
     * @return Pair of (displayText, sourceToDisplayMapping)
     */
    fun buildDisplayText(
        content: String,
        results: Map<String, DirectiveResult>,
        lineNoteId: String? = null
    ): DisplayTextResult {
        val segments = segmentLine(content, results, lineNoteId)

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

                    // Use effectiveResult to include synthesized alarm results
                    val resultValue = segment.effectiveResult?.toValue()
                    val isViewResult = resultValue is ViewVal
                    val isButtonResult = resultValue is ButtonVal
                    val isAlarmResult = resultValue is AlarmVal
                    // Extract alarm IDs directly from source text — immune to
                    // result cache key mismatches caused by stale noteIds.
                    val alarmId = DirectiveSegment.Directive.alarmIdFromSource(segment.sourceText)
                    val recurringAlarmId = DirectiveSegment.Directive.recurringAlarmIdFromSource(segment.sourceText)

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
                            isButton = isButtonResult,
                            isAlarm = isAlarmResult,
                            alarmId = alarmId,
                            recurringAlarmId = recurringAlarmId
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
 * Maps a source text offset to the corresponding display text offset,
 * accounting for directives that may have different lengths when rendered.
 *
 * @param mapInsideDirective controls where offsets inside a directive source map to:
 *   true = end of display range (for selection/highlight), false = start (for cursor positioning)
 */
fun mapSourceToDisplayOffset(
    sourceOffset: Int,
    displayResult: DisplayTextResult,
    mapInsideDirective: Boolean = true
): Int {
    if (displayResult.directiveDisplayRanges.isEmpty()) return sourceOffset

    var displayOffset = sourceOffset
    for (range in displayResult.directiveDisplayRanges) {
        if (sourceOffset <= range.sourceRange.first) break
        val sourceLength = range.sourceRange.last - range.sourceRange.first + 1
        val displayLength = range.displayRange.last - range.displayRange.first + 1
        if (sourceOffset > range.sourceRange.last) {
            displayOffset += displayLength - sourceLength
        } else {
            return if (mapInsideDirective) range.displayRange.last + 1
            else range.displayRange.first
        }
    }
    return displayOffset.coerceAtLeast(0)
}

/**
 * Maps a display text offset back to the corresponding source text offset.
 * Inverse of [mapSourceToDisplayOffset]. A display offset that lands inside a
 * directive's rendered value resolves to the end of the directive's source range.
 */
fun mapDisplayToSourceOffset(displayOffset: Int, displayResult: DisplayTextResult): Int {
    if (displayResult.directiveDisplayRanges.isEmpty()) return displayOffset

    var sourceOffset = displayOffset
    for (range in displayResult.directiveDisplayRanges) {
        if (displayOffset <= range.displayRange.first) break
        val sourceLength = range.sourceRange.last - range.sourceRange.first + 1
        val displayLength = range.displayRange.last - range.displayRange.first + 1
        if (displayOffset > range.displayRange.last) {
            sourceOffset += sourceLength - displayLength
        } else {
            return range.sourceRange.last + 1
        }
    }
    return sourceOffset.coerceAtLeast(0)
}

/**
 * Tracks where a directive appears in both source and display text.
 *
 * Added hasWarning for no-effect warnings.
 * Added isView for view directive special rendering.
 * Button UI: Added isButton for button directive special rendering.
 * Alarm identity: Added isAlarm/alarmId for alarm directive rendering (no dashed box).
 */
data class DirectiveDisplayRange(
    val key: String,             // Directive key (e.g., "noteId:15")
    val sourceRange: IntRange,
    val displayRange: IntRange,
    val sourceText: String,
    val displayText: String,
    val isComputed: Boolean,
    val hasError: Boolean,
    val hasWarning: Boolean = false,
    val isView: Boolean = false,   // True if result is a ViewVal
    val isButton: Boolean = false, // True if result is a ButtonVal
    val isAlarm: Boolean = false,  // True if result is an AlarmVal
    val alarmId: String? = null,   // Alarm document ID (when isAlarm is true)
    val recurringAlarmId: String? = null // Recurring alarm ID (when directive is [recurringAlarm(...)])
)
