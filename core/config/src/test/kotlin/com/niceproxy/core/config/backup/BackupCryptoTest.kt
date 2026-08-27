package com.niceproxy.core.config.backup

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class BackupCryptoTest {

    private val secret = """{"servers":[{"password":"super-secret"}]}"""

    @Test
    @DisplayName("加密后能用同一密码解回原文")
    fun roundTrip() {
        val blob = BackupCrypto.encrypt(secret, "correct horse".toCharArray())
        val decrypted = BackupCrypto.decrypt(blob, "correct horse".toCharArray()).getOrThrow()
        assertThat(decrypted).isEqualTo(secret)
    }

    @Test
    @DisplayName("密文中不出现明文片段")
    fun ciphertextLeaksNothing() {
        val blob = BackupCrypto.encrypt(secret, "pw".toCharArray())
        val asText = String(blob, Charsets.ISO_8859_1)
        assertThat(asText).doesNotContain("super-secret")
        assertThat(asText).doesNotContain("password")
    }

    @Test
    @DisplayName("相同明文两次加密结果不同（盐与 IV 随机）")
    fun randomizedOutput() {
        val a = BackupCrypto.encrypt(secret, "pw".toCharArray())
        val b = BackupCrypto.encrypt(secret, "pw".toCharArray())
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    @DisplayName("密码错误时明确报错而不是解出乱码")
    fun wrongPassword() {
        val blob = BackupCrypto.encrypt(secret, "right".toCharArray())
        val error = BackupCrypto.decrypt(blob, "wrong".toCharArray()).exceptionOrNull()

        assertThat(error).isInstanceOf(BackupException::class.java)
        assertThat((error as BackupException).error).isEqualTo(BackupError.WrongPassword)
    }

    @Test
    @DisplayName("密文被篡改时校验失败")
    fun tamperDetected() {
        // AES-GCM 自带完整性校验，改动任意一个字节都应当被发现
        val blob = BackupCrypto.encrypt(secret, "pw".toCharArray())
        blob[blob.size - 1] = (blob[blob.size - 1] + 1).toByte()

        val error = BackupCrypto.decrypt(blob, "pw".toCharArray()).exceptionOrNull()
        assertThat((error as BackupException).error).isEqualTo(BackupError.WrongPassword)
    }

    @Test
    @DisplayName("非备份文件被识别出来，而不是当成密码错误")
    fun notABackup() {
        val error = BackupCrypto
            .decrypt("just some random bytes here padding".toByteArray(), "pw".toCharArray())
            .exceptionOrNull()

        assertThat((error as BackupException).error).isEqualTo(BackupError.NotABackup)
    }

    @Test
    @DisplayName("更高的格式版本被拒绝并给出可读原因")
    fun futureVersion() {
        val blob = BackupCrypto.encrypt(secret, "pw".toCharArray())
        // 版本字节紧跟在 8 字节 magic 之后
        blob[8] = 99

        val error = BackupCrypto.decrypt(blob, "pw".toCharArray()).exceptionOrNull()
        assertThat((error as BackupException).error)
            .isEqualTo(BackupError.UnsupportedVersion(99))
    }
}
