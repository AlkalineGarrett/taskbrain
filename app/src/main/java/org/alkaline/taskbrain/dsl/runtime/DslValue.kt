@file:Suppress("unused")
package org.alkaline.taskbrain.dsl.runtime

/**
 * Re-exports from the values package for backward compatibility.
 * All Mindl value types have been moved to org.alkaline.taskbrain.dsl.runtime.values.
 */

// Re-export the sealed base class and companion
typealias DslValue = org.alkaline.taskbrain.dsl.runtime.values.DslValue

// Re-export primitive values
typealias UndefinedVal = org.alkaline.taskbrain.dsl.runtime.values.UndefinedVal
typealias NumberVal = org.alkaline.taskbrain.dsl.runtime.values.NumberVal
typealias StringVal = org.alkaline.taskbrain.dsl.runtime.values.StringVal
typealias BooleanVal = org.alkaline.taskbrain.dsl.runtime.values.BooleanVal

// Re-export temporal values
typealias DateVal = org.alkaline.taskbrain.dsl.runtime.values.DateVal
typealias TimeVal = org.alkaline.taskbrain.dsl.runtime.values.TimeVal
typealias DateTimeVal = org.alkaline.taskbrain.dsl.runtime.values.DateTimeVal

// Re-export pattern value
typealias PatternVal = org.alkaline.taskbrain.dsl.runtime.values.PatternVal

// Re-export note value
typealias NoteVal = org.alkaline.taskbrain.dsl.runtime.values.NoteVal

// Re-export list value
typealias ListVal = org.alkaline.taskbrain.dsl.runtime.values.ListVal

// Re-export lambda value (Milestone 8)
typealias LambdaVal = org.alkaline.taskbrain.dsl.runtime.values.LambdaVal

// Re-export view value (Milestone 10)
typealias ViewVal = org.alkaline.taskbrain.dsl.runtime.values.ViewVal

// Re-export action values (Phase 0f)
typealias ButtonVal = org.alkaline.taskbrain.dsl.runtime.values.ButtonVal
typealias ScheduleVal = org.alkaline.taskbrain.dsl.runtime.values.ScheduleVal
typealias ScheduleFrequency = org.alkaline.taskbrain.dsl.runtime.values.ScheduleFrequency

// Re-export alarm value (alarm identity)
typealias AlarmVal = org.alkaline.taskbrain.dsl.runtime.values.AlarmVal
