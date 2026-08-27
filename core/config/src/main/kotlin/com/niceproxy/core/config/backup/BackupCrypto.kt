package com.niceproxy.core.config.backup

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 备份文件的加解密。
 *
 * 备份里含节点密码、UUID 和入站认证凭据 —— 一份明文备份泄露等于把
 * 所有节点拱手让人。所以加密是强制的，不给「不加密导出」的选项。
 *
 * 文件格式：
 * ```
 * magic(8) | version(1) | salt(16) | iv(12) | ciphertext+tag(...)
 * ```
 * 用 AES-GCM 而不是 AES-CBC：GCM 自带完整性校验，密码错误或文件被篡改
 * 都会在解密时直接失败，而不是解出一堆乱码再让上层去猜。
 */
object BackupCrypto {

    private val MAGIC = "NICEBAK\u0000".toByteArray(Charsets.US_ASCII)
    private const val FORMAT_VERSION: Byte = 1
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256

    /**
     * PBKDF2 迭代次数。
     *
     * 取值是在「手机上导出/恢复的等待时间」与「离线爆破成本」之间的折中：
     * 中端设备上约 200~400ms，用户几乎无感，但把爆破成本抬高了五个数量级。
     */
    private const val ITERATIONS = 210_000

    fun encrypt(plaintext: String, password: CharArray): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        return MAGIC + byteArrayOf(FORMAT_VERSION) + salt + iv + ciphertext
    }

    fun decrypt(data: ByteArray, password: CharArray): Result<String> {
        val headerSize = MAGIC.size + 1 + SALT_BYTES + IV_BYTES
        if (data.size <= headerSize) {
            return Result.failure(BackupException(BackupError.NotABackup))
        }
        if (!data.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            return Result.failure(BackupException(BackupError.NotABackup))
        }

        val version = data[MAGIC.size]
        if (version > FORMAT_VERSION) {
            return Result.failure(
                BackupException(BackupError.UnsupportedVersion(version.toInt())),
            )
        }

        var offset = MAGIC.size + 1
        val salt = data.copyOfRange(offset, offset + SALT_BYTES)
        offset += SALT_BYTES
        val iv = data.copyOfRange(offset, offset + IV_BYTES)
        offset += IV_BYTES
        val ciphertext = data.copyOfRange(offset, data.size)

        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                deriveKey(password, salt),
                GCMParameterSpec(TAG_BITS, iv),
            )
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.recoverCatching { cause ->
            // GCM 的认证失败既可能是密码错，也可能是文件被改过。
            // 对用户来说这两者的处置方式一样，合并成一条提示。
            throw if (cause is AEADBadTagException) {
                BackupException(BackupError.WrongPassword)
            } else {
                BackupException(BackupError.Failed(cause.message ?: "解密失败"))
            }
        }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, ITERATIONS, KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return try {
            SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
}

class BackupException(val error: BackupError) : Exception(error.message)
