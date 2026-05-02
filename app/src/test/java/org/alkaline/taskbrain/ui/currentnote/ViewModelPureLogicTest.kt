package org.alkaline.taskbrain.ui.currentnote

import com.google.firebase.Timestamp
import kotlinx.coroutines.test.runTest
import org.alkaline.taskbrain.data.Alarm
import org.alkaline.taskbrain.data.AlarmMarkers
import org.alkaline.taskbrain.data.AlarmStatus
import org.alkaline.taskbrain.data.NoteIdSentinel
import org.alkaline.taskbrain.data.NoteLine
import org.alkaline.taskbrain.ui.currentnote.util.SymbolBadge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class ViewModelPureLogicTest {

    // ==================== findAlarmNoteIdUpdates ====================

    @Test
    fun `findAlarmNoteIdUpdates returns empty when no directives`() = runTest {
        val lines = listOf(
            NoteLine("Plain text", "note1"),
            NoteLine("No alarms", "note2")
        )
        val updates = findAlarmNoteIdUpdates(lines) { null }
        assertTrue(updates.isEmpty())
    }

    @Test
    fun `findAlarmNoteIdUpdates detects stale noteId`() = runTest {
        val lines = listOf(
            NoteLine("Task [alarm(\"alarm1\")]", "note2")
        )
        val updates = findAlarmNoteIdUpdates(lines) { alarmId ->
            if (alarmId == "alarm1") "note1" else null // alarm currently on note1
        }
        assertEquals(1, updates.size)
        assertEquals("alarm1", updates[0].alarmId)
        assertEquals("note2", updates[0].lineNoteId) // should be updated to note2
    }

    @Test
    fun `findAlarmNoteIdUpdates skips when noteId matches`() = runTest {
        val lines = listOf(
            NoteLine("Task [alarm(\"alarm1\")]", "note1")
        )
        val updates = findAlarmNoteIdUpdates(lines) { "note1" }
        assertTrue(updates.isEmpty())
    }

    @Test
    fun `findAlarmNoteIdUpdates skips lines without noteId`() = runTest {
        val lines = listOf(
            NoteLine("Task [alarm(\"alarm1\")]", NoteIdSentinel.new(NoteIdSentinel.Origin.TYPED))
        )
        val updates = findAlarmNoteIdUpdates(lines) { "note1" }
        assertTrue(updates.isEmpty())
    }

    @Test
    fun `findAlarmNoteIdUpdates handles multiple directives on one line`() = runTest {
        val lines = listOf(
            NoteLine("Task [alarm(\"a1\")] [alarm(\"a2\")]", "note1")
        )
        val alarmNoteIds = mapOf("a1" to "note1", "a2" to "oldNote")
        val updates = findAlarmNoteIdUpdates(lines) { alarmNoteIds[it] }

        assertEquals(1, updates.size) // only a2 needs update
        assertEquals("a2", updates[0].alarmId)
    }

    // ==================== mapResultsByPosition ====================

    // ==================== extractAlarmIds ====================

    @Test
    fun `extractAlarmIds returns empty for lines without directives`() {
        val lines = listOf(
            NoteLine("Plain text", "note1"),
            NoteLine("No alarms here", "note2")
        )
        val result = extractAlarmIds(lines)
        assertTrue(result.alarmIds.isEmpty())
        assertTrue(result.recurringAlarmIds.isEmpty())
    }

    @Test
    fun `extractAlarmIds extracts single alarm ID`() {
        val lines = listOf(
            NoteLine("Task [alarm(\"abc123\")]", "note1")
        )
        assertEquals(listOf("abc123"), extractAlarmIds(lines).alarmIds)
    }

    @Test
    fun `extractAlarmIds extracts multiple alarm IDs from one line`() {
        val lines = listOf(
            NoteLine("Task [alarm(\"a1\")] [alarm(\"a2\")]", "note1")
        )
        assertEquals(listOf("a1", "a2"), extractAlarmIds(lines).alarmIds)
    }

    @Test
    fun `extractAlarmIds extracts across multiple lines`() {
        val lines = listOf(
            NoteLine("Line 1 [alarm(\"a1\")]", "note1"),
            NoteLine("Line 2 [alarm(\"a2\")]", "note2")
        )
        assertEquals(listOf("a1", "a2"), extractAlarmIds(lines).alarmIds)
    }

    @Test
    fun `extractAlarmIds deduplicates`() {
        val lines = listOf(
            NoteLine("Line 1 [alarm(\"a1\")]", "note1"),
            NoteLine("Line 2 [alarm(\"a1\")]", "note2")
        )
        assertEquals(listOf("a1"), extractAlarmIds(lines).alarmIds)
    }

    @Test
    fun `extractAlarmIds ignores lines with only plain alarm symbols`() {
        val lines = listOf(
            NoteLine("Task ⏰", "note1")
        )
        val result = extractAlarmIds(lines)
        assertTrue(result.alarmIds.isEmpty())
        assertTrue(result.recurringAlarmIds.isEmpty())
    }

    @Test
    fun `extractAlarmIds extracts recurring alarm IDs`() {
        val lines = listOf(
            NoteLine("Task [recurringAlarm(\"rec1\")]", "note1"),
            NoteLine("Task [alarm(\"a1\")] [recurringAlarm(\"rec2\")]", "note2")
        )
        val result = extractAlarmIds(lines)
        assertEquals(listOf("a1"), result.alarmIds)
        assertEquals(listOf("rec1", "rec2"), result.recurringAlarmIds)
    }

    @Test
    fun `extractAlarmIds deduplicates recurring IDs`() {
        val lines = listOf(
            NoteLine("Line 1 [recurringAlarm(\"rec1\")]", "note1"),
            NoteLine("Line 2 [recurringAlarm(\"rec1\")]", "note2")
        )
        val result = extractAlarmIds(lines)
        assertEquals(listOf("rec1"), result.recurringAlarmIds)
    }

    // ==================== ExtractedAlarmIds.plus ====================

    @Test
    fun `plus deduplicates alarm IDs across parent and supplemental`() {
        val parent = extractAlarmIds(listOf(NoteLine("Task [alarm(\"a1\")]", "note1")))
        val supplemental = extractAlarmIds(listOf(
            NoteLine("Child [alarm(\"a2\")]", NoteIdSentinel.new(NoteIdSentinel.Origin.TYPED)),
            NoteLine("Child [alarm(\"a1\")]", NoteIdSentinel.new(NoteIdSentinel.Origin.TYPED))  // duplicate with parent
        ))
        val combined = parent + supplemental
        assertEquals(listOf("a1", "a2"), combined.alarmIds)
    }

    @Test
    fun `plus combines recurring IDs from both sources`() {
        val parent = extractAlarmIds(listOf(NoteLine("No alarms here", NoteIdSentinel.new(NoteIdSentinel.Origin.TYPED))))
        val supplemental = extractAlarmIds(listOf(
            NoteLine("Child [recurringAlarm(\"rec1\")]", NoteIdSentinel.new(NoteIdSentinel.Origin.TYPED)),
            NoteLine("Child [alarm(\"a1\")]", NoteIdSentinel.new(NoteIdSentinel.Origin.TYPED))
        ))
        val combined = parent + supplemental
        assertEquals(listOf("a1"), combined.alarmIds)
        assertEquals(listOf("rec1"), combined.recurringAlarmIds)
    }

    // ==================== extractAlarmIdsFromContent ====================

    @Test
    fun `extractAlarmIdsFromContent extracts from raw content strings`() {
        val result = extractAlarmIdsFromContent(listOf(
            "Task [alarm(\"view1\")] with alarm",
            "Another [recurringAlarm(\"viewRec1\")]"
        ))
        assertEquals(listOf("view1"), result.alarmIds)
        assertEquals(listOf("viewRec1"), result.recurringAlarmIds)
    }

    @Test
    fun `extractAlarmIdsFromContent returns EMPTY for empty list`() {
        assertEquals(ExtractedAlarmIds.EMPTY, extractAlarmIdsFromContent(emptyList()))
    }

    // ==================== selectCurrentInstance ====================

    @Test
    fun `selectCurrentInstance returns null for empty list`() {
        assertEquals(null, selectCurrentInstance(emptyList()))
    }

    @Test
    fun `selectCurrentInstance prefers today's instance regardless of status`() {
        val now = Date()
        val cal = java.util.Calendar.getInstance().apply { time = now }
        // Today, 1 hour ago (done)
        cal.add(java.util.Calendar.HOUR_OF_DAY, -1)
        val todayDone = Alarm(id = "todayDone", status = AlarmStatus.DONE, dueTime = Timestamp(cal.time))
        // Tomorrow (pending)
        cal.time = now
        cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        val tomorrowPending = Alarm(id = "tomorrow", status = AlarmStatus.PENDING, dueTime = Timestamp(cal.time))

        assertEquals("todayDone", selectCurrentInstance(listOf(tomorrowPending, todayDone), now)?.id)
    }

    @Test
    fun `selectCurrentInstance picks most recent past if it is PENDING`() {
        val now = Date()
        val cal = java.util.Calendar.getInstance().apply { time = now }
        // Yesterday (pending — e.g. overdue)
        cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
        val yesterdayPending = Alarm(id = "yesterdayPending", status = AlarmStatus.PENDING, dueTime = Timestamp(cal.time))
        // Tomorrow
        cal.time = now
        cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        val tomorrow = Alarm(id = "tomorrow", status = AlarmStatus.PENDING, dueTime = Timestamp(cal.time))

        assertEquals("yesterdayPending", selectCurrentInstance(listOf(tomorrow, yesterdayPending), now)?.id)
    }

    @Test
    fun `selectCurrentInstance picks earliest future when most recent past is not PENDING`() {
        val now = Date()
        val cal = java.util.Calendar.getInstance().apply { time = now }
        // Yesterday (done)
        cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
        val yesterdayDone = Alarm(id = "yesterdayDone", status = AlarmStatus.DONE, dueTime = Timestamp(cal.time))
        // Tomorrow
        cal.time = now
        cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        val tomorrow = Alarm(id = "tomorrow", status = AlarmStatus.PENDING, dueTime = Timestamp(cal.time))
        // 3 days from now
        cal.time = now
        cal.add(java.util.Calendar.DAY_OF_YEAR, 3)
        val threeDays = Alarm(id = "threeDays", status = AlarmStatus.PENDING, dueTime = Timestamp(cal.time))

        assertEquals("tomorrow", selectCurrentInstance(listOf(threeDays, yesterdayDone, tomorrow), now)?.id)
    }

    @Test
    fun `selectCurrentInstance falls back to most recent past when no future exists`() {
        val now = Date()
        val cal = java.util.Calendar.getInstance().apply { time = now }
        // Yesterday (done)
        cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
        val yesterdayDone = Alarm(id = "yesterdayDone", status = AlarmStatus.DONE, dueTime = Timestamp(cal.time))
        // 2 days ago (done)
        cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
        val twoDaysAgo = Alarm(id = "twoDaysAgo", status = AlarmStatus.DONE, dueTime = Timestamp(cal.time))

        assertEquals("yesterdayDone", selectCurrentInstance(listOf(twoDaysAgo, yesterdayDone), now)?.id)
    }

    @Test
    fun `selectCurrentInstance falls back to nearest future when no past exists`() {
        val now = Date()
        val cal = java.util.Calendar.getInstance().apply { time = now }
        // Tomorrow
        cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        val tomorrow = Alarm(id = "tomorrow", status = AlarmStatus.PENDING, dueTime = Timestamp(cal.time))
        // 3 days from now
        cal.add(java.util.Calendar.DAY_OF_YEAR, 2)
        val threeDays = Alarm(id = "threeDays", status = AlarmStatus.PENDING, dueTime = Timestamp(cal.time))

        assertEquals("tomorrow", selectCurrentInstance(listOf(threeDays, tomorrow), now)?.id)
    }

    @Test
    fun `selectCurrentInstance picks first today's instance in list order`() {
        val now = Date()
        val cal = java.util.Calendar.getInstance().apply { time = now }
        // Today, 2 hours ago (done)
        cal.add(java.util.Calendar.HOUR_OF_DAY, -2)
        val todayEarlier = Alarm(id = "todayEarlier", status = AlarmStatus.DONE, dueTime = Timestamp(cal.time))
        // Today, 1 hour from now (pending)
        cal.time = now
        cal.add(java.util.Calendar.HOUR_OF_DAY, 1)
        val todayLater = Alarm(id = "todayLater", status = AlarmStatus.PENDING, dueTime = Timestamp(cal.time))

        // find returns first match in list order
        assertEquals("todayEarlier", selectCurrentInstance(listOf(todayEarlier, todayLater), now)?.id)
        assertEquals("todayLater", selectCurrentInstance(listOf(todayLater, todayEarlier), now)?.id)
    }

    // ==================== computeSymbolOverlays ====================

    private fun alarm(
        id: String,
        status: AlarmStatus = AlarmStatus.PENDING,
        dueTime: Timestamp? = null
    ) = Alarm(id = id, status = status, dueTime = dueTime)

    @Test
    fun `computeSymbolOverlays returns empty for line without directives`() {
        assertTrue(computeSymbolOverlays("Plain text", emptyMap(), emptyMap(), Timestamp.now()).isEmpty())
    }

    @Test
    fun `computeSymbolOverlays returns overlay for cached alarm`() {
        val cache = mapOf("a1" to alarm("a1", AlarmStatus.DONE))
        val overlays = computeSymbolOverlays("Task [alarm(\"a1\")]", cache, emptyMap(), Timestamp.now())

        assertEquals(1, overlays.size)
        assertEquals(AlarmMarkers.ALARM_SYMBOL, overlays[0].symbol)
        assertTrue(overlays[0].badge is SymbolBadge.Corner) // done = checkmark corner badge
    }

    @Test
    fun `computeSymbolOverlays returns None badge for missing alarm`() {
        val overlays = computeSymbolOverlays("Task [alarm(\"missing\")]", emptyMap(), emptyMap(), Timestamp.now())

        assertEquals(1, overlays.size)
        assertEquals(SymbolBadge.None, overlays[0].badge)
    }

    @Test
    fun `computeSymbolOverlays preserves left-to-right order`() {
        val cache = mapOf(
            "a1" to alarm("a1", AlarmStatus.DONE),
            "a2" to alarm("a2", AlarmStatus.CANCELLED)
        )
        val overlays = computeSymbolOverlays(
            "Task [alarm(\"a1\")] [alarm(\"a2\")]", cache, emptyMap(), Timestamp.now()
        )

        assertEquals(2, overlays.size)
        // First is DONE (checkmark), second is CANCELLED (X)
        val firstBadge = overlays[0].badge as SymbolBadge.Corner
        val secondBadge = overlays[1].badge as SymbolBadge.Corner
        assertEquals("✓", firstBadge.text)
        assertEquals("✕", secondBadge.text)
    }

    @Test
    fun `computeSymbolOverlays shows past due badge for overdue alarm`() {
        val pastDue = Timestamp(Date(System.currentTimeMillis() - 3600_000)) // 1 hour ago
        val cache = mapOf(
            "a1" to Alarm(
                id = "a1",
                status = AlarmStatus.PENDING,
                dueTime = pastDue
            )
        )
        val overlays = computeSymbolOverlays("Task [alarm(\"a1\")]", cache, emptyMap(), Timestamp.now())

        assertEquals(1, overlays.size)
        assertTrue(overlays[0].badge is SymbolBadge.Centered) // past due = centered "!"
    }

    @Test
    fun `computeSymbolOverlays returns overlay for recurring alarm in cache`() {
        val recurringCache = mapOf("rec1" to alarm("inst1", AlarmStatus.DONE))
        val overlays = computeSymbolOverlays(
            "Task [recurringAlarm(\"rec1\")]", emptyMap(), recurringCache, Timestamp.now()
        )

        assertEquals(1, overlays.size)
        assertEquals(AlarmMarkers.ALARM_SYMBOL, overlays[0].symbol)
        assertTrue(overlays[0].badge is SymbolBadge.Corner) // done = checkmark corner badge
    }

    @Test
    fun `computeSymbolOverlays handles mixed alarm and recurringAlarm in left-to-right order`() {
        val alarmCache = mapOf("a1" to alarm("a1", AlarmStatus.DONE))
        val recurringCache = mapOf("rec1" to alarm("inst1", AlarmStatus.CANCELLED))
        val overlays = computeSymbolOverlays(
            "Task [alarm(\"a1\")] [recurringAlarm(\"rec1\")]", alarmCache, recurringCache, Timestamp.now()
        )

        assertEquals(2, overlays.size)
        val firstBadge = overlays[0].badge as SymbolBadge.Corner
        val secondBadge = overlays[1].badge as SymbolBadge.Corner
        assertEquals("✓", firstBadge.text) // alarm a1 is DONE
        assertEquals("✕", secondBadge.text) // recurring rec1's instance is CANCELLED
    }

    @Test
    fun `computeSymbolOverlays returns None badge for missing recurring alarm`() {
        val overlays = computeSymbolOverlays(
            "Task [recurringAlarm(\"missing\")]", emptyMap(), emptyMap(), Timestamp.now()
        )

        assertEquals(1, overlays.size)
        assertEquals(SymbolBadge.None, overlays[0].badge)
    }

    // ==================== findAlarmNoteIdUpdates with recurring ====================

    @Test
    fun `findAlarmNoteIdUpdates detects stale noteId on recurringAlarm directive`() = runTest {
        val lines = listOf(
            NoteLine("[recurringAlarm(\"rec1\")]", "note-new")
        )
        val updates = findAlarmNoteIdUpdates(lines) { alarmId ->
            if (alarmId == "rec1") "note-old" else null
        }
        assertEquals(1, updates.size)
        assertEquals("rec1", updates[0].alarmId)
        assertEquals("note-new", updates[0].lineNoteId)
    }

}
