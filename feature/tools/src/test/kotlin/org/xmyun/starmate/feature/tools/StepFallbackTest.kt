package org.xmyun.starmate.feature.tools

import java.time.Duration
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmyun.starmate.feature.tools.db.ToolsDatabase
import org.xmyun.starmate.feature.tools.health.StepFallback

@RunWith(RobolectricTestRunner::class)
class StepFallbackTest {

    private lateinit var db: ToolsDatabase
    private val clock = clockAt("2026-11-10T00:30")
    private lateinit var fallback: StepFallback
    private val today = LocalDate.parse("2026-11-10")

    @Before
    fun setUp() {
        db = newDatabase()
        fallback = StepFallback(db.stepSampleDao(), clock)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun sampleAfter(hours: Long, value: Long) {
        clock.now = clock.now.plus(Duration.ofHours(hours))
        fallback.record(value)
    }

    @Test
    fun noSamplesIsNull() = runTest {
        assertNull(fallback.stepsOn(today))
    }

    @Test
    fun singleSampleIsZero() = runTest {
        fallback.record(12_000)
        assertEquals(0L, fallback.stepsOn(today))
    }

    @Test
    fun normalAccumulation() = runTest {
        fallback.record(10_000)       // 00:30
        sampleAfter(8, 10_500)        // 08:30
        sampleAfter(1, 11_700)        // 09:30
        sampleAfter(9, 15_000)        // 18:30
        assertEquals(5_000L, fallback.stepsOn(today))
    }

    @Test
    fun rebootRestartsFromNewValue() = runTest {
        fallback.record(10_000)       // 00:30
        sampleAfter(9, 12_000)        // +2000
        sampleAfter(1, 300)           // 重启后计步器从 0 起算：+300
        sampleAfter(1, 1_300)         // +1000
        assertEquals(3_300L, fallback.stepsOn(today))
    }

    @Test
    fun otherDaysAreSeparate() = runTest {
        fallback.record(1_000)        // 11-10 00:30
        sampleAfter(12, 6_000)        // 11-10 12:30
        sampleAfter(12, 8_000)        // 11-11 00:30
        sampleAfter(10, 9_500)        // 11-11 10:30
        // 12:30 到次日 00:30 这一段的 2000 步按时间比例分：11.5 小时在前一天，0.5 小时在后一天
        assertEquals(5_000L + 1_916L, fallback.stepsOn(today))
        assertEquals(1_500L + 83L, fallback.stepsOn(today.plusDays(1)))
    }

    @Test
    fun stepsBeforeFirstSampleOfTheDayAreNotLost() = runTest {
        clock.now = clock.now.minus(Duration.ofHours(2))  // 前一天 22:30
        fallback.record(20_000)
        sampleAfter(11, 23_000)       // 当天 09:30：这 11 小时里有 9.5 小时在当天
        sampleAfter(12, 26_000)       // 当天 21:30
        // 3000 * 9.5 / 11 = 2590，加上白天的 3000
        assertEquals(2_590L + 3_000L, fallback.stepsOn(today))
    }

    @Test
    fun farAwaySampleIsNotUsedAsBaseline() = runTest {
        clock.now = clock.now.minus(Duration.ofHours(30))  // 一天多以前
        fallback.record(1_000)
        sampleAfter(40, 9_000)        // 当天 10:30
        sampleAfter(2, 9_400)         // 当天 12:30
        assertEquals(400L, fallback.stepsOn(today))
    }
}
