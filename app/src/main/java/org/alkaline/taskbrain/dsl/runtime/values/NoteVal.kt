package org.alkaline.taskbrain.dsl.runtime.values

import com.google.firebase.Timestamp
import org.alkaline.taskbrain.data.Note
import org.alkaline.taskbrain.dsl.runtime.Arguments
import org.alkaline.taskbrain.dsl.runtime.Environment
import org.alkaline.taskbrain.dsl.runtime.NoteMethodHandler
import org.alkaline.taskbrain.dsl.runtime.NotePropertyHandler

/**
 * A note value, wrapping a Note object from the data layer.
 * Created by the find() function or note references.
 *
 * Basic NoteVal with serialization.
 * Adds property access for [.path], [.created], etc.
 * Adds property setting and method calls (.append).
 *
 * Property access and method calls are delegated to [NotePropertyHandler] and [NoteMethodHandler].
 */
data class NoteVal(val note: Note) : DslValue() {
    override val typeName: String = "note"

    override fun toDisplayString(): String {
        // Display the path if set, otherwise the first line of content, otherwise the id
        return when {
            note.path.isNotEmpty() -> note.path
            note.content.isNotEmpty() -> note.content.lines().firstOrNull() ?: note.id
            else -> note.id
        }
    }

    override fun serializeValue(): Any = mapOf(
        "id" to note.id,
        "userId" to note.userId,
        "path" to note.path,
        "content" to note.content,
        "createdAt" to note.createdAt?.toDate()?.time,
        "updatedAt" to note.updatedAt?.toDate()?.time,
    )

    /**
     * Get a property value from the note.
     * Delegates to [NotePropertyHandler].
     */
    fun getProperty(property: String, env: Environment): DslValue =
        NotePropertyHandler.getProperty(this, property, env)

    /**
     * Set a property value on the note.
     * Delegates to [NotePropertyHandler].
     */
    fun setProperty(property: String, value: DslValue, env: Environment) =
        NotePropertyHandler.setProperty(this, property, value, env)

    /**
     * Call a method on the note.
     * Delegates to [NoteMethodHandler].
     */
    fun callMethod(
        methodName: String,
        args: Arguments,
        env: Environment,
        position: Int
    ): DslValue = NoteMethodHandler.callMethod(this, methodName, args, env, position)

    companion object {
        /**
         * Deserialize a NoteVal from a Firestore map.
         */
        fun deserialize(map: Map<String, Any?>): NoteVal {
            val createdAtMillis = map["createdAt"] as? Long
            val updatedAtMillis = map["updatedAt"] as? Long

            val note = Note(
                id = map["id"] as? String ?: "",
                userId = map["userId"] as? String ?: "",
                path = map["path"] as? String ?: "",
                content = map["content"] as? String ?: "",
                createdAt = createdAtMillis?.let { Timestamp(java.util.Date(it)) },
                updatedAt = updatedAtMillis?.let { Timestamp(java.util.Date(it)) },
            )
            return NoteVal(note)
        }
    }
}
