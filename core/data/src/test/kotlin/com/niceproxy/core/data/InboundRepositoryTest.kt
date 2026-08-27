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
        // 默认监听 0.0.0.0 且免认证，UI 必须为此显示警告徽章
        assertThat(created.isExposedWithoutAuth).isTrue()
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
