package org.alkaline.taskbrain.dsl.runtime

import org.alkaline.taskbrain.data.Note
import org.alkaline.taskbrain.dsl.runtime.values.DslValue
import org.alkaline.taskbrain.dsl.runtime.values.NoteVal
import java.time.LocalDateTime

/**
 * Execution environment for Mindl evaluation.
 * Holds variable bindings, context data, and resources for directive execution.
 *
 * Minimal implementation with variables and scopes.
 * Adds note list for find() operations.
 * Adds current note for [.] reference and property access.
 * Adds note operations for mutations and hierarchy navigation.
 * Adds view stack for circular dependency detection.
 */
class Environment private constructor(
    private val parent: Environment?,
    private val context: NoteContext
) {
    /**
     * Create a root environment with the given context.
     */
    constructor(context: NoteContext = NoteContext.EMPTY) : this(null, context)

    private val variables = mutableMapOf<String, DslValue>()

    /**
     * Mutations that occurred during directive execution.
     * Only tracked at the root environment; child environments delegate to parent.
     */
    private val mutations = mutableListOf<NoteMutation>()

    /**
     * Define a variable in the current scope.
     */
    fun define(name: String, value: DslValue) {
        variables[name] = value
    }

    /**
     * Look up a variable, searching parent scopes if not found locally.
     * @return The value, or null if not found
     */
    fun get(name: String): DslValue? {
        return variables[name] ?: parent?.get(name)
    }

    /**
     * Create a child environment for nested scopes.
     * Inherits the note context from the parent.
     *
     * Propagates executor for lambda invocation.
     * Propagates view stack for circular dependency detection.
     * Propagates onceCache for once[...] expression caching.
     * Propagates mockedTime for trigger verification.
     * Propagates cachedExecutor for transitive dependency tracking.
     */
    fun child(): Environment = Environment(
        parent = this,
        context = NoteContext(
            notes = getNotes(),
            currentNote = getCurrentNoteRaw(),
            noteOperations = getNoteOperations(),
            executor = getExecutor(),
            viewStack = getViewStack(),
            onceCache = getOnceCache(),
            mockedTime = getMockedTime(),
            cachedExecutor = getCachedExecutor()
        )
    )

    /**
     * Capture the current environment for closures.
     */
    fun capture(): Environment = this

    /**
     * Get the list of notes available for find() operations.
     * Searches up the parent chain if not set locally.
     */
    fun getNotes(): List<Note>? = context.notes ?: parent?.getNotes()

    /**
     * Get the current note as a NoteVal for [.] reference.
     * Searches up the parent chain if not set locally.
     */
    fun getCurrentNote(): NoteVal? = getCurrentNoteRaw()?.let { NoteVal(it) }

    /**
     * Get the raw current note (without wrapping in NoteVal).
     * Used internally for environment propagation and view rendering.
     */
    internal fun getCurrentNoteRaw(): Note? = context.currentNote ?: parent?.getCurrentNoteRaw()

    /**
     * Get the note operations interface for performing mutations.
     * Searches up the parent chain if not set locally.
     */
    fun getNoteOperations(): NoteOperations? = context.noteOperations ?: parent?.getNoteOperations()

    /**
     * Get the executor for lambda invocation.
     * Searches up the parent chain if not set locally.
     */
    fun getExecutor(): Executor? = context.executor ?: parent?.getExecutor()

    /**
     * Create a child environment with an executor set.
     * Used by Executor to inject itself for lambda invocation.
     */
    fun withExecutor(executor: Executor): Environment = Environment(
        parent = this,
        context = NoteContext(
            notes = getNotes(),
            currentNote = getCurrentNoteRaw(),
            noteOperations = getNoteOperations(),
            executor = executor,
            viewStack = getViewStack(),
            onceCache = getOnceCache(),
            mockedTime = getMockedTime(),
            cachedExecutor = getCachedExecutor()
        )
    )

    // region Cached Executor

    /**
     * Get the cached executor for nested directive execution.
     * Used by view() to execute nested directives with proper dependency tracking.
     */
    fun getCachedExecutor(): CachedExecutorInterface? =
        context.cachedExecutor ?: parent?.getCachedExecutor()

    /**
     * Create a child environment with a cached executor set.
     * Used by CachedDirectiveExecutor to inject itself for nested execution.
     */
    fun withCachedExecutor(cachedExecutor: CachedExecutorInterface): Environment = Environment(
        parent = this,
        context = NoteContext(
            notes = getNotes(),
            currentNote = getCurrentNoteRaw(),
            noteOperations = getNoteOperations(),
            executor = getExecutor(),
            viewStack = getViewStack(),
            onceCache = getOnceCache(),
            mockedTime = getMockedTime(),
            cachedExecutor = cachedExecutor
        )
    )

    // endregion

    // region OnceCache

    /**
     * Get the once cache for caching once[...] expression results.
     * Searches up the parent chain if not set locally.
     */
    fun getOnceCache(): OnceCache? = context.onceCache ?: parent?.getOnceCache()

    /**
     * Get the once cache, creating a default in-memory cache if none exists.
     * This ensures once[...] expressions always have a cache available.
     */
    fun getOrCreateOnceCache(): OnceCache = getOnceCache() ?: InMemoryOnceCache()

    // endregion

    // region View Stack

    /**
     * Get the current view stack.
     * Used for circular dependency detection in view().
     */
    fun getViewStack(): List<String> = context.viewStack.ifEmpty { parent?.getViewStack() ?: emptyList() }

    /**
     * Check if a note ID is already in the view stack.
     * Used for circular dependency detection.
     */
    fun isInViewStack(noteId: String): Boolean = getViewStack().contains(noteId)

    /**
     * Get a formatted string of the view stack path for error messages.
     * Example: "note1 → note2 → note3"
     */
    fun getViewStackPath(): String = getViewStack().joinToString(" → ")

    /**
     * Create a child environment with a note ID added to the view stack.
     * Used when entering a view to track the dependency chain.
     */
    fun pushViewStack(noteId: String): Environment = Environment(
        parent = this,
        context = NoteContext(
            notes = getNotes(),
            currentNote = getCurrentNoteRaw(),
            noteOperations = getNoteOperations(),
            executor = getExecutor(),
            viewStack = getViewStack() + noteId,
            onceCache = getOnceCache(),
            mockedTime = getMockedTime(),
            cachedExecutor = getCachedExecutor()
        )
    )

    // endregion

    // region Mocked Time

    /**
     * Get the mocked time for trigger verification.
     * Returns null if not mocking time (use real time).
     */
    fun getMockedTime(): LocalDateTime? = context.mockedTime ?: parent?.getMockedTime()

    /**
     * Create a child environment with mocked time for trigger verification.
     * When mocked time is set, date/time/datetime functions return values based on it.
     */
    fun withMockedTime(time: LocalDateTime): Environment = Environment(
        parent = this,
        context = NoteContext(
            notes = getNotes(),
            currentNote = getCurrentNoteRaw(),
            noteOperations = getNoteOperations(),
            executor = getExecutor(),
            viewStack = getViewStack(),
            onceCache = getOnceCache(),
            mockedTime = time,
            cachedExecutor = getCachedExecutor()
        )
    )

    // endregion

    /**
     * Find a note by ID from the available notes.
     * Used for hierarchy navigation (.up, .root).
     */
    fun getNoteById(noteId: String): Note? {
        return getNotes()?.find { it.id == noteId }
    }

    /**
     * Get the parent note of a given note.
     * Returns null if the note has no parent or parent is not found.
     * Used for hierarchy navigation (.up).
     */
    fun getParentNote(note: Note): Note? {
        val parentId = note.parentNoteId ?: return null
        return getNoteById(parentId)
    }

    // region Mutation Tracking

    /**
     * Register a mutation that occurred during directive execution.
     * Mutations are tracked at the root environment for later retrieval.
     */
    fun registerMutation(mutation: NoteMutation) {
        if (parent != null) {
            // Delegate to root environment
            parent.registerMutation(mutation)
        } else {
            mutations.add(mutation)
        }
    }

    /**
     * Get all mutations that occurred during directive execution.
     * Should be called on the root environment after execution completes.
     */
    fun getMutations(): List<NoteMutation> {
        return if (parent != null) {
            parent.getMutations()
        } else {
            mutations.toList()
        }
    }

    /**
     * Clear all tracked mutations.
     * Called after mutations have been processed.
     */
    fun clearMutations() {
        if (parent != null) {
            parent.clearMutations()
        } else {
            mutations.clear()
        }
    }

    // endregion

    companion object {
        /**
         * Create an environment with the given note context.
         */
        fun withContext(context: NoteContext): Environment = Environment(context)

        // Convenience factory methods for common use cases

        fun withNotes(notes: List<Note>): Environment =
            Environment(NoteContext(notes = notes))

        fun withCurrentNote(currentNote: Note): Environment =
            Environment(NoteContext(currentNote = currentNote))

        fun withNotesAndCurrentNote(notes: List<Note>, currentNote: Note): Environment =
            Environment(NoteContext(notes = notes, currentNote = currentNote))

        fun withNoteOperations(noteOperations: NoteOperations): Environment =
            Environment(NoteContext(noteOperations = noteOperations))

        fun withAll(
            notes: List<Note>,
            currentNote: Note,
            noteOperations: NoteOperations
        ): Environment = Environment(NoteContext(notes = notes, currentNote = currentNote, noteOperations = noteOperations))
    }
}
