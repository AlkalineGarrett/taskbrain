package org.alkaline.taskbrain.dsl.cache

import org.alkaline.taskbrain.data.Note
import org.alkaline.taskbrain.dsl.runtime.NumberVal
import org.alkaline.taskbrain.dsl.runtime.StringVal
import org.alkaline.taskbrain.util.CacheStats
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for DirectiveCache implementations.
 * Phase 5: Cache architecture.
 */
class DirectiveCacheTest {

    // region Test Fixtures

    private fun createSuccessResult(value: Int): CachedDirectiveResult {
        return CachedDirectiveResult.success(
            result = NumberVal(value.toDouble()),
            dependencies = DirectiveDependencies.EMPTY
        )
    }

    private fun createSuccessResultWithDeps(
        value: Int,
        deps: DirectiveDependencies,
        metadataHashes: MetadataHashes = MetadataHashes.EMPTY
    ): CachedDirectiveResult {
        return CachedDirectiveResult.success(
            result = NumberVal(value.toDouble()),
            dependencies = deps,
            metadataHashes = metadataHashes
        )
    }

    // endregion

    // region GlobalDirectiveCache Tests

    @Test
    fun `GlobalDirectiveCache stores and retrieves results`() {
        val cache = GlobalDirectiveCache()

        val result = createSuccessResult(42)
        cache.put("hash1", result)

        assertEquals(result, cache.get("hash1"))
    }

    @Test
    fun `GlobalDirectiveCache returns null for missing key`() {
        val cache = GlobalDirectiveCache()

        assertNull(cache.get("missing"))
    }

    @Test
    fun `GlobalDirectiveCache respects max size`() {
        val cache = GlobalDirectiveCache(maxSize = 3)

        cache.put("hash1", createSuccessResult(1))
        cache.put("hash2", createSuccessResult(2))
        cache.put("hash3", createSuccessResult(3))
        cache.put("hash4", createSuccessResult(4))

        assertEquals(3, cache.stats().size)
        assertNull(cache.get("hash1"))  // Evicted
        assertEquals(4, (cache.get("hash4")?.result as? NumberVal)?.value?.toInt())
    }

    @Test
    fun `GlobalDirectiveCache remove works`() {
        val cache = GlobalDirectiveCache()

        cache.put("hash1", createSuccessResult(1))
        cache.remove("hash1")

        assertNull(cache.get("hash1"))
    }

    @Test
    fun `GlobalDirectiveCache clear removes all entries`() {
        val cache = GlobalDirectiveCache()

        cache.put("hash1", createSuccessResult(1))
        cache.put("hash2", createSuccessResult(2))
        cache.clear()

        assertEquals(0, cache.stats().size)
    }

    // endregion

    // region PerNoteDirectiveCache Tests

    @Test
    fun `PerNoteDirectiveCache stores and retrieves per-note results`() {
        val cache = PerNoteDirectiveCache()

        val result = createSuccessResult(42)
        cache.put("note1", "directive1", result)

        assertEquals(result, cache.get("note1", "directive1"))
    }

    @Test
    fun `PerNoteDirectiveCache isolates notes from each other`() {
        val cache = PerNoteDirectiveCache()

        cache.put("note1", "directive1", createSuccessResult(1))
        cache.put("note2", "directive1", createSuccessResult(2))

        assertEquals(1, (cache.get("note1", "directive1")?.result as? NumberVal)?.value?.toInt())
        assertEquals(2, (cache.get("note2", "directive1")?.result as? NumberVal)?.value?.toInt())
    }

    @Test
    fun `PerNoteDirectiveCache returns null for missing note`() {
        val cache = PerNoteDirectiveCache()

        assertNull(cache.get("missing", "directive1"))
    }

    @Test
    fun `PerNoteDirectiveCache returns null for missing directive in note`() {
        val cache = PerNoteDirectiveCache()

        cache.put("note1", "directive1", createSuccessResult(1))

        assertNull(cache.get("note1", "missing"))
    }

    @Test
    fun `PerNoteDirectiveCache clearNote removes only that note`() {
        val cache = PerNoteDirectiveCache()

        cache.put("note1", "directive1", createSuccessResult(1))
        cache.put("note2", "directive1", createSuccessResult(2))
        cache.clearNote("note1")

        assertNull(cache.get("note1", "directive1"))
        assertEquals(2, (cache.get("note2", "directive1")?.result as? NumberVal)?.value?.toInt())
    }

    @Test
    fun `PerNoteDirectiveCache clear removes all notes`() {
        val cache = PerNoteDirectiveCache()

        cache.put("note1", "directive1", createSuccessResult(1))
        cache.put("note2", "directive1", createSuccessResult(2))
        cache.clear()

        assertNull(cache.get("note1", "directive1"))
        assertNull(cache.get("note2", "directive1"))
    }

    @Test
    fun `PerNoteDirectiveCache respects per-note max size`() {
        val cache = PerNoteDirectiveCache(maxEntriesPerNote = 3)

        cache.put("note1", "d1", createSuccessResult(1))
        cache.put("note1", "d2", createSuccessResult(2))
        cache.put("note1", "d3", createSuccessResult(3))
        cache.put("note1", "d4", createSuccessResult(4))

        val stats = cache.noteStats("note1")
        assertEquals(3, stats?.size)
        assertNull(cache.get("note1", "d1"))  // Evicted
    }

    @Test
    fun `PerNoteDirectiveCache respects max notes limit`() {
        val cache = PerNoteDirectiveCache(maxTotalNotes = 3)

        cache.put("note1", "d1", createSuccessResult(1))
        cache.put("note2", "d1", createSuccessResult(2))
        cache.put("note3", "d1", createSuccessResult(3))
        cache.put("note4", "d1", createSuccessResult(4))

        val stats = cache.stats()
        assertEquals(3, stats.noteCount)
    }

    @Test
    fun `PerNoteDirectiveCache stats shows aggregate info`() {
        val cache = PerNoteDirectiveCache(maxEntriesPerNote = 100, maxTotalNotes = 500)

        cache.put("note1", "d1", createSuccessResult(1))
        cache.put("note1", "d2", createSuccessResult(2))
        cache.put("note2", "d1", createSuccessResult(3))

        val stats = cache.stats()
        assertEquals(2, stats.noteCount)
        assertEquals(3, stats.totalEntries)
        assertEquals(100, stats.maxEntriesPerNote)
        assertEquals(500, stats.maxNotes)
    }

    // endregion

    // region DirectiveCacheManager Tests

    private lateinit var manager: DirectiveCacheManager
    private val testNotes = listOf(
        Note(id = "note1", content = "Test note 1", path = "test/note1"),
        Note(id = "note2", content = "Test note 2", path = "test/note2")
    )

    @Before
    fun setUp() {
        manager = DirectiveCacheManager()
        // Phase 3: Clear cached metadata hashes before each test
        MetadataHasher.invalidateCache()
    }

    @Test
    fun `manager routes self-less directives to global cache`() {
        val result = createSuccessResult(42)

        manager.put("hash1", "note1", usesSelfAccess = false, result)
        val retrieved = manager.get("hash1", "note1", usesSelfAccess = false)

        assertEquals(result, retrieved)
    }

    @Test
    fun `manager routes self-referencing directives to per-note cache`() {
        val result = createSuccessResult(42)

        manager.put("hash1", "note1", usesSelfAccess = true, result)
        val retrieved = manager.get("hash1", "note1", usesSelfAccess = true)

        assertEquals(result, retrieved)
    }

    @Test
    fun `manager cache is per-note even for non-self-access`() {
        val result = createSuccessResult(42)

        manager.put("hash1", "note1", usesSelfAccess = false, result)
        val retrievedFromNote2 = manager.get("hash1", "note2", usesSelfAccess = false)

        assertNull(retrievedFromNote2)
        // Same note finds it
        assertEquals(result, manager.get("hash1", "note1", usesSelfAccess = false))
    }

    @Test
    fun `manager per-note cache is not shared across notes`() {
        val result = createSuccessResult(42)

        manager.put("hash1", "note1", usesSelfAccess = true, result)
        val retrievedFromNote2 = manager.get("hash1", "note2", usesSelfAccess = true)

        assertNull(retrievedFromNote2)
    }

    @Test
    fun `manager getIfValid returns null for stale results`() {
        val deps = DirectiveDependencies.EMPTY.copy(dependsOnNoteExistence = true)
        val result = createSuccessResultWithDeps(
            42, deps,
            MetadataHashes(existenceHash = "old-hash")
        )

        manager.put("hash1", "note1", usesSelfAccess = false, result)

        // Current notes have different existence hash
        val retrieved = manager.getIfValid(
            "hash1", "note1",
            usesSelfAccess = false,
            currentNotes = testNotes,
            currentNote = testNotes[0]
        )

        assertNull(retrieved)  // Stale
    }

    @Test
    fun `manager getIfValid returns result when fresh`() {
        val existenceHash = MetadataHasher.computeExistenceHash(testNotes)
        val deps = DirectiveDependencies.EMPTY.copy(dependsOnNoteExistence = true)
        val result = createSuccessResultWithDeps(
            42, deps,
            MetadataHashes(existenceHash = existenceHash)
        )

        manager.put("hash1", "note1", usesSelfAccess = false, result)

        val retrieved = manager.getIfValid(
            "hash1", "note1",
            usesSelfAccess = false,
            currentNotes = testNotes,
            currentNote = testNotes[0]
        )

        assertEquals(result, retrieved)
    }

    @Test
    fun `manager getIfValid returns null for non-deterministic errors`() {
        val errorResult = CachedDirectiveResult.error(NetworkError("Connection failed"))

        manager.put("hash1", "note1", usesSelfAccess = false, errorResult)

        val retrieved = manager.getIfValid(
            "hash1", "note1",
            usesSelfAccess = false,
            currentNotes = testNotes,
            currentNote = testNotes[0]
        )

        assertNull(retrieved)  // Should retry
    }

    @Test
    fun `manager getIfValid returns deterministic errors`() {
        val errorResult = CachedDirectiveResult.error(SyntaxError("Parse error"))

        manager.put("hash1", "note1", usesSelfAccess = false, errorResult)

        val retrieved = manager.getIfValid(
            "hash1", "note1",
            usesSelfAccess = false,
            currentNotes = testNotes,
            currentNote = testNotes[0]
        )

        assertEquals(errorResult, retrieved)  // Cached error
    }

    @Test
    fun `manager invalidateForNotes clears per-note caches`() {
        val result = createSuccessResult(42)

        manager.put("hash1", "note1", usesSelfAccess = true, result)
        manager.put("hash1", "note2", usesSelfAccess = true, result)
        manager.invalidateForNotes(setOf("note1"))

        assertNull(manager.get("hash1", "note1", usesSelfAccess = true))
        assertEquals(result, manager.get("hash1", "note2", usesSelfAccess = true))
    }

    @Test
    fun `manager clearNote clears only specified note`() {
        val result = createSuccessResult(42)

        manager.put("hash1", "note1", usesSelfAccess = true, result)
        manager.put("hash1", "note2", usesSelfAccess = true, result)
        manager.clearNote("note1")

        assertNull(manager.get("hash1", "note1", usesSelfAccess = true))
        assertEquals(result, manager.get("hash1", "note2", usesSelfAccess = true))
    }

    @Test
    fun `manager clearAll clears both caches`() {
        val result = createSuccessResult(42)

        manager.put("global", "note1", usesSelfAccess = false, result)
        manager.put("perNote", "note1", usesSelfAccess = true, result)
        manager.clearAll()

        assertNull(manager.get("global", "note1", usesSelfAccess = false))
        assertNull(manager.get("perNote", "note1", usesSelfAccess = true))
    }

    @Test
    fun `manager stats returns combined statistics`() {
        manager.put("hash1", "note1", usesSelfAccess = false, createSuccessResult(1))
        manager.put("hash2", "note1", usesSelfAccess = true, createSuccessResult(2))
        manager.put("hash3", "note2", usesSelfAccess = true, createSuccessResult(3))

        val stats = manager.stats()

        // All entries go to per-note cache (global cache not used)
        assertEquals(0, stats.global.size)
        assertEquals(2, stats.perNote.noteCount)
        assertEquals(3, stats.perNote.totalEntries)
    }

    // endregion

    // region CombinedCacheStats Tests

    @Test
    fun `CombinedCacheStats toLogString formats correctly`() {
        val stats = CombinedCacheStats(
            global = CacheStats(size = 50, maxSize = 1000, utilizationPercent = 5),
            perNote = PerNoteCacheStats(
                noteCount = 10,
                maxNotes = 500,
                totalEntries = 75,
                maxEntriesPerNote = 100,
                utilizationPercent = 7
            )
        )

        val log = stats.toLogString()

        assertTrue(log.contains("5%"))
        assertTrue(log.contains("50/1000"))
        assertTrue(log.contains("75 entries"))
        assertTrue(log.contains("10 notes"))
    }

    // endregion
}
