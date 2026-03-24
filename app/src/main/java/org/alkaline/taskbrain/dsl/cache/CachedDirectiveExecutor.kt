package org.alkaline.taskbrain.dsl.cache

import org.alkaline.taskbrain.data.Note
import org.alkaline.taskbrain.dsl.directives.DirectiveFinder
import org.alkaline.taskbrain.dsl.directives.DirectiveResult
import org.alkaline.taskbrain.dsl.language.Lexer
import org.alkaline.taskbrain.dsl.language.LexerException
import org.alkaline.taskbrain.dsl.language.ParseException
import org.alkaline.taskbrain.dsl.language.Parser
import org.alkaline.taskbrain.dsl.runtime.CachedExecutionResultInterface
import org.alkaline.taskbrain.dsl.runtime.CachedExecutorInterface
import org.alkaline.taskbrain.dsl.runtime.NoteOperations
import org.alkaline.taskbrain.dsl.runtime.values.ViewVal

/**
 * Integrates directive caching with execution.
 *
 * Phase 10: Integration of caching system.
 *
 * This executor:
 * 1. Checks L1 (in-memory) and L2 (Firestore) caches before executing
 * 2. Validates cache staleness using dependency tracking
 * 3. Executes and caches new results with proper dependencies
 * 4. Handles edit session suppression for inline editing
 *
 * Usage:
 * ```kotlin
 * val executor = CachedDirectiveExecutor(cacheManager, editSessionManager)
 *
 * // Execute with caching
 * val result = executor.execute(
 *     sourceText = "[find(path: \"inbox\")]",
 *     notes = allNotes,
 *     currentNote = thisNote
 * )
 * ```
 */
class CachedDirectiveExecutor(
    private val cacheManager: DirectiveCacheManager,
    private val editSessionManager: EditSessionManager? = null,
    private val dependencyCollector: TransitiveDependencyCollector = TransitiveDependencyCollector(),
    private val dependencyRegistry: DirectiveDependencyRegistry = DirectiveDependencyRegistry()
) : CachedExecutorInterface {
    /**
     * Result of cached execution.
     */
    data class CachedExecutionResult(
        /** The directive result */
        val result: DirectiveResult,
        /** Whether this was a cache hit */
        val cacheHit: Boolean,
        /** The collected dependencies (for transitive merging) */
        val dependencies: DirectiveDependencies,
        /** Any mutations that occurred (only for non-cached execution) */
        val mutations: List<org.alkaline.taskbrain.dsl.runtime.NoteMutation>
    )

    /**
     * Execute a directive with caching.
     *
     * Flow:
     * 1. Analyze AST to get cache key and dependencies
     * 2. Check L1/L2 cache for valid result
     * 3. If cache miss or stale, execute directive
     * 4. Cache successful results
     * 5. Return result with cache metadata
     *
     * @param sourceText The directive source text (including brackets)
     * @param notes All notes for find() operations
     * @param currentNote The note containing this directive
     * @param noteOperations Optional operations for mutations
     * @param viewStack View stack for circular dependency detection
     * @return CachedExecutionResult with result and metadata
     */
    fun execute(
        sourceText: String,
        notes: List<Note>,
        currentNote: Note?,
        noteOperations: NoteOperations? = null,
        viewStack: List<String> = emptyList()
    ): CachedExecutionResult {
        // Step 1: Parse and analyze for cache key and dependencies
        val analysisResult = analyzeDirective(sourceText)
        if (analysisResult == null) {
            // Parsing failed - execute to get the error
            return executeFresh(sourceText, notes, currentNote, noteOperations, viewStack)
        }

        val (cacheKey, analysis) = analysisResult
        val noteId = currentNote?.id ?: GLOBAL_CACHE_PLACEHOLDER
        val usesSelfAccess = analysis.usesSelfAccess
        val isMutating = analysis.isMutating
        val dependencies = analysis.toPartialDependencies()

        // Step 2: Check if we should suppress staleness (during inline editing)
        val shouldSuppressStaleness = currentNote?.id != null &&
            editSessionManager?.shouldSuppressInvalidation(currentNote.id) == true

        // Step 3: For mutating directives, NEVER re-execute due to staleness
        // Mutating directives (e.g., [.root.name: "x"]) should only run once,
        // not be re-triggered when cache becomes stale. Otherwise, edits to the
        // mutated note would be overwritten by the original mutation value.
        if (isMutating) {
            // Check for ANY cached result (even stale)
            val cachedResult = cacheManager.get(cacheKey, noteId, usesSelfAccess)
            if (cachedResult != null) {
                val directiveResult = cachedResultToDirectiveResult(cachedResult)
                return CachedExecutionResult(
                    result = directiveResult,
                    cacheHit = true,
                    dependencies = cachedResult.dependencies,
                    mutations = emptyList()
                )
            }
            // No cached result - this is the first execution, allow it to proceed
        }

        // Step 4: Check cache (with staleness for non-mutating directives)
        val cachedResult = if (shouldSuppressStaleness) {
            // During editing, return cached result without staleness check
            cacheManager.get(cacheKey, noteId, usesSelfAccess)
        } else {
            // Normal flow: check with staleness validation
            cacheManager.getIfValid(cacheKey, noteId, usesSelfAccess, notes, currentNote)
        }

        if (cachedResult != null) {
            val directiveResult = cachedResultToDirectiveResult(cachedResult)
            return CachedExecutionResult(
                result = directiveResult,
                cacheHit = true,
                dependencies = cachedResult.dependencies,
                mutations = emptyList()
            )
        }

        // Step 5: Cache miss - execute fresh
        return executeFreshWithCaching(
            sourceText = sourceText,
            notes = notes,
            currentNote = currentNote,
            noteOperations = noteOperations,
            viewStack = viewStack,
            cacheKey = cacheKey,
            baseDependencies = dependencies,
            usesSelfAccess = usesSelfAccess
        )
    }

    /**
     * Execute a directive with caching support - interface implementation.
     * Used by view() to execute nested directives with proper dependency tracking.
     *
     * Phase 1 (Caching Audit): Added to implement CachedExecutorInterface.
     */
    override fun executeCached(
        sourceText: String,
        notes: List<Note>,
        currentNote: Note?,
        noteOperations: NoteOperations?,
        viewStack: List<String>
    ): CachedExecutionResultInterface {
        val result = execute(sourceText, notes, currentNote, noteOperations, viewStack)
        return CachedExecutionResultAdapter(result)
    }

    /**
     * Analyze a directive to get its cache key and dependencies.
     * Returns null if parsing fails (will be executed fresh to get error).
     */
    private fun analyzeDirective(sourceText: String): Pair<String, DirectiveAnalysis>? {
        return try {
            val tokens = Lexer(sourceText).tokenize()
            val directive = Parser(tokens, sourceText).parseDirective()
            val cacheKey = DirectiveResult.hashDirective(sourceText)
            val analysis = DependencyAnalyzer.analyze(directive.expression)
            Pair(cacheKey, analysis)
        } catch (e: LexerException) {
            null
        } catch (e: ParseException) {
            null
        }
    }

    /**
     * Execute directive without caching (for parse errors or when analysis fails).
     *
     * Phase 1 (Caching Audit): Passes this executor for nested view() execution.
     */
    private fun executeFresh(
        sourceText: String,
        notes: List<Note>,
        currentNote: Note?,
        noteOperations: NoteOperations?,
        viewStack: List<String>
    ): CachedExecutionResult {
        val execResult = DirectiveFinder.executeDirective(
            sourceText = sourceText,
            notes = notes,
            currentNote = currentNote,
            noteOperations = noteOperations,
            viewStack = viewStack,
            cachedExecutor = this  // Phase 1: Pass executor for nested directives
        )
        return CachedExecutionResult(
            result = execResult.result,
            cacheHit = false,
            dependencies = DirectiveDependencies.EMPTY,
            mutations = execResult.mutations
        )
    }

    /**
     * Execute directive fresh and cache the result.
     */
    private fun executeFreshWithCaching(
        sourceText: String,
        notes: List<Note>,
        currentNote: Note?,
        noteOperations: NoteOperations?,
        viewStack: List<String>,
        cacheKey: String,
        baseDependencies: DirectiveDependencies,
        usesSelfAccess: Boolean
    ): CachedExecutionResult {
        // Start dependency collection
        dependencyCollector.startDirective(cacheKey, baseDependencies)

        try {
            // Execute the directive
            // Phase 1 (Caching Audit): Pass this executor for nested view() directives
            val execResult = DirectiveFinder.executeDirective(
                sourceText = sourceText,
                notes = notes,
                currentNote = currentNote,
                noteOperations = noteOperations,
                viewStack = viewStack,
                cachedExecutor = this
            )

            // Finish collecting dependencies
            var finalDependencies = dependencyCollector.finishDirective()

            // Phase 1.3: Extract viewed note IDs from ViewVal results
            // When view() renders notes, we need to track those note IDs as dependencies
            // so that changes to viewed notes trigger staleness detection.
            finalDependencies = enrichWithViewDependencies(execResult.result, finalDependencies)

            // Register dependencies for transitive lookups
            dependencyRegistry.register(cacheKey, finalDependencies)

            // Cache the result if successful or deterministic error
            val noteId = currentNote?.id ?: GLOBAL_CACHE_PLACEHOLDER
            if (execResult.result.isComputed || shouldCacheError(execResult.result)) {
                val cachedResult = buildCachedResult(
                    execResult.result,
                    finalDependencies,
                    notes
                )
                cacheManager.put(cacheKey, noteId, usesSelfAccess, cachedResult)
            }

            return CachedExecutionResult(
                result = execResult.result,
                cacheHit = false,
                dependencies = finalDependencies,
                mutations = execResult.mutations
            )
        } catch (e: Exception) {
            // Abort collection on error
            val abortedDependencies = dependencyCollector.abortDirective()
            return CachedExecutionResult(
                result = DirectiveResult.failure("Unexpected error: ${e.message}"),
                cacheHit = false,
                dependencies = abortedDependencies,
                mutations = emptyList()
            )
        }
    }

    /**
     * Check if an error result should be cached.
     * Only deterministic errors are cached.
     */
    private fun shouldCacheError(result: DirectiveResult): Boolean {
        if (result.isComputed) return false
        val errorMessage = result.error ?: return false

        // Use DirectiveErrorFactory to classify
        val error = DirectiveErrorFactory.fromExecutionException(errorMessage)
        return error.isDeterministic
    }

    /**
     * Enrich dependencies with viewed note IDs from ViewVal results.
     *
     * Phase 1.3 (Caching Audit): When view() renders notes, we track those note IDs
     * in BOTH firstLineNotes and nonFirstLineNotes so that ANY changes to viewed
     * note content trigger staleness detection.
     *
     * We track in both sets because view() displays the full content of each note:
     * - firstLineNotes: detects changes to note names (first line)
     * - nonFirstLineNotes: detects changes to note bodies (lines 2+)
     */
    private fun enrichWithViewDependencies(
        result: DirectiveResult,
        dependencies: DirectiveDependencies
    ): DirectiveDependencies {
        val value = result.toValue() ?: return dependencies
        if (value !is ViewVal) return dependencies

        // Extract all viewed note IDs
        val viewedNoteIds = value.notes.map { it.id }.toSet()
        if (viewedNoteIds.isEmpty()) return dependencies

        // Add viewed note IDs to both first-line and non-first-line dependencies
        // This ensures any content change in viewed notes triggers staleness
        return dependencies.copy(
            firstLineNotes = dependencies.firstLineNotes + viewedNoteIds,
            nonFirstLineNotes = dependencies.nonFirstLineNotes + viewedNoteIds
        )
    }

    /**
     * Build a CachedDirectiveResult from execution result.
     */
    private fun buildCachedResult(
        result: DirectiveResult,
        dependencies: DirectiveDependencies,
        notes: List<Note>
    ): CachedDirectiveResult {
        val builder = CachedResultBuilder(dependencies, notes)

        val value = result.toValue()
        return if (value != null) {
            builder.buildSuccess(value)
        } else {
            val errorMessage = result.error ?: "Unknown error"
            val error = DirectiveErrorFactory.fromExecutionException(errorMessage)
            builder.buildError(error)
        }
    }

    /**
     * Convert a cached result back to DirectiveResult.
     */
    private fun cachedResultToDirectiveResult(cached: CachedDirectiveResult): DirectiveResult {
        return if (cached.isSuccess && cached.result != null) {
            DirectiveResult.success(cached.result)
        } else if (cached.isError && cached.error != null) {
            DirectiveResult.failure(cached.error.message)
        } else {
            DirectiveResult.failure("Invalid cached result")
        }
    }

    /**
     * Invalidate cache for notes that changed.
     * Call this after note saves.
     *
     * @param changedNoteIds IDs of notes that changed
     */
    fun invalidateForChangedNotes(changedNoteIds: Set<String>) {
        if (editSessionManager != null) {
            // Use edit-aware invalidation
            for (noteId in changedNoteIds) {
                editSessionManager.requestInvalidation(noteId, InvalidationReason.CONTENT_CHANGED)
            }
        } else {
            // Direct invalidation
            for (noteId in changedNoteIds) {
                cacheManager.clearNote(noteId)
            }
        }
    }

    /**
     * Clear all caches.
     */
    fun clearAll() {
        cacheManager.clearAll()
        dependencyRegistry.clear()
    }

    /**
     * Get cache statistics for debugging.
     */
    fun getStats(): CacheStats {
        return CacheStats(
            registeredDependencies = dependencyRegistry.size()
        )
    }

    data class CacheStats(
        val registeredDependencies: Int
    )

    companion object {
        /** Placeholder noteId for global (self-less) directives */
        private const val GLOBAL_CACHE_PLACEHOLDER = "__global__"
    }
}

/**
 * Factory for creating CachedDirectiveExecutor with standard configuration.
 */
object CachedDirectiveExecutorFactory {
    /**
     * Create an executor with in-memory caching only.
     */
    fun createInMemoryOnly(): CachedDirectiveExecutor {
        return CachedDirectiveExecutor(
            cacheManager = DirectiveCacheManager()
        )
    }

    /**
     * Create an executor with edit session support.
     */
    fun createWithEditSupport(): CachedDirectiveExecutor {
        val cacheManager = DirectiveCacheManager()
        val editSessionManager = EditSessionManager(cacheManager)
        return CachedDirectiveExecutor(
            cacheManager = cacheManager,
            editSessionManager = editSessionManager
        )
    }

    /**
     * Create an executor with L2 (Firestore) persistence.
     * Note: Requires Firebase setup.
     */
    fun createWithL2Cache(l2Cache: L2DirectiveCache): CachedDirectiveExecutor {
        val cacheManager = DirectiveCacheManager(l2Cache = l2Cache)
        val editSessionManager = EditSessionManager(cacheManager)
        return CachedDirectiveExecutor(
            cacheManager = cacheManager,
            editSessionManager = editSessionManager
        )
    }
}

/**
 * Adapter to expose CachedExecutionResult through the interface.
 * This breaks the circular dependency between runtime and cache packages.
 *
 * Phase 1 (Caching Audit): Added for view() transitive dependency tracking.
 */
internal class CachedExecutionResultAdapter(
    private val result: CachedDirectiveExecutor.CachedExecutionResult
) : CachedExecutionResultInterface {
    override val displayValue: String?
        get() = if (result.result.isComputed) {
            result.result.toValue()?.toDisplayString()
        } else null

    override val errorMessage: String?
        get() = result.result.error

    override val cacheHit: Boolean
        get() = result.cacheHit

    override val dependencies: Any
        get() = result.dependencies
}
