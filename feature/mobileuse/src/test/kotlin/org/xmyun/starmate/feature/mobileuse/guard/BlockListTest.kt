package org.xmyun.starmate.feature.mobileuse.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xmyun.starmate.feature.mobileuse.realBlockList

class BlockListTest {

    /** 设计文档要求起步清单必须包含的包名。 */
    private val required = mapOf(
        "com.eg.android.AlipayGphone" to BlockCategory.PAYMENT,
        "com.unionpay" to BlockCategory.PAYMENT,
        "com.paypal.android.p2pmobile" to BlockCategory.PAYMENT,
        "com.google.android.apps.walletnfcrel" to BlockCategory.PAYMENT,
        "com.icbc" to BlockCategory.BANK,
        "cmb.pb" to BlockCategory.BANK,
        "com.binance.dev" to BlockCategory.CRYPTO,
        "io.metamask" to BlockCategory.CRYPTO,
        "com.wallet.crypto.trustapp" to BlockCategory.CRYPTO,
        "com.google.android.apps.authenticator2" to BlockCategory.AUTHENTICATOR,
        "com.azure.authenticator" to BlockCategory.AUTHENTICATOR,
        "com.authy.authy" to BlockCategory.AUTHENTICATOR,
        "com.beemdevelopment.aegis" to BlockCategory.AUTHENTICATOR,
        "com.x8bit.bitwarden" to BlockCategory.PASSWORD_MANAGER,
        "com.onepassword.android" to BlockCategory.PASSWORD_MANAGER,
        "com.android.settings" to BlockCategory.SYSTEM,
        "com.android.packageinstaller" to BlockCategory.SYSTEM,
        "com.google.android.packageinstaller" to BlockCategory.SYSTEM,
        "com.android.permissioncontroller" to BlockCategory.SYSTEM,
        "com.google.android.permissioncontroller" to BlockCategory.SYSTEM,
    )

    @Test
    fun everyRequiredPackageMatchesWithItsCategory() {
        for ((pkg, category) in required) {
            assertEquals(pkg, category, realBlockList.categoryOfPackage(pkg))
        }
    }

    @Test
    fun realFileHasEveryCategory() {
        val categories = realBlockList.packages.mapNotNull { realBlockList.categoryOfPackage(it) }.toSet()
        assertEquals(BlockCategory.entries.toSet(), categories)
        assertTrue(realBlockList.packages.size >= 100)
    }

    @Test
    fun commentsAndBlankLinesAreIgnored() {
        val list = BlockList.parse(
            """
            # 顶部注释

            [payment]
            com.example.pay   # 行尾注释
               # 缩进的注释

            [bank]
            com.example.bank
            """.trimIndent(),
        )
        assertEquals(setOf("com.example.pay", "com.example.bank"), list.packages)
        assertEquals(BlockCategory.PAYMENT, list.categoryOfPackage("com.example.pay"))
        assertEquals(BlockCategory.BANK, list.categoryOfPackage("com.example.bank"))
    }

    @Test
    fun packageMatchIgnoresCaseAndCoversSubPackages() {
        assertEquals(BlockCategory.BANK, realBlockList.categoryOfPackage("COM.ICBC"))
        assertEquals(BlockCategory.BANK, realBlockList.categoryOfPackage("com.icbc.elife"))
        assertNull(realBlockList.categoryOfPackage("com.icbcx"))
        assertNull(realBlockList.categoryOfPackage("com.example.notes"))
        assertNull(realBlockList.categoryOfPackage(null))
    }

    @Test
    fun keywordMatchIgnoresCase() {
        assertEquals(BlockCategory.BANK, realBlockList.matchAppName("MY BANK")?.second)
        assertEquals(BlockCategory.BANK, realBlockList.matchAppName("mybank")?.second)
        assertEquals(BlockCategory.PAYMENT, realBlockList.matchAppName("PAYPAL Business")?.second)
        assertEquals(BlockCategory.PAYMENT, realBlockList.matchAppName("some wallet")?.second)
        assertEquals(BlockCategory.CRYPTO, realBlockList.matchAppName("CRYPTO Hub")?.second)
        assertEquals(BlockCategory.BROKER, realBlockList.matchAppName("Super broker")?.second)
    }

    @Test
    fun chineseKeywordsMatch() {
        for (name in listOf("某某银行", "某某证券", "某某基金", "某某钱包", "某某支付")) {
            assertNotNull(name, realBlockList.matchAppName(name))
        }
        assertEquals(BlockCategory.BANK, realBlockList.matchAppName("招商 银行")?.second)
    }

    @Test
    fun ordinaryAppNamesDoNotMatch() {
        for (name in listOf("Notes", "微信", "Google Play Music", "Chrome", "Calendar", "Maps", "小红书")) {
            assertNull(name, realBlockList.matchAppName(name))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun packageBeforeSectionIsRejected() {
        BlockList.parse("com.example.pay\n")
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownSectionIsRejected() {
        BlockList.parse("[games]\ncom.example.game\n")
    }

    @Test(expected = IllegalArgumentException::class)
    fun malformedPackageIsRejected() {
        BlockList.parse("[bank]\nnot a package\n")
    }
}
