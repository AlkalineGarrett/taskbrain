package org.alkaline.taskbrain.dsl.runtime.values

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Runtime values in Mindl.
 * Each subclass represents a different type of value that can result from evaluation.
 *
 * Milestone 2: Adds DateVal, TimeVal, DateTimeVal.
 * Milestone 4: Adds PatternVal for pattern matching, BooleanVal for matches().
 * Milestone 5: Adds NoteVal and ListVal for find() function.
 */
sealed class DslValue {
    /**
     * Type identifier for serialization.
     */
    abstract val typeName: String

    /**
     * Convert to a display string for rendering results.
     */
    abstract fun toDisplayString(): String

    /**
     * Serialize to a Map for Firestore storage.
     */
    fun serialize(): Map<String, Any?> = mapOf(
        "type" to typeName,
        "value" to serializeValue()
    )

    protected abstract fun serializeValue(): Any?

    companion object {
        /**
         * Deserialize a DslValue from a Firestore map.
         * @throws IllegalArgumentException for unknown types
         */
        fun deserialize(map: Map<String, Any?>): DslValue {
            val type = map["type"] as? String
                ?: throw IllegalArgumentException("Missing 'type' field in serialized DslValue")
            val value = map["value"]

            return when (type) {
                "undefined" -> UndefinedVal
                "number" -> NumberVal((value as Number).toDouble())
                "string" -> StringVal(value as String)
                "boolean" -> BooleanVal(value as Boolean)
                "date" -> DateVal(LocalDate.parse(value as String))
                "time" -> TimeVal(LocalTime.parse(value as String))
                "datetime" -> DateTimeVal(LocalDateTime.parse(value as String))
                "pattern" -> {
                    // Patterns are stored as regex string; reconstruct PatternVal
                    val regexString = value as String
                    PatternVal.fromRegexString(regexString)
                }
                "note" -> {
                    @Suppress("UNCHECKED_CAST")
                    val noteMap = value as Map<String, Any?>
                    NoteVal.deserialize(noteMap)
                }
                "list" -> {
                    @Suppress("UNCHECKED_CAST")
                    val items = (value as List<Map<String, Any?>>).map { deserialize(it) }
                    ListVal(items)
                }
                "view" -> {
                    @Suppress("UNCHECKED_CAST")
                    val viewMap = value as Map<String, Any?>
                    ViewVal.deserialize(viewMap)
                }
                "button" -> {
                    @Suppress("UNCHECKED_CAST")
                    val buttonMap = value as Map<String, Any?>
                    ButtonVal.deserialize(buttonMap)
                }
                "schedule" -> {
                    @Suppress("UNCHECKED_CAST")
                    val scheduleMap = value as Map<String, Any?>
                    ScheduleVal.deserialize(scheduleMap)
                }
                else -> throw IllegalArgumentException("Unknown DslValue type: $type")
            }
        }
    }
}
