package org.xmyun.starmate.feature.proactive.gate

import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.xmyun.starmate.core.model.Clinginess
import org.xmyun.starmate.core.model.QuietHours
import org.xmyun.starmate.feature.proactive.TEST_ZONE
import org.xmyun.starmate.feature.proactive.local

class ProactiveGateTest {

    private val noon = local("2026-11-12T12:00")

    /** 全部通过的基线。 */
    private fun input() = GateInput(
        now = noon,
        zone = TEST_ZONE,
        enabled = true,
        clinginess = Clinginess.MEDIUM,
        networkAvailable = true,
        quietHours = QuietHours.DEFAULT,
        sentToday = 0,
        lastProactiveAt = null,
        proactiveSinceLastUserMessage = 0,
        lastChatMessageAt = noon.minus(Duration.ofHours(3)),
        engineBusy = false,
        budgetAllows = true,
    )

    private fun assertAllow(input: GateInput) = assertEquals(GateResult.Allow, ProactiveGate.evaluate(input))

    private fun assertDeny(reason: DenyReason, input: GateInput) =
        assertEquals(GateResult.Deny(reason), ProactiveGate.evaluate(input))

    @Test
    fun baselinePasses() = assertAllow(input())

    // 1. 总开关
    @Test
    fun masterSwitchOn_passes() = assertAllow(input().copy(enabled = true))

    @Test
    fun masterSwitchOff_isDisabled() = assertDeny(DenyReason.DISABLED, input().copy(enabled = false))

    // 2. 粘人程度
    @Test
    fun clinginessLow_passes() = assertAllow(input().copy(clinginess = Clinginess.LOW))

    @Test
    fun clinginessOff_isDisabled() = assertDeny(DenyReason.DISABLED, input().copy(clinginess = Clinginess.OFF))

    // 3. 网络
    @Test
    fun online_passes() = assertAllow(input().copy(networkAvailable = true))

    @Test
    fun offline_isNoNetwork() = assertDeny(DenyReason.NO_NETWORK, input().copy(networkAvailable = false))

    // 4. 免打扰（默认 23:00–08:00，跨午夜）
    @Test
    fun outsideQuietHours_passes() = assertAllow(input().copy(now = local("2026-11-12T22:59")))

    @Test
    fun lateEvening_isQuietHours() = assertDeny(DenyReason.QUIET_HOURS, input().copy(now = local("2026-11-12T23:30")))

    @Test
    fun earlyMorning_isQuietHours() = assertDeny(DenyReason.QUIET_HOURS, input().copy(now = local("2026-11-13T07:59")))

    @Test
    fun quietHoursEndAtEight_passes() = assertAllow(input().copy(now = local("2026-11-13T08:00")))

    // 5. 每日上限
    @Test
    fun underDailyCap_passes() = assertAllow(input().copy(sentToday = 1))

    @Test
    fun dailyCapReached_isDailyCap() = assertDeny(DenyReason.DAILY_CAP, input().copy(sentToday = 2))

    @Test
    fun dailyCapPerClinginess() {
        // LOW 第 2 条、MEDIUM 第 3 条、HIGH 第 5 条被拦（已发数分别为 1、2、4）
        assertAllow(input().copy(clinginess = Clinginess.LOW, sentToday = 0))
        assertDeny(DenyReason.DAILY_CAP, input().copy(clinginess = Clinginess.LOW, sentToday = 1))
        assertAllow(input().copy(clinginess = Clinginess.MEDIUM, sentToday = 1))
        assertDeny(DenyReason.DAILY_CAP, input().copy(clinginess = Clinginess.MEDIUM, sentToday = 2))
        assertAllow(input().copy(clinginess = Clinginess.HIGH, sentToday = 3))
        assertDeny(DenyReason.DAILY_CAP, input().copy(clinginess = Clinginess.HIGH, sentToday = 4))
    }

    // 6. 距上一条主动消息
    @Test
    fun sixHoursSinceLastProactive_passes() =
        assertAllow(input().copy(lastProactiveAt = noon.minus(Duration.ofHours(6))))

    @Test
    fun underSixHoursSinceLastProactive_isTooSoon() =
        assertDeny(DenyReason.TOO_SOON, input().copy(lastProactiveAt = noon.minus(Duration.ofHours(6)).plusSeconds(1)))

    // 7. 连续未回复
    @Test
    fun oneUnanswered_passes() = assertAllow(input().copy(proactiveSinceLastUserMessage = 1))

    @Test
    fun twoUnanswered_isUnanswered() = assertDeny(DenyReason.UNANSWERED, input().copy(proactiveSinceLastUserMessage = 2))

    // 8. 正在聊天
    @Test
    fun tenMinutesSinceChat_passes() =
        assertAllow(input().copy(lastChatMessageAt = noon.minus(Duration.ofMinutes(10))))

    @Test
    fun chatWithinTenMinutes_isChatting() =
        assertDeny(DenyReason.CHATTING, input().copy(lastChatMessageAt = noon.minus(Duration.ofMinutes(9))))

    @Test
    fun engineBusy_isChatting() = assertDeny(DenyReason.CHATTING, input().copy(engineBusy = true))

    // 9. 后台预算
    @Test
    fun budgetLeft_passes() = assertAllow(input().copy(budgetAllows = true))

    @Test
    fun budgetUsedUp_isBudget() = assertDeny(DenyReason.BUDGET, input().copy(budgetAllows = false))

    @Test
    fun firstFailingGateWins() {
        val everythingWrong = input().copy(
            networkAvailable = false,
            now = local("2026-11-12T23:30"),
            sentToday = 9,
            budgetAllows = false,
        )
        assertDeny(DenyReason.NO_NETWORK, everythingWrong)
        assertDeny(DenyReason.DISABLED, everythingWrong.copy(enabled = false))
    }
}
