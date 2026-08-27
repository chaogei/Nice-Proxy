package com.niceproxy.core.database.crypto

import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 提供落盘加密用的对称密钥。
 *
 * 抽成接口是为了把「拿密钥」和「用密钥」分开：后者是纯 JCE，能在普通
 * JUnit 里跑；前者只有真机上的 AndroidKeyStore 才有，见
 * [KeystoreSecretKeyProvider]。
 */
interface SecretKeyProvider {

    /**
     * 返回可用的密钥；设备上根本没有可用 Keystore 时返回 null。
     *
     * 刻意不抛异常：这个调用会出现在每一次数据库读写的路径上，
     * 让它抛异常等于给整个应用加了一个新的崩溃来源。
     */
    fun keyOrNull(): SecretKey?
}

/**
 * 敏感字段的加解密。
 *
 * 选 AES-GCM 而不是 AES-CBC：GCM 自带完整性校验，密钥不对或数据被改过会在
 * 解密时直接失败，而不是解出一段乱码再让上层拿它去连节点。和
 * `core:config` 的 [com.niceproxy.core.config.backup.BackupCrypto] 保持一致的选择，
 * 区别只在密钥来源 —— 那边是用户口令 PBKDF2 派生，这边是 Keystore 里
 * 不可导出的密钥，所以这里没有盐、没有迭代次数可调。
 *
 * 本类的所有方法都不抛异常。任何失败都会退化成一个可识别的状态，
 * 理由见 [SecretText]。
 */
class SecretCodec(private val keyProvider: SecretKeyProvider) {

    /**
     * 是否已退化为明文存储。
     *
     * 只有在设备完全拿不到 Keystore 密钥时才会为 true。这时继续用明文落盘
     * 是刻意的取舍：字段级加密在本项目里是纵深防御，真正兜底的是应用沙箱
     * 与文件级加密（FBE）；为了它让用户完全没法保存节点是本末倒置。
     * 但这个状态必须能被上层查询到并提示用户，不能悄悄发生。
     */
    @Volatile
    var isDegraded: Boolean = false
        private set

    fun encrypt(plaintext: String): String {
        val key = keyProvider.keyOrNull() ?: return degrade(plaintext)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            // 不传 GCMParameterSpec：AndroidKeyStore 默认要求随机化加密
            // （setRandomizedEncryptionRequired），由它自己生成 IV 再读回来。
            // 手工指定 IV 会被直接拒绝，而复用 IV 对 GCM 是致命的。
            cipher.init(Cipher.ENCRYPT_MODE, key)
            SecretEnvelope.wrap(cipher.iv, cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)))
        } catch (_: GeneralSecurityException) {
            degrade(plaintext)
        }
    }

    fun decrypt(stored: String): SecretText {
        // 没有信封前缀的一律当作升级前的明文。这是唯一的迁移机制，
        // 见 SecretEnvelope 的说明。
        val parsed = SecretEnvelope.unwrap(stored) ?: return SecretText.Readable(stored)
        val key = keyProvider.keyOrNull() ?: return SecretText.Unreadable(stored)

        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(SecretEnvelope.TAG_BITS, parsed.iv),
            )
            SecretText.Readable(String(cipher.doFinal(parsed.ciphertext), Charsets.UTF_8))
        } catch (_: GeneralSecurityException) {
            // 认证失败可能是密钥换了，也可能是这一行被改过。对上层来说处置方式
            // 一样：这条凭据回不来了，只能重新导入。
            SecretText.Unreadable(stored)
        }
    }

    private fun degrade(plaintext: String): String {
        isDegraded = true
        return plaintext
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
