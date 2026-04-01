package org.alkaline.taskbrain.dsl.cache

import org.alkaline.taskbrain.data.Note
import org.alkaline.taskbrain.dsl.runtime.values.NumberVal
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for error caching behavior.
 * Phase 4: Error classification for caching.
 */
class ErrorCachingTest {

    @Before
    fun setUp() {
        // Phase 3: Clear cached metadata hashes before each test
        MetadataHasher.invalidateCache()
    }

    // region CachedDirectiveResult Error Support

    @Test
    fun `CachedDirectiveResult success factory creates success result`() {
        val result = CachedDirectiveResult.success(
            result = NumberVal(42.0),
            dependencies = DirectiveDependencies.EMPTY
        )

        assertTrue(result.isSuccess)
        assertFalse(result.isError)
        assertEquals(NumberVal(42.0), result.result)
        assertNull(result.error)
    }

    @Test
    fun `CachedDirectiveResult error factory creates error result`() {
        val error = SyntaxError("Parse error")
        val result = CachedDirectiveResult.error(error = error)

        assertTrue(result.isError)
        assertFalse(result.isSuccess)
        assertNull(result.result)
        assertEquals(error, result.error)
    }

    @Test
    fun `shouldRetryError is false for deterministic errors`() {
        val error = SyntaxError("Parse error")
        val result = CachedDirectiveResult.error(error = error)

        assertFalse(result.shouldRetryError)
    }

    @Test
    fun `shouldRetryError is true for non-deterministic errors`() {
        val error = NetworkError("Connection failed")
        val result = CachedDirectiveResult.error(error = error)

        assertTrue(result.shouldRetryError)
    }

    @Test
    fun `shouldRetryError is false for success results`() {
        val result = CachedDirectiveResult.success(
            result = NumberVal(42.0),
            dependencies = DirectiveDependencies.EMPTY
        )

        assertFalse(result.shouldRetryError)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `CachedDirectiveResult rejects both result and error`() {
        CachedDirectiveResult(
            result = NumberVal(42.0),
            error = SyntaxError("Error"),
            dependencies = DirectiveDependencies.EMPTY,
            noteContentHashes = emptyMap(),
            metadataHashes = MetadataHashes.EMPTY
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `CachedDirectiveResult rejects neither result nor error`() {
        CachedDirectiveResult(
            result = null,
            error = null,
            dependencies = DirectiveDependencies.EMPTY,
            noteContentHashes = emptyMap(),
            metadataHashes = MetadataHashes.EMPTY
        )
    }

    // endregion

    // region StalenessChecker.shouldReExecute

    private val testNotes = listOf(
        Note(id = "1", content = "Test note", path = "test")
    )

    @Test
    fun `shouldReExecute returns true for non-deterministic errors`() {
        val error = NetworkError("Connection failed")
        val cached = CachedDirectiveResult.error(error = error)

        assertTrue(StalenessChecker.shouldReExecute(cached, testNotes))
    }

    @Test
    fun `shouldReExecute returns false for fresh deterministic errors`() {
        val error = SyntaxError("Parse error")
        val cached = CachedDirectiveResult.error(error = error)

        // No dependencies, so not stale
        assertFalse(StalenessChecker.shouldReExecute(cached, testNotes))
    }

    @Test
    fun `shouldReExecute returns false for fresh success results`() {
        val cached = CachedDirectiveResult.success(
            result = NumberVal(42.0),
            dependencies = DirectiveDependencies.EMPTY
        )

        // No dependencies, so not stale
        assertFalse(StalenessChecker.shouldReExecute(cached, testNotes))
    }

    @Test
    fun `shouldReExecute returns true for stale results with dependencies`() {
        val cached = CachedDirectiveResult.success(
            result = NumberVal(42.0),
            dependencies = DirectiveDependencies.EMPTY.copy(dependsOnNoteExistence = true),
            metadataHashes = MetadataHashes(existenceHash = "old-hash")
        )

        // Existence hash changed → stale
        assertTrue(StalenessChecker.shouldReExecute(cached, testNotes))
    }

    @Test
    fun `shouldReExecute returns true for TimeoutError`() {
        val error = TimeoutError("Request timed out")
        val cached = CachedDirectiveResult.error(error = error)

        assertTrue(StalenessChecker.shouldReExecute(cached, testNotes))
    }

    @Test
    fun `shouldReExecute returns true for ResourceUnavailableError`() {
        val error = ResourceUnavailableError("Note not found")
        val cached = CachedDirectiveResult.error(error = error)

        assertTrue(StalenessChecker.shouldReExecute(cached, testNotes))
    }

    @Test
    fun `shouldReExecute returns true for PermissionError`() {
        val error = PermissionError("Access denied")
        val cached = CachedDirectiveResult.error(error = error)

        assertTrue(StalenessChecker.shouldReExecute(cached, testNotes))
    }

    @Test
    fun `shouldReExecute returns true for ExternalServiceError`() {
        val error = ExternalServiceError("Firebase unavailable")
        val cached = CachedDirectiveResult.error(error = error)

        assertTrue(StalenessChecker.shouldReExecute(cached, testNotes))
    }

    @Test
    fun `shouldReExecute returns false for TypeError`() {
        val error = TypeError("Expected number, got string")
        val cached = CachedDirectiveResult.error(error = error)

        assertFalse(StalenessChecker.shouldReExecute(cached, testNotes))
    }

    @Test
    fun `shouldReExecute returns false for ArgumentError`() {
        val error = ArgumentError("Missing required argument")
        val cached = CachedDirectiveResult.error(error = error)

        assertFalse(StalenessChecker.shouldReExecute(cached, testNotes))
    }

    @Test
    fun `shouldReExecute returns false for CircularDependencyError`() {
        val error = CircularDependencyError("Circular dependency: A -> B -> A")
        val cached = CachedDirectiveResult.error(error = error)

        assertFalse(StalenessChecker.shouldReExecute(cached, testNotes))
    }

    // endregion

    // region Deterministic Error Caching Scenarios

    @Test
    fun `deterministic error is reused when dependencies unchanged`() {
        val error = UnknownIdentifierError("Unknown function 'foo'")
        val cached = CachedDirectiveResult.error(error = error)

        // Should NOT re-execute - error is deterministic and no dependencies changed
        assertFalse(StalenessChecker.shouldReExecute(cached, testNotes))
    }

    @Test
    fun `deterministic error is re-executed when dependencies change`() {
        val error = UnknownIdentifierError("Unknown function 'foo'")
        val cached = CachedDirectiveResult.error(
            error = error,
            dependencies = DirectiveDependencies.EMPTY.copy(dependsOnNoteExistence = true),
            metadataHashes = MetadataHashes(existenceHash = "old-hash")
        )

        // Should re-execute - dependencies changed
        assertTrue(StalenessChecker.shouldReExecute(cached, testNotes))
    }

    // endregion

    // region Non-Deterministic Error Always Retry

    @Test
    fun `non-deterministic error always retries even with no dependencies`() {
        val error = NetworkError("Connection refused")
        val cached = CachedDirectiveResult.error(
            error = error,
            dependencies = DirectiveDependencies.EMPTY  // No dependencies
        )

        // Should ALWAYS re-execute non-deterministic errors
        assertTrue(StalenessChecker.shouldReExecute(cached, testNotes))
    }

    @Test
    fun `non-deterministic error retries regardless of dependencies`() {
        val error = TimeoutError("Timed out")
        val existenceHash = MetadataHasher.computeExistenceHash(testNotes)
        val cached = CachedDirectiveResult.error(
            error = error,
            dependencies = DirectiveDependencies.EMPTY.copy(dependsOnNoteExistence = true),
            metadataHashes = MetadataHashes(existenceHash = existenceHash)  // Hashes match!
        )

        // Should still re-execute - non-deterministic error overrides staleness check
        assertTrue(StalenessChecker.shouldReExecute(cached, testNotes))
    }

    // endregion
}
