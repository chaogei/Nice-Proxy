package com.niceproxy.core.database.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 信封格式是这次改动能不做数据库迁移的全部依据，所以它的边界要单独钉死：
 * 任何「把明文误判成密文」的情况都会让一条好数据被标成失效。
 */
class SecretEnvelopeTest {

    private val iv = ByteArray(SecretEnvelope.IV_BYTES) { it.toByte() }
    private val ciphertext = ByteArray(20) { (0xF0 or (it and 0x0F)).toByte() }

    @Test
    @DisplayName("wrap 后能原样 unwrap 回来")
    fun roundTrip() {
        val parsed = SecretEnvelope.unwrap(SecretEnvelope.wrap(iv, ciphertext))
        assertThat(parsed).isEqualTo(SecretEnvelope.Parsed(iv, ciphertext))
    }

    @Test
    @DisplayName("wrap 的结果带前缀且只含十六进制字符")
    fun format() {
        val wrapped = SecretEnvelope.wrap(iv, ciphertext)

        assertThat(SecretEnvelope.isEnvelope(wrapped)).isTrue()
        assertThat(wrapped.removePrefix(SecretEnvelope.PREFIX)).matches("[0-9a-f]+")
    }

    @Test
    @DisplayName("负数字节按无符号编码，不产生 ff 之外的怪值")
    fun signedBytes() {
        // 密文长度必须够一个 GCM 标签，否则会被 unwrap 的长度校验挡下 ——
        // 这里补齐到 16 字节，同时保留 -1/-128/0/127 这几个符号边界值
        val boundary = byteArrayOf(-1, -128, 0, 127) + ByteArray(12)
        val parsed = SecretEnvelope.unwrap(SecretEnvelope.wrap(iv, boundary))

        assertThat(parsed?.ciphertext).isEqualTo(boundary)
        assertThat(SecretEnvelope.wrap(iv, boundary)).contains("ff80007f")
    }

    @Test
    @DisplayName("没有前缀的一律不是信封")
    fun notAnEnvelope() {
        assertThat(SecretEnvelope.unwrap("""{"password":"x"}""")).isNull()
        assertThat(SecretEnvelope.unwrap("")).isNull()
    }

    @Test
    @DisplayName("前缀对但内容不是合法十六进制时按非信封处理")
    fun malformedBody() {
        assertThat(SecretEnvelope.unwrap(SecretEnvelope.PREFIX + "zz")).isNull()
        // 奇数长度不可能是字节序列
        assertThat(SecretEnvelope.unwrap(SecretEnvelope.PREFIX + "abc")).isNull()
    }

    @Test
    @DisplayName("短于 IV + 标签的内容不可能是有效密文")
    fun tooShort() {
        val minimum = SecretEnvelope.IV_BYTES + SecretEnvelope.TAG_BITS / 8
        val body = ByteArray(minimum - 1)

        assertThat(SecretEnvelope.unwrap(SecretEnvelope.wrap(body, ByteArray(0)))).isNull()
    }

    @Test
    @DisplayName("空明文也能构成有效信封（IV + 标签就够长）")
    fun emptyPlaintextStillValid() {
        val tagOnly = ByteArray(SecretEnvelope.TAG_BITS / 8)
        assertThat(SecretEnvelope.unwrap(SecretEnvelope.wrap(iv, tagOnly))).isNotNull()
    }

    @Test
    @DisplayName("大写十六进制也能解析，避免对外部写入的数据过于苛刻")
    fun uppercaseHex() {
        val wrapped = SecretEnvelope.wrap(iv, ciphertext)
        val upper = SecretEnvelope.PREFIX + wrapped.removePrefix(SecretEnvelope.PREFIX).uppercase()

        assertThat(SecretEnvelope.unwrap(upper)).isEqualTo(SecretEnvelope.Parsed(iv, ciphertext))
    }
}
