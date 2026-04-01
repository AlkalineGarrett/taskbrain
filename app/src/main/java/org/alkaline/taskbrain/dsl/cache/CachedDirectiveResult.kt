package org.alkaline.taskbrain.dsl.cache

import org.alkaline.taskbrain.dsl.runtime.values.DslValue

/**
 * A cached directive execution result with its dependencies and hashes.
 *
 * Data structures and hashing infrastructure.
 * Added error support for error caching.
 *
 * This is stored in cache and used to check if the cached result is still valid.
 * A cached result may be either a success (result != null) or an error (error != null),
 * but not both.
 */
data class CachedDirectiveResult(
    /** The computed value from directive execution (null if error) */
    val result: DslValue? = null,

    /** Error that occurred during execution (null if success) */
    val error: DirectiveError? = null,

    /** Dependencies detected during execution */
    val dependencies: DirectiveDependencies,

    /** Content hashes for per-note dependencies (noteId -> hashes) */
    val noteContentHashes: Map<String, ContentHashes>,

    /** Collection-level metadata hashes */
    val metadataHashes: MetadataHashes,

    /** Timestamp when this result was cached */
    val cachedAt: Long = System.currentTimeMillis()
) {
    init {
        require((result != null) xor (error != null)) {
            "CachedDirectiveResult must have either result or error, not both or neither"
        }
    }

    /** True if this cached result represents a successful execution */
    val isSuccess: Boolean get() = result != null

    /** True if this cached result represents an error */
    val isError: Boolean get() = error != null

    /** True if this error should be retried (non-deterministic error) */
    val shouldRetryError: Boolean get() = error != null && !error.isDeterministic

    companion object {
        /**
         * Create a successful cached result.
         */
        fun success(
            result: DslValue,
            dependencies: DirectiveDependencies,
            noteContentHashes: Map<String, ContentHashes> = emptyMap(),
            metadataHashes: MetadataHashes = MetadataHashes.EMPTY
        ) = CachedDirectiveResult(
            result = result,
            error = null,
            dependencies = dependencies,
            noteContentHashes = noteContentHashes,
            metadataHashes = metadataHashes
        )

        /**
         * Create an error cached result.
         */
        fun error(
            error: DirectiveError,
            dependencies: DirectiveDependencies = DirectiveDependencies.EMPTY,
            noteContentHashes: Map<String, ContentHashes> = emptyMap(),
            metadataHashes: MetadataHashes = MetadataHashes.EMPTY
        ) = CachedDirectiveResult(
            result = null,
            error = error,
            dependencies = dependencies,
            noteContentHashes = noteContentHashes,
            metadataHashes = metadataHashes
        )
    }
}

/**
 * Content hashes for a single note.
 * Tracks the hash of first line (name) and remaining content separately.
 */
data class ContentHashes(
    /** Hash of the first line (null if not depended on) */
    val firstLineHash: String? = null,
    /** Hash of content after first line (null if not depended on) */
    val nonFirstLineHash: String? = null
) {
    companion object {
        val EMPTY = ContentHashes()
    }
}

/**
 * Collection-level metadata hashes.
 * These are computed on-demand during staleness check.
 *
 * Only fields that the directive depends on will have non-null values.
 */
data class MetadataHashes(
    /** Hash of all note paths (null if directive doesn't depend on path) */
    val pathHash: String? = null,
    /** Hash of all modified timestamps */
    val modifiedHash: String? = null,
    /** Hash of all created timestamps */
    val createdHash: String? = null,
    /** Hash of all viewed timestamps */
    val viewedHash: String? = null,
    /** Hash of sorted note IDs (for existence check) */
    val existenceHash: String? = null,
    /** Hash of all note names/first lines (for find(name: ...) queries) */
    val allNamesHash: String? = null
) {
    companion object {
        val EMPTY = MetadataHashes()
    }
}
