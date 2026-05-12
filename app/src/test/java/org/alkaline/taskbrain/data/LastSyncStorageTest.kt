package org.alkaline.taskbrain.data

import android.content.Context
import android.content.SharedPreferences
import com.google.firebase.Timestamp
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The watermark is per-user, ms-encoded. These tests cover the encode /
 * decode round-trip and per-user isolation. Storage layer (SharedPreferences)
 * is mocked because the JVM test runner has no Android Context; the goal is
 * to pin the encoding contract, not to test SharedPreferences itself.
 */
class LastSyncStorageTest {

    private val backing = HashMap<String, Long>()
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        // In-memory SharedPreferences double. Only the Long-typed surface is
        // exercised by LastSyncStorage.
        backing.clear()
        val editor = mockk<SharedPreferences.Editor>()
        val putKey = slot<String>()
        val putValue = slot<Long>()
        every { editor.putLong(capture(putKey), capture(putValue)) } answers {
            backing[putKey.captured] = putValue.captured
            editor
        }
        val removeKey = slot<String>()
        every { editor.remove(capture(removeKey)) } answers {
            backing.remove(removeKey.captured)
            editor
        }
        every { editor.apply() } returns Unit

        prefs = mockk()
        every { prefs.getLong(any(), 0L) } answers {
            backing[arg<String>(0)] ?: 0L
        }
        every { prefs.edit() } returns editor

        val ctx = mockk<Context>()
        val appCtx = mockk<Context>()
        every { ctx.applicationContext } returns appCtx
        every { appCtx.getSharedPreferences(any(), any()) } returns prefs

        // Force a fresh attach for each test so the prefs reference points
        // at the mock built above. The field is private; the only way to
        // re-attach is to test-call attach, which is idempotent — set a new
        // value by overwriting the static prefs field via reflection.
        val field = LastSyncStorage::class.java.getDeclaredField("prefs")
        field.isAccessible = true
        field.set(LastSyncStorage, null)
        LastSyncStorage.attach(ctx)
    }

    @Test
    fun `read returns Timestamp(0, 0) when unset`() {
        val ts = LastSyncStorage.read("uid-1")
        assertEquals(0L, ts.seconds)
        assertEquals(0, ts.nanoseconds)
    }

    @Test
    fun `write then read round-trips at ms precision`() {
        val original = Timestamp(1_700_000_000L, 123_000_000)
        LastSyncStorage.write("uid-1", original)
        val read = LastSyncStorage.read("uid-1")
        assertEquals(1_700_000_000L, read.seconds)
        assertEquals(123_000_000, read.nanoseconds)
    }

    @Test
    fun `per-user watermarks are isolated`() {
        LastSyncStorage.write("uid-a", Timestamp(100L, 0))
        LastSyncStorage.write("uid-b", Timestamp(200L, 0))
        assertEquals(100L, LastSyncStorage.read("uid-a").seconds)
        assertEquals(200L, LastSyncStorage.read("uid-b").seconds)
    }

    @Test
    fun `clear removes the watermark so the next read returns 0`() {
        LastSyncStorage.write("uid-1", Timestamp(500L, 0))
        LastSyncStorage.clear("uid-1")
        val ts = LastSyncStorage.read("uid-1")
        assertEquals(0L, ts.seconds)
        verify { prefs.edit() }
    }
}
