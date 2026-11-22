package org.xmyun.starmate.core.data.secret

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AndroidKeyStore 里别名 [ALIAS] 的 AES-256-GCM 密钥。输出格式：iv(12 字节) + 密文（含 16 字节 tag）。
 * 密钥不可导出，卸载应用后随之消失。
 */
@Singleton
class KeystoreSecretCipher @Inject constructor() : SecretCipher {

    private val lock = Any()

    @Volatile
    private var cachedKey: SecretKey? = null

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // GCM 的 iv 由 Keystore 随机生成，不能自己指定
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        check(iv.size == IV_SIZE) { "unexpected iv size" }
        return iv + cipher.doFinal(plain)
    }

    override fun decrypt(data: ByteArray): ByteArray? {
        if (data.size <= IV_SIZE) return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, data, 0, IV_SIZE))
            cipher.doFinal(data, IV_SIZE, data.size - IV_SIZE)
        } catch (e: Exception) {
            null
        }
    }

    private fun key(): SecretKey {
        cachedKey?.let { return it }
        synchronized(lock) {
            cachedKey?.let { return it }
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val key = (keyStore.getKey(ALIAS, null) as? SecretKey) ?: generateKey()
            cachedKey = key
            return key
        }
    }

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        const val ALIAS = "starmate_secrets"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
        private const val TAG_BITS = 128
    }
}
