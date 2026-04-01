package org.alkaline.taskbrain.dsl.runtime.values

import org.alkaline.taskbrain.dsl.language.Expression
import org.alkaline.taskbrain.dsl.runtime.Environment

/**
 * A lambda value that captures its defining environment.
 * Used for functional operations like filtering with find(where: [...]).
 *
 * Lambdas cannot be serialized to Firestore - they are runtime-only values.
 */
data class LambdaVal(
    val params: List<String>,
    val body: Expression,
    val capturedEnv: Environment
) : DslValue() {
    override val typeName: String = "lambda"

    override fun toDisplayString(): String = "<lambda(${params.joinToString()})>"

    override fun serializeValue(): Any {
        throw UnsupportedOperationException("Lambdas cannot be serialized")
    }
}
