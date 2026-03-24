package org.alkaline.taskbrain.dsl.directives

import android.util.Log
import org.alkaline.taskbrain.data.Note
import org.alkaline.taskbrain.dsl.language.IdempotencyAnalyzer
import org.alkaline.taskbrain.dsl.language.Lexer
import org.alkaline.taskbrain.dsl.language.LexerException
import org.alkaline.taskbrain.dsl.language.ParseException
import org.alkaline.taskbrain.dsl.language.Parser
import org.alkaline.taskbrain.dsl.runtime.CachedExecutorInterface
import org.alkaline.taskbrain.dsl.runtime.Environment
import org.alkaline.taskbrain.dsl.runtime.ExecutionException
import org.alkaline.taskbrain.dsl.runtime.Executor
import org.alkaline.taskbrain.dsl.runtime.LambdaVal
import org.alkaline.taskbrain.dsl.runtime.NoteContext
import org.alkaline.taskbrain.dsl.runtime.NoteMutation
import org.alkaline.taskbrain.dsl.runtime.NoteOperations
import org.alkaline.taskbrain.dsl.runtime.PatternVal

private const val TAG = "DirectiveFinder"

/**
 * Result of executing a directive, including any mutations that occurred.
 * The result is for storage/display; mutations are for propagating changes.
 */
data class DirectiveExecutionResult(
    val result: DirectiveResult,
    val mutations: List<NoteMutation>
)

/**
 * Utility for finding directives in note content.
 *
 * A directive is text enclosed in square brackets: [...]
 * Milestone 1: Simple non-nested matching with \[.*?\]
 * Milestone 6: Adds current note context for [.] reference.
 * Milestone 7: Adds note operations for mutations.
 */
object DirectiveFinder {

    /**
     * Creates a unique key for a directive based on its line identity and position.
     * Produces `"lineId:startOffset"` which is stable across line reordering.
     *
     * @param lineId The line's effective ID — either a Firestore noteId or a temporary UUID.
     *   Must never be line-index-based.
     */
    fun directiveKey(lineId: String, startOffset: Int): String =
        "$lineId:$startOffset"

    /**
     * Extracts the startOffset from a directive key regardless of format.
     */
    fun startOffsetFromKey(key: String): Int? =
        key.substringAfterLast(':').toIntOrNull()

    /**
     * A located directive in note content.
     *
     * @property sourceText The full directive text including brackets
     * @property startOffset The character offset where the directive starts
     * @property endOffset The character offset where the directive ends (exclusive)
     */
    data class FoundDirective(
        val sourceText: String,
        val startOffset: Int,
        val endOffset: Int
    ) {
        /**
         * Compute a hash for this directive for storage lookups.
         */
        fun hash(): String = DirectiveResult.hashDirective(sourceText)
    }

    /**
     * Find all directives in the given content.
     * Handles nested brackets for lambda syntax: [lambda[...]]
     *
     * Milestone 8: Updated to support nested brackets.
     *
     * @param content The note content to search
     * @return List of found directives in order of appearance
     */
    fun findDirectives(content: String): List<FoundDirective> {
        val directives = mutableListOf<FoundDirective>()
        var i = 0

        while (i < content.length) {
            if (content[i] == '[') {
                val startOffset = i
                var depth = 1
                i++

                // Find matching closing bracket, tracking nesting depth
                while (i < content.length && depth > 0) {
                    when (content[i]) {
                        '[' -> depth++
                        ']' -> depth--
                    }
                    i++
                }

                // Only add if we found a matching closing bracket
                if (depth == 0) {
                    directives.add(
                        FoundDirective(
                            sourceText = content.substring(startOffset, i),
                            startOffset = startOffset,
                            endOffset = i
                        )
                    )
                }
            } else {
                i++
            }
        }

        return directives
    }

    /**
     * Check if the given text contains any directives.
     */
    fun containsDirectives(content: String): Boolean {
        return findDirectives(content).isNotEmpty()
    }

    /**
     * Parse and execute a single directive, returning the result and any mutations.
     *
     * @param sourceText The directive source text (including brackets)
     * @param notes Optional list of notes for find() operations
     * @param currentNote Optional current note for [.] reference (Milestone 6)
     * @param noteOperations Optional note operations for mutations (Milestone 7)
     * @param viewStack Optional view stack for circular dependency detection (Milestone 10)
     * @param cachedExecutor Optional cached executor for nested directive execution (Phase 1)
     * @return DirectiveExecutionResult containing the result and any mutations that occurred
     */
    fun executeDirective(
        sourceText: String,
        notes: List<Note>? = null,
        currentNote: Note? = null,
        noteOperations: NoteOperations? = null,
        viewStack: List<String> = emptyList(),
        cachedExecutor: CachedExecutorInterface? = null
    ): DirectiveExecutionResult {
        Log.d(TAG, "executeDirective: sourceText='$sourceText'")
        val env = createEnvironment(notes, currentNote, noteOperations, viewStack, cachedExecutor)
        return try {
            val tokens = Lexer(sourceText).tokenize()
            Log.d(TAG, "executeDirective: tokenized successfully, ${tokens.size} tokens")
            val directive = Parser(tokens, sourceText).parseDirective()
            Log.d(TAG, "executeDirective: parsed successfully")

            // Check idempotency before execution
            val idempotencyResult = IdempotencyAnalyzer.analyze(directive.expression)
            Log.d(TAG, "executeDirective: idempotency check - isIdempotent=${idempotencyResult.isIdempotent}, reason=${idempotencyResult.nonIdempotentReason}")
            if (!idempotencyResult.isIdempotent) {
                return DirectiveExecutionResult(
                    DirectiveResult.failure(idempotencyResult.nonIdempotentReason ?: "Non-idempotent operation"),
                    emptyList()
                )
            }

            // Check for unwrapped mutations (property assignments must be in once[])
            val mutationResult = IdempotencyAnalyzer.checkUnwrappedMutations(directive.expression)
            Log.d(TAG, "executeDirective: mutation check - isIdempotent=${mutationResult.isIdempotent}, reason=${mutationResult.nonIdempotentReason}")
            if (!mutationResult.isIdempotent) {
                return DirectiveExecutionResult(
                    DirectiveResult.failure(mutationResult.nonIdempotentReason ?: "Unwrapped mutation"),
                    emptyList()
                )
            }

            val value = Executor().execute(directive, env)
            Log.d(TAG, "executeDirective: executed successfully, value type=${value::class.simpleName}")

            // Check for no-effect values that can't be meaningfully displayed
            val warningType = checkNoEffectValue(value)
            if (warningType != null) {
                Log.d(TAG, "executeDirective: no-effect warning - $warningType")
                return DirectiveExecutionResult(
                    DirectiveResult.warning(warningType),
                    env.getMutations()
                )
            }

            DirectiveExecutionResult(DirectiveResult.success(value), env.getMutations())
        } catch (e: LexerException) {
            Log.e(TAG, "executeDirective: LexerException", e)
            DirectiveExecutionResult(DirectiveResult.failure("Lexer error: ${e.message}"), env.getMutations())
        } catch (e: ParseException) {
            Log.e(TAG, "executeDirective: ParseException", e)
            DirectiveExecutionResult(DirectiveResult.failure("Parse error: ${e.message}"), env.getMutations())
        } catch (e: ExecutionException) {
            Log.e(TAG, "executeDirective: ExecutionException", e)
            DirectiveExecutionResult(DirectiveResult.failure("Execution error: ${e.message}"), env.getMutations())
        } catch (e: Exception) {
            Log.e(TAG, "executeDirective: Unexpected Exception", e)
            DirectiveExecutionResult(DirectiveResult.failure("Unexpected error: ${e.message}"), env.getMutations())
        }
    }

    /**
     * Create an environment with the appropriate context.
     *
     * Phase 1 (Caching Audit): Added cachedExecutor parameter for transitive dependency tracking.
     */
    private fun createEnvironment(
        notes: List<Note>?,
        currentNote: Note?,
        noteOperations: NoteOperations?,
        viewStack: List<String> = emptyList(),
        cachedExecutor: CachedExecutorInterface? = null
    ): Environment {
        return Environment(
            NoteContext(
                notes = notes,
                currentNote = currentNote,
                noteOperations = noteOperations,
                viewStack = viewStack,
                cachedExecutor = cachedExecutor
            )
        )
    }

    /**
     * Check if a value is a no-effect value that produces a warning.
     * These are values that can't be meaningfully displayed or stored.
     *
     * Milestone 8.
     */
    private fun checkNoEffectValue(value: org.alkaline.taskbrain.dsl.runtime.DslValue): DirectiveWarningType? {
        return when (value) {
            is LambdaVal -> DirectiveWarningType.NO_EFFECT_LAMBDA
            is PatternVal -> DirectiveWarningType.NO_EFFECT_PATTERN
            else -> null
        }
    }

    /**
     * Find, parse, and execute all directives in the content.
     *
     * @param content The note content (single line)
     * @param lineId The line's effective ID (noteId or temp UUID)
     * @param notes Optional list of notes for find() operations
     * @param currentNote Optional current note for [.] reference (Milestone 6)
     * @return Map of directive position key to execution result
     */
    fun executeAllDirectives(
        content: String,
        lineId: String,
        notes: List<Note>? = null,
        currentNote: Note? = null
    ): Map<String, DirectiveResult> {
        return findDirectives(content).associate { found ->
            DirectiveResult.hashDirective(found.sourceText) to
                executeDirective(found.sourceText, notes, currentNote).result
        }
    }
}
