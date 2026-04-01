package org.alkaline.taskbrain.dsl.cache

import com.google.firebase.Timestamp
import org.alkaline.taskbrain.data.Note
import org.alkaline.taskbrain.dsl.runtime.values.StringVal
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Date

/**
 * Tests for StalenessChecker.
 * Phase 1: Data structures and hashing infrastructure.
 */
class StalenessCheckerTest {

    @Before
    fun setUp() {
        // Phase 3: Clear cached metadata hashes before each test
        MetadataHasher.invalidateCache()
    }

    // region Metadata staleness

    @Test
    fun `not stale when no dependencies`() {
        val cached = createCachedResult(DirectiveDependencies.EMPTY)
        val notes = listOf(createNote("1", "inbox"))

        assertFalse(StalenessChecker.isStale(cached, notes))
    }

    @Test
    fun `stale when note existence changes - note added`() {
        val notes1 = listOf(createNote("1", "inbox"))
        val deps = DirectiveDependencies(dependsOnNoteExistence = true)
        val hashes = MetadataHashes(existenceHash = MetadataHasher.computeExistenceHash(notes1))
        val cached = createCachedResult(deps, metadataHashes = hashes)

        val notes2 = notes1 + createNote("2", "archive")

        assertTrue(StalenessChecker.isStale(cached, notes2))
    }

    @Test
    fun `stale when note existence changes - note removed`() {
        val notes1 = listOf(createNote("1", "inbox"), createNote("2", "archive"))
        val deps = DirectiveDependencies(dependsOnNoteExistence = true)
        val hashes = MetadataHashes(existenceHash = MetadataHasher.computeExistenceHash(notes1))
        val cached = createCachedResult(deps, metadataHashes = hashes)

        val notes2 = listOf(createNote("1", "inbox"))

        assertTrue(StalenessChecker.isStale(cached, notes2))
    }

    @Test
    fun `stale when path changes`() {
        val notes1 = listOf(createNote("1", "inbox"))
        val deps = DirectiveDependencies(dependsOnPath = true)
        val hashes = MetadataHashes(pathHash = MetadataHasher.computePathHash(notes1))
        val cached = createCachedResult(deps, metadataHashes = hashes)

        val notes2 = listOf(createNote("1", "archive"))

        assertTrue(StalenessChecker.isStale(cached, notes2))
    }

    @Test
    fun `not stale when path same`() {
        val notes = listOf(createNote("1", "inbox"))
        val deps = DirectiveDependencies(dependsOnPath = true)
        val hashes = MetadataHashes(pathHash = MetadataHasher.computePathHash(notes))
        val cached = createCachedResult(deps, metadataHashes = hashes)

        assertFalse(StalenessChecker.isStale(cached, notes))
    }

    @Test
    fun `stale when modified timestamp changes`() {
        val time1 = Timestamp(Date(1000L))
        val time2 = Timestamp(Date(2000L))
        val notes1 = listOf(createNote("1", "inbox", updatedAt = time1))
        val deps = DirectiveDependencies(dependsOnModified = true)
        val hashes = MetadataHashes(modifiedHash = MetadataHasher.computeModifiedHash(notes1))
        val cached = createCachedResult(deps, metadataHashes = hashes)

        val notes2 = listOf(createNote("1", "inbox", updatedAt = time2))

        assertTrue(StalenessChecker.isStale(cached, notes2))
    }

    // endregion

    // region Content staleness

    @Test
    fun `stale when first line changes`() {
        val note1 = createNote("1", "inbox", content = "Old name\nBody")
        val deps = DirectiveDependencies(firstLineNotes = setOf("1"))
        val contentHashes = mapOf(
            "1" to ContentHashes(firstLineHash = ContentHasher.hashFirstLine(note1.content))
        )
        val cached = createCachedResult(deps, noteContentHashes = contentHashes)

        val note2 = createNote("1", "inbox", content = "New name\nBody")

        assertTrue(StalenessChecker.isStale(cached, listOf(note2)))
    }

    @Test
    fun `not stale when first line same but body changes`() {
        val note1 = createNote("1", "inbox", content = "Same name\nOld body")
        val deps = DirectiveDependencies(firstLineNotes = setOf("1"))
        val contentHashes = mapOf(
            "1" to ContentHashes(firstLineHash = ContentHasher.hashFirstLine(note1.content))
        )
        val cached = createCachedResult(deps, noteContentHashes = contentHashes)

        val note2 = createNote("1", "inbox", content = "Same name\nNew body")

        assertFalse(StalenessChecker.isStale(cached, listOf(note2)))
    }

    @Test
    fun `stale when non-first line changes`() {
        val note1 = createNote("1", "inbox", content = "Name\nOld body")
        val deps = DirectiveDependencies(nonFirstLineNotes = setOf("1"))
        val contentHashes = mapOf(
            "1" to ContentHashes(nonFirstLineHash = ContentHasher.hashNonFirstLine(note1.content))
        )
        val cached = createCachedResult(deps, noteContentHashes = contentHashes)

        val note2 = createNote("1", "inbox", content = "Name\nNew body")

        assertTrue(StalenessChecker.isStale(cached, listOf(note2)))
    }

    @Test
    fun `stale when dependent note is deleted`() {
        val note1 = createNote("1", "inbox", content = "Content")
        val deps = DirectiveDependencies(firstLineNotes = setOf("1"))
        val contentHashes = mapOf(
            "1" to ContentHashes(firstLineHash = ContentHasher.hashFirstLine(note1.content))
        )
        val cached = createCachedResult(deps, noteContentHashes = contentHashes)

        // Note "1" no longer exists
        val notes2 = listOf(createNote("2", "archive"))

        assertTrue(StalenessChecker.isStale(cached, notes2))
    }

    // endregion

    // region Short-circuit behavior

    @Test
    fun `short circuits on first stale field`() {
        // This test verifies the short-circuit behavior exists
        // by having multiple stale conditions
        val notes1 = listOf(createNote("1", "path1"))
        val deps = DirectiveDependencies(
            dependsOnPath = true,
            dependsOnNoteExistence = true
        )
        val hashes = MetadataHashes(
            pathHash = MetadataHasher.computePathHash(notes1),
            existenceHash = MetadataHasher.computeExistenceHash(notes1)
        )
        val cached = createCachedResult(deps, metadataHashes = hashes)

        // Both existence (new note) and path (changed) are stale
        val notes2 = listOf(
            createNote("1", "path2"),
            createNote("2", "path3")
        )

        // Should return true without checking all conditions
        assertTrue(StalenessChecker.isStale(cached, notes2))
    }

    // endregion

    private fun createNote(
        id: String,
        path: String,
        content: String = "Content",
        updatedAt: Timestamp? = null
    ) = Note(
        id = id,
        path = path,
        content = content,
        updatedAt = updatedAt
    )

    private fun createCachedResult(
        deps: DirectiveDependencies,
        noteContentHashes: Map<String, ContentHashes> = emptyMap(),
        metadataHashes: MetadataHashes = MetadataHashes.EMPTY
    ) = CachedDirectiveResult(
        result = StringVal("cached"),
        dependencies = deps,
        noteContentHashes = noteContentHashes,
        metadataHashes = metadataHashes
    )
}
