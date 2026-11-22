package org.xmyun.starmate.feature.mobileuse.guard

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.xmyun.starmate.feature.mobileuse.CHAT_APP
import org.xmyun.starmate.feature.mobileuse.LAUNCHER
import org.xmyun.starmate.feature.mobileuse.NOTES
import org.xmyun.starmate.feature.mobileuse.OWN
import org.xmyun.starmate.feature.mobileuse.SMS_APP
import org.xmyun.starmate.feature.mobileuse.model.AgentAction
import org.xmyun.starmate.feature.mobileuse.model.FindingCode
import org.xmyun.starmate.feature.mobileuse.model.Foreground
import org.xmyun.starmate.feature.mobileuse.model.GuardRule
import org.xmyun.starmate.feature.mobileuse.model.Observation
import org.xmyun.starmate.feature.mobileuse.model.RawObservation
import org.xmyun.starmate.feature.mobileuse.model.ScreenState
import org.xmyun.starmate.feature.mobileuse.model.ScrollDirection
import org.xmyun.starmate.feature.mobileuse.model.Verdict
import org.xmyun.starmate.feature.mobileuse.node
import org.xmyun.starmate.feature.mobileuse.policy
import org.xmyun.starmate.feature.mobileuse.screen

class GuardTest {

    private val guard = Guard()
    private val policy = policy()

    private fun check(action: AgentAction, s: ScreenState, confirmation: String? = null) =
        guard.check(action, s, policy, confirmation)

    private fun assertHalt(v: Verdict, rule: GuardRule): Verdict.Halt {
        assertTrue("expected Halt($rule) but was $v", v is Verdict.Halt)
        assertEquals(rule, (v as Verdict.Halt).rule)
        return v
    }

    private fun assertAllow(v: Verdict) {
        assertTrue("expected Allow but was $v", v is Verdict.Allow)
    }

    private suspend fun observe(fg: Foreground?, raw: RawObservation? = null): Pair<Observation, Int> {
        var reads = 0
        val o = guard.canObserve(fg, policy) {
            reads++
            raw
        }
        return o to reads
    }

    // ---- G1 白名单 ----

    @Test
    fun g1_allowlistedAppTapIsAllowed() {
        assertAllow(check(AgentAction.Tap(1), screen()))
    }

    @Test
    fun g1_appNotInAllowlistIsHalted() {
        val s = screen(pkg = "com.example.other", label = "Other")
        val v = assertHalt(check(AgentAction.Tap(1), s), GuardRule.G1)
        assertEquals(FindingCode.NOT_ALLOWLISTED, v.finding.code)
    }

    @Test
    fun g1_openAppTargetNotInAllowlistIsHalted() {
        val v = guard.check(
            AgentAction.OpenApp("com.example.other"),
            screen(),
            policy(labels = mapOf(NOTES to "Notes", "com.example.other" to "Other")),
        )
        assertHalt(v, GuardRule.G1)
    }

    @Test
    fun g1_openAppTargetInAllowlistIsAllowed() {
        assertAllow(check(AgentAction.OpenApp(CHAT_APP), screen()))
    }

    // ---- G2 黑名单优先 ----

    @Test
    fun g2_blockListBeatsAllowlist() {
        val alipay = "com.eg.android.AlipayGphone"
        val p = policy(allowlist = setOf(alipay, NOTES))
        val v = guard.check(AgentAction.Scroll(0, ScrollDirection.DOWN), screen(pkg = alipay), p)
        val halt = assertHalt(v, GuardRule.G2)
        assertEquals(FindingCode.BLOCKED_PACKAGE, halt.finding.code)
        assertEquals(BlockCategory.PAYMENT, halt.finding.category)
    }

    @Test
    fun g2_bankAppNameWithUnknownPackageIsHalted() {
        val pkg = "com.unknown.mobilebanking"
        val p = policy(allowlist = setOf(pkg))
        val v = guard.check(AgentAction.Tap(1), screen(pkg = pkg, label = "某某银行"), p)
        val halt = assertHalt(v, GuardRule.G2)
        assertEquals(FindingCode.BLOCKED_APP_NAME, halt.finding.code)
        assertEquals(BlockCategory.BANK, halt.finding.category)
    }

    @Test
    fun g2_canObserveIsFalseForBlockedAppAndNeverReads() = runTest {
        val (o, reads) = observe(Foreground("com.eg.android.AlipayGphone", "支付宝"))
        assertFalse(o.canSee)
        assertTrue(o is Observation.Halt)
        assertEquals(GuardRule.G2, (o as Observation.Halt).finding.rule)
        assertEquals(0, reads)
    }

    @Test
    fun g2_openAppOfBlockedAppIsHaltedEvenIfAllowlisted() {
        val alipay = "com.eg.android.AlipayGphone"
        val v = guard.check(AgentAction.OpenApp(alipay), screen(), policy(allowlist = setOf(NOTES, alipay)))
        assertHalt(v, GuardRule.G2)
    }

    @Test
    fun g2_blockedAppInSplitScreenHaltsEverything() {
        val s = screen(others = setOf("cmb.pb"))
        val v = assertHalt(check(AgentAction.Tap(1), s), GuardRule.G2)
        assertEquals(FindingCode.OTHER_WINDOW_BLOCKED, v.finding.code)
    }

    // ---- G3 收银台 ----

    @Test
    fun g3_confirmPaymentButtonHaltsEvenForScroll() {
        val s = screen(nodes = listOf(node(0, "订单详情"), node(1, "确认付款", clickable = true), node(2, scrollable = true)))
        val v = assertHalt(check(AgentAction.Scroll(2, ScrollDirection.DOWN), s), GuardRule.G3)
        assertEquals(FindingCode.CASHIER_TEXT, v.finding.code)
    }

    @Test
    fun g3_passwordKeyboardHalts() {
        val v = assertHalt(check(AgentAction.Back(), screen(passwordKeyboard = true)), GuardRule.G3)
        assertEquals(FindingCode.PASSWORD_KEYBOARD, v.finding.code)
    }

    @Test
    fun g3_cashierTextBeyondFirst150NodesStillHalts() {
        val nodes = (0 until 200).map { node(it, "item $it", clickable = true) } + node(200, "Pay now", clickable = true)
        assertHalt(check(AgentAction.Tap(0), screen(nodes = nodes)), GuardRule.G3)
    }

    @Test
    fun g3_inAppPinPadHalts() {
        val digits = (0..9).map { node(it + 1, it.toString(), clickable = true) }
        val s = screen(nodes = listOf(node(0, "请输入6位数字密码")) + digits)
        val v = assertHalt(check(AgentAction.Tap(1), s), GuardRule.G3)
        assertTrue(v.finding.code == FindingCode.PIN_PAD || v.finding.code == FindingCode.CASHIER_TEXT)
    }

    @Test
    fun g3_calculatorDigitsAloneDoNotHalt() {
        val digits = (0..9).map { node(it, it.toString(), clickable = true) }
        assertAllow(check(AgentAction.Tap(1), screen(nodes = digits)))
    }

    @Test
    fun g3_canObserveDropsCashierScreenContent() = runTest {
        val cashier = screen(nodes = listOf(node(0, "立即支付", clickable = true)))
        val (o, reads) = observe(Foreground(NOTES, "Notes"), RawObservation(cashier, "SHOT"))
        assertEquals(1, reads)
        assertTrue(o is Observation.Halt)
        assertEquals(GuardRule.G3, (o as Observation.Halt).finding.rule)
    }

    // ---- G4 密码框 ----

    @Test
    fun g4_inputIntoPasswordFieldIsHalted() {
        val s = screen(nodes = listOf(node(0, "登录"), node(1, editable = true, password = true)))
        val v = assertHalt(check(AgentAction.InputText(1, "secret"), s), GuardRule.G4)
        assertEquals(FindingCode.PASSWORD_FIELD, v.finding.code)
    }

    @Test
    fun g4_inputIntoNormalFieldIsAllowed() {
        val s = screen(nodes = listOf(node(0, "搜索"), node(1, editable = true, hint = "搜索笔记")))
        assertAllow(check(AgentAction.InputText(1, "咖啡"), s))
    }

    @Test
    fun g4_inputIntoVerificationCodeFieldIsHalted() {
        val s = screen(nodes = listOf(node(0, editable = true, hint = "请输入短信验证码")))
        val v = assertHalt(check(AgentAction.InputText(0, "123456"), s), GuardRule.G4)
        assertEquals(FindingCode.SECRET_FIELD, v.finding.code)
    }

    // ---- G5 敏感点击 ----

    @Test
    fun g5_tapOnSendNeedsConfirmation() {
        val s = screen(nodes = listOf(node(0, "你好"), node(1, "发送", clickable = true)))
        val v = check(AgentAction.Tap(1), s)
        assertTrue(v is Verdict.NeedsUserConfirm)
        assertEquals(GuardRule.G5, (v as Verdict.NeedsUserConfirm).finding.rule)
        assertEquals("发送", v.finding.subject)
    }

    @Test
    fun g5_tapOnNextPageIsAllowed() {
        assertAllow(check(AgentAction.Tap(1), screen()))
    }

    @Test
    fun g5_englishContentDescriptionNeedsConfirmation() {
        val s = screen(nodes = listOf(node(0, desc = "Delete note", clickable = true)))
        assertTrue(check(AgentAction.LongPress(0), s) is Verdict.NeedsUserConfirm)
    }

    @Test
    fun g5_wordInsideAnotherWordIsNotSensitive() {
        val s = screen(nodes = listOf(node(0, "Border color", clickable = true)))
        assertAllow(check(AgentAction.Tap(0), s))
    }

    @Test
    fun g3_broadWordInsideLongTextDoesNotHalt() {
        for (text in listOf("我明天把钱转账给你，别着急", "上次充值的事后来怎么样了", "I will withdraw from the course next week")) {
            val s = screen(nodes = listOf(node(0, "下一页", clickable = true), node(1, text)))
            assertAllow(check(AgentAction.Tap(0), s))
        }
    }

    @Test
    fun g3_broadWordOnButtonOrShortLabelStillHalts() {
        for (label in listOf("转账", "向张三转账", "立即充值", "添加银行卡", "Proceed to checkout", "Withdraw")) {
            val s = screen(nodes = listOf(node(0, "下一页", clickable = true), node(1, label, clickable = true)))
            assertTrue(label, check(AgentAction.Tap(0), s) is Verdict.Halt)
        }
    }

    @Test
    fun g3_explicitPaymentWordsHaltEvenInsideLongText() {
        val text = "订单金额 128 元，请核对后点击确认付款完成本次交易"
        val s = screen(nodes = listOf(node(0, "下一页", clickable = true), node(1, text)))
        assertTrue(check(AgentAction.Tap(0), s) is Verdict.Halt)
    }

    @Test
    fun g5_wordAtStartOfLongerWordIsNotSensitive() {
        for (label in listOf("Postpone", "Payload details", "Blocklist", "Okinawa trip")) {
            val s = screen(nodes = listOf(node(0, label, clickable = true)))
            assertAllow(check(AgentAction.Tap(0), s))
        }
    }

    @Test
    fun g5_inflectedSensitiveWordsStillNeedConfirmation() {
        for (label in listOf("Okay", "Sends", "Paying", "Deleted", "Sign out")) {
            val s = screen(nodes = listOf(node(0, label, clickable = true)))
            assertTrue(label, check(AgentAction.Tap(0), s) is Verdict.NeedsUserConfirm)
        }
    }

    @Test
    fun g5_matchingConfirmationAllowsAndOtherTargetDoesNot() {
        val s = screen(nodes = listOf(node(0, "发送", clickable = true), node(1, "删除", clickable = true)))
        val first = check(AgentAction.Tap(0), s) as Verdict.NeedsUserConfirm
        assertAllow(check(AgentAction.Tap(0), s, first.confirmationKey))
        // 同一个确认不能拿去点别的按钮
        assertTrue(check(AgentAction.Tap(1), s, first.confirmationKey) is Verdict.NeedsUserConfirm)
    }

    @Test
    fun g5_confirmationNeverOverridesHalt() {
        val ok = screen(nodes = listOf(node(0, "提交", clickable = true)))
        val key = (check(AgentAction.Tap(0), ok) as Verdict.NeedsUserConfirm).confirmationKey
        val nowCashier = screen(nodes = listOf(node(0, "提交", clickable = true), node(1, "确认支付")))
        assertHalt(check(AgentAction.Tap(0), nowCashier, key), GuardRule.G3)
    }

    // ---- G6 系统类 ----

    @Test
    fun g6_systemSettingsInFrontIsHalted() {
        val s = screen(pkg = "com.android.settings")
        val v = assertHalt(check(AgentAction.Back(), s), GuardRule.G6)
        assertEquals(FindingCode.SYSTEM_APP, v.finding.code)
    }

    @Test
    fun g6_systemUiAllowsBackButNotTap() {
        val s = screen(pkg = "com.android.systemui")
        assertAllow(check(AgentAction.Back(), s))
        assertAllow(check(AgentAction.Home(), s))
        assertHalt(check(AgentAction.Tap(1), s), GuardRule.G6)
    }

    @Test
    fun g6_systemUiIsNeverRead() = runTest {
        val (o, reads) = observe(Foreground("com.android.systemui", "System UI"))
        assertTrue(o is Observation.Blind)
        assertEquals(0, reads)
    }

    @Test
    fun g6_starmateItselfIsNotReadOrTappedButCanOpenAllowedApp() = runTest {
        val (o, reads) = observe(Foreground(OWN, "星伴"))
        assertTrue(o is Observation.Blind)
        assertEquals(0, reads)
        val header = ScreenState.headerOnly(Foreground(OWN, "星伴"))
        assertAllow(check(AgentAction.OpenApp(NOTES), header))
        assertHalt(check(AgentAction.Tap(0), header), GuardRule.G6)
        assertHalt(check(AgentAction.Back(), header), GuardRule.G6)
    }

    @Test
    fun g6_openingSettingsIsHalted() {
        assertHalt(check(AgentAction.OpenApp("com.android.settings"), screen()), GuardRule.G6)
    }

    @Test
    fun g6_openingStarmateIsHalted() {
        assertHalt(check(AgentAction.OpenApp(OWN), screen()), GuardRule.G6)
    }

    // ---- G7 短信、验证器、密码管理器 ----

    @Test
    fun g7_defaultSmsAppIsHaltedEvenIfAllowlisted() {
        val p = policy(allowlist = setOf(SMS_APP))
        val v = guard.check(AgentAction.Tap(1), screen(pkg = SMS_APP), p)
        val halt = assertHalt(v, GuardRule.G7)
        assertEquals(FindingCode.DEFAULT_SMS_APP, halt.finding.code)
    }

    @Test
    fun g7_authenticatorIsHalted() {
        val pkg = "com.google.android.apps.authenticator2"
        assertHalt(guard.check(AgentAction.Tap(1), screen(pkg = pkg), policy(allowlist = setOf(pkg))), GuardRule.G7)
    }

    @Test
    fun g7_openingDefaultSmsAppIsHalted() {
        assertHalt(check(AgentAction.OpenApp(SMS_APP), screen()), GuardRule.G7)
    }

    @Test
    fun g7_nonSmsAppIsAllowed() {
        assertAllow(guard.check(AgentAction.Tap(1), screen(pkg = CHAT_APP), policy()))
    }

    // ---- G10 拿不准就拦 ----

    @Test
    fun g10_noForegroundIsHaltedWithoutReading() = runTest {
        val (o, reads) = observe(null)
        assertTrue(o is Observation.Halt)
        assertEquals(GuardRule.G10, (o as Observation.Halt).finding.rule)
        assertEquals(0, reads)
    }

    @Test
    fun g10_missingAppNameIsHalted() {
        val pkg = "com.example.nolabel"
        val v = guard.check(AgentAction.Tap(1), screen(pkg = pkg, label = null), policy(allowlist = setOf(pkg)))
        assertHalt(v, GuardRule.G10)
    }

    @Test
    fun g10_emptyTreeIsHalted() {
        assertHalt(check(AgentAction.Back(), screen(nodes = emptyList())), GuardRule.G10)
    }

    @Test
    fun g10_readFailureIsHalted() = runTest {
        val (o, reads) = observe(Foreground(NOTES, "Notes"), raw = null)
        assertEquals(1, reads)
        assertEquals(GuardRule.G10, (o as Observation.Halt).finding.rule)
    }

    @Test
    fun g10_packageChangedDuringReadIsHalted() = runTest {
        val (o, _) = observe(Foreground(NOTES, "Notes"), RawObservation(screen(pkg = CHAT_APP)))
        assertEquals(FindingCode.PACKAGE_CHANGED, (o as Observation.Halt).finding.code)
    }

    @Test
    fun g10_allowlistedNormalScreenIsVisible() = runTest {
        val s = screen()
        val (o, reads) = observe(Foreground(NOTES, "Notes"), RawObservation(s))
        assertEquals(1, reads)
        assertTrue(o.canSee)
        assertEquals(s, (o as Observation.Visible).screen)
    }

    @Test
    fun g10_launcherIsNotReadButCanOpenAllowedApp() = runTest {
        val (o, reads) = observe(Foreground(LAUNCHER, "Home"))
        assertTrue(o is Observation.Blind)
        assertEquals(0, reads)
        val header = ScreenState.headerOnly(Foreground(LAUNCHER, "Home"))
        assertAllow(check(AgentAction.OpenApp(NOTES), header))
        assertHalt(check(AgentAction.Tap(0), header), GuardRule.G1)
    }

    @Test
    fun targetIndexOutOfRangeIsHalted() {
        assertHalt(check(AgentAction.Tap(99), screen()), GuardRule.G10)
    }

    // ---- 许可和放行凭证 ----

    @Test
    fun observePermitIsRevokedAfterRead() = runTest {
        var captured: ObservePermit? = null
        guard.canObserve(Foreground(NOTES, "Notes"), policy) {
            captured = it
            assertTrue(it.isValid)
            RawObservation(screen())
        }
        assertNotNull(captured)
        assertFalse(captured!!.isValid)
    }

    @Test
    fun approvedActionCanBeUsedOnlyOnce() {
        val v = check(AgentAction.Tap(1), screen()) as Verdict.Allow
        assertTrue(v.approved.consume())
        assertFalse(v.approved.consume())
        assertEquals(NOTES, v.approved.packageName)
        assertEquals("下一页", v.approved.target?.text)
    }

    @Test
    fun permitsCannotBeForgedOutsideGuard() {
        try {
            ObservePermit(Any(), NOTES)
            fail("ObservePermit created without Guard key")
        } catch (e: IllegalStateException) {
            // 预期
        }
        try {
            ApprovedAction(Any(), AgentAction.Back(), NOTES, 1, null)
            fail("ApprovedAction created without Guard key")
        } catch (e: IllegalStateException) {
            // 预期
        }
    }

    @Test
    fun nonDeviceActionsAreNotApproved() {
        assertHalt(check(AgentAction.Done("x"), screen()), GuardRule.G10)
    }

    @Test
    fun allowlistEligibility() {
        assertNull(guard.allowlistBlockReason(NOTES, "Notes", policy))
        assertNotNull(guard.allowlistBlockReason("com.eg.android.AlipayGphone", "支付宝", policy))
        assertNotNull(guard.allowlistBlockReason("com.unknown.x", "XX Wallet", policy))
        assertNotNull(guard.allowlistBlockReason(OWN, "星伴", policy))
        assertNotNull(guard.allowlistBlockReason(SMS_APP, "Messenger X", policy))
    }
}
