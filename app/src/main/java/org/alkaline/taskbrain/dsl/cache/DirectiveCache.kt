package org.alkaline.taskbrain.dsl.cache

import org.alkaline.taskbrain.data.Note
import org.alkaline.taskbrain.util.CacheStats
import org.alkaline.taskbrain.util.LruCache

/**
 * Interface for directive result caching.
 *
 * Phase 5: Cache architecture.
 */
interface DirectiveCache {
    /** Get a cached result, or null if not present */
    fun get(key: String): CachedDirectiveResult?

    /** Store a result in the cache */
    fun put(key: String, result: CachedDirectiveResult)

    /** Remove a cached result */
    fun remove(key: String)

    /** Clear all cached results */
    fun clear()

    /** Get cache statistics */
    fun stats(): CacheStats
}

/**
 * Global cache for self-less directives (no `.` self-reference).
 *
 * These directives can be shared across all notes since they don't
 * depend on the current note's context. Keyed by directive hash.
 *
 * Example: `find(path: "inbox")` - same result regardless of which note contains it
 */
class GlobalDirectiveCache(
    maxSize: Int = DEFAULT_GLOBAL_CACHE_SIZE
) : DirectiveCache {

    private val cache = LruCache<String, CachedDirectiveResult>(maxSize)

    override fun get(key: String): CachedDirectiveResult? = cache.get(key)

    override fun put(key: String, result: CachedDirectiveResult) = cache.put(key, result)

    override fun remove(key: String) { cache.remove(key) }

    override fun clear() = cache.clear()

    override fun stats(): CacheStats = cache.stats()

    /** Evict entries until size is at or below the target */
    fun evictTo(targetSize: Int) = cache.evictTo(targetSize)

    companion object {
        const val DEFAULT_GLOBAL_CACHE_SIZE = 1000
    }
}

/**
 * Per-note cache for self-referencing directives (uses `.` to access current note).
 *
 * These directives depend on the note containing them, so results must be
 * cached per-note. Keyed by directive hash within each note's cache.
 *
 * Example: `[.name]` - result depends on which note contains the directive
 *
 * This class manages multiple note caches with a total entry limit.
 */
class PerNoteDirectiveCache(
    private val maxEntriesPerNote: Int = DEFAULT_PER_NOTE_CACHE_SIZE,
    private val maxTotalNotes: Int = DEFAULT_MAX_NOTES
) {
    // Map of noteId -> cache for that note
    private val noteCaches = LruCache<String, LruCache<String, CachedDirectiveResult>>(maxTotalNotes)

    /** Get a cached result for a specific note */
    fun get(noteId: String, directiveKey: String): CachedDirectiveResult? {
        return noteCaches.get(noteId)?.get(directiveKey)
    }

    /** Store a result for a specific note */
    fun put(noteId: String, directiveKey: String, result: CachedDirectiveResult) {
        val noteCache = noteCaches.getOrPut(noteId) {
            LruCache(maxEntriesPerNote)
        }
        noteCache.put(directiveKey, result)
    }

    /** Remove a cached result for a specific note */
    fun remove(noteId: String, directiveKey: String) {
        noteCaches.get(noteId)?.remove(directiveKey)
    }

    /** Clear all cached results for a specific note */
    fun clearNote(noteId: String) {
        noteCaches.remove(noteId)
    }

    /** Clear all cached results for all notes */
    fun clear() {
        noteCaches.clear()
    }

    /** Get statistics for a specific note's cache */
    fun noteStats(noteId: String): CacheStats? {
        return noteCaches.get(noteId)?.stats()
    }

    /** Get aggregate statistics across all note caches */
    fun stats(): PerNoteCacheStats {
        val noteIds = noteCaches.keys()
        var totalEntries = 0
        var totalCapacity = 0

        for (noteId in noteIds) {
            val noteCache = noteCaches.get(noteId)
            if (noteCache != null) {
                totalEntries += noteCache.size
                totalCapacity += maxEntriesPerNote
            }
        }

        return PerNoteCacheStats(
            noteCount = noteIds.size,
            maxNotes = maxTotalNotes,
            totalEntries = totalEntries,
            maxEntriesPerNote = maxEntriesPerNote,
            utilizationPercent = if (totalCapacity > 0) (totalEntries * 100) / totalCapacity else 0
        )
    }

    companion object {
        const val DEFAULT_PER_NOTE_CACHE_SIZE = 100
        const val DEFAULT_MAX_NOTES = 500
    }
}

/**
 * Statistics for per-note cache.
 */
data class PerNoteCacheStats(
    val noteCount: Int,
    val maxNotes: Int,
    val totalEntries: Int,
    val maxEntriesPerNote: Int,
    val utilizationPercent: Int
)

/**
 * Central manager for all directive caches.
 *
 * Coordinates between global and per-note caches based on directive analysis.
 * Handles staleness checking and cache invalidation.
 *
 * Phase 5: In-memory L1 cache
 * Phase 6: Added L2 (Firestore) cache support
 *
 * Cache lookup flow:
 * 1. Check L1 (in-memory) cache
 * 2. If L1 miss, check L2 (Firestore) cache
 * 3. If L2 hit, populate L1 and return
 * 4. If both miss, return null (caller should execute directive)
 *
 * Cache storage flow:
 * 1. Store in L1 (immediate, synchronous)
 * 2. Store in L2 (background, asynchronous)
 */
class DirectiveCacheManager(
    private val globalCache: GlobalDirectiveCache = GlobalDirectiveCache(),
    private val perNoteCache: PerNoteDirectiveCache = PerNoteDirectiveCache(),
    private val l2Cache: L2DirectiveCache? = null  // Optional L2 cache
) {
    /**
     * Get a cached result for a directive (L1 only, synchronous).
     *
     * @param directiveKey The cache key (hash of normalized AST)
     * @param noteId The note containing the directive (for per-note cache lookup)
     * @param usesSelfAccess Whether the directive uses `.` self-reference
     * @return Cached result if present in L1, null otherwise
     */
    fun get(
        directiveKey: String,
        noteId: String,
        @Suppress("UNUSED_PARAMETER") usesSelfAccess: Boolean
    ): CachedDirectiveResult? {
        // Always use per-note cache: directive results are scoped to the parent note
        // that contains them, even for directives that don't reference the current note.
        return perNoteCache.get(noteId, directiveKey)
    }

    /**
     * Get a cached result for a directive with L2 fallback (async).
     *
     * Checks L1 first, then falls back to L2 if available.
     * If L2 hit, populates L1 before returning.
     *
     * @param directiveKey The cache key
     * @param noteId The note containing the directive
     * @param usesSelfAccess Whether the directive uses `.` self-reference
     * @return Cached result if present in L1 or L2, null otherwise
     */
    suspend fun getWithL2Fallback(
        directiveKey: String,
        noteId: String,
        usesSelfAccess: Boolean
    ): CachedDirectiveResult? {
        // Check L1 first
        val l1Result = get(directiveKey, noteId, usesSelfAccess)
        if (l1Result != null) return l1Result

        // Check L2 if available
        val l2 = l2Cache ?: return null

        val l2Result = l2.getPerNote(noteId, directiveKey)

        // If L2 hit, populate L1
        if (l2Result != null) {
            put(directiveKey, noteId, usesSelfAccess, l2Result)
        }

        return l2Result
    }

    /**
     * Get a cached result if it's still valid (not stale). L1 only.
     *
     * @param directiveKey The cache key
     * @param noteId The note containing the directive
     * @param usesSelfAccess Whether the directive uses `.` self-reference
     * @param currentNotes All current notes for staleness check
     * @param currentNote The note containing this directive (for hierarchy checks)
     * @return Cached result if valid, null if stale or not present
     */
    fun getIfValid(
        directiveKey: String,
        noteId: String,
        usesSelfAccess: Boolean,
        currentNotes: List<Note>,
        currentNote: Note?
    ): CachedDirectiveResult? {
        val cached = get(directiveKey, noteId, usesSelfAccess) ?: return null

        // Check if we should re-execute (handles both error retry and staleness)
        if (StalenessChecker.shouldReExecute(cached, currentNotes, currentNote)) {
            return null
        }

        return cached
    }

    /**
     * Get a cached result if valid, with L2 fallback (async).
     *
     * Checks L1 first, then L2 if available. Performs staleness check on results.
     *
     * @param directiveKey The cache key
     * @param noteId The note containing the directive
     * @param usesSelfAccess Whether the directive uses `.` self-reference
     * @param currentNotes All current notes for staleness check
     * @param currentNote The note containing this directive
     * @return Cached result if valid, null if stale or not present
     */
    suspend fun getIfValidWithL2Fallback(
        directiveKey: String,
        noteId: String,
        usesSelfAccess: Boolean,
        currentNotes: List<Note>,
        currentNote: Note?
    ): CachedDirectiveResult? {
        val cached = getWithL2Fallback(directiveKey, noteId, usesSelfAccess) ?: return null

        // Check if we should re-execute
        if (StalenessChecker.shouldReExecute(cached, currentNotes, currentNote)) {
            return null
        }

        return cached
    }

    /**
     * Store a result in the appropriate L1 cache.
     *
     * @param directiveKey The cache key
     * @param noteId The note containing the directive
     * @param usesSelfAccess Whether the directive uses `.` self-reference
     * @param result The result to cache
     */
    fun put(
        directiveKey: String,
        noteId: String,
        @Suppress("UNUSED_PARAMETER") usesSelfAccess: Boolean,
        result: CachedDirectiveResult
    ) {
        perNoteCache.put(noteId, directiveKey, result)
    }

    /**
     * Store a result in both L1 and L2 caches.
     *
     * L1 storage is synchronous, L2 storage is asynchronous.
     *
     * @param directiveKey The cache key
     * @param noteId The note containing the directive
     * @param usesSelfAccess Whether the directive uses `.` self-reference
     * @param result The result to cache
     */
    suspend fun putWithL2(
        directiveKey: String,
        noteId: String,
        usesSelfAccess: Boolean,
        result: CachedDirectiveResult
    ) {
        // Store in L1 immediately
        put(directiveKey, noteId, usesSelfAccess, result)

        // Store in L2 if available
        val l2 = l2Cache ?: return
        l2.putPerNote(noteId, directiveKey, result)
    }

    /**
     * Invalidate cached results that depend on specific notes (L1 only).
     *
     * Called when notes are modified to trigger re-evaluation of dependent directives.
     *
     * @param changedNoteIds IDs of notes that changed
     */
    fun invalidateForNotes(changedNoteIds: Set<String>) {
        // Clear per-note caches for changed notes
        for (noteId in changedNoteIds) {
            perNoteCache.clearNote(noteId)
        }

        // Note: Global cache invalidation is handled via staleness check
        // since global directives don't depend on specific notes directly
    }

    /**
     * Invalidate cached results with L2 clearing (async).
     *
     * @param changedNoteIds IDs of notes that changed
     */
    suspend fun invalidateForNotesWithL2(changedNoteIds: Set<String>) {
        // Clear L1
        invalidateForNotes(changedNoteIds)

        // Clear L2 if available
        val l2 = l2Cache ?: return
        for (noteId in changedNoteIds) {
            l2.clearNote(noteId)
        }
    }

    /**
     * Clear a specific note's cache (L1 only).
     */
    fun clearNote(noteId: String) {
        perNoteCache.clearNote(noteId)
    }

    /**
     * Clear a specific note's cache from both L1 and L2.
     */
    suspend fun clearNoteWithL2(noteId: String) {
        clearNote(noteId)
        l2Cache?.clearNote(noteId)
    }

    /**
     * Clear all caches.
     */
    fun clearAll() {
        globalCache.clear()
        perNoteCache.clear()
    }

    /**
     * Evict entries to reduce memory pressure.
     *
     * @param percentToKeep Percentage of entries to keep (0-100)
     */
    fun evictForMemoryPressure(percentToKeep: Int = 50) {
        val globalStats = globalCache.stats()
        val targetGlobalSize = (globalStats.size * percentToKeep) / 100
        globalCache.evictTo(targetGlobalSize)
    }

    /**
     * Get combined statistics for logging.
     */
    fun stats(): CombinedCacheStats {
        return CombinedCacheStats(
            global = globalCache.stats(),
            perNote = perNoteCache.stats()
        )
    }
}

/**
 * Combined statistics from all caches.
 */
data class CombinedCacheStats(
    val global: CacheStats,
    val perNote: PerNoteCacheStats
) {
    fun toLogString(): String {
        return "Global cache: ${global.utilizationPercent}% used (${global.size}/${global.maxSize}), " +
               "Per-note: ${perNote.utilizationPercent}% used (${perNote.totalEntries} entries across ${perNote.noteCount} notes)"
    }
}
