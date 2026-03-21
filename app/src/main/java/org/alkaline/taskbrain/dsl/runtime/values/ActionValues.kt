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
 * @param precise Whether to use exact timing (AlarmManager) vs approximate (WorkManager)
 */
/**
 * An alarm reference value that renders as an alarm symbol (⏰) in the editor.
 * Created by the alarm() function to link a directive to a specific alarm document.
 *
 * Unlike ButtonVal/ScheduleVal, AlarmVal has no lambda — it's a pure reference to
 * an existing alarm document. The alarm ID is the Firestore document ID.
 *
 * @param alarmId The Firestore alarm document ID
 */
data class AlarmVal(
    val alarmId: String
) : DslValue() {
    override val typeName: String = "alarm"

    override fun toDisplayString(): String = "⏰"

    override fun serializeValue(): Any = mapOf(
        "alarmId" to alarmId
    )

    companion object {
        fun deserialize(map: Map<String, Any?>): AlarmVal {
            val alarmId = map["alarmId"] as? String ?: ""
            return AlarmVal(alarmId)
        }
    }
}

data class ScheduleVal(
    val frequency: ScheduleFrequency,
    val action: LambdaVal,
    val atTime: String? = null,
    val precise: Boolean = false
) : DslValue() {
    override val typeName: String = "schedule"

    override fun toDisplayString(): String {
        val timeStr = atTime?.let { " at $it" } ?: ""
        val preciseStr = if (precise) " (precise)" else ""
        return "[Schedule: ${frequency.identifier}$timeStr$preciseStr]"
    }

    override fun serializeValue(): Any = mapOf(
        "frequency" to frequency.identifier,
        "atTime" to atTime,
        "precise" to precise
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
            val precise = map["precise"] as? Boolean ?: false
            // Create a placeholder lambda - the actual action must be re-executed from source
            val placeholderLambda = LambdaVal(
                params = emptyList(),
                body = StringLiteral("Placeholder - re-execute from source", 0),
                capturedEnv = Environment()
            )
            return ScheduleVal(frequency, placeholderLambda, atTime, precise)
        }
    }
}
