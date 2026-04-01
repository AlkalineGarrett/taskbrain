package org.alkaline.taskbrain.dsl.cache

import org.alkaline.taskbrain.data.Note
import org.alkaline.taskbrain.dsl.runtime.values.DslValue

/**
 * Collects and merges transitive dependencies during directive execution.
 *
 * Transitive dependency merging.
 *
 * When directive A calls directive B, A should inherit B's dependencies.
 * This collector tracks:
 * 1. Dependencies from referenced directives (lambda references)
 * 2. Dependencies from nested view rendering
 * 3. Dependencies from dynamically executed expressions
 *
 * Usage:
 * ```kotlin
 * val collector = TransitiveDependencyCollector()
 *
 * // When executing a directive
 * collector.startDirective("directive-hash")
 *
 * // When a referenced directive is resolved
 * collector.addReferencedDependencies(referencedDirectiveDeps)
 *
 * // When a nested view is rendered
 * collector.addNestedViewDependencies(nestedViewDeps)
 *
 * // When accessing note content
 * collector.recordContentAccess(noteId, accessedFirstLine = true)
 *
 * // Get final dependencies
 * val finalDeps = collector.finishDirective()
 * ```
 */
class TransitiveDependencyCollector {

    // Stack of contexts for nested directive execution
    private val contextStack = mutableListOf<CollectorContext>()

    /**
     * Start collecting dependencies for a new directive.
     * Call this when beginning directive execution.
     *
     * @param directiveKey Optional cache key for the directive
     * @param baseDependencies Base dependencies from static analysis
     */
    fun startDirective(
        directiveKey: String? = null,
        baseDependencies: DirectiveDependencies = DirectiveDependencies.EMPTY
    ) {
        contextStack.add(CollectorContext(directiveKey, baseDependencies))
    }

    /**
     * Check if currently collecting dependencies.
     */
    fun isCollecting(): Boolean = contextStack.isNotEmpty()

    /**
     * Get the current context, or null if not collecting.
     */
    private fun currentContext(): CollectorContext? = contextStack.lastOrNull()

    /**
     * Add dependencies from a referenced directive.
     * Called when one directive invokes another.
     *
     * @param dependencies Dependencies of the referenced directive
     */
    fun addReferencedDependencies(dependencies: DirectiveDependencies) {
        currentContext()?.addTransitiveDependencies(dependencies)
    }

    /**
     * Add dependencies from a nested view rendering.
     * Called when view() renders notes that contain their own directives.
     *
     * @param dependencies Dependencies from the nested view
     */
    fun addNestedViewDependencies(dependencies: DirectiveDependencies) {
        currentContext()?.addTransitiveDependencies(dependencies)
    }

    /**
     * Record that a note's first line (name) was accessed.
     *
     * @param noteId ID of the note accessed
     */
    fun recordFirstLineAccess(noteId: String) {
        currentContext()?.recordFirstLineAccess(noteId)
    }

    /**
     * Record that a note's non-first-line content was accessed.
     *
     * @param noteId ID of the note accessed
     */
    fun recordNonFirstLineAccess(noteId: String) {
        currentContext()?.recordNonFirstLineAccess(noteId)
    }

    /**
     * Record a resolved hierarchy dependency.
     * Called when .up or .root is resolved to an actual note.
     *
     * @param dependency The resolved hierarchy dependency
     */
    fun recordHierarchyDependency(dependency: HierarchyDependency) {
        currentContext()?.recordHierarchyDependency(dependency)
    }

    /**
     * Record that find() was called.
     */
    fun recordFindUsage() {
        currentContext()?.recordFindUsage()
    }

    /**
     * Finish collecting dependencies and return the merged result.
     * Call this when directive execution completes.
     *
     * @return The final merged dependencies
     */
    fun finishDirective(): DirectiveDependencies {
        if (contextStack.isEmpty()) {
            return DirectiveDependencies.EMPTY
        }

        val context = contextStack.removeLast()
        val finalDeps = context.buildDependencies()

        // If there's a parent context, propagate dependencies up
        currentContext()?.addTransitiveDependencies(finalDeps)

        return finalDeps
    }

    /**
     * Abort dependency collection (e.g., on error).
     * Returns whatever dependencies were collected so far.
     */
    fun abortDirective(): DirectiveDependencies {
        return if (contextStack.isNotEmpty()) {
            contextStack.removeLast().buildDependencies()
        } else {
            DirectiveDependencies.EMPTY
        }
    }

    /**
     * Get current collected dependencies without finishing.
     * Useful for intermediate inspection.
     */
    fun currentDependencies(): DirectiveDependencies {
        return currentContext()?.buildDependencies() ?: DirectiveDependencies.EMPTY
    }

    /**
     * Clear all collection state.
     */
    fun reset() {
        contextStack.clear()
    }

    /**
     * Get the nesting depth (for debugging).
     */
    fun depth(): Int = contextStack.size
}

/**
 * Internal context for a single directive's dependency collection.
 */
private class CollectorContext(
    val directiveKey: String?,
    private var dependencies: DirectiveDependencies
) {
    private val firstLineNotes = mutableSetOf<String>()
    private val nonFirstLineNotes = mutableSetOf<String>()
    private val hierarchyDeps = mutableListOf<HierarchyDependency>()

    fun addTransitiveDependencies(deps: DirectiveDependencies) {
        dependencies = dependencies.merge(deps)
    }

    fun recordFirstLineAccess(noteId: String) {
        firstLineNotes.add(noteId)
    }

    fun recordNonFirstLineAccess(noteId: String) {
        nonFirstLineNotes.add(noteId)
    }

    fun recordHierarchyDependency(dep: HierarchyDependency) {
        hierarchyDeps.add(dep)
    }

    fun recordFindUsage() {
        dependencies = dependencies.copy(dependsOnNoteExistence = true)
    }

    fun buildDependencies(): DirectiveDependencies {
        return dependencies.copy(
            firstLineNotes = dependencies.firstLineNotes + firstLineNotes,
            nonFirstLineNotes = dependencies.nonFirstLineNotes + nonFirstLineNotes,
            hierarchyDeps = dependencies.hierarchyDeps + hierarchyDeps
        )
    }
}

/**
 * Registry for caching directive dependencies by their cache key.
 *
 * When a directive is executed, its dependencies are registered here.
 * When another directive references it, the dependencies can be looked up
 * for transitive merging.
 */
class DirectiveDependencyRegistry {
    private val registry = mutableMapOf<String, DirectiveDependencies>()

    /**
     * Register dependencies for a directive.
     *
     * @param directiveKey Cache key for the directive
     * @param dependencies The directive's dependencies
     */
    fun register(directiveKey: String, dependencies: DirectiveDependencies) {
        registry[directiveKey] = dependencies
    }

    /**
     * Get dependencies for a directive.
     *
     * @param directiveKey Cache key for the directive
     * @return Dependencies if registered, null otherwise
     */
    fun get(directiveKey: String): DirectiveDependencies? {
        return registry[directiveKey]
    }

    /**
     * Remove a directive's dependencies.
     */
    fun remove(directiveKey: String) {
        registry.remove(directiveKey)
    }

    /**
     * Clear all registered dependencies.
     */
    fun clear() {
        registry.clear()
    }

    /**
     * Check if a directive is registered.
     */
    fun contains(directiveKey: String): Boolean = directiveKey in registry

    /**
     * Get the number of registered directives.
     */
    fun size(): Int = registry.size
}

/**
 * Helper for resolving and recording hierarchy dependencies at runtime.
 */
object HierarchyDependencyResolver {

    /**
     * Resolve hierarchy access patterns to actual dependencies.
     * Called during directive execution when accessing .up or .root.
     *
     * @param patterns Patterns from static analysis
     * @param currentNote The note containing the directive
     * @param allNotes All notes in the system
     * @return Resolved hierarchy dependencies
     */
    fun resolvePatterns(
        patterns: List<HierarchyAccessPattern>,
        currentNote: Note,
        allNotes: List<Note>
    ): List<HierarchyDependency> {
        return patterns.map { pattern ->
            resolvePattern(pattern, currentNote, allNotes)
        }
    }

    /**
     * Resolve a single hierarchy pattern.
     */
    fun resolvePattern(
        pattern: HierarchyAccessPattern,
        currentNote: Note,
        allNotes: List<Note>
    ): HierarchyDependency {
        val resolvedNote = HierarchyResolver.resolve(pattern.path, currentNote, allNotes)
        val fieldHash = if (pattern.field != null && resolvedNote != null) {
            ContentHasher.hashField(resolvedNote, pattern.field)
        } else {
            null
        }

        return HierarchyDependency(
            path = pattern.path,
            resolvedNoteId = resolvedNote?.id,
            field = pattern.field,
            fieldHash = fieldHash
        )
    }
}

/**
 * Builder for creating CachedDirectiveResult with all dependency information.
 */
class CachedResultBuilder(
    private val dependencies: DirectiveDependencies,
    private val allNotes: List<Note>
) {
    /**
     * Build metadata hashes for the dependencies.
     */
    fun buildMetadataHashes(): MetadataHashes {
        val allNamesHash = if (dependencies.dependsOnAllNames) {
            MetadataHasher.computeAllNamesHash(allNotes)
        } else null

        return MetadataHashes(
            pathHash = if (dependencies.dependsOnPath)
                MetadataHasher.computePathHash(allNotes) else null,
            modifiedHash = if (dependencies.dependsOnModified)
                MetadataHasher.computeModifiedHash(allNotes) else null,
            createdHash = if (dependencies.dependsOnCreated)
                MetadataHasher.computeCreatedHash(allNotes) else null,
            viewedHash = if (dependencies.dependsOnViewed)
                MetadataHasher.computeViewedHash(allNotes) else null,
            existenceHash = if (dependencies.dependsOnNoteExistence)
                MetadataHasher.computeExistenceHash(allNotes) else null,
            allNamesHash = allNamesHash
        )
    }

    /**
     * Build content hashes for accessed notes.
     */
    fun buildContentHashes(): Map<String, ContentHashes> {
        val result = mutableMapOf<String, ContentHashes>()

        // Hash first lines
        for (noteId in dependencies.firstLineNotes) {
            val note = allNotes.find { it.id == noteId } ?: continue
            val existing = result[noteId] ?: ContentHashes()
            result[noteId] = existing.copy(
                firstLineHash = ContentHasher.hashFirstLine(note.content)
            )
        }

        // Hash non-first lines
        for (noteId in dependencies.nonFirstLineNotes) {
            val note = allNotes.find { it.id == noteId } ?: continue
            val existing = result[noteId] ?: ContentHashes()
            result[noteId] = existing.copy(
                nonFirstLineHash = ContentHasher.hashNonFirstLine(note.content)
            )
        }

        return result
    }

    /**
     * Build a complete CachedDirectiveResult for a success case.
     */
    fun buildSuccess(result: DslValue): CachedDirectiveResult {
        return CachedDirectiveResult.success(
            result = result,
            dependencies = dependencies,
            noteContentHashes = buildContentHashes(),
            metadataHashes = buildMetadataHashes()
        )
    }

    /**
     * Build a complete CachedDirectiveResult for an error case.
     */
    fun buildError(error: DirectiveError): CachedDirectiveResult {
        return CachedDirectiveResult.error(
            error = error,
            dependencies = dependencies,
            noteContentHashes = buildContentHashes(),
            metadataHashes = buildMetadataHashes()
        )
    }
}
