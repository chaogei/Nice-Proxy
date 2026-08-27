package com.niceproxy.core.database.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class SecretTextConverterTest {

    private val key: SecretKey =
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private val codec = SecretCodec(
        object : SecretKeyProvider {
            override fun keyOrNull(): SecretKey = key
        },
    )

    private val converter = SecretTextConverter(codec)

    @Test
    @DisplayName("写入时加密，读取时解密")
    fun roundTrip() {
        val stored = converter.fromSecretText(SecretText.Readable("uuid-1234"))

        assertThat(stored).startsWith(SecretEnvelope.PREFIX)
        assertThat(converter.toSecretText(stored)).isEqualTo(SecretText.Readable("uuid-1234"))
    }

    @Test
    @DisplayName("解不开的值被原样写回，不会被占位符覆盖")
    fun unreadableIsPreservedOnWrite() {
        // 这是「读出来又存回去」路径上唯一的保护：解密失败可能只是暂时的，
        // 一旦覆盖，本来还能救的密文就永久没了。
        val ciphertext = codec.encrypt("原始凭据")
        val unreadable = SecretText.Unreadable(ciphertext)

        assertThat(converter.fromSecretText(unreadable)).isEqualTo(ciphertext)
    }

    @Test
    @DisplayName("升级前的明文读出来就是明文")
    fun legacyValue() {
        assertThat(converter.toSecretText("plain-password"))
            .isEqualTo(SecretText.Readable("plain-password"))
    }
}
