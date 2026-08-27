package com.niceproxy.core.config.share

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * 大订阅的解析规模测试。
 *
 * 机场动辄给出几千个节点，解析里任何一处退化成平方复杂度，
 * 在开发机的小样本上都看不出来，到用户手上就是「点更新之后卡住十几秒」。
 * 时限取得很宽松（正常耗时不到一秒），只用来兜住数量级上的退化。
 */
class ParserScaleTest {

    private companion object {
        const val NODE_COUNT = 3000
    }

    private fun links(count: Int): String =
        (0 until count).joinToString("\n") { "trojan://pw-$it@node$it.example.com:443#节点%20$it" }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    @DisplayName("三千个节点的 Base64 订阅")
    fun base64Subscription() {
        val content = Base64.getEncoder().encodeToString(links(NODE_COUNT).toByteArray())
        val result = SubscriptionParser.parse(content).getOrThrow()

        assertThat(result.format).isEqualTo(SubscriptionFormat.BASE64_LINKS)
        assertThat(result.nodes).hasSize(NODE_COUNT)
        assertThat(result.failedEntries).isEmpty()
        assertThat(result.nodes.first().name).isEqualTo("节点 0")
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    @DisplayName("三千个节点的明文链接列表")
    fun plainSubscription() {
        val result = SubscriptionParser.parse(links(NODE_COUNT)).getOrThrow()
        assertThat(result.nodes).hasSize(NODE_COUNT)
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("两千个 proxies 的 Clash YAML")
    fun clashSubscription() {
        val yaml = buildString {
            append("proxies:\n")
            repeat(2000) { index ->
                append("  - name: \"节点 $index\"\n")
                append("    type: trojan\n")
                append("    server: node$index.example.com\n")
                append("    port: 443\n")
                append("    password: pw-$index\n")
                append("    sni: node$index.example.com\n")
            }
        }
        val result = SubscriptionParser.parse(yaml).getOrThrow()

        assertThat(result.format).isEqualTo(SubscriptionFormat.CLASH_YAML)
        assertThat(result.nodes).hasSize(2000)
    }
}
