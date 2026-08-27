package com.niceproxy.core.database.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * [SecretCodec] 的加解密逻辑是纯 JCE，和 AndroidKeyStore 唯一的耦合是
 * [SecretKeyProvider]。这里用一把普通的内存 AES 密钥替换它，于是整套
 * 信封格式、降级分支、篡改检测都能在普通 JUnit 里跑。
 *
 * 真机上才能覆盖的部分只有 [KeystoreSecretKeyProvider] 本身：密钥生成参数
 * 是否被该 ROM 接受、进程重启后能否取回同一把密钥、清除应用数据后条目是否
 * 真的消失。
 */
class SecretCodecTest {

    private fun aesKey(): SecretKey =
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun codecWith(key: SecretKey?) = SecretCodec(
        object : SecretKeyProvider {
            override fun keyOrNull(): SecretKey? = key
        },
    )

    private val codec = codecWith(aesKey())

    private val secret = """{"type":"trojan","password":"super-secret"}"""

    @Test
    @DisplayName("加密后能解回原文")
    fun roundTrip() {
        val stored = codec.encrypt(secret)
        assertThat(codec.decrypt(stored)).isEqualTo(SecretText.Readable(secret))
    }

    @Test
    @DisplayName("密文中不出现明文片段")
    fun ciphertextLeaksNothing() {
        val stored = codec.encrypt(secret)
        assertThat(stored).doesNotContain("super-secret")
        assertThat(stored).doesNotContain("password")
    }

    @Test
    @DisplayName("相同明文两次加密结果不同（IV 随机）")
    fun randomizedOutput() {
        assertThat(codec.encrypt(secret)).isNotEqualTo(codec.encrypt(secret))
    }

    @Test
    @DisplayName("换一把密钥解不开，且报告为 Unreadable 而不是抛异常")
    fun wrongKey() {
        val stored = codec.encrypt(secret)
        val decrypted = codecWith(aesKey()).decrypt(stored)

        assertThat(decrypted).isInstanceOf(SecretText.Unreadable::class.java)
        // 原样保留存储值，这样上层把实体写回去时不会覆盖掉密文
        assertThat((decrypted as SecretText.Unreadable).stored).isEqualTo(stored)
    }

    @Test
    @DisplayName("密文被篡改时校验失败")
    fun tamperDetected() {
        // AES-GCM 自带完整性校验，改动任意一个十六进制字符都应当被发现
        val stored = codec.encrypt(secret)
        val flipped = stored.dropLast(1) + if (stored.last() == 'a') 'b' else 'a'

        assertThat(codec.decrypt(flipped)).isInstanceOf(SecretText.Unreadable::class.java)
    }

    @Test
    @DisplayName("密钥不可用时解密报 Unreadable，不返回乱码")
    fun missingKeyOnDecrypt() {
        val stored = codec.encrypt(secret)
        assertThat(codecWith(null).decrypt(stored))
            .isEqualTo(SecretText.Unreadable(stored))
    }

    // ------------------------------------------------------------ 兼容与降级

    @Test
    @DisplayName("升级前的明文没有信封前缀，原样读出")
    fun legacyPlaintextPassesThrough() {
        assertThat(codec.decrypt(secret)).isEqualTo(SecretText.Readable(secret))
    }

    @Test
    @DisplayName("恰好以前缀开头但不是有效信封的值，当明文处理而不是当解密失败")
    fun prefixLookalikeIsNotAFailure() {
        // 用户完全可以把 "nsec1:..." 设成入站密码。误判成 Unreadable 会让
        // 一个本来好好的入站被强制停用，这比多存一份明文严重得多。
        val lookalike = SecretEnvelope.PREFIX + "这不是十六进制"
        assertThat(codec.decrypt(lookalike)).isEqualTo(SecretText.Readable(lookalike))

        val tooShort = SecretEnvelope.PREFIX + "00112233"
        assertThat(codec.decrypt(tooShort)).isEqualTo(SecretText.Readable(tooShort))
    }

    @Test
    @DisplayName("拿不到密钥时退化为明文落盘，并把降级状态标出来")
    fun degradesWhenKeystoreUnavailable() {
        val degraded = codecWith(null)
        assertThat(degraded.isDegraded).isFalse()

        val stored = degraded.encrypt(secret)

        // 宁可明文也不能让用户存不了节点，但这件事必须能被上层看见
        assertThat(stored).isEqualTo(secret)
        assertThat(degraded.isDegraded).isTrue()
        assertThat(degraded.decrypt(stored)).isEqualTo(SecretText.Readable(secret))
    }

    @Test
    @DisplayName("正常路径不会误报降级")
    fun notDegradedOnHappyPath() {
        codec.encrypt(secret)
        assertThat(codec.isDegraded).isFalse()
    }

    // ------------------------------------------------------------ 边界

    @Test
    @DisplayName("空串与非 ASCII 明文都能正确往返")
    fun edgeCaseContents() {
        assertThat(codec.decrypt(codec.encrypt(""))).isEqualTo(SecretText.Readable(""))

        val unicode = "密码🔑ünïcode"
        assertThat(codec.decrypt(codec.encrypt(unicode)))
            .isEqualTo(SecretText.Readable(unicode))
    }

    @Test
    @DisplayName("加密结果可以再次被加密与解密（多次改写不会累积破坏）")
    fun reEncryptionIsStable() {
        var value = secret
        repeat(3) {
            val stored = codec.encrypt(value)
            value = (codec.decrypt(stored) as SecretText.Readable).value
        }
        assertThat(value).isEqualTo(secret)
    }
}
