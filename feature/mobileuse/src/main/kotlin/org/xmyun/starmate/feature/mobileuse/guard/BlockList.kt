package org.xmyun.starmate.feature.mobileuse.guard

import java.util.Locale
import org.xmyun.starmate.feature.mobileuse.model.GuardRule

/** 黑名单的类别。section 是 blocked_packages.txt 里的段名。 */
enum class BlockCategory(val section: String, val rule: GuardRule) {
    PAYMENT("payment", GuardRule.G2),
    BANK("bank", GuardRule.G2),
    BROKER("broker", GuardRule.G2),
    CRYPTO("crypto", GuardRule.G2),
    AUTHENTICATOR("authenticator", GuardRule.G7),
    PASSWORD_MANAGER("password_manager", GuardRule.G7),
    SMS("sms", GuardRule.G7),
    SYSTEM("system", GuardRule.G6),
    ;

    companion object {
        fun fromSection(name: String): BlockCategory? = entries.firstOrNull { it.section == name }
    }
}

/**
 * 内置黑名单。用户不能修改。
 * - 包名：完全相同，或以“表里的包名 + .”开头（同一家的子应用一并拦下）。比较时不区分大小写。
 * - 应用名：包含任一关键词即命中，不区分大小写。宁可误拦。
 */
class BlockList(entries: Map<String, BlockCategory>) {

    private val byPackage: Map<String, BlockCategory> = entries.mapKeys { it.key.lowercase(Locale.ROOT) }

    val packages: Set<String> get() = byPackage.keys

    fun categoryOfPackage(packageName: String?): BlockCategory? {
        if (packageName.isNullOrBlank()) return null
        val pkg = packageName.trim().lowercase(Locale.ROOT)
        byPackage[pkg]?.let { return it }
        // 找最长的前缀，命中“com.icbc.xxx”这类子应用
        var best: Pair<String, BlockCategory>? = null
        for ((listed, category) in byPackage) {
            if (pkg.startsWith("$listed.") && (best == null || listed.length > best.first.length)) {
                best = listed to category
            }
        }
        return best?.second
    }

    /** 应用名命中的关键词及其类别。 */
    fun matchAppName(appLabel: String?): Pair<String, BlockCategory>? {
        if (appLabel.isNullOrBlank()) return null
        val label = normalize(appLabel)
        for ((category, words) in KEYWORDS) {
            for (word in words) {
                if (label.contains(normalize(word))) return word to category
            }
        }
        return null
    }

    companion object {
        private val SECTION = Regex("""^\[([a-z_]+)]$""")
        private val PACKAGE = Regex("""^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$""")

        /**
         * 应用名关键词。顺序即优先级：系统类（G6）先于保护类（G7）先于资金类（G2）。
         * 资金类包含设计文档指定的：银行、证券、基金、钱包、支付、Bank、Wallet、Pay、Broker、Crypto。
         */
        val KEYWORDS: List<Pair<BlockCategory, List<String>>> = listOf(
            BlockCategory.SYSTEM to listOf(
                "设置", "Settings", "安全中心", "手机管家", "权限", "Permission", "安装程序", "Installer",
                "应用商店", "应用市场", "软件商店", "App Store", "Play Store", "Play 商店", "Galaxy Store",
            ),
            BlockCategory.AUTHENTICATOR to listOf(
                "Authenticator", "验证器", "身份验证", "令牌", "将军令", "2FA", "OTP",
            ),
            BlockCategory.PASSWORD_MANAGER to listOf(
                "密码", "Password", "KeePass", "Bitwarden", "1Password", "LastPass",
            ),
            BlockCategory.SMS to listOf("短信", "SMS", "Messages"),
            BlockCategory.BANK to listOf("银行", "信用卡", "Bank"),
            BlockCategory.BROKER to listOf(
                "证券", "基金", "股票", "期货", "理财", "Broker", "Securities", "Stock", "Trading",
            ),
            BlockCategory.CRYPTO to listOf("Crypto", "比特币", "加密货币", "交易所", "Bitcoin", "Coin"),
            BlockCategory.PAYMENT to listOf(
                "支付", "付款", "闪付", "钱包", "贷款", "借贷", "Wallet", "Pay",
            ),
        )

        /** 解析 blocked_packages.txt。格式不对时抛 IllegalArgumentException，宁可启动失败也不带着残缺的表运行。 */
        fun parse(text: String): BlockList {
            val entries = LinkedHashMap<String, BlockCategory>()
            var current: BlockCategory? = null
            text.lineSequence().forEachIndexed { lineNo, rawLine ->
                val line = rawLine.substringBefore('#').trim()
                if (line.isEmpty()) return@forEachIndexed
                val section = SECTION.matchEntire(line)
                if (section != null) {
                    current = BlockCategory.fromSection(section.groupValues[1])
                        ?: throw IllegalArgumentException("unknown section on line ${lineNo + 1}: $line")
                    return@forEachIndexed
                }
                require(PACKAGE.matches(line)) { "bad package name on line ${lineNo + 1}: $line" }
                val category = current
                    ?: throw IllegalArgumentException("package before any section on line ${lineNo + 1}")
                entries.putIfAbsent(line, category)
            }
            require(entries.isNotEmpty()) { "block list is empty" }
            return BlockList(entries)
        }

        /** 小写并去掉空白，防止“支 付”这类写法绕开匹配。 */
        internal fun normalize(s: String): String =
            s.lowercase(Locale.ROOT).filterNot { it.isWhitespace() || it == '​' || it == ' ' }
    }
}
