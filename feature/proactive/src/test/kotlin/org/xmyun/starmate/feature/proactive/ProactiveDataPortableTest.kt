package org.xmyun.starmate.feature.proactive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.Duration
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmyun.starmate.feature.proactive.db.ProactiveDatabase
import org.xmyun.starmate.feature.proactive.db.ProactiveDatabaseHolder
import org.xmyun.starmate.feature.proactive.model.Care
import org.xmyun.starmate.feature.proactive.prefs.ProactivePrefs
import org.xmyun.starmate.feature.proactive.schedule.CareRescheduler
import org.xmyun.starmate.feature.proactive.store.RoomCareStore
import org.xmyun.starmate.feature.proactive.store.RoomSendLogStore

@RunWith(RobolectricTestRunner::class)
class ProactiveDataPortableTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val now = local("2026-11-12T10:00")
    private val clock = MutableClock(now)
    private val scheduler = FakeScheduler()
    private val checkIn = FakeCheckInScheduler()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var holder: ProactiveDatabaseHolder
    private lateinit var cares: RoomCareStore
    private lateinit var prefs: ProactivePrefs
    private lateinit var portable: ProactiveDataPortable

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "proactive-${UUID.randomUUID()}.db"
        holder = ProactiveDatabaseHolder(context.getDatabasePath(name)) { ProactiveDatabase.build(context, name) }
        cares = RoomCareStore(holder)
        prefs = newPrefs()
        val logs = RoomSendLogStore(holder)
        val rescheduler = CareRescheduler(
            cares, logs, scheduler, checkIn, prefs, FakeCompanionPrefs(), FixedRandom(0), clock, FakeDiag(),
        )
        portable = ProactiveDataPortable(holder, prefs, rescheduler)
    }

    @After
    fun tearDown() {
        holder.close()
        scope.cancel()
    }

    private fun care(topic: String, at: java.time.Instant) =
        Care(topic = topic, reason = "", scheduledFor = at, nextAttemptAt = at, createdAt = now, updatedAt = now)

    @Test
    fun exportThenImport_restoresCaresAndReschedules() = runTest {
        val keptId = cares.insert(care("kept", now.plus(Duration.ofDays(1))))
        prefs.setEnabled(false)
        val exportDir = tmp.newFolder("export")
        portable.exportTo(exportDir)
        assertTrue(File(exportDir, ProactiveDatabase.FILE_NAME).isFile)
        assertTrue(File(exportDir, ProactiveDataPortable.PREFS_FILE).isFile)

        val addedId = cares.insert(care("added later", now.plus(Duration.ofDays(2))))
        prefs.setEnabled(true)
        scheduler.scheduled.clear()

        portable.importFrom(exportDir)

        assertEquals(listOf("kept"), cares.pending().map { it.topic })
        assertFalse(prefs.enabled.first())
        // 导入前撤掉了旧库两条约定的闹钟；导入后总开关是关的，所以没有新登记，周期任务也被关掉
        assertTrue(scheduler.cancelled.containsAll(listOf(keptId, addedId)))
        assertTrue(scheduler.scheduled.isEmpty())
        assertEquals(false, checkIn.enabled)
    }

    @Test
    fun import_whenEnabled_registersAlarms() = runTest {
        val id = cares.insert(care("kept", now.plus(Duration.ofDays(1))))
        val exportDir = tmp.newFolder("export")
        portable.exportTo(exportDir)
        scheduler.scheduled.clear()

        portable.importFrom(exportDir)

        assertEquals(now.plus(Duration.ofDays(1)), scheduler.scheduled[id])
        assertEquals(true, checkIn.enabled)
    }

    @Test
    fun import_rejectsNonSqliteFile() = runTest {
        cares.insert(care("kept", now.plus(Duration.ofDays(1))))
        val bad = tmp.newFolder("bad")
        File(bad, ProactiveDatabase.FILE_NAME).writeText("not a database")
        try {
            portable.importFrom(bad)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // 预期
        }
        assertEquals(listOf("kept"), cares.pending().map { it.topic })
    }
}
