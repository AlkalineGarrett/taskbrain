package org.alkaline.taskbrain.ui.currentnote

import com.google.firebase.Timestamp
import kotlinx.coroutines.test.runTest
import org.alkaline.taskbrain.data.Alarm
import org.alkaline.taskbrain.data.AlarmStatus
import org.alkaline.taskbrain.data.NoteLine
import org.alkaline.taskbrain.dsl.directives.DirectiveInstance
import org.alkaline.taskbrain.dsl.directives.DirectiveResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.alkaline.taskbrain.dsl.runtime.values.UndefinedVal
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
            NoteLine("Task [alarm(\"alarm1\")]", null)
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

    // ==================== filterToActiveRecurringInstances ====================

    private fun alarm(
        id: String,
        recurringAlarmId: String? = null,
        status: AlarmStatus = AlarmStatus.PENDING,
        updatedAt: Timestamp? = null
    ) = Alarm(
        id = id,
        recurringAlarmId = recurringAlarmId,
        status = status,
        updatedAt = updatedAt
    )

    @Test
    fun `filterToActiveRecurringInstances keeps all non-recurring`() {
        val alarms = listOf(alarm("a1"), alarm("a2"), alarm("a3"))
        val result = filterToActiveRecurringInstances(alarms)
        assertEquals(3, result.size)
    }

    @Test
    fun `filterToActiveRecurringInstances keeps one instance per recurring`() {
        val alarms = listOf(
            alarm("i1", recurringAlarmId = "r1", status = AlarmStatus.DONE),
            alarm("i2", recurringAlarmId = "r1", status = AlarmStatus.PENDING),
            alarm("i3", recurringAlarmId = "r1", status = AlarmStatus.DONE)
        )
        val result = filterToActiveRecurringInstances(alarms)
        assertEquals(1, result.size)
        assertEquals("i2", result[0].id) // PENDING preferred
    }

    @Test
    fun `filterToActiveRecurringInstances falls back to most recently updated when no PENDING`() {
        val old = Timestamp(Date(1000))
        val recent = Timestamp(Date(2000))
        val alarms = listOf(
            alarm("i1", recurringAlarmId = "r1", status = AlarmStatus.DONE, updatedAt = old),
            alarm("i2", recurringAlarmId = "r1", status = AlarmStatus.DONE, updatedAt = recent)
        )
        val result = filterToActiveRecurringInstances(alarms)
        assertEquals(1, result.size)
        assertEquals("i2", result[0].id) // most recently updated
    }

    @Test
    fun `filterToActiveRecurringInstances mixes recurring and non-recurring`() {
        val alarms = listOf(
            alarm("standalone"),
            alarm("i1", recurringAlarmId = "r1", status = AlarmStatus.PENDING),
            alarm("i2", recurringAlarmId = "r1", status = AlarmStatus.DONE)
        )
        val result = filterToActiveRecurringInstances(alarms)
        assertEquals(2, result.size)
        assertTrue(result.any { it.id == "standalone" })
        assertTrue(result.any { it.id == "i1" })
    }

    @Test
    fun `filterToActiveRecurringInstances handles multiple recurring groups`() {
        val alarms = listOf(
            alarm("i1", recurringAlarmId = "r1", status = AlarmStatus.PENDING),
            alarm("i2", recurringAlarmId = "r1", status = AlarmStatus.DONE),
            alarm("i3", recurringAlarmId = "r2", status = AlarmStatus.PENDING),
            alarm("i4", recurringAlarmId = "r2", status = AlarmStatus.DONE)
        )
        val result = filterToActiveRecurringInstances(alarms)
        assertEquals(2, result.size)
        assertTrue(result.any { it.id == "i1" })
        assertTrue(result.any { it.id == "i3" })
    }

    @Test
    fun `filterToActiveRecurringInstances handles empty list`() {
        val result = filterToActiveRecurringInstances(emptyList())
        assertTrue(result.isEmpty())
    }

    // ==================== migrateAlarmSymbolLines ====================

    @Test
    fun `migrateAlarmSymbolLines returns null when no alarm symbols`() {
        val result = migrateAlarmSymbolLines(
            listOf("Plain text", "No alarms"),
            listOf("note1", "note2"),
            emptyMap()
        )
        assertNull(result)
    }

    @Test
    fun `migrateAlarmSymbolLines migrates symbol to directive`() {
        val alarms = listOf(
            Alarm(id = "a1", createdAt = Timestamp(Date(1000)))
        )
        val result = migrateAlarmSymbolLines(
            listOf("Task ⏰"),
            listOf("note1"),
            mapOf("note1" to alarms)
        )

        assertEquals(true, result?.migrated)
        assertEquals("Task [alarm(\"a1\")]", result?.lines?.get(0))
    }

    @Test
    fun `migrateAlarmSymbolLines skips lines without noteId`() {
        val result = migrateAlarmSymbolLines(
            listOf("Task ⏰"),
            listOf(null),
            emptyMap()
        )

        // Has symbols but noteId is null, so no migration possible
        assertEquals(false, result?.migrated)
    }

    @Test
    fun `migrateAlarmSymbolLines skips lines with no matching alarms`() {
        val result = migrateAlarmSymbolLines(
            listOf("Task ⏰"),
            listOf("note1"),
            emptyMap() // no alarms for note1
        )

        assertEquals(false, result?.migrated)
    }

    // ==================== mapResultsByPosition ====================

    private fun directiveInstance(uuid: String, lineIndex: Int, startOffset: Int) =
        DirectiveInstance(uuid, lineIndex, startOffset, "[test]")

    @Test
    fun `mapResultsByPosition maps UUIDs to position keys`() {
        val instances = listOf(
            directiveInstance("uuid1", 0, 5),
            directiveInstance("uuid2", 2, 10)
        )
        val results = mapOf(
            "uuid1" to DirectiveResult.success(UndefinedVal),
            "uuid2" to DirectiveResult.success(UndefinedVal)
        )

        val mapped = mapResultsByPosition(instances, results)
        assertEquals(2, mapped.size)
        assertTrue(mapped.containsKey("0:5"))
        assertTrue(mapped.containsKey("2:10"))
    }

    @Test
    fun `mapResultsByPosition skips instances without results`() {
        val instances = listOf(
            directiveInstance("uuid1", 0, 0),
            directiveInstance("uuid2", 1, 0)
        )
        val results = mapOf(
            "uuid1" to DirectiveResult.success(UndefinedVal)
        )

        val mapped = mapResultsByPosition(instances, results)
        assertEquals(1, mapped.size)
        assertTrue(mapped.containsKey("0:0"))
    }

    @Test
    fun `mapResultsByPosition returns empty for empty inputs`() {
        val mapped = mapResultsByPosition(emptyList(), emptyMap())
        assertTrue(mapped.isEmpty())
    }

    // ==================== findExpandedPositions ====================

    @Test
    fun `findExpandedPositions returns positions of non-collapsed directives`() {
        val instances = listOf(
            directiveInstance("uuid1", 0, 5),
            directiveInstance("uuid2", 2, 10)
        )
        val results = mapOf(
            "uuid1" to DirectiveResult.success(UndefinedVal).copy(collapsed = false),
            "uuid2" to DirectiveResult.success(UndefinedVal).copy(collapsed = true)
        )

        val positions = findExpandedPositions(instances, results)
        assertEquals(1, positions.size)
        assertTrue(positions.contains(DirectivePosition(0, 5)))
    }

    @Test
    fun `findExpandedPositions returns empty when all collapsed`() {
        val instances = listOf(directiveInstance("uuid1", 0, 0))
        val results = mapOf(
            "uuid1" to DirectiveResult.success(UndefinedVal).copy(collapsed = true)
        )

        val positions = findExpandedPositions(instances, results)
        assertTrue(positions.isEmpty())
    }

    @Test
    fun `findExpandedPositions returns empty for empty results`() {
        val positions = findExpandedPositions(emptyList(), emptyMap())
        assertTrue(positions.isEmpty())
    }

    // ==================== mergeDirectiveResults ====================

    @Test
    fun `mergeDirectiveResults preserves existing collapsed state`() {
        val fresh = mapOf(
            "uuid1" to DirectiveResult.success(UndefinedVal),
            "uuid2" to DirectiveResult.success(UndefinedVal)
        )
        val current = mapOf(
            "uuid1" to DirectiveResult.success(UndefinedVal).copy(collapsed = false),
            "uuid2" to DirectiveResult.success(UndefinedVal).copy(collapsed = true)
        )

        val merged = mergeDirectiveResults(fresh, current)
        assertEquals(false, merged["uuid1"]!!.collapsed)
        assertEquals(true, merged["uuid2"]!!.collapsed)
    }

    @Test
    fun `mergeDirectiveResults defaults to collapsed when no existing state`() {
        val fresh = mapOf("uuid1" to DirectiveResult.success(UndefinedVal))
        val merged = mergeDirectiveResults(fresh, null)
        assertEquals(true, merged["uuid1"]!!.collapsed)
    }

    @Test
    fun `mergeDirectiveResults defaults to collapsed for new UUIDs`() {
        val fresh = mapOf("uuid1" to DirectiveResult.success(UndefinedVal))
        val current = mapOf("other" to DirectiveResult.success(UndefinedVal).copy(collapsed = false))

        val merged = mergeDirectiveResults(fresh, current)
        assertEquals(true, merged["uuid1"]!!.collapsed) // not in current, defaults to true
    }
}
