package org.alkaline.taskbrain.dsl.cache

import org.alkaline.taskbrain.data.Note
import org.alkaline.taskbrain.util.Sha256Hasher

/**
 * Types of metadata hashes that can be computed over a notes collection.
 */
enum class MetadataHashType {
    PATH,
    MODIFIED,
    CREATED,
    EXISTENCE,
    ALL_NAMES
}

/**
 * Computes collection-level metadata hashes for staleness checks.
 *
 * Hashes are computed on-demand and cached until invalidated. This is efficient because:
 * - Only computed for fields the directive actually depends on
 * - Short-circuit evaluation stops at first stale field
 * - Cached results avoid recomputing for multiple directives in the same execution cycle
 * - Cache is invalidated when notes change (via invalidateCache() or when a different notes list is passed)
 */
object MetadataHasher {

    /**
     * Cached metadata hashes along with a snapshot of the notes identity.
     * The cache is only valid if the same notes list (by identity) is being used.
     */
    private data class CachedHashes(
        val notesIdentity: Int,
        val hashes: MutableMap<MetadataHashType, String> = mutableMapOf()
    )

    @Volatile
    private var cachedHashes: CachedHashes? = null

    /**
     * Compute an identity hash for a notes list.
     * Uses list's identity hash code (reference equality) to ensure
     * cached values are only returned when the EXACT same list object is passed.
     */
    private fun computeNotesIdentity(notes: List<Note>): Int {
        return System.identityHashCode(notes)
    }

    /**
     * Get a cached hash if the cache is valid for the given notes list.
     */
    private fun getCachedHash(notes: List<Note>, type: MetadataHashType): String? {
        val cached = cachedHashes ?: return null
        if (cached.notesIdentity != computeNotesIdentity(notes)) return null
        return cached.hashes[type]
    }

    /**
     * Store a computed hash in the cache.
     */
    private fun cacheHash(notes: List<Note>, type: MetadataHashType, hash: String) {
        val identity = computeNotesIdentity(notes)
        val cached = cachedHashes?.takeIf { it.notesIdentity == identity }
            ?: CachedHashes(notesIdentity = identity)
        cached.hashes[type] = hash
        cachedHashes = cached
    }

    /**
     * Compute a hash with caching support.
     */
    private inline fun computeWithCache(
        notes: List<Note>,
        type: MetadataHashType,
        compute: () -> String
    ): String {
        getCachedHash(notes, type)?.let { return it }
        val hash = compute()
        cacheHash(notes, type, hash)
        return hash
    }

    /**
     * Invalidate all cached hashes.
     * Call this when the notes collection changes.
     */
    fun invalidateCache() {
        cachedHashes = null
    }

    /**
     * Compute hash of all note paths.
     */
    fun computePathHash(notes: List<Note>): String = computeWithCache(notes, MetadataHashType.PATH) {
        val values = notes.sortedBy { it.id }.map { "${it.id}:${it.path}" }
        Sha256Hasher.hash(values.joinToString("\n"))
    }

    /**
     * Compute hash of all modified timestamps.
     */
    fun computeModifiedHash(notes: List<Note>): String = computeWithCache(notes, MetadataHashType.MODIFIED) {
        val values = notes.sortedBy { it.id }.map { note ->
            val timestamp = note.updatedAt?.toDate()?.time ?: 0L
            "${note.id}:$timestamp"
        }
        Sha256Hasher.hash(values.joinToString("\n"))
    }

    /**
     * Compute hash of all created timestamps.
     */
    fun computeCreatedHash(notes: List<Note>): String = computeWithCache(notes, MetadataHashType.CREATED) {
        val values = notes.sortedBy { it.id }.map { note ->
            val timestamp = note.createdAt?.toDate()?.time ?: 0L
            "${note.id}:$timestamp"
        }
        Sha256Hasher.hash(values.joinToString("\n"))
    }

    /**
     * Compute hash of note IDs (for existence check).
     * Used to detect note creation/deletion.
     */
    fun computeExistenceHash(notes: List<Note>): String = computeWithCache(notes, MetadataHashType.EXISTENCE) {
        val sortedIds = notes.map { it.id }.sorted()
        Sha256Hasher.hash(sortedIds.joinToString("\n"))
    }

    /**
     * Compute hash of all note names (first lines).
     * Used for find(name: ...) which checks all note names.
     */
    fun computeAllNamesHash(notes: List<Note>): String = computeWithCache(notes, MetadataHashType.ALL_NAMES) {
        val values = notes.sortedBy { it.id }.map { note ->
            val firstLine = note.content.lines().firstOrNull() ?: ""
            "${note.id}:$firstLine"
        }
        Sha256Hasher.hash(values.joinToString("\n"))
    }

    /**
     * Compute all metadata hashes based on which fields are depended on.
     * Only computes hashes for fields that are needed.
     * Uses cached values when available.
     */
    fun computeHashes(notes: List<Note>, deps: DirectiveDependencies): MetadataHashes {
        return MetadataHashes(
            pathHash = if (deps.dependsOnPath) computePathHash(notes) else null,
            modifiedHash = if (deps.dependsOnModified) computeModifiedHash(notes) else null,
            createdHash = if (deps.dependsOnCreated) computeCreatedHash(notes) else null,
            existenceHash = if (deps.dependsOnNoteExistence) computeExistenceHash(notes) else null,
            allNamesHash = if (deps.dependsOnAllNames) computeAllNamesHash(notes) else null
        )
    }
}
