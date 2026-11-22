package org.xmyun.starmate.core.data.secret

/** 加解密隔离层：真机用 AndroidKeyStore（[KeystoreSecretCipher]），单元测试用假实现。 */
interface SecretCipher {
    fun encrypt(plain: ByteArray): ByteArray

    /** 密文被改动、密钥丢失等任何失败都返回 null，不抛异常。 */
    fun decrypt(data: ByteArray): ByteArray?
}
