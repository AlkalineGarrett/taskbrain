package org.alkaline.taskbrain.dsl.runtime.values

import org.alkaline.taskbrain.dsl.language.StringLiteral
import org.alkaline.taskbrain.dsl.runtime.Environment

/**
 * Schedule frequency for scheduled actions.
 * Phase 0f.
 */
enum class ScheduleFrequency(val identifier: String) {
    DAILY("daily"),
    HOURLY("hourly"),
    WEEKLY("weekly");

    companion object {
        fun fromIdentifier(id: String): ScheduleFrequency? =
            entries.find { it.identifier == id }
    }
}

/**
 * A button value representing a clickable action.
 * Created by the button() function to display a button that executes an action when clicked.
 *
 * Phase 0f.
 *
 * @param label The button label displayed to the user
 * @param action The lambda to execute when clicked
 */
data class ButtonVal(
    val label: String,
    val action: LambdaVal
) : DslValue() {
    override val typeName: String = "button"

    override fun toDisplayString(): String = "[Button: $label]"

    override fun serializeValue(): Any = mapOf(
        "label" to label
        // action (lambda) cannot be serialized
    )

    companion object {
        /**
         * Deserialize a ButtonVal from a Firestore map.
         * The action lambda cannot be restored, so a placeholder is used.
         * The actual action will be re-created from the directive source when needed.
         */
        fun deserialize(map: Map<String, Any?>): ButtonVal {
            val label = map["label"] as? String ?: "Button"
            // Create a placeholder lambda - the actual action must be re-executed from source
            val placeholderLambda = LambdaVal(
                params = emptyList(),
                body = StringLiteral("Placeholder - re-execute from source", 0),
                capturedEnv = Environment()
            )
            return ButtonVal(label, placeholderLambda)
        }
    }
}

/**
 * A scheduled action value representing a time-triggered action.
 * Created by the schedule() function to execute an action on a schedule.
 *
 * Phase 0f.
 *
 * @param frequency The schedule frequency (daily, hourly, weekly)
 * @param action The lambda to execute on schedule
 * @param atTime Optional specific time for daily/weekly schedules (e.g., "09:00")
 */
data class ScheduleVal(
    val frequency: ScheduleFrequency,
    val action: LambdaVal,
    val atTime: String? = null
) : DslValue() {
    override val typeName: String = "schedule"

    override fun toDisplayString(): String {
        val timeStr = atTime?.let { " at $it" } ?: ""
        return "[Schedule: ${frequency.identifier}$timeStr]"
    }

    override fun serializeValue(): Any = mapOf(
        "frequency" to frequency.identifier,
        "atTime" to atTime
        // action (lambda) cannot be serialized
    )

    companion object {
        /**
         * Deserialize a ScheduleVal from a Firestore map.
         * The action lambda cannot be restored, so a placeholder is used.
         */
        fun deserialize(map: Map<String, Any?>): ScheduleVal {
            val frequencyId = map["frequency"] as? String ?: "daily"
            val frequency = ScheduleFrequency.fromIdentifier(frequencyId) ?: ScheduleFrequency.DAILY
            val atTime = map["atTime"] as? String
            // Create a placeholder lambda - the actual action must be re-executed from source
            val placeholderLambda = LambdaVal(
                params = emptyList(),
                body = StringLiteral("Placeholder - re-execute from source", 0),
                capturedEnv = Environment()
            )
            return ScheduleVal(frequency, placeholderLambda, atTime)
        }
    }
}
