package org.xmyun.starmate.feature.proactive.tools

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import java.time.Duration
import java.util.UUID
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmyun.starmate.core.model.ToolContext
import org.xmyun.starmate.core.model.TurnPurpose
import org.xmyun.starmate.feature.proactive.FakeNotifier
import org.xmyun.starmate.feature.proactive.FakeScheduler
import org.xmyun.starmate.feature.proactive.FixedRandom
import org.xmyun.starmate.feature.proactive.MutableClock
import org.xmyun.starmate.feature.proactive.db.ProactiveDatabase
import org.xmyun.starmate.feature.proactive.db.ProactiveDatabaseHolder
import org.xmyun.starmate.feature.proactive.local
import org.xmyun.starmate.feature.proactive.model.CareStatus
import org.xmyun.starmate.feature.proactive.store.RoomCareStore

@RunWith(RobolectricTestRunner::class)
class ScheduleCareToolTest {

    private val now = local("2026-11-12T10:00")
    private val clock = MutableClock(now)
    private val scheduler = FakeScheduler()
    private val notifier = FakeNotifier()
    private val ctx = ToolContext(TurnPurpose.CHAT)
    private lateinit var holder: ProactiveDatabaseHolder
    private lateinit var store: RoomCareStore
    private lateinit var service: CareService
    private lateinit var schedule: ScheduleCareTool
    private lateinit var cancel: CancelCareTool
    private lateinit var list: ListCaresTool

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "proactive-${UUID.randomUUID()}.db"
        holder = ProactiveDatabaseHolder(context.getDatabasePath(name)) { ProactiveDatabase.build(context, name) }
        store = RoomCareStore(holder)
        service = CareService(store, scheduler, FixedRandom(120), clock)
        schedule = ScheduleCareTool(service, notifier, clock)
        cancel = CancelCareTool(service)
        list = ListCaresTool(service, clock)
    }

    @After
    fun tearDown() {
        holder.close()
    }

    private fun args(whenText: String, topic: String = "interview", reason: String = "ask how it went"): JsonObject =
        buildJsonObject {
            put("when", whenText)
            put("topic", topic)
            put("reason", reason)
        }

    @Test
    fun pastTime_isRejected() = runTest {
        val result = schedule.execute(args("2026-11-12T09:59"), ctx)
        assertTrue(result.isError)
        assertTrue(store.pending().isEmpty())
        assertTrue(scheduler.scheduled.isEmpty())
    }

    @Test
    fun ninetyOneDaysAhead_isRejected() = runTest {
        val result = schedule.execute(args("2027-02-11T10:00"), ctx) // 91 天后
        assertTrue(result.isError)
        assertTrue(store.pending().isEmpty())

        val ok = schedule.execute(args("2027-02-10T10:00"), ctx) // 正好 90 天
        assertFalse(ok.content, ok.isError)
    }

    @Test
    fun twentyFirstPendingCare_isRejected() = runTest {
        repeat(20) { i ->
            val result = schedule.execute(args("2026-11-13T%02d:00".format(i % 24), topic = "t$i"), ctx)
            assertFalse(result.content, result.isError)
        }
        val result = schedule.execute(args("2026-11-14T10:00", topic = "one too many"), ctx)
        assertTrue(result.isError)
        assertEquals(20, store.countPending())
    }

    @Test
    fun success_storesCareAndSchedulesAlarm() = runTest {
        val result = schedule.execute(args("2026-11-13T18:30"), ctx)

        assertFalse(result.content, result.isError)
        val care = store.pending().single()
        assertTrue(result.content.contains("#${care.id}"))
        assertEquals("interview", care.topic)
        assertEquals("ask how it went", care.reason)
        assertEquals(CareStatus.PENDING, care.status)
        assertEquals(local("2026-11-13T18:30"), care.scheduledFor)
        // 加了 +120 秒的随机偏移
        assertEquals(local("2026-11-13T18:30").plusSeconds(120), care.nextAttemptAt)
        assertEquals(care.nextAttemptAt, scheduler.scheduled[care.id])
        assertFalse(result.content.contains("notification permission"))
    }

    @Test
    fun success_withoutNotificationPermission_tellsModel() = runTest {
        notifier.allowed = false
        val result = schedule.execute(args("2026-11-13T18:30"), ctx)
        assertFalse(result.isError)
        assertTrue(result.content.contains("notification permission is not granted"))
    }

    @Test
    fun acceptsSpaceSeparatorAndOffset() = runTest {
        assertFalse(schedule.execute(args("2026-11-13 18:30"), ctx).isError)
        assertFalse(schedule.execute(args("2026-11-13T18:30+08:00"), ctx).isError)
        assertTrue(schedule.execute(args("tomorrow evening"), ctx).isError)
    }

    @Test
    fun cancel_notifiesScheduler() = runTest {
        schedule.execute(args("2026-11-13T18:30"), ctx)
        val id = store.pending().single().id

        val result = cancel.execute(buildJsonObject { put("id", id) }, ctx)

        assertFalse(result.content, result.isError)
        assertEquals(listOf(id), scheduler.cancelled)
        assertEquals(CareStatus.CANCELLED, store.byId(id)!!.status)
        assertTrue(store.pending().isEmpty())

        // 再取消一次报错
        assertTrue(cancel.execute(buildJsonObject { put("id", id) }, ctx).isError)
    }

    @Test
    fun list_showsPendingCares() = runTest {
        assertEquals("No pending check-ins.", list.execute(JsonObject(emptyMap()), ctx).content)
        schedule.execute(args("2026-11-13T18:30"), ctx)
        val content = list.execute(JsonObject(emptyMap()), ctx).content
        assertTrue(content, content.contains("2026-11-13T18:30"))
        assertTrue(content.contains("interview"))
    }

    @Test
    fun cancel_acceptsStringId() = runTest {
        schedule.execute(args("2026-11-13T18:30"), ctx)
        val id = store.pending().single().id
        val result = cancel.execute(JsonObject(mapOf("id" to JsonPrimitive("#$id"))), ctx)
        assertFalse(result.isError)
    }

    @Test
    fun clockAdvancing_keepsNinetyDayWindowRelativeToNow() = runTest {
        clock.now = now.plus(Duration.ofDays(10))
        assertTrue(schedule.execute(args("2026-11-13T18:30"), ctx).isError) // 已经过去
    }
}
