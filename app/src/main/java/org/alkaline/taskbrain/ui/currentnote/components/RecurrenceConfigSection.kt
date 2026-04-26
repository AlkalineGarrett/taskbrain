package org.alkaline.taskbrain.ui.currentnote.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.alkaline.taskbrain.R
import com.google.firebase.Timestamp
import org.alkaline.taskbrain.data.RecurrencePreset
import org.alkaline.taskbrain.data.RecurrenceType
import org.alkaline.taskbrain.ui.components.DateTimePickerRow
import org.alkaline.taskbrain.util.HOUR_MS
import java.util.Calendar

/**
 * State holder for recurrence configuration in the alarm dialog.
 */
data class RecurrenceConfig(
    val enabled: Boolean = false,
    val recurrenceType: RecurrenceType = RecurrenceType.FIXED,

    // Fixed (RRULE) config
    val selectedPreset: RecurrencePreset? = RecurrencePreset.DAILY,
    val customInterval: Int = 1,
    val customFrequency: CustomFrequency = CustomFrequency.DAYS,
    val selectedDays: Set<Int> = emptySet(),

    // Relative config
    val relativeInterval: Int = 1,
    val relativeUnit: RelativeUnit = RelativeUnit.DAYS,

    // End condition
    val endType: EndType = EndType.NEVER,
    val endDate: Timestamp? = null,
    val repeatCount: Int = 10
)

enum class CustomFrequency(val label: String) {
    DAYS("Days"),
    WEEKS("Weeks")
}

enum class RelativeUnit(val label: String, val toMs: Long) {
    HOURS("Hours", HOUR_MS),
    DAYS("Days", 24 * HOUR_MS),
    WEEKS("Weeks", 7 * 24 * HOUR_MS)
}

enum class EndType(val label: String) {
    NEVER("Never ends"),
    ON_DATE("Until date"),
    AFTER_COUNT("N times")
}

private val DAY_LABELS = listOf(
    Calendar.MONDAY to "M",
    Calendar.TUESDAY to "T",
    Calendar.WEDNESDAY to "W",
    Calendar.THURSDAY to "T",
    Calendar.FRIDAY to "F",
    Calendar.SATURDAY to "S",
    Calendar.SUNDAY to "S"
)

private val SegmentedButtonFontSize = 12.sp

/**
 * UI section for configuring alarm recurrence.
 * Shown inside AlarmConfigDialog when creating a new alarm.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecurrenceConfigSection(
    config: RecurrenceConfig,
    onConfigChange: (RecurrenceConfig) -> Unit,
    modifier: Modifier = Modifier,
    showToggle: Boolean = true
) {
    Column(modifier = modifier.padding(horizontal = 16.dp)) {
        if (showToggle) {
            // Enable/disable toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.recurrence_repeat),
                    style = MaterialTheme.typography.bodyLarge
                )
                Switch(
                    checked = config.enabled,
                    onCheckedChange = { onConfigChange(config.copy(enabled = it)) }
                )
            }

            if (!config.enabled) return
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Recurrence type toggle
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = config.recurrenceType == RecurrenceType.FIXED,
                onClick = { onConfigChange(config.copy(recurrenceType = RecurrenceType.FIXED)) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
            ) { Text(stringResource(R.string.recurrence_fixed_schedule), fontSize = SegmentedButtonFontSize) }
            SegmentedButton(
                selected = config.recurrenceType == RecurrenceType.RELATIVE,
                onClick = { onConfigChange(config.copy(recurrenceType = RecurrenceType.RELATIVE)) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
            ) { Text(stringResource(R.string.recurrence_after_completion), fontSize = SegmentedButtonFontSize) }
        }

        Spacer(modifier = Modifier.height(12.dp))

        when (config.recurrenceType) {
            RecurrenceType.FIXED -> FixedRecurrenceConfig(config, onConfigChange)
            RecurrenceType.RELATIVE -> RelativeRecurrenceConfig(config, onConfigChange)
        }

        Spacer(modifier = Modifier.height(12.dp))

        // End condition
        EndConditionConfig(config, onConfigChange)
    }
}

@Composable
private fun FixedRecurrenceConfig(
    config: RecurrenceConfig,
    onConfigChange: (RecurrenceConfig) -> Unit
) {
    val options = RecurrencePreset.entries.map { it.label } + stringResource(R.string.recurrence_custom)
    val selectedIndex = config.selectedPreset?.let { RecurrencePreset.entries.indexOf(it) }
        ?: options.size - 1
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, label ->
            SegmentedButton(
                selected = index == selectedIndex,
                onClick = {
                    val preset = RecurrencePreset.entries.getOrNull(index)
                    onConfigChange(config.copy(
                        selectedPreset = preset,
                        selectedDays = if (preset != null) emptySet() else config.selectedDays
                    ))
                },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size)
            ) { Text(label, fontSize = SegmentedButtonFontSize) }
        }
    }

    // Custom interval (shown when no preset selected)
    if (config.selectedPreset == null) {
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.recurrence_custom_interval),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(stringResource(R.string.recurrence_every))
            OutlinedTextField(
                value = config.customInterval.toString(),
                onValueChange = { text ->
                    val value = text.toIntOrNull()
                    if (value != null && value > 0) {
                        onConfigChange(config.copy(customInterval = value))
                    }
                },
                modifier = Modifier.width(64.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )
            SingleChoiceSegmentedButtonRow {
                CustomFrequency.entries.forEachIndexed { index, freq ->
                    SegmentedButton(
                        selected = config.customFrequency == freq,
                        onClick = { onConfigChange(config.copy(customFrequency = freq)) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = CustomFrequency.entries.size
                        )
                    ) { Text(freq.label, fontSize = SegmentedButtonFontSize) }
                }
            }
        }

        // Day-of-week picker (only for weekly custom)
        if (config.customFrequency == CustomFrequency.WEEKS) {
            Spacer(modifier = Modifier.height(8.dp))
            DayOfWeekPicker(config.selectedDays) { days ->
                onConfigChange(config.copy(selectedDays = days))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DayOfWeekPicker(
    selectedDays: Set<Int>,
    onDaysChange: (Set<Int>) -> Unit
) {
    Text(
        text = stringResource(R.string.recurrence_on_days),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(4.dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        DAY_LABELS.forEach { (calDay, label) ->
            FilterChip(
                selected = calDay in selectedDays,
                onClick = {
                    val newDays = if (calDay in selectedDays) {
                        selectedDays - calDay
                    } else {
                        selectedDays + calDay
                    }
                    onDaysChange(newDays)
                },
                label = { Text(label) },
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            )
        }
    }
}

@Composable
private fun RelativeRecurrenceConfig(
    config: RecurrenceConfig,
    onConfigChange: (RecurrenceConfig) -> Unit
) {
    Text(
        text = stringResource(R.string.recurrence_interval_after_completion),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(4.dp))

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = config.relativeInterval.toString(),
            onValueChange = { text ->
                val value = text.toIntOrNull()
                if (value != null && value > 0) {
                    onConfigChange(config.copy(relativeInterval = value))
                }
            },
            modifier = Modifier.width(64.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true
        )

        SingleChoiceSegmentedButtonRow {
            RelativeUnit.entries.forEachIndexed { index, unit ->
                SegmentedButton(
                    selected = config.relativeUnit == unit,
                    onClick = { onConfigChange(config.copy(relativeUnit = unit)) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = RelativeUnit.entries.size
                    )
                ) { Text(unit.label, fontSize = SegmentedButtonFontSize) }
            }
        }
    }
}

@Composable
private fun EndConditionConfig(
    config: RecurrenceConfig,
    onConfigChange: (RecurrenceConfig) -> Unit
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        EndType.entries.forEachIndexed { index, endType ->
            SegmentedButton(
                selected = config.endType == endType,
                onClick = { onConfigChange(config.copy(endType = endType)) },
                shape = SegmentedButtonDefaults.itemShape(
                    index = index,
                    count = EndType.entries.size
                )
            ) { Text(endType.label, fontSize = SegmentedButtonFontSize) }
        }
    }

    when (config.endType) {
        EndType.NEVER -> { /* nothing */ }
        EndType.ON_DATE -> {
            Spacer(modifier = Modifier.height(4.dp))
            DateTimePickerRow(
                label = "End date",
                value = config.endDate,
                onValueChange = { onConfigChange(config.copy(endDate = it)) },
                showDelete = true
            )
        }
        EndType.AFTER_COUNT -> {
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(stringResource(R.string.recurrence_after))
                OutlinedTextField(
                    value = config.repeatCount.toString(),
                    onValueChange = { text ->
                        val value = text.toIntOrNull()
                        if (value != null && value > 0) {
                            onConfigChange(config.copy(repeatCount = value))
                        }
                    },
                    modifier = Modifier.width(64.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                Text(stringResource(R.string.recurrence_times))
            }
        }
    }
}
