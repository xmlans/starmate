package org.xmyun.starmate.feature.mobileuse.guard

import java.util.Locale
import org.xmyun.starmate.feature.mobileuse.model.UiNode

/**
 * G5：点击或长按的目标像是“发送、发布、删除、提交、下单、购买、确认”这类不可撤回的操作时，要先问用户。
 * G4 的补充：往验证码、卡号、证件号这类输入框里输字一律拦下。
 */
object SensitiveActionDetector {

    /** 中文按子串匹配（去空白）。 */
    val SENSITIVE_ZH: List<String> = listOf(
        "发送", "发布", "发表", "删除", "提交", "下单", "购买", "确认", "确定", "支付", "付款",
        "转发", "分享", "拨打", "呼叫", "卸载", "安装", "清空", "退出登录", "注销", "授权", "允许", "同意",
        "订阅", "开通", "续费", "兑换", "解绑", "移除", "举报", "拉黑",
    )

    /** 英文按词首匹配，避免 “Border” 命中 “order”；少数复合词单列。 */
    val SENSITIVE_EN: List<String> = listOf(
        "send", "resend", "post", "repost", "publish", "delete", "remove", "erase", "clear", "reset",
        "submit", "buy", "purchase", "order", "reorder", "checkout", "pay", "confirm", "ok", "okay",
        "share", "forward", "call", "dial", "uninstall", "install", "subscribe",
        "agree", "accept", "allow", "grant", "sign out", "log out", "logout", "report", "block", "unlink",
    )

    // 词首、词尾都要是边界，只放过常见的词形变化（sends、deleted、paying）。
    // 没有词尾边界的话，“Postpone”“Payload”“Blocklist”都会被当成敏感操作，确认框弹得太多反而没人看。
    private val enRegex = Regex(
        "(?<![a-z])(" + SENSITIVE_EN.joinToString("|") { it.split(" ").joinToString("\\s*") { part -> Regex.escape(part) } } +
            ")(?:s|d|ed|ing)?(?![a-z])",
    )

    /** 输入框标签里出现这些字样时不许输入。 */
    val SECRET_FIELD_WORDS: List<String> = listOf(
        "验证码", "校验码", "动态码", "短信码", "动态密码", "密码", "卡号", "银行卡", "身份证", "证件号", "安全码",
        "cvv", "cvc", "verificationcode", "one-timecode", "onetimecode", "otp", "passcode", "password",
        "cardnumber", "securitycode", "ssn",
    )
    private val PIN_WORD = Regex("""(?i)(?<![a-z])pin(?![a-z])""")

    /** 命中的敏感词，否则 null。看文字、内容描述、子孙文字；都没有的纯图标按钮再看 viewId。 */
    fun sensitiveWord(node: UiNode): String? {
        val labels = listOfNotNull(node.text, node.contentDescription, node.descendantText).filter { it.isNotBlank() }
        val texts = labels.ifEmpty { viewIdWords(node.viewId) }
        for (text in texts) {
            if (text.isBlank()) continue
            val zh = BlockList.normalize(text)
            SENSITIVE_ZH.firstOrNull { zh.contains(it) }?.let { return it }
            enRegex.find(text.lowercase(Locale.ROOT))?.let { return it.value }
        }
        return null
    }

    /** 输入框像是验证码、密码、卡号、证件号时返回命中的词。 */
    fun secretFieldWord(node: UiNode): String? {
        val texts = node.allTexts() + viewIdWords(node.viewId)
        for (text in texts) {
            val n = BlockList.normalize(text)
            SECRET_FIELD_WORDS.firstOrNull { n.contains(it) }?.let { return it }
            if (PIN_WORD.containsMatchIn(text)) return "PIN"
        }
        return null
    }

    /** “com.tencent.mm:id/send_btn” → “send btn”；驼峰也拆开。 */
    private fun viewIdWords(viewId: String?): List<String> {
        if (viewId.isNullOrBlank()) return emptyList()
        val entry = viewId.substringAfterLast('/')
        val spaced = entry.replace(Regex("([a-z])([A-Z])"), "$1 $2").replace('_', ' ').replace('-', ' ')
        return listOf(spaced)
    }
}
