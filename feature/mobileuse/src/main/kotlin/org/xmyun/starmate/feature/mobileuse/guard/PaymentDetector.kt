package org.xmyun.starmate.feature.mobileuse.guard

import org.xmyun.starmate.feature.mobileuse.model.FindingCode
import org.xmyun.starmate.feature.mobileuse.model.ScreenState
import org.xmyun.starmate.feature.mobileuse.model.UiNode

/**
 * G3 收银台识别。扫描整份界面树（不只是发给模型的前 150 个），
 * 命中密码键盘、数字密码盘或收银台特征文字中的任意一项即判定为收银台。
 * 特征词分两类：明确的付款用语在任何文字里出现都算；聊天里也常见的词只在按钮和短标签上才算。
 */
object PaymentDetector {

    /** 收银台特征。比较前去掉空白并转小写。 */
    val CASHIER_PHRASES: List<String> = listOf(
        // 中文
        "确认付款", "立即付款", "立即支付", "确认支付", "去支付", "继续支付", "完成支付",
        "支付密码", "输入支付密码", "免密支付", "指纹支付", "面容支付", "刷脸支付",
        "转账", "付款给", "向商家付款", "付款码", "收银台", "确认交易",
        "充值", "提现", "还款", "借款", "打赏", "发红包", "塞钱进红包",
        "银行卡", "卡号", "安全码",
        // 英文
        "Pay now", "Confirm payment", "Complete payment", "Authorize payment", "Confirm and pay",
        "Send money", "Transfer money", "Payment password", "Payment PIN", "Enter PIN",
        "Checkout", "Check out", "Top up", "Withdraw", "Card number", "CVV", "CVC",
    )

    /**
     * 日常聊天里也常出现的词。它们只在按钮、短标签上才算收银台特征（见 [isLabelLike]），
     * 否则聊天记录里有人写一句“我明天转账给你”，整个聊天应用就没法操作了。
     * 其余的词（“确认付款”“立即支付”“支付密码”等）在任何文字里出现都算。
     */
    val BROAD_PHRASES: Set<String> = setOf(
        "转账", "充值", "提现", "还款", "借款", "打赏", "发红包", "银行卡", "卡号",
        "Send money", "Transfer money", "Checkout", "Check out", "Top up", "Withdraw", "Card number",
    )

    private class Phrase(val normalized: String, val original: String, val broad: Boolean)

    private val phrases = CASHIER_PHRASES.map { Phrase(BlockList.normalize(it), it, it in BROAD_PHRASES) }

    /**
     * 这段文字是不是像按钮或短标签：整段不比特征词长多少。
     * “转账”“向张三转账”“Proceed to checkout”算；“我明天把钱转账给你”不算。
     */
    private fun isLabelLike(normalizedText: String, phrase: String): Boolean =
        normalizedText.length <= maxOf(phrase.length * 3, phrase.length + 4)
    private val PIN_WORD = Regex("""(?i)(?<![a-z])pin(?![a-z])""")
    private val DIGITS = (0..9).map { it.toString() }.toSet()

    /** 命中时返回原因和命中的文字，否则 null。 */
    fun detect(screen: ScreenState): Pair<FindingCode, String?>? {
        if (screen.passwordKeyboardShown) return FindingCode.PASSWORD_KEYBOARD to null
        for (node in screen.nodes) {
            for (text in node.allTexts()) {
                val normalized = BlockList.normalize(text)
                val hit = phrases.firstOrNull { phrase ->
                    normalized.contains(phrase.normalized) && (!phrase.broad || isLabelLike(normalized, phrase.normalized))
                }
                if (hit != null) return FindingCode.CASHIER_TEXT to hit.original
            }
        }
        if (looksLikePinPad(screen.nodes)) return FindingCode.PIN_PAD to null
        return null
    }

    /**
     * 应用自己画的数字密码盘：0–9 十个数字键都在，且屏幕上有密码框或“密码 / password / PIN”字样。
     * 只看数字会把计算器也拦下，所以加了第二个条件。
     */
    fun looksLikePinPad(nodes: List<UiNode>): Boolean {
        val digits = HashSet<String>()
        var passwordHint = false
        for (node in nodes) {
            if (node.isPassword) passwordHint = true
            for (text in node.allTexts()) {
                val t = text.trim()
                if (t in DIGITS) digits += t
                val n = BlockList.normalize(t)
                if (n.contains("密码") || n.contains("password") || n.contains("passcode") || PIN_WORD.containsMatchIn(t)) {
                    passwordHint = true
                }
            }
        }
        return passwordHint && digits.containsAll(DIGITS)
    }
}
