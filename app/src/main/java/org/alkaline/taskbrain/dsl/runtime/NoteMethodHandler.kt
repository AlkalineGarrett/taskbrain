package org.alkaline.taskbrain.dsl.runtime

import org.alkaline.taskbrain.dsl.runtime.values.DslValue
import org.alkaline.taskbrain.dsl.runtime.values.NoteVal
import org.alkaline.taskbrain.dsl.runtime.values.NumberVal
import org.alkaline.taskbrain.dsl.runtime.values.StringVal

/**
 * Handles method calls for NoteVal.
 *
 * Supported methods:
 * - `append(text)`: Append text to the note's content
 * - `up()`: Returns parent note (1 level up); same as .up property
 * - `up(n)`: Returns ancestor n levels up; returns undefined if exceeds hierarchy
 */
object NoteMethodHandler {

    /**
     * Call a method on the note.
     */
    fun callMethod(
        noteVal: NoteVal,
        methodName: String,
        args: Arguments,
        env: Environment,
        position: Int
    ): DslValue {
        return when (methodName) {
            "up" -> handleUp(noteVal, args, position, env)
            "append" -> handleAppend(noteVal, args, env, position)
            else -> throw ExecutionException(
                "Unknown method '$methodName' on note",
                position
            )
        }
    }

    private fun handleUp(
        noteVal: NoteVal,
        args: Arguments,
        position: Int,
        env: Environment
    ): DslValue {
        // up() with no args defaults to 1 level (parent)
        // up(n) goes up n levels
        val levels = if (args.positional.isEmpty()) {
            1
        } else {
            val arg = args.require(0, "levels")
            (arg as? NumberVal)?.value?.toInt()
                ?: throw ExecutionException("'up' expects a number argument", position)
        }
        return NotePropertyHandler.getUp(noteVal.note, levels, env)
    }

    private fun handleAppend(
        noteVal: NoteVal,
        args: Arguments,
        env: Environment,
        position: Int
    ): DslValue {
        val text = args.require(0, "text")
        // Accept any value and convert to display string
        val textStr = when (text) {
            is StringVal -> text.value
            else -> text.toDisplayString()
        }

        val ops = env.getNoteOperations()
            ?: throw ExecutionException(
                "Cannot append to note: note operations not available",
                position
            )

        val updatedNote = kotlinx.coroutines.runBlocking {
            ops.appendToNote(noteVal.note.id, textStr)
        }
        env.registerMutation(NoteMutation(noteVal.note.id, updatedNote, MutationType.CONTENT_APPENDED, appendedText = textStr))
        return NoteVal(updatedNote)
    }
}
