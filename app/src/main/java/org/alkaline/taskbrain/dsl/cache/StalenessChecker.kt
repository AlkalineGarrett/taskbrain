package org.alkaline.taskbrain.dsl.cache

import org.alkaline.taskbrain.data.Note

/**
 * Checks if a cached directive result is stale and needs re-execution.
 *
 * Data structures and hashing infrastructure.
 * Added error-based cache decisions.
 *
 * Staleness is determined by comparing cached hashes with current data.
 * The algorithm short-circuits at the first stale dependency for efficiency.
 *
 * For error results:
 * - Deterministic errors (syntax, type, etc.) are cached normally
 * - Non-deterministic errors (network, timeout, etc.) always trigger re-execution
 */
object StalenessChecker {

    /**
     * Determine if a cached result should be re-executed.
     *
     * This is the main entry point for cache decisions. It considers:
     * 1. Whether the cached result is a non-deterministic error (always retry)
     * 2. Whether the cached result is stale based on dependency hashes
     *
     * @param cached The cached directive result
     * @param currentNotes All current notes in the system
     * @param currentNote The note containing this directive (required for hierarchy checks)
     * @return true if the directive should be re-executed
     */
    fun shouldReExecute(
        cached: CachedDirectiveResult,
        currentNotes: List<Note>,
        currentNote: Note? = null
    ): Boolean {
        // Non-deterministic errors should always be retried
        if (cached.shouldRetryError) {
            return true
        }

        // Otherwise, check if the cache is stale
        return isStale(cached, currentNotes, currentNote)
    }

    /**
     * Check if a cached result is stale based on dependency hashes.
     *
     * Note: For error-aware caching, use shouldReExecute() instead, which
     * also considers non-deterministic error retry logic.
     *
     * @param cached The cached directive result
     * @param currentNotes All current notes in the system
     * @param currentNote The note containing this directive (required for hierarchy checks)
     * @return true if the cache is stale and needs re-execution
     */
    fun isStale(
        cached: CachedDirectiveResult,
        currentNotes: List<Note>,
        currentNote: Note? = null
    ): Boolean {
        val deps = cached.dependencies
        val cachedHashes = cached.metadataHashes

        // Check global metadata dependencies (lazy, on-demand hashing)
        // Short-circuit: return true at first stale field

        if (deps.dependsOnNoteExistence) {
            val currentHash = MetadataHasher.computeExistenceHash(currentNotes)
            if (cachedHashes.existenceHash != currentHash) {
                return true
            }
        }

        if (deps.dependsOnPath) {
            val currentHash = MetadataHasher.computePathHash(currentNotes)
            if (cachedHashes.pathHash != currentHash) {
                return true
            }
        }

        if (deps.dependsOnModified) {
            val currentHash = MetadataHasher.computeModifiedHash(currentNotes)
            if (cachedHashes.modifiedHash != currentHash) {
                return true
            }
        }

        if (deps.dependsOnCreated) {
            val currentHash = MetadataHasher.computeCreatedHash(currentNotes)
            if (cachedHashes.createdHash != currentHash) {
                return true
            }
        }

        if (deps.dependsOnAllNames) {
            val currentHash = MetadataHasher.computeAllNamesHash(currentNotes)
            if (cachedHashes.allNamesHash != currentHash) {
                return true
            }
        }

        // Check per-note content dependencies
        for (noteId in deps.firstLineNotes) {
            val note = currentNotes.find { it.id == noteId }
            if (note == null) return true // Note deleted

            val currentHash = ContentHasher.hashFirstLine(note.content)
            val cachedHash = cached.noteContentHashes[noteId]?.firstLineHash
            if (cachedHash == null || currentHash != cachedHash) {
                return true
            }
        }

        for (noteId in deps.nonFirstLineNotes) {
            val note = currentNotes.find { it.id == noteId }
            if (note == null) return true // Note deleted

            val currentHash = ContentHasher.hashNonFirstLine(note.content)
            val cachedHash = cached.noteContentHashes[noteId]?.nonFirstLineHash
            if (cachedHash == null || currentHash != cachedHash) {
                return true
            }
        }

        // Check hierarchy dependencies (.up, .root)
        // Requires currentNote to be passed in
        if (deps.hierarchyDeps.isNotEmpty() && currentNote != null) {
            for (dep in deps.hierarchyDeps) {
                if (isHierarchyStale(dep, currentNote, currentNotes)) {
                    return true
                }
            }
        }

        return false
    }

    /**
     * Check if a hierarchy dependency is stale.
     */
    private fun isHierarchyStale(
        dep: HierarchyDependency,
        currentNote: Note,
        allNotes: List<Note>
    ): Boolean {
        // 1. Re-resolve the hierarchy path from current note
        val currentResolved = HierarchyResolver.resolve(dep.path, currentNote, allNotes)

        // 2. If resolves to different note (or existence changed), hierarchy changed → stale
        if (currentResolved?.id != dep.resolvedNoteId) return true

        // 3. If same note and a field was accessed, check field hash
        if (dep.field != null && currentResolved != null) {
            val currentHash = ContentHasher.hashField(currentResolved, dep.field)
            if (currentHash != dep.fieldHash) return true
        }

        return false
    }
}

/**
 * Resolves hierarchy navigation paths (.up, .root) to notes.
 *
 * Notes are organized in a hierarchy via their `path` property.
 * For example, "inbox/tasks/todo" is a child of "inbox/tasks" which is a child of "inbox".
 */
object HierarchyResolver {

    /**
     * Resolve a hierarchy path from a note.
     *
     * @param path The hierarchy path to resolve (Up, UpN, Root)
     * @param note The starting note
     * @param allNotes All notes in the system
     * @return The resolved note, or null if not found
     */
    fun resolve(path: HierarchyPath, note: Note, allNotes: List<Note>): Note? {
        return when (path) {
            is HierarchyPath.Up -> findParent(note, allNotes)
            is HierarchyPath.UpN -> findAncestor(note, path.levels, allNotes)
            is HierarchyPath.Root -> findRoot(note, allNotes)
        }
    }

    /**
     * Find the immediate parent of a note.
     * Parent is determined by the note's path - e.g., "inbox/tasks" is parent of "inbox/tasks/todo".
     */
    fun findParent(note: Note, allNotes: List<Note>): Note? {
        val parentPath = getParentPath(note.path) ?: return null
        return allNotes.find { it.path == parentPath }
    }

    /**
     * Find an ancestor n levels up.
     */
    fun findAncestor(note: Note, levels: Int, allNotes: List<Note>): Note? {
        var current: Note? = note
        repeat(levels) {
            current = current?.let { findParent(it, allNotes) }
            if (current == null) return null
        }
        return current
    }

    /**
     * Find the root ancestor of a note.
     * The root is the topmost parent (first segment of the path).
     */
    fun findRoot(note: Note, allNotes: List<Note>): Note? {
        val rootPath = getRootPath(note.path) ?: return null
        return allNotes.find { it.path == rootPath }
    }

    /**
     * Get the parent path from a path string.
     * "inbox/tasks/todo" -> "inbox/tasks"
     * "inbox" -> null (no parent)
     */
    private fun getParentPath(path: String): String? {
        val lastSlash = path.lastIndexOf('/')
        return if (lastSlash > 0) path.substring(0, lastSlash) else null
    }

    /**
     * Get the root path from a path string.
     * "inbox/tasks/todo" -> "inbox"
     * "inbox" -> "inbox" (it is the root)
     */
    private fun getRootPath(path: String): String? {
        if (path.isEmpty()) return null
        val firstSlash = path.indexOf('/')
        return if (firstSlash > 0) path.substring(0, firstSlash) else path
    }
}
