package org.xmyun.starmate.core.data.store

import java.io.File
import java.time.LocalDate
import java.time.ZoneId
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmyun.starmate.core.data.MutableClock
import org.xmyun.starmate.core.data.db.ChatDatabaseHolder
import org.xmyun.starmate.core.data.newHolder
import org.xmyun.starmate.core.data.newPrefsDataStore
import org.xmyun.starmate.core.data.prefs.DataStoreCompanionPrefs
import org.xmyun.starmate.core.model.DailyUsage
import org.xmyun.starmate.core.model.TurnPurpose
import org.xmyun.starmate.core.model.UsageEntry

@RunWith(RobolectricTestRunner::class)
class RoomUsageLedgerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.of(2026, 11, 11)
    private val noon = today.atTime(12, 0).atZone(zone).toInstant()

    private lateinit var scope: CoroutineScope
    private lateinit var holder: ChatDatabaseHolder
    private lateinit var prefs: DataStoreCompanionPrefs
    private lateinit var clock: MutableClock
    private lateinit var ledger: RoomUsageLedger

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        holder = newHolder(tmp.root)
        prefs = DataStoreCompanionPrefs(newPrefsDataStore(scope, File(tmp.root, "p.preferences_pb")))
        clock = MutableClock(noon)
        ledger = RoomUsageLedger(holder, prefs, clock)
    }

    @After
    fun tearDown() {
        holder.close()
        scope.cancel()
    }

    private fun entry(purpose: TurnPurpose, input: Int, output: Int, at: java.time.Instant = noon, tts: Int = 0) =
        UsageEntry(purpose = purpose, model = "m", inputTokens = input, outputTokens = output, ttsChars = tts, at = at)

    @Test
    fun twoEntriesAccumulate() = runTest {
        ledger.record(entry(TurnPurpose.MEMORY, 100, 50))
        ledger.record(entry(TurnPurpose.MEMORY, 10, 5, at = noon.plusSeconds(60)))
        ledger.record(entry(TurnPurpose.DIARY, 1000, 1000))
        assertEquals(165, ledger.usedToday(TurnPurpose.MEMORY))
    }

    @Test
    fun yesterdayIsNotCounted() = runTest {
        val lateYesterday = today.atStartOfDay(zone).toInstant().minusMillis(1)
        ledger.record(entry(TurnPurpose.MEMORY, 500, 500, at = lateYesterday))
        ledger.record(entry(TurnPurpose.MEMORY, 1, 2, at = today.atStartOfDay(zone).toInstant()))
        assertEquals(3, ledger.usedToday(TurnPurpose.MEMORY))
    }

    @Test
    fun allowFalseOnceBudgetReached() = runTest {
        prefs.setDailyBudget(TurnPurpose.MEMORY, 1000)
        ledger.record(entry(TurnPurpose.MEMORY, 600, 399))
        assertTrue(ledger.allow(TurnPurpose.MEMORY))
        ledger.record(entry(TurnPurpose.MEMORY, 1, 0))
        assertFalse(ledger.allow(TurnPurpose.MEMORY))
        // 第二天重新可用
        clock.now = noon.plusSeconds(24 * 3600)
        assertTrue(ledger.allow(TurnPurpose.MEMORY))
    }

    @Test
    fun allowChatAlwaysTrue() = runTest {
        prefs.setDailyBudget(TurnPurpose.CHAT, 1)
        ledger.record(entry(TurnPurpose.CHAT, 1_000_000, 1_000_000))
        assertTrue(ledger.allow(TurnPurpose.CHAT))
        assertTrue(ledger.allow(TurnPurpose.ONBOARDING))
        assertTrue(ledger.allow(TurnPurpose.MOBILE_USE))
    }

    @Test
    fun observeDailyGroupsByDateAndPurpose() = runTest {
        val yesterdayNoon = noon.minusSeconds(24 * 3600)
        val longAgo = noon.minusSeconds(10L * 24 * 3600)
        ledger.record(entry(TurnPurpose.CHAT, 10, 20, at = yesterdayNoon, tts = 5))
        ledger.record(entry(TurnPurpose.CHAT, 1, 2, at = yesterdayNoon.plusSeconds(60), tts = 1))
        ledger.record(entry(TurnPurpose.MEMORY, 100, 200, at = yesterdayNoon))
        ledger.record(entry(TurnPurpose.CHAT, 3, 4, at = noon))
        ledger.record(entry(TurnPurpose.CHAT, 9999, 9999, at = longAgo))

        val daily = ledger.observeDaily(7).first()
        assertEquals(
            listOf(
                DailyUsage(today.minusDays(1), TurnPurpose.CHAT, 11, 22, 6),
                DailyUsage(today.minusDays(1), TurnPurpose.MEMORY, 100, 200, 0),
                DailyUsage(today, TurnPurpose.CHAT, 3, 4, 0),
            ),
            daily,
        )
    }
}
