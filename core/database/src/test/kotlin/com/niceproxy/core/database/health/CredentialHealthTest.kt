package com.niceproxy.core.database.health

import com.google.common.truth.Truth.assertThat
import com.niceproxy.core.database.crypto.SecretCodec
import com.niceproxy.core.database.crypto.SecretKeyProvider
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * 「用户以为加密了，其实是明文」这件事必须能被界面拿到。
 *
 * `SecretCodec` 的降级是静默且单向的：拿不到 Keystore 密钥时它退回明文写入，
 * 此后每一次写入都是明文，而界面上和加密时一模一样。在此之前这个状态位
 * 没有任何生产代码读过。
 */
class CredentialHealthTest {

    private fun healthWith(key: SecretKey?) = CredentialHealth(
        SecretCodec(
            object : SecretKeyProvider {
                override fun keyOrNull(): SecretKey? = key
            },
        ),
    )

    private fun aesKey(): SecretKey =
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    @DisplayName("密钥可用时不报降级")
    fun healthyDeviceIsNotDegraded() {
        val health = healthWith(aesKey())

        assertThat(health.probe()).isFalse()
        assertThat(health.degraded.value).isFalse()
    }

    @Test
    @DisplayName("拿不到密钥时 probe 立刻报出来，不必等到用户保存第一个节点")
    fun probeSurfacesDegradationEarly() {
        // 不主动探测的话，这个状态位要到第一次真正写入失败才翻转 ——
        // 而那时第一份明文凭据已经落盘了，提示已经晚了一步
        val health = healthWith(null)

        assertThat(health.degraded.value).isFalse()
        assertThat(health.probe()).isTrue()
        assertThat(health.degraded.value).isTrue()
    }

    @Test
    @DisplayName("降级是单向的，探测多少次都不会自己好起来")
    fun degradationIsSticky() {
        val health = healthWith(null)

        repeat(3) { health.probe() }

        assertThat(health.degraded.value).isTrue()
    }
}
