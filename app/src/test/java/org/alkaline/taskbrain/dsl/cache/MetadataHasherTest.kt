package org.alkaline.taskbrain.dsl.cache

import com.google.firebase.Timestamp
import org.alkaline.taskbrain.data.Note
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Date

/**
 * Tests for MetadataHasher.
 * Phase 1: Data structures and hashing infrastructure.
 */
class MetadataHasherTest {

    @Before
    fun setUp() {
        // Phase 3: Clear cached metadata hashes before each test
        MetadataHasher.invalidateCache()
    }

    // region computePathHash

    @Test
    fun `computePathHash returns consistent hash`() {
        val notes = listOf(
            createNote("1", "inbox"),
            createNote("2", "archive")
        )

        val hash1 = MetadataHasher.computePathHash(notes)
        val hash2 = MetadataHasher.computePathHash(notes)

        assertEquals(hash1, hash2)
    }

    @Test
    fun `computePathHash changes when path changes`() {
        val notes1 = listOf(createNote("1", "inbox"))
        val notes2 = listOf(createNote("1", "archive"))

        val hash1 = MetadataHasher.computePathHash(notes1)
        val hash2 = MetadataHasher.computePathHash(notes2)

        assertNotEquals(hash1, hash2)
    }

    @Test
    fun `computePathHash is order independent`() {
        val notesOrder1 = listOf(
            createNote("1", "inbox"),
            createNote("2", "archive")
        )
        val notesOrder2 = listOf(
            createNote("2", "archive"),
            createNote("1", "inbox")
        )

        val hash1 = MetadataHasher.computePathHash(notesOrder1)
        val hash2 = MetadataHasher.computePathHash(notesOrder2)

        assertEquals(hash1, hash2)
    }

    // endregion

    // region computeExistenceHash

    @Test
    fun `computeExistenceHash changes when note is added`() {
        val notes1 = listOf(createNote("1", "a"))
        val notes2 = listOf(createNote("1", "a"), createNote("2", "b"))

        val hash1 = MetadataHasher.computeExistenceHash(notes1)
        val hash2 = MetadataHasher.computeExistenceHash(notes2)

        assertNotEquals(hash1, hash2)
    }

    @Test
    fun `computeExistenceHash changes when note is removed`() {
        val notes1 = listOf(createNote("1", "a"), createNote("2", "b"))
        val notes2 = listOf(createNote("1", "a"))

        val hash1 = MetadataHasher.computeExistenceHash(notes1)
        val hash2 = MetadataHasher.computeExistenceHash(notes2)

        assertNotEquals(hash1, hash2)
    }

    @Test
    fun `computeExistenceHash same when content changes`() {
        val notes1 = listOf(createNote("1", "path1"))
        val notes2 = listOf(createNote("1", "path2")) // Different path, same ID

        val hash1 = MetadataHasher.computeExistenceHash(notes1)
        val hash2 = MetadataHasher.computeExistenceHash(notes2)

        assertEquals(hash1, hash2)
    }

    // endregion

    // region computeModifiedHash

    @Test
    fun `computeModifiedHash changes when timestamp changes`() {
        val time1 = Timestamp(Date(1000L))
        val time2 = Timestamp(Date(2000L))

        val notes1 = listOf(createNote("1", "a", updatedAt = time1))
        val notes2 = listOf(createNote("1", "a", updatedAt = time2))

        val hash1 = MetadataHasher.computeModifiedHash(notes1)
        val hash2 = MetadataHasher.computeModifiedHash(notes2)

        assertNotEquals(hash1, hash2)
    }

    // endregion

    // region computeHashes

    @Test
    fun `computeHashes only includes requested fields`() {
        val notes = listOf(createNote("1", "inbox"))

        val depsPathOnly = DirectiveDependencies(dependsOnPath = true)
        val hashesPathOnly = MetadataHasher.computeHashes(notes, depsPathOnly)

        assertNotNull(hashesPathOnly.pathHash)
        assertNull(hashesPathOnly.modifiedHash)
        assertNull(hashesPathOnly.createdHash)
        assertNull(hashesPathOnly.existenceHash)
    }

    @Test
    fun `computeHashes includes multiple fields`() {
        val notes = listOf(createNote("1", "inbox"))

        val deps = DirectiveDependencies(
            dependsOnPath = true,
            dependsOnNoteExistence = true
        )
        val hashes = MetadataHasher.computeHashes(notes, deps)

        assertNotNull(hashes.pathHash)
        assertNotNull(hashes.existenceHash)
        assertNull(hashes.modifiedHash)
    }

    @Test
    fun `computeHashes returns empty for no dependencies`() {
        val notes = listOf(createNote("1", "inbox"))

        val deps = DirectiveDependencies.EMPTY
        val hashes = MetadataHasher.computeHashes(notes, deps)

        assertNull(hashes.pathHash)
        assertNull(hashes.modifiedHash)
        assertNull(hashes.createdHash)
        assertNull(hashes.existenceHash)
    }

    // endregion

    private fun createNote(
        id: String,
        path: String,
        updatedAt: Timestamp? = null
    ) = Note(
        id = id,
        path = path,
        updatedAt = updatedAt
    )
}
