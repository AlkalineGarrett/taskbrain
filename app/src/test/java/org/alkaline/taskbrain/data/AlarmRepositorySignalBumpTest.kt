package org.alkaline.taskbrain.data

import com.google.android.gms.tasks.Tasks
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.Date

/**
 * Pins the contract that every alarm-collection write method bumps
 * [UserDocSignal.Channel.ALARMS] after the Firestore write succeeds, plus
 * verifies [AlarmRepository.buildCreateBatchOp] tags its extra op with the
 * same channel. Without these, remote clients would only catch new alarms
 * on their next foreground count() detection (slow + uses extra reads).
 */
class AlarmRepositorySignalBumpTest {

    private lateinit var mockFirestore: FirebaseFirestore
    private lateinit var mockAuth: FirebaseAuth
    private lateinit var mockUsersCollection: CollectionReference
    private lateinit var mockUserDoc: DocumentReference
    private lateinit var mockAlarmsCollection: CollectionReference
    private lateinit var repository: AlarmRepository

    private val userId = "uid-test"

    @Before
    fun setUp() {
        mockFirestore = mockk(relaxed = true)
        mockAuth = mockk(relaxed = true)
        mockUsersCollection = mockk(relaxed = true)
        mockUserDoc = mockk(relaxed = true)
        mockAlarmsCollection = mockk(relaxed = true)

        every { mockAuth.currentUser } returns mockk { every { uid } returns userId }
        every { mockFirestore.collection("users") } returns mockUsersCollection
        every { mockUsersCollection.document(userId) } returns mockUserDoc
        every { mockUserDoc.collection("alarms") } returns mockAlarmsCollection

        AlarmRepository.clear()
        repository = AlarmRepository(mockFirestore, mockAuth)

        // Stub the bump call so the test doesn't try to write to a real
        // user-doc through the unmocked Firestore graph; verify it was
        // invoked with the right channel.
        mockkObject(UserDocSignal)
        every {
            UserDocSignal.bump(any(), any(), any<UserDocSignal.Channel>())
        } returns Job()
        every {
            UserDocSignal.bump(any(), any(), any<Collection<UserDocSignal.Channel>>())
        } returns Job()
    }

    @After
    fun tearDown() {
        unmockkObject(UserDocSignal)
    }

    @Test
    fun `buildCreateBatchOp tags the extra-op with Channel ALARMS`() {
        val alarm = Alarm(
            id = "alarm-1",
            userId = userId,
            noteId = "note-1",
            lineContent = "fire me",
            dueTime = Timestamp(Date()),
        )
        val mockNewAlarmRef = mockk<DocumentReference>()
        every { mockNewAlarmRef.id } returns "alarm-1"
        every { mockAlarmsCollection.document("alarm-1") } returns mockNewAlarmRef

        val extra = repository.buildCreateBatchOp(alarm)

        assert(extra.signalChannel == UserDocSignal.Channel.ALARMS) {
            "expected signalChannel=ALARMS so NoteRepository.commitInBatches bumps " +
                "the alarms channel after the combined save; got ${extra.signalChannel}"
        }
    }

    @Test
    fun `markDone bumps Channel ALARMS after the Firestore update succeeds`() = runTest {
        val mockAlarmRef = mockk<DocumentReference>(relaxed = true)
        val mockSnapshot = mockk<DocumentSnapshot>(relaxed = true)
        every { mockAlarmsCollection.document("alarm-1") } returns mockAlarmRef
        every { mockAlarmRef.update(any<Map<String, Any?>>()) } returns Tasks.forResult(null)
        every { mockAlarmRef.get() } returns Tasks.forResult(mockSnapshot)
        every { mockSnapshot.exists() } returns true
        every { mockSnapshot.id } returns "alarm-1"

        repository.markDone("alarm-1").getOrThrow()

        coVerify(exactly = 1) {
            UserDocSignal.bump(mockFirestore, userId, UserDocSignal.Channel.ALARMS)
        }
    }

    @Test
    fun `deleteAlarm bumps Channel ALARMS after the delete succeeds`() = runTest {
        val mockAlarmRef = mockk<DocumentReference>(relaxed = true)
        every { mockAlarmsCollection.document("alarm-1") } returns mockAlarmRef
        every { mockAlarmRef.delete() } returns Tasks.forResult(null)

        repository.deleteAlarm("alarm-1").getOrThrow()

        coVerify(exactly = 1) {
            UserDocSignal.bump(mockFirestore, userId, UserDocSignal.Channel.ALARMS)
        }
    }
}
