package org.alkaline.taskbrain.dsl.cache

import org.alkaline.taskbrain.data.Note
import org.alkaline.taskbrain.dsl.runtime.values.NumberVal
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
    fun `manager stores and retrieves results`() {
        val result = createSuccessResult(42)

        manager.put("hash1", "note1", result)
        val retrieved = manager.get("hash1", "note1")

        assertEquals(result, retrieved)
    }

    @Test
    fun `manager stores per-note results`() {
        val result = createSuccessResult(42)

        manager.put("hash1", "note1", result)
        val retrieved = manager.get("hash1", "note1")

        assertEquals(result, retrieved)
    }

    @Test
    fun `manager cache is per-note`() {
        val result = createSuccessResult(42)

        manager.put("hash1", "note1", result)
        val retrievedFromNote2 = manager.get("hash1", "note2")

        assertNull(retrievedFromNote2)
        // Same note finds it
        assertEquals(result, manager.get("hash1", "note1"))
    }

    @Test
    fun `manager per-note cache is not shared across notes`() {
        val result = createSuccessResult(42)

        manager.put("hash1", "note1", result)
        val retrievedFromNote2 = manager.get("hash1", "note2")

        assertNull(retrievedFromNote2)
    }

    @Test
    fun `manager getIfValid returns null for stale results`() {
        val deps = DirectiveDependencies.EMPTY.copy(dependsOnNoteExistence = true)
        val result = createSuccessResultWithDeps(
            42, deps,
            MetadataHashes(existenceHash = "old-hash")
        )

        manager.put("hash1", "note1", result)

        // Current notes have different existence hash
        val retrieved = manager.getIfValid(
            "hash1", "note1",
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

        manager.put("hash1", "note1", result)

        val retrieved = manager.getIfValid(
            "hash1", "note1",
            currentNotes = testNotes,
            currentNote = testNotes[0]
        )

        assertEquals(result, retrieved)
    }

    @Test
    fun `manager getIfValid returns null for non-deterministic errors`() {
        val errorResult = CachedDirectiveResult.error(NetworkError("Connection failed"))

        manager.put("hash1", "note1", errorResult)

        val retrieved = manager.getIfValid(
            "hash1", "note1",
            currentNotes = testNotes,
            currentNote = testNotes[0]
        )

        assertNull(retrieved)  // Should retry
    }

    @Test
    fun `manager getIfValid returns deterministic errors`() {
        val errorResult = CachedDirectiveResult.error(SyntaxError("Parse error"))

        manager.put("hash1", "note1", errorResult)

        val retrieved = manager.getIfValid(
            "hash1", "note1",
            currentNotes = testNotes,
            currentNote = testNotes[0]
        )

        assertEquals(errorResult, retrieved)  // Cached error
    }

    @Test
    fun `manager invalidateForNotes clears per-note caches`() {
        val result = createSuccessResult(42)

        manager.put("hash1", "note1", result)
        manager.put("hash1", "note2", result)
        manager.invalidateForNotes(setOf("note1"))

        assertNull(manager.get("hash1", "note1"))
        assertEquals(result, manager.get("hash1", "note2"))
    }

    @Test
    fun `manager clearNote clears only specified note`() {
        val result = createSuccessResult(42)

        manager.put("hash1", "note1", result)
        manager.put("hash1", "note2", result)
        manager.clearNote("note1")

        assertNull(manager.get("hash1", "note1"))
        assertEquals(result, manager.get("hash1", "note2"))
    }

    @Test
    fun `manager clearAll clears all caches`() {
        val result = createSuccessResult(42)

        manager.put("global", "note1", result)
        manager.put("perNote", "note1", result)
        manager.clearAll()

        assertNull(manager.get("global", "note1"))
        assertNull(manager.get("perNote", "note1"))
    }

    @Test
    fun `manager stats returns per-note statistics`() {
        manager.put("hash1", "note1", createSuccessResult(1))
        manager.put("hash2", "note1", createSuccessResult(2))
        manager.put("hash3", "note2", createSuccessResult(3))

        val stats = manager.stats()

        assertEquals(2, stats.noteCount)
        assertEquals(3, stats.totalEntries)
    }

    // endregion
}
