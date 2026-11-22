package org.xmyun.starmate.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CoroutineScope
import org.xmyun.starmate.core.data.db.ChatDatabase
import org.xmyun.starmate.core.data.db.ChatDatabaseHolder
import org.xmyun.starmate.core.data.secret.SecretCipher

/** 固定 AES-256 密钥的 GCM 实现，输出格式与 KeystoreSecretCipher 相同。 */
class FakeSecretCipher : SecretCipher {
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    private val random = SecureRandom()

    override fun encrypt(plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return iv + cipher.doFinal(plain)
    }

    override fun decrypt(data: ByteArray): ByteArray? {
        if (data.size <= 12) return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data, 0, 12))
            cipher.doFinal(data, 12, data.size - 12)
        } catch (e: Exception) {
            null
        }
    }
}

class MutableClock(var now: Instant, private val zone: ZoneId = ZoneId.systemDefault()) : Clock() {
    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)
    override fun instant(): Instant = now
}

fun appContext(): Context = ApplicationProvider.getApplicationContext()

/**
 * 测试用文件数据库，放在 Robolectric 每个测试独立的数据库目录里，驱动同生产环境
 * （BundledSQLiteDriver，测试类路径上是 JVM 变体）。dir 参数保留只为调用处可读，不使用。
 */
@Suppress("UNUSED_PARAMETER")
fun newHolder(dir: File): ChatDatabaseHolder {
    val context = appContext()
    val name = "chat-${UUID.randomUUID()}.db"
    return ChatDatabaseHolder(context.getDatabasePath(name)) { ChatDatabase.build(context, name) }
}

fun newPrefsDataStore(scope: CoroutineScope, file: File): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
