package app.syncwake.social

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.syncwake.alarm.AlarmCoordinator
import app.syncwake.alarm.AlarmScheduler
import app.syncwake.alarm.WakeProofManager
import app.syncwake.data.AlarmEntity
import app.syncwake.data.AlarmRepository
import app.syncwake.data.SyncWakeDatabase
import app.syncwake.domain.state.AlarmEvent
import app.syncwake.domain.time.WallClock
import app.syncwake.settings.AppSettings
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SocialRepositoryTest {
    private val ny = ZoneId.of("America/New_York")
    private val friday = LocalDate.of(2026, 10, 2)
    private val remoteId = "11111111-2222-3333-4444-555555555555"

    private class FakeClock(var instant: Instant, private val zone: ZoneId) : WallClock {
        override fun now(): Instant = instant
        override fun zone(): ZoneId = zone
    }

    private class RecordingScheduler : SyncScheduler {
        var requested = 0
        var cancelled = 0
        override fun requestNow() { requested++ }
        override fun schedulePeriodic() = Unit
        override fun cancel() { cancelled++ }
    }

    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private lateinit var db: SyncWakeDatabase
    private lateinit var alarms: AlarmRepository
    private lateinit var scheduler: AlarmScheduler
    private lateinit var coordinator: AlarmCoordinator
    private lateinit var store: SocialStore
    private lateinit var syncScheduler: RecordingScheduler
    private lateinit var social: SocialRepository
    private lateinit var clock: FakeClock
    private var lastEvents: JSONArray? = null

    @Before
    fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("social", Context.MODE_PRIVATE).edit().clear().commit()
        server = MockWebServer().apply { start() }
        db = Room.inMemoryDatabaseBuilder(context, SyncWakeDatabase::class.java).allowMainThreadQueries().build()
        alarms = AlarmRepository(db)
        scheduler = AlarmScheduler(context)
        clock = FakeClock(ZonedDateTime.of(friday.atTime(6, 0), ny).toInstant(), ny)
        coordinator = AlarmCoordinator(context, alarms, scheduler, WakeProofManager(context, alarms, scheduler, AppSettings(context), clock), clock)
        store = SocialStore(context).apply { saveAccount(Account("me", "Alex", "session-token")) }
        syncScheduler = RecordingScheduler()
        val config = SocialConfig(server.url("/").toString().trimEnd('/'), "client-id", "", "", "", "")
        social = SocialRepository(
            context, config, store, alarms, coordinator, clock,
            api = ApiClient(config.apiBaseUrl, { store.account.value?.token }),
            scheduler = syncScheduler,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    private fun remoteAlarm(hour: Int = 7, status: String = "ACTIVE", version: Int = 1, role: String = "MEMBER") = JSONObject()
        .put("id", remoteId).put("ownerId", "owner").put("label", "Run club")
        .put("hour", hour).put("minute", 0).put("repeatDaysMask", 127).put("oneTimeDate", JSONObject.NULL)
        .put("zoneId", "America/New_York").put("status", status).put("version", version).put("myRole", role)
        .put("restricted", false).put("maxGroupSize", 3)
        .put(
            "participants",
            JSONArray()
                .put(JSONObject().put("userId", "owner").put("displayName", "Rahul").put("role", "OWNER"))
                .put(JSONObject().put("userId", "me").put("displayName", "Alex").put("role", "MEMBER")),
        )

    private fun json(body: Any, code: Int = 200) = MockResponse().setResponseCode(code).setBody(body.toString())

    @Test
    fun joiningSchedulesTheSharedAlarmLocallyInItsTimeZone() = runBlocking {
        server.enqueue(json(remoteAlarm()))
        val joined = social.join("k7mhr9xa")

        val request = server.takeRequest()
        assertEquals("/v1/invites/K7MHR9XA/accept", request.path)
        assertEquals("Bearer session-token", request.getHeader("Authorization"))
        assertEquals("Rahul", joined.ownerName)

        val local = alarms.alarm(remoteId)!!
        assertEquals("America/New_York", local.anchorZone)
        assertTrue(local.enabled)
        assertTrue(scheduler.isRingRegistered("$remoteId@$friday"))
        assertEquals(SharedInfo(1, false, "Rahul", 2), store.shared.value[remoteId])
    }

    @Test
    fun pullUpdatesTheScheduleButKeepsPersonalSettings() = runBlocking {
        server.enqueue(json(remoteAlarm()))
        social.join("CODE1234")
        alarms.saveAlarm(alarms.alarm(remoteId)!!.copy(soundUri = "content://my/sound", vibrate = false, ringDurationMinutes = 5))

        server.enqueue(json(JSONObject().put("alarms", JSONArray().put(remoteAlarm(hour = 6, version = 2)))))
        social.pullAlarms()

        val local = alarms.alarm(remoteId)!!
        assertEquals(6, local.hour)
        assertEquals("content://my/sound", local.soundUri)
        assertFalse(local.vibrate)
        assertEquals(5, local.ringDurationMinutes)
        assertEquals(2, store.shared.value[remoteId]!!.version)
    }

    @Test
    fun cancelledOrRemovedSharedAlarmsAreDeletedLocally() = runBlocking {
        server.enqueue(json(remoteAlarm()))
        social.join("CODE1234")
        assertTrue(scheduler.isRingRegistered("$remoteId@$friday"))

        server.enqueue(json(JSONObject().put("alarms", JSONArray().put(remoteAlarm(status = "CANCELLED")))))
        social.pullAlarms()

        assertNull(alarms.alarm(remoteId))
        assertFalse(scheduler.isRingRegistered("$remoteId@$friday"))
        assertTrue(store.shared.value.isEmpty())
    }

    @Test
    fun uploadsHighLevelStatusesForSharedAlarmsOnly() = runBlocking {
        server.enqueue(json(remoteAlarm()))
        social.join("CODE1234")
        server.takeRequest()
        val personal = AlarmEntity(
            alarmId = "personal", label = "", hour = 7, minute = 0, repeatDaysMask = 127, oneTimeDate = null,
            anchorZone = null, enabled = true, vibrate = true, soundUri = null, snoozeMinutes = 9, maxSnoozes = 3,
            ringDurationMinutes = 2, createdAt = 0, updatedAt = 0,
        )
        coordinator.saveAlarm(personal)

        val shared = "$remoteId@$friday"
        for (e in listOf(AlarmEvent.Fire, AlarmEvent.BeginChallenge, AlarmEvent.ChallengeSucceeded)) alarms.transition(shared, e, 1_000)
        alarms.transition("personal@$friday", AlarmEvent.Fire, 1_000)

        // The server echoes every event back as accepted.
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val events = JSONObject(request.body.readUtf8()).getJSONArray("events")
                lastEvents = events
                val results = JSONArray()
                for (i in 0 until events.length()) {
                    results.put(JSONObject().put("eventId", events.getJSONObject(i).getString("eventId")).put("result", "accepted"))
                }
                return json(JSONObject().put("results", results))
            }
        }
        social.uploadEvents()

        val sent = lastEvents!!
        assertEquals(3, sent.length())
        val described = (0 until sent.length()).map { i ->
            val e = sent.getJSONObject(i)
            assertEquals(shared, e.getString("occurrenceId"))
            e.getString("type") + ":" + e.optString("status")
        }
        assertEquals(listOf("STATUS_CHANGED:RINGING", "STATUS_CHANGED:COMPLETING_CHALLENGE", "ALARM_COMPLETED:"), described)
        assertTrue(alarms.unsyncedEvents(100).isEmpty())
    }

    @Test
    fun failedUploadsStayQueuedForRetry() = runBlocking {
        server.enqueue(json(remoteAlarm()))
        social.join("CODE1234")
        alarms.transition("$remoteId@$friday", AlarmEvent.Fire, 1_000)
        server.enqueue(MockResponse().setResponseCode(503).setBody("{\"error\":\"internal\"}"))
        try {
            social.uploadEvents()
            fail("expected failure")
        } catch (e: ApiException) {
            assertEquals(503, e.status)
        }
        assertEquals(1, alarms.unsyncedEvents(100).size)
    }

    @Test
    fun expiredSessionSignsOutLocally() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":\"invalid_token\"}"))
        try {
            social.pullAlarms()
            fail("expected failure")
        } catch (e: ApiException) {
            assertEquals("invalid_token", e.code)
        }
        assertNull(store.account.value)
        assertEquals(1, syncScheduler.cancelled)
    }

    @Test
    fun creatingASharedAlarmUsesTheServerIdAndDeviceZone() = runBlocking {
        server.enqueue(json(remoteAlarm(role = "OWNER"), 201))
        val local = AlarmEntity(
            alarmId = "temp", label = "Run club", hour = 7, minute = 0, repeatDaysMask = 127, oneTimeDate = null,
            anchorZone = null, enabled = true, vibrate = true, soundUri = null, snoozeMinutes = 9, maxSnoozes = 3,
            ringDurationMinutes = 2, createdAt = 0, updatedAt = 0,
        )
        social.createShared(local)
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("America/New_York", body.getString("zoneId"))
        assertNull(alarms.alarm("temp"))
        assertNotNull(alarms.alarm(remoteId))
        assertTrue(store.shared.value[remoteId]!!.isOwner)
    }

    @Test
    fun conflictingEditReloadsFromServer() = runBlocking {
        server.enqueue(json(remoteAlarm(role = "OWNER"), 201))
        social.join("CODE1234")
        server.enqueue(MockResponse().setResponseCode(409).setBody("{\"error\":\"version_conflict\"}"))
        server.enqueue(json(JSONObject().put("alarms", JSONArray().put(remoteAlarm(hour = 8, version = 3, role = "OWNER")))))
        try {
            social.updateShared(alarms.alarm(remoteId)!!.copy(hour = 6))
            fail("expected conflict")
        } catch (e: ApiException) {
            assertEquals("version_conflict", e.code)
        }
        assertEquals(8, alarms.alarm(remoteId)!!.hour)
    }
}
