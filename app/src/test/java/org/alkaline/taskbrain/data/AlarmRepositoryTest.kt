package org.alkaline.taskbrain.data

import com.google.android.gms.tasks.Tasks
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Date

class AlarmRepositoryTest {

    private lateinit var mockFirestore: FirebaseFirestore
    private lateinit var mockAuth: FirebaseAuth
    private lateinit var mockUsersCollection: CollectionReference
    private lateinit var mockUserDoc: DocumentReference
    private lateinit var mockAlarmsCollection: CollectionReference
    private lateinit var repository: AlarmRepository

    @Before
    fun setUp() {
        mockFirestore = mockk(relaxed = true)
        mockAuth = mockk(relaxed = true)
        mockUsersCollection = mockk(relaxed = true)
        mockUserDoc = mockk(relaxed = true)
        mockAlarmsCollection = mockk(relaxed = true)

        every { mockAuth.currentUser } returns mockk { every { uid } returns USER_ID }
        every { mockFirestore.collection("users") } returns mockUsersCollection
        every { mockUsersCollection.document(USER_ID) } returns mockUserDoc
        every { mockUserDoc.collection("alarms") } returns mockAlarmsCollection

        repository = AlarmRepository(mockFirestore, mockAuth)
    }

    private fun signOut() {
        every { mockAuth.currentUser } returns null
    }

    private fun mockAlarmDocument(alarmId: String, data: Map<String, Any?>?): DocumentReference {
        val ref = mockk<DocumentReference>()
        val snapshot = mockk<DocumentSnapshot>()
        every { mockAlarmsCollection.document(alarmId) } returns ref
        every { ref.get() } returns Tasks.forResult(snapshot)
        every { snapshot.exists() } returns (data != null)
        every { snapshot.id } returns alarmId
        every { snapshot.data } returns data
        return ref
    }

    private fun alarmData(
        noteId: String = "note_1",
        lineContent: String = "Test content",
        status: AlarmStatus = AlarmStatus.PENDING,
        dueTime: Timestamp? = null
    ): Map<String, Any?> = mapOf(
        "userId" to USER_ID,
        "noteId" to noteId,
        "lineContent" to lineContent,
        "status" to status.name,
        "dueTime" to dueTime,
        "stages" to null,
        "createdAt" to null,
        "updatedAt" to null,
        "snoozedUntil" to null
    )

    // region Auth Tests

    @Test
    fun `createAlarm fails when user is not signed in`() = runTest {
        signOut()

        val result = repository.createAlarm(Alarm())

        assertTrue(result.isFailure)
        assertEquals("User not signed in", result.exceptionOrNull()?.message)
    }

    @Test
    fun `getAlarm fails when user is not signed in`() = runTest {
        signOut()

        val result = repository.getAlarm("alarm_1")

        assertTrue(result.isFailure)
        assertEquals("User not signed in", result.exceptionOrNull()?.message)
    }

    @Test
    fun `markDone fails when user is not signed in`() = runTest {
        signOut()

        val result = repository.markDone("alarm_1")

        assertTrue(result.isFailure)
        assertEquals("User not signed in", result.exceptionOrNull()?.message)
    }

    // endregion

    // region Create Tests

    @Test
    fun `createAlarm returns new alarm ID`() = runTest {
        val newRef = mockk<DocumentReference> { every { id } returns "new_alarm_id" }
        every { mockAlarmsCollection.document() } returns newRef
        every { newRef.set(any<Map<String, Any?>>()) } returns Tasks.forResult(null)

        val result = repository.createAlarm(
            Alarm(noteId = "note_1", lineContent = "Test")
        )

        assertTrue(result.isSuccess)
        assertEquals("new_alarm_id", result.getOrNull())
    }

    // endregion

    // region Get Tests

    @Test
    fun `getAlarm returns null when document does not exist`() = runTest {
        mockAlarmDocument("alarm_1", null)

        val result = repository.getAlarm("alarm_1")

        assertTrue(result.isSuccess)
        assertNull(result.getOrNull())
    }

    @Test
    fun `getAlarm returns alarm when document exists`() = runTest {
        mockAlarmDocument("alarm_1", alarmData(noteId = "note_1", lineContent = "Test content"))

        val result = repository.getAlarm("alarm_1")

        assertTrue(result.isSuccess)
        val alarm = result.getOrNull()
        assertNotNull(alarm)
        assertEquals("alarm_1", alarm?.id)
        assertEquals("note_1", alarm?.noteId)
        assertEquals("Test content", alarm?.lineContent)
        assertEquals(AlarmStatus.PENDING, alarm?.status)
    }

    @Test
    fun `getAlarm handles invalid status gracefully`() = runTest {
        val data = alarmData().toMutableMap()
        data["status"] = "INVALID_STATUS"
        mockAlarmDocument("alarm_1", data)

        val result = repository.getAlarm("alarm_1")

        assertTrue(result.isSuccess)
        assertEquals(AlarmStatus.PENDING, result.getOrNull()?.status)
    }

    // endregion

    // region Status Change Tests

    @Test
    fun `markDone updates alarm status`() = runTest {
        val ref = mockAlarmDocument("alarm_1", alarmData())
        every { ref.update(any<Map<String, Any>>()) } returns Tasks.forResult(null)

        val result = repository.markDone("alarm_1")

        assertTrue(result.isSuccess)
        verify {
            ref.update(match<Map<String, Any>> {
                it["status"] == AlarmStatus.DONE.name
            })
        }
    }

    @Test
    fun `markCancelled updates alarm status`() = runTest {
        val ref = mockAlarmDocument("alarm_1", alarmData())
        every { ref.update(any<Map<String, Any>>()) } returns Tasks.forResult(null)

        val result = repository.markCancelled("alarm_1")

        assertTrue(result.isSuccess)
        verify {
            ref.update(match<Map<String, Any>> {
                it["status"] == AlarmStatus.CANCELLED.name
            })
        }
    }

    @Test
    fun `reactivateAlarm sets status back to PENDING`() = runTest {
        val ref = mockAlarmDocument("alarm_1", alarmData(status = AlarmStatus.DONE))
        every { ref.update(any<Map<String, Any>>()) } returns Tasks.forResult(null)

        val result = repository.reactivateAlarm("alarm_1")

        assertTrue(result.isSuccess)
        verify {
            ref.update(match<Map<String, Any>> {
                it["status"] == AlarmStatus.PENDING.name
            })
        }
    }

    // endregion

    // region Snooze Tests

    @Test
    fun `snoozeAlarm sets snoozedUntil timestamp`() = runTest {
        val ref = mockAlarmDocument("alarm_1", alarmData())
        every { ref.update(any<Map<String, Any?>>()) } returns Tasks.forResult(null)

        val result = repository.snoozeAlarm("alarm_1", SnoozeDuration.TEN_MINUTES)

        assertTrue(result.isSuccess)
        verify {
            ref.update(match<Map<String, Any?>> {
                it.containsKey("snoozedUntil") && it["snoozedUntil"] is Timestamp
            })
        }
    }

    @Test
    fun `clearSnooze sets snoozedUntil to null`() = runTest {
        val ref = mockAlarmDocument("alarm_1", alarmData())
        every { ref.update(any<Map<String, Any?>>()) } returns Tasks.forResult(null)

        val result = repository.clearSnooze("alarm_1")

        assertTrue(result.isSuccess)
        verify {
            ref.update(match<Map<String, Any?>> {
                it["snoozedUntil"] == null
            })
        }
    }

    // endregion

    // region Delete Tests

    @Test
    fun `deleteAlarm removes document`() = runTest {
        val ref = mockAlarmDocument("alarm_1", alarmData())
        every { ref.delete() } returns Tasks.forResult(null)

        val result = repository.deleteAlarm("alarm_1")

        assertTrue(result.isSuccess)
        verify { ref.delete() }
    }

    // endregion

    // region Query Tests

    @Test
    fun `getAlarmsForNote returns alarms matching noteId`() = runTest {
        val query = mockk<Query>()
        val docs = listOf(
            mockk<QueryDocumentSnapshot> {
                every { id } returns "alarm_1"
                every { data } returns alarmData(noteId = "note_1")
            },
            mockk<QueryDocumentSnapshot> {
                every { id } returns "alarm_2"
                every { data } returns alarmData(noteId = "note_1")
            }
        )
        every { mockAlarmsCollection.whereEqualTo("noteId", "note_1") } returns query
        every { query.get() } returns Tasks.forResult(mockk {
            every { documents } returns docs
        })

        val result = repository.getAlarmsForNote("note_1")

        assertTrue(result.isSuccess)
        assertEquals(2, result.getOrNull()?.size)
    }

    @Test
    fun `getUpcomingAlarms filters to PENDING with dueTime set`() = runTest {
        val now = Timestamp(Date())
        val query = mockk<Query>()
        val orderedQuery = mockk<Query>()
        val docs = listOf(
            mockk<QueryDocumentSnapshot> {
                every { id } returns "alarm_1"
                every { data } returns alarmData(dueTime = now)
            },
            mockk<QueryDocumentSnapshot> {
                every { id } returns "alarm_2"
                every { data } returns alarmData(dueTime = null) // Should be filtered out
            }
        )
        every { mockAlarmsCollection.whereEqualTo("status", AlarmStatus.PENDING.name) } returns query
        every { query.orderBy("dueTime", Query.Direction.ASCENDING) } returns orderedQuery
        every { orderedQuery.get() } returns Tasks.forResult(mockk {
            every { documents } returns docs
        })

        val result = repository.getUpcomingAlarms()

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrNull()?.size)
        assertEquals("alarm_1", result.getOrNull()?.first()?.id)
    }

    @Test
    fun `getLaterAlarms filters to PENDING without dueTime`() = runTest {
        val now = Timestamp(Date())
        val query = mockk<Query>()
        val orderedQuery = mockk<Query>()
        val docs = listOf(
            mockk<QueryDocumentSnapshot> {
                every { id } returns "alarm_1"
                every { data } returns alarmData(dueTime = now) // Should be filtered out
            },
            mockk<QueryDocumentSnapshot> {
                every { id } returns "alarm_2"
                every { data } returns alarmData(dueTime = null)
            }
        )
        every { mockAlarmsCollection.whereEqualTo("status", AlarmStatus.PENDING.name) } returns query
        every { query.orderBy("createdAt", Query.Direction.DESCENDING) } returns orderedQuery
        every { orderedQuery.get() } returns Tasks.forResult(mockk {
            every { documents } returns docs
        })

        val result = repository.getLaterAlarms()

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrNull()?.size)
        assertEquals("alarm_2", result.getOrNull()?.first()?.id)
    }

    // endregion

    // region Error Handling Tests

    @Test
    fun `createAlarm returns failure on Firebase exception`() = runTest {
        val newRef = mockk<DocumentReference> { every { id } returns "new_alarm_id" }
        every { mockAlarmsCollection.document() } returns newRef
        every { newRef.set(any<Map<String, Any?>>()) } returns Tasks.forException(
            RuntimeException("PERMISSION_DENIED: Missing or insufficient permissions")
        )

        val result = repository.createAlarm(
            Alarm(noteId = "note_1", lineContent = "Test")
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("PERMISSION_DENIED") == true)
    }

    @Test
    fun `getAlarm returns failure on Firebase exception`() = runTest {
        val ref = mockk<DocumentReference>()
        every { mockAlarmsCollection.document("alarm_1") } returns ref
        every { ref.get() } returns Tasks.forException(
            RuntimeException("Network error")
        )

        val result = repository.getAlarm("alarm_1")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Network error") == true)
    }

    @Test
    fun `markDone returns failure on Firebase exception`() = runTest {
        val ref = mockAlarmDocument("alarm_1", alarmData())
        every { ref.update(any<Map<String, Any>>()) } returns Tasks.forException(
            RuntimeException("Permission denied")
        )

        val result = repository.markDone("alarm_1")

        assertTrue(result.isFailure)
    }

    @Test
    fun `markCancelled returns failure on Firebase exception`() = runTest {
        val ref = mockAlarmDocument("alarm_1", alarmData())
        every { ref.update(any<Map<String, Any>>()) } returns Tasks.forException(
            RuntimeException("Permission denied")
        )

        val result = repository.markCancelled("alarm_1")

        assertTrue(result.isFailure)
    }

    @Test
    fun `reactivateAlarm returns failure on Firebase exception`() = runTest {
        val ref = mockAlarmDocument("alarm_1", alarmData())
        every { ref.update(any<Map<String, Any>>()) } returns Tasks.forException(
            RuntimeException("Permission denied")
        )

        val result = repository.reactivateAlarm("alarm_1")

        assertTrue(result.isFailure)
    }

    @Test
    fun `getUpcomingAlarms returns failure on Firebase exception`() = runTest {
        val query = mockk<Query>()
        val orderedQuery = mockk<Query>()
        every { mockAlarmsCollection.whereEqualTo("status", AlarmStatus.PENDING.name) } returns query
        every { query.orderBy("dueTime", Query.Direction.ASCENDING) } returns orderedQuery
        every { orderedQuery.get() } returns Tasks.forException(
            RuntimeException("PERMISSION_DENIED: Missing or insufficient permissions")
        )

        val result = repository.getUpcomingAlarms()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("PERMISSION_DENIED") == true)
    }

    @Test
    fun `getLaterAlarms returns failure on Firebase exception`() = runTest {
        val query = mockk<Query>()
        val orderedQuery = mockk<Query>()
        every { mockAlarmsCollection.whereEqualTo("status", AlarmStatus.PENDING.name) } returns query
        every { query.orderBy("createdAt", Query.Direction.DESCENDING) } returns orderedQuery
        every { orderedQuery.get() } returns Tasks.forException(
            RuntimeException("PERMISSION_DENIED: Missing or insufficient permissions")
        )

        val result = repository.getLaterAlarms()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("PERMISSION_DENIED") == true)
    }

    @Test
    fun `snoozeAlarm returns failure on Firebase exception`() = runTest {
        val ref = mockAlarmDocument("alarm_1", alarmData())
        every { ref.update(any<Map<String, Any?>>()) } returns Tasks.forException(
            RuntimeException("Permission denied")
        )

        val result = repository.snoozeAlarm("alarm_1", SnoozeDuration.TEN_MINUTES)

        assertTrue(result.isFailure)
    }

    @Test
    fun `deleteAlarm returns failure on Firebase exception`() = runTest {
        val ref = mockAlarmDocument("alarm_1", alarmData())
        every { ref.delete() } returns Tasks.forException(
            RuntimeException("Permission denied")
        )

        val result = repository.deleteAlarm("alarm_1")

        assertTrue(result.isFailure)
    }

    // endregion

    // region Line Content Sync Tests

    @Test
    fun `updateLineContentForNote updates only lineContent not updatedAt`() = runTest {
        val query = mockk<Query>()
        val mockBatch = mockk<WriteBatch>(relaxed = true)
        val doc1Ref = mockk<DocumentReference>()
        val doc2Ref = mockk<DocumentReference>()
        val docs = listOf(
            mockk<QueryDocumentSnapshot> {
                every { id } returns "alarm_1"
                every { reference } returns doc1Ref
                every { data } returns alarmData(noteId = "note_1", lineContent = "Old content")
            },
            mockk<QueryDocumentSnapshot> {
                every { id } returns "alarm_2"
                every { reference } returns doc2Ref
                every { data } returns alarmData(noteId = "note_1", lineContent = "Old content")
            }
        )
        every { mockAlarmsCollection.whereEqualTo("noteId", "note_1") } returns query
        every { query.get() } returns Tasks.forResult(mockk {
            every { documents } returns docs
        })
        every { mockFirestore.batch() } returns mockBatch
        every { mockBatch.commit() } returns Tasks.forResult(null)

        val result = repository.updateLineContentForNote("note_1", "New content")

        assertTrue(result.isSuccess)

        // Verify batch.update was called with only lineContent, not updatedAt
        verify {
            mockBatch.update(doc1Ref, match<Map<String, Any>> { map ->
                map.containsKey("lineContent") &&
                map["lineContent"] == "New content" &&
                !map.containsKey("updatedAt")
            })
        }
        verify {
            mockBatch.update(doc2Ref, match<Map<String, Any>> { map ->
                map.containsKey("lineContent") &&
                map["lineContent"] == "New content" &&
                !map.containsKey("updatedAt")
            })
        }
        verify { mockBatch.commit() }
    }

    @Test
    fun `updateLineContentForNote does nothing when no alarms match`() = runTest {
        val query = mockk<Query>()
        val mockBatch = mockk<WriteBatch>(relaxed = true)
        every { mockAlarmsCollection.whereEqualTo("noteId", "note_1") } returns query
        every { query.get() } returns Tasks.forResult(mockk {
            every { documents } returns emptyList()
        })
        every { mockFirestore.batch() } returns mockBatch
        every { mockBatch.commit() } returns Tasks.forResult(null)

        val result = repository.updateLineContentForNote("note_1", "New content")

        assertTrue(result.isSuccess)
        verify(exactly = 0) { mockBatch.update(any<DocumentReference>(), any<Map<String, Any>>()) }
        verify { mockBatch.commit() }
    }

    @Test
    fun `updateLineContentForNote fails when user is not signed in`() = runTest {
        signOut()

        val result = repository.updateLineContentForNote("note_1", "New content")

        assertTrue(result.isFailure)
        assertEquals("User not signed in", result.exceptionOrNull()?.message)
    }

    // endregion

    // region updateAlarmNoteId Tests

    @Test
    fun `updateAlarmNoteId updates noteId field`() = runTest {
        val alarmRef = mockk<DocumentReference>()
        every { mockAlarmsCollection.document("alarm_1") } returns alarmRef
        every { alarmRef.update("noteId", "new_note_id") } returns Tasks.forResult(null)

        val result = repository.updateAlarmNoteId("alarm_1", "new_note_id")

        assertTrue(result.isSuccess)
        verify { alarmRef.update("noteId", "new_note_id") }
    }

    @Test
    fun `updateAlarmNoteId fails when user is not signed in`() = runTest {
        signOut()

        val result = repository.updateAlarmNoteId("alarm_1", "new_note_id")

        assertTrue(result.isFailure)
        assertEquals("User not signed in", result.exceptionOrNull()?.message)
    }

    // endregion

    companion object {
        private const val USER_ID = "test_user_id"
    }
}
