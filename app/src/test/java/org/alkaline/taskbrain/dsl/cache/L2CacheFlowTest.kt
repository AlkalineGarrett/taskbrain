package org.alkaline.taskbrain.dsl.cache

import kotlinx.coroutines.runBlocking
import org.alkaline.taskbrain.data.Note
import org.alkaline.taskbrain.dsl.runtime.NumberVal
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for L1/L2 cache flow in DirectiveCacheManager.
 * Phase 6: Firestore persistence layer.
 */
class L2CacheFlowTest {

    private lateinit var l2Cache: InMemoryL2Cache
    private lateinit var manager: DirectiveCacheManager

    private val testNotes = listOf(
        Note(id = "note1", content = "Test note 1", path = "test/note1"),
        Note(id = "note2", content = "Test note 2", path = "test/note2")
    )

    @Before
    fun setUp() {
        l2Cache = InMemoryL2Cache()
        manager = DirectiveCacheManager(l2Cache = l2Cache)
        // Phase 3: Clear cached metadata hashes before each test
        MetadataHasher.invalidateCache()
    }

    // region L2 Fallback Tests

    @Test
    fun `getWithL2Fallback returns L1 result when present`() = runBlocking {
        val result = createResult(42)

        // Put in L1 only
        manager.put("hash1", "note1", usesSelfAccess = false, result)

        // Should return from L1 without touching L2
        val retrieved = manager.getWithL2Fallback("hash1", "note1", usesSelfAccess = false)

        assertEquals(result, retrieved)
        assertEquals(0, l2Cache.globalGetCount)
    }

    @Test
    fun `getWithL2Fallback falls back to L2 on L1 miss`() = runBlocking {
        val result = createResult(42)

        // Put directly in L2 per-note cache
        l2Cache.putPerNote("note1", "hash1", result)

        // L1 miss should trigger L2 lookup
        val retrieved = manager.getWithL2Fallback("hash1", "note1", usesSelfAccess = false)

        assertEquals(result, retrieved)
        assertEquals(1, l2Cache.perNoteGetCount)
    }

    @Test
    fun `getWithL2Fallback populates L1 from L2 hit`() = runBlocking {
        val result = createResult(42)

        // Put directly in L2 per-note cache
        l2Cache.putPerNote("note1", "hash1", result)

        // First call - L2 hit, should populate L1
        manager.getWithL2Fallback("hash1", "note1", usesSelfAccess = false)

        // Second call - should hit L1, not L2
        manager.getWithL2Fallback("hash1", "note1", usesSelfAccess = false)

        assertEquals(1, l2Cache.perNoteGetCount)  // Only one L2 access
    }

    @Test
    fun `getWithL2Fallback returns null when both L1 and L2 miss`() = runBlocking {
        val retrieved = manager.getWithL2Fallback("missing", "note1", usesSelfAccess = false)

        assertNull(retrieved)
        assertEquals(1, l2Cache.perNoteGetCount)
    }

    @Test
    fun `getWithL2Fallback works for per-note cache`() = runBlocking {
        val result = createResult(42)

        // Put directly in L2 per-note cache
        l2Cache.putPerNote("note1", "hash1", result)

        // Should fall back to L2
        val retrieved = manager.getWithL2Fallback("hash1", "note1", usesSelfAccess = true)

        assertEquals(result, retrieved)
        assertEquals(1, l2Cache.perNoteGetCount)
    }

    @Test
    fun `per-note L2 cache is isolated per note`() = runBlocking {
        val result = createResult(42)

        // Put in note1's L2 cache
        l2Cache.putPerNote("note1", "hash1", result)

        // Should not find in note2's cache
        val retrieved = manager.getWithL2Fallback("hash1", "note2", usesSelfAccess = true)

        assertNull(retrieved)
    }

    // endregion

    // region L2 Storage Tests

    @Test
    fun `putWithL2 stores in both L1 and L2`() = runBlocking {
        val result = createResult(42)

        manager.putWithL2("hash1", "note1", usesSelfAccess = false, result)

        // Verify L1
        val l1Result = manager.get("hash1", "note1", usesSelfAccess = false)
        assertEquals(result, l1Result)

        // Verify L2 (always per-note now)
        val l2Result = l2Cache.getPerNote("note1", "hash1")
        assertEquals(result, l2Result)
    }

    @Test
    fun `putWithL2 works for per-note cache`() = runBlocking {
        val result = createResult(42)

        manager.putWithL2("hash1", "note1", usesSelfAccess = true, result)

        // Verify L1
        val l1Result = manager.get("hash1", "note1", usesSelfAccess = true)
        assertEquals(result, l1Result)

        // Verify L2
        val l2Result = l2Cache.getPerNote("note1", "hash1")
        assertEquals(result, l2Result)
    }

    // endregion

    // region Staleness with L2 Tests

    @Test
    fun `getIfValidWithL2Fallback returns null for stale L2 result`() = runBlocking {
        val deps = DirectiveDependencies.EMPTY.copy(dependsOnNoteExistence = true)
        val result = CachedDirectiveResult.success(
            result = NumberVal(42.0),
            dependencies = deps,
            metadataHashes = MetadataHashes(existenceHash = "old-hash")
        )

        // Put stale result in L2
        l2Cache.putPerNote("note1", "hash1", result)

        // Should return null because stale
        val retrieved = manager.getIfValidWithL2Fallback(
            "hash1", "note1",
            usesSelfAccess = false,
            currentNotes = testNotes,
            currentNote = testNotes[0]
        )

        assertNull(retrieved)
    }

    @Test
    fun `getIfValidWithL2Fallback returns fresh L2 result`() = runBlocking {
        val existenceHash = MetadataHasher.computeExistenceHash(testNotes)
        val deps = DirectiveDependencies.EMPTY.copy(dependsOnNoteExistence = true)
        val result = CachedDirectiveResult.success(
            result = NumberVal(42.0),
            dependencies = deps,
            metadataHashes = MetadataHashes(existenceHash = existenceHash)
        )

        // Put fresh result in L2
        l2Cache.putPerNote("note1", "hash1", result)

        // Should return result
        val retrieved = manager.getIfValidWithL2Fallback(
            "hash1", "note1",
            usesSelfAccess = false,
            currentNotes = testNotes,
            currentNote = testNotes[0]
        )

        assertEquals(result, retrieved)
    }

    @Test
    fun `getIfValidWithL2Fallback returns null for non-deterministic error`() = runBlocking {
        val result = CachedDirectiveResult.error(NetworkError("Connection failed"))

        // Put non-deterministic error in L2
        l2Cache.putPerNote("note1", "hash1", result)

        // Should return null (retry)
        val retrieved = manager.getIfValidWithL2Fallback(
            "hash1", "note1",
            usesSelfAccess = false,
            currentNotes = testNotes,
            currentNote = testNotes[0]
        )

        assertNull(retrieved)
    }

    // endregion

    // region Invalidation Tests

    @Test
    fun `invalidateForNotesWithL2 clears both L1 and L2`() = runBlocking {
        val result = createResult(42)

        // Put in both caches
        manager.putWithL2("hash1", "note1", usesSelfAccess = true, result)

        // Invalidate
        manager.invalidateForNotesWithL2(setOf("note1"))

        // Verify both cleared
        assertNull(manager.get("hash1", "note1", usesSelfAccess = true))
        assertNull(l2Cache.getPerNote("note1", "hash1"))
    }

    @Test
    fun `clearNoteWithL2 clears both L1 and L2`() = runBlocking {
        val result = createResult(42)

        // Put in both caches
        manager.putWithL2("hash1", "note1", usesSelfAccess = true, result)

        // Clear
        manager.clearNoteWithL2("note1")

        // Verify both cleared
        assertNull(manager.get("hash1", "note1", usesSelfAccess = true))
        assertNull(l2Cache.getPerNote("note1", "hash1"))
    }

    // endregion

    // region NoOpL2Cache Tests

    @Test
    fun `manager works without L2 cache`() = runBlocking {
        val managerNoL2 = DirectiveCacheManager()  // No L2
        val result = createResult(42)

        managerNoL2.put("hash1", "note1", usesSelfAccess = false, result)

        // getWithL2Fallback should still work
        val retrieved = managerNoL2.getWithL2Fallback("hash1", "note1", usesSelfAccess = false)
        assertEquals(result, retrieved)
    }

    @Test
    fun `NoOpL2Cache always returns null`() = runBlocking {
        val noOp = NoOpL2Cache()

        noOp.putGlobal("hash1", createResult(42))
        assertNull(noOp.getGlobal("hash1"))

        noOp.putPerNote("note1", "hash1", createResult(42))
        assertNull(noOp.getPerNote("note1", "hash1"))
    }

    // endregion

    // region Helper Functions

    private fun createResult(value: Int) = CachedDirectiveResult.success(
        result = NumberVal(value.toDouble()),
        dependencies = DirectiveDependencies.EMPTY
    )

    // endregion
}

/**
 * In-memory implementation of L2DirectiveCache for testing.
 */
class InMemoryL2Cache : L2DirectiveCache {
    private val globalStorage = mutableMapOf<String, CachedDirectiveResult>()
    private val perNoteStorage = mutableMapOf<String, MutableMap<String, CachedDirectiveResult>>()

    var globalGetCount = 0
        private set
    var perNoteGetCount = 0
        private set

    override suspend fun getGlobal(directiveHash: String): CachedDirectiveResult? {
        globalGetCount++
        return globalStorage[directiveHash]
    }

    override suspend fun putGlobal(directiveHash: String, result: CachedDirectiveResult) {
        globalStorage[directiveHash] = result
    }

    override suspend fun removeGlobal(directiveHash: String) {
        globalStorage.remove(directiveHash)
    }

    override suspend fun getPerNote(noteId: String, directiveHash: String): CachedDirectiveResult? {
        perNoteGetCount++
        return perNoteStorage[noteId]?.get(directiveHash)
    }

    override suspend fun putPerNote(noteId: String, directiveHash: String, result: CachedDirectiveResult) {
        perNoteStorage.getOrPut(noteId) { mutableMapOf() }[directiveHash] = result
    }

    override suspend fun removePerNote(noteId: String, directiveHash: String) {
        perNoteStorage[noteId]?.remove(directiveHash)
    }

    override suspend fun clearNote(noteId: String) {
        perNoteStorage.remove(noteId)
    }

    fun reset() {
        globalStorage.clear()
        perNoteStorage.clear()
        globalGetCount = 0
        perNoteGetCount = 0
    }
}
