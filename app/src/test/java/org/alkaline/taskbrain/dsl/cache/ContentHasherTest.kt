package org.alkaline.taskbrain.dsl.cache

import com.google.firebase.Timestamp
import org.alkaline.taskbrain.data.Note
import org.alkaline.taskbrain.util.Sha256Hasher
import org.junit.Assert.*
import org.junit.Test
import java.util.Date

/**
 * Tests for ContentHasher.
 */
class ContentHasherTest {

    // region hashFirstLine

    @Test
    fun `hashFirstLine returns hash of first line`() {
        val content = "First line\nSecond line\nThird line"
        val hash = ContentHasher.hashFirstLine(content)

        assertNotNull(hash)
        assertTrue(hash.isNotEmpty())
        // Hash should be consistent
        assertEquals(hash, ContentHasher.hashFirstLine(content))
    }

    @Test
    fun `hashFirstLine handles single line content`() {
        val content = "Only one line"
        val hash = ContentHasher.hashFirstLine(content)

        assertNotNull(hash)
        assertEquals(hash, ContentHasher.hashFirstLine("Only one line"))
    }

    @Test
    fun `hashFirstLine handles empty content`() {
        val hash = ContentHasher.hashFirstLine("")

        assertNotNull(hash)
    }

    @Test
    fun `hashFirstLine differs for different first lines`() {
        val hash1 = ContentHasher.hashFirstLine("Line A\nRest")
        val hash2 = ContentHasher.hashFirstLine("Line B\nRest")

        assertNotEquals(hash1, hash2)
    }

    @Test
    fun `hashFirstLine same for same first line with different rest`() {
        val hash1 = ContentHasher.hashFirstLine("Same first\nDifferent A")
        val hash2 = ContentHasher.hashFirstLine("Same first\nDifferent B")

        assertEquals(hash1, hash2)
    }

    // endregion

    // region hashNonFirstLine

    @Test
    fun `hashNonFirstLine returns hash of content after first line`() {
        val content = "First line\nSecond line\nThird line"
        val hash = ContentHasher.hashNonFirstLine(content)

        assertNotNull(hash)
        assertTrue(hash.isNotEmpty())
    }

    @Test
    fun `hashNonFirstLine returns empty hash for single line`() {
        val content = "Only one line"
        val hash = ContentHasher.hashNonFirstLine(content)

        // Should hash empty string
        assertEquals(ContentHasher.hashNonFirstLine("Another single line"), hash)
    }

    @Test
    fun `hashNonFirstLine differs for different non-first content`() {
        val hash1 = ContentHasher.hashNonFirstLine("Same\nContent A")
        val hash2 = ContentHasher.hashNonFirstLine("Same\nContent B")

        assertNotEquals(hash1, hash2)
    }

    @Test
    fun `hashNonFirstLine same for same non-first with different first`() {
        val hash1 = ContentHasher.hashNonFirstLine("First A\nSame content")
        val hash2 = ContentHasher.hashNonFirstLine("First B\nSame content")

        assertEquals(hash1, hash2)
    }

    // endregion

    // region hashField

    @Test
    fun `hashField NAME hashes first line`() {
        val note = createNote(content = "My Name\nBody content")
        val hash = ContentHasher.hashField(note, NoteField.NAME)

        assertEquals(ContentHasher.hashFirstLine(note.content), hash)
    }

    @Test
    fun `hashField PATH hashes path`() {
        val note = createNote(path = "inbox/tasks")
        val hash = ContentHasher.hashField(note, NoteField.PATH)

        assertEquals(Sha256Hasher.hash("inbox/tasks"), hash)
    }

    @Test
    fun `hashField MODIFIED hashes updatedAt timestamp`() {
        val timestamp = Timestamp(Date(1234567890000L))
        val note = createNote(updatedAt = timestamp)
        val hash = ContentHasher.hashField(note, NoteField.MODIFIED)

        assertEquals(Sha256Hasher.hash("1234567890000"), hash)
    }

    @Test
    fun `hashField CREATED hashes createdAt timestamp`() {
        val timestamp = Timestamp(Date(1234567890000L))
        val note = createNote(createdAt = timestamp)
        val hash = ContentHasher.hashField(note, NoteField.CREATED)

        assertEquals(Sha256Hasher.hash("1234567890000"), hash)
    }


    @Test
    fun `hashField handles null timestamps`() {
        val note = createNote(updatedAt = null)
        val hash = ContentHasher.hashField(note, NoteField.MODIFIED)

        // Should hash empty string
        assertEquals(Sha256Hasher.hash(""), hash)
    }

    // endregion

    // region computeContentHashes

    @Test
    fun `computeContentHashes includes only requested hashes`() {
        val note = createNote(content = "First\nSecond\nThird")

        val hashesFirstOnly = ContentHasher.computeContentHashes(note, needsFirstLine = true, needsNonFirstLine = false)
        assertNotNull(hashesFirstOnly.firstLineHash)
        assertNull(hashesFirstOnly.nonFirstLineHash)

        val hashesNonFirstOnly = ContentHasher.computeContentHashes(note, needsFirstLine = false, needsNonFirstLine = true)
        assertNull(hashesNonFirstOnly.firstLineHash)
        assertNotNull(hashesNonFirstOnly.nonFirstLineHash)

        val hashesBoth = ContentHasher.computeContentHashes(note, needsFirstLine = true, needsNonFirstLine = true)
        assertNotNull(hashesBoth.firstLineHash)
        assertNotNull(hashesBoth.nonFirstLineHash)

        val hashesNeither = ContentHasher.computeContentHashes(note, needsFirstLine = false, needsNonFirstLine = false)
        assertNull(hashesNeither.firstLineHash)
        assertNull(hashesNeither.nonFirstLineHash)
    }

    // endregion

    // region hash consistency

    @Test
    fun `hash is deterministic`() {
        val input = "test input string"
        val hash1 = Sha256Hasher.hash(input)
        val hash2 = Sha256Hasher.hash(input)

        assertEquals(hash1, hash2)
    }

    @Test
    fun `hash is hex string`() {
        val hash = Sha256Hasher.hash("test")

        assertTrue(hash.matches(Regex("[0-9a-f]+")))
        assertEquals(64, hash.length) // SHA-256 produces 64 hex chars
    }

    // endregion

    private fun createNote(
        id: String = "note1",
        path: String = "test",
        content: String = "Content",
        createdAt: Timestamp? = null,
        updatedAt: Timestamp? = null,
    ) = Note(
        id = id,
        path = path,
        content = content,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
