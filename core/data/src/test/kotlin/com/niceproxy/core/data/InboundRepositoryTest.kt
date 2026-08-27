package com.niceproxy.core.data

import com.google.common.truth.Truth.assertThat
import com.niceproxy.core.model.InboundService
import com.niceproxy.core.model.InboundType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

internal class InboundRepositoryTest {

    private val dao = FakeInboundDao()
    private val repository = InboundRepository(dao, Dispatchers.Unconfined)

    @Test
    fun `首次启动写入一个监听全网段的 mixed 入站`() = runTest {
        repository.ensureDefaults()
        val created = repository.getAll().single()

        assertThat(created.type).isEqualTo(InboundType.MIXED)
        assertThat(created.listen).isEqualTo(InboundService.LISTEN_ALL)
        assertThat(created.listenPort).isEqualTo(InboundService.DEFAULT_MIXED_PORT)
    }

    @Test
    fun `默认入站带随机凭据，绝不免认证`() = runTest {
        // 监听 0.0.0.0 是这个产品存在的意义，但免认证的话，同网段任何人
        // 不只能白嫖用户付费的机场流量，还能靠「私有 IP 走直连」这条内置规则
        // 拿这台手机当跳板打进整个局域网乃至手机自己的回环地址
        repository.ensureDefaults()
        val created = repository.getAll().single()

        assertThat(created.isExposedWithoutAuth).isFalse()
        val auth = requireNotNull(created.auth)
        assertThat(auth.username).isNotEmpty()
        assertThat(auth.password.length).isAtLeast(12)
    }

    @Test
    fun `凭据每次生成都不同，且不含易混字符`() = runTest {
        // 固定口令等于没有口令。而 0O1lI 要靠用户在游戏机软键盘上手抄，
        // 认错一个字符的排查成本远高于多两位熵
        repository.ensureDefaults()
        val first = requireNotNull(repository.getAll().single().auth)

        val second = requireNotNull(
            InboundRepository(FakeInboundDao(), Dispatchers.Unconfined)
                .also { it.ensureDefaults() }
                .getAll().single().auth,
        )

        assertThat(first.password).isNotEqualTo(second.password)
        assertThat(first.username).isNotEqualTo(second.username)
        assertThat(first.password.any { it in "0O1lI" }).isFalse()
    }

    @Test
    fun `已有入站时不再写入默认值`() = runTest {
        // 用户把默认入站改到 1080 之后重启应用，不能又给他塞回一个 8080
        repository.save(inbound("custom", port = 1080))
        repository.ensureDefaults()

        assertThat(repository.getAll()).hasSize(1)
        assertThat(repository.getAll().single().listenPort).isEqualTo(1080)
    }

    @Test
    fun `端口冲突检测要排除入站自己`() = runTest {
        // 否则用户编辑一个已有入站、什么都没改就保存，会被自己占用的端口挡下来
        repository.save(inbound("a", port = 8080))

        assertThat(repository.isPortTaken(8080, excludeId = "b")).isTrue()
        assertThat(repository.isPortTaken(8080, excludeId = "a")).isFalse()
    }

    @Test
    fun `已停用的入站不占端口`() = runTest {
        repository.save(inbound("a", port = 8080))
        repository.setEnabled("a", false)

        assertThat(repository.isPortTaken(8080, excludeId = "b")).isFalse()
    }

    @Test
    fun `新建入站按类型给出默认端口`() {
        assertThat(repository.newInbound(InboundType.SOCKS).listenPort)
            .isEqualTo(InboundService.DEFAULT_SOCKS_PORT)
        assertThat(repository.newInbound(InboundType.PAC).listenPort)
            .isEqualTo(InboundService.DEFAULT_PAC_PORT)
    }
}
