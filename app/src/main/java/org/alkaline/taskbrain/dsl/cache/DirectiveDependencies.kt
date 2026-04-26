package org.alkaline.taskbrain.dsl.cache

/**
 * Tracks what data a directive depends on for cache invalidation.
 *
 * Data structures and hashing infrastructure.
 *
 * Dependencies are categorized into:
 * 1. Per-note content dependencies (first line, non-first line)
 * 2. Metadata dependencies (path, modified, created, viewed, existence)
 * 3. Hierarchy dependencies (for .up, .root access)
 */
data class DirectiveDependencies(
    // Per-note content dependencies
    /** Note IDs where first line (name) was accessed */
    val firstLineNotes: Set<String> = emptySet(),
    /** Note IDs where content beyond first line was accessed */
    val nonFirstLineNotes: Set<String> = emptySet(),

    // Metadata dependencies (true = this directive depends on this field)
    /** Depends on note paths (find(path: ...), note.path) */
    val dependsOnPath: Boolean = false,
    /** Depends on modified timestamps (find with modified filter) */
    val dependsOnModified: Boolean = false,
    /** Depends on created timestamps (find with created filter) */
    val dependsOnCreated: Boolean = false,
    /** Depends on note existence (any find() was used) */
    val dependsOnNoteExistence: Boolean = false,
    /** Depends on all note names/first lines (find(name: ...) was used) */
    val dependsOnAllNames: Boolean = false,

    // Hierarchy dependencies (for .up, .root access)
    /** Dependencies on parent/ancestor notes */
    val hierarchyDeps: List<HierarchyDependency> = emptyList()
) {
    /**
     * Merge with another DirectiveDependencies (for transitive dependencies).
     * Used when one directive references another.
     */
    fun merge(other: DirectiveDependencies): DirectiveDependencies = DirectiveDependencies(
        firstLineNotes = firstLineNotes + other.firstLineNotes,
        nonFirstLineNotes = nonFirstLineNotes + other.nonFirstLineNotes,
        dependsOnPath = dependsOnPath || other.dependsOnPath,
        dependsOnModified = dependsOnModified || other.dependsOnModified,
        dependsOnCreated = dependsOnCreated || other.dependsOnCreated,
        dependsOnNoteExistence = dependsOnNoteExistence || other.dependsOnNoteExistence,
        dependsOnAllNames = dependsOnAllNames || other.dependsOnAllNames,
        hierarchyDeps = hierarchyDeps + other.hierarchyDeps
    )

    /**
     * Check if this directive has any dependencies.
     */
    fun isEmpty(): Boolean =
        firstLineNotes.isEmpty() &&
            nonFirstLineNotes.isEmpty() &&
            !dependsOnPath &&
            !dependsOnModified &&
            !dependsOnCreated &&
            !dependsOnNoteExistence &&
            !dependsOnAllNames &&
            hierarchyDeps.isEmpty()

    companion object {
        /** Empty dependencies - no external data accessed */
        val EMPTY = DirectiveDependencies()
    }
}

/**
 * A dependency on a note accessed through hierarchy navigation (.up, .root).
 *
 * These are inherently per-note since they depend on the current note's position
 * in the hierarchy.
 */
data class HierarchyDependency(
    /** The hierarchy path used (e.g., UP, UP_N(2), ROOT) */
    val path: HierarchyPath,
    /** Note ID it resolved to at cache time (null if didn't exist) */
    val resolvedNoteId: String?,
    /** Which field was accessed (null = note itself was accessed) */
    val field: NoteField?,
    /** Hash of field value at cache time (null if field is null) */
    val fieldHash: String?
)

/**
 * Hierarchy navigation paths.
 */
sealed class HierarchyPath {
    /** .up or .up() - immediate parent */
    data object Up : HierarchyPath()

    /** .up(n) where n > 1 - ancestor n levels up */
    data class UpN(val levels: Int) : HierarchyPath()

    /** .root - root ancestor */
    data object Root : HierarchyPath()
}

/**
 * Note fields that can be accessed for dependency tracking.
 */
enum class NoteField {
    /** First line of content (note name) */
    NAME,
    /** Note path */
    PATH,
    /** Modified timestamp */
    MODIFIED,
    /** Created timestamp */
    CREATED,
}
