package com.niceproxy.core.data

import com.google.common.truth.Truth.assertThat
import com.niceproxy.core.model.GroupType
import com.niceproxy.core.model.SubscriptionTraffic
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * 订阅更新中不依赖网络的那一段。
 *
 * 这里的每条判断都直接决定用户点「更新」之后看到什么：
 * 节点变少了、订阅看起来空了、或者一句没头没尾的「更新失败」。
 */
internal class SubscriptionPipelineTest {

    private val body = """
        trojan://pw@a.com:443#香港 01
        trojan://pw@b.com:443#剩余流量：100GB
        trojan://pw@c.com:443#官网 example.com
    """.trimIndent()

    private fun subscription(
        name: String = "我的机场",
        remarksFilter: String? = null,
        filterExclude: Boolean = true,
    ) = group(
        id = "g1",
        name = name,
        type = GroupType.SUBSCRIPTION,
        url = "https://sub.example.com/link",
        remarksFilter = remarksFilter,
        filterExclude = filterExclude,
    )

    @Nested
    @DisplayName("备注过滤")
    inner class RemarksFilter {

        @Test
        fun `把机场塞进来的公告条目挡在外面`() {
            // 「剩余流量」「官网地址」这类伪装成节点的条目会混进列表，
            // 还会被批量测速当成真节点反复尝试
            val outcome = SubscriptionPipeline
                .process(subscription(remarksFilter = "剩余流量|官网|到期"), body)
                .getOrThrow()

            assertThat(outcome.nodes.map { it.name }).containsExactly("香港 01")
            assertThat(outcome.filteredCount).isEqualTo(2)
        }

        @Test
        fun `保留模式只留下命中的节点`() {
            val outcome = SubscriptionPipeline
                .process(subscription(remarksFilter = "香港", filterExclude = false), body)
                .getOrThrow()

            assertThat(outcome.nodes.map { it.name }).containsExactly("香港 01")
        }

        @Test
        fun `正则写错时视为不过滤而不是全部丢弃`() {
            // 把节点全滤掉会让订阅看起来「空了」，用户完全不知道是自己的正则写错了
            val outcome = SubscriptionPipeline
                .process(subscription(remarksFilter = "[未闭合"), body)
                .getOrThrow()

            assertThat(outcome.nodes).hasSize(3)
            assertThat(outcome.filteredCount).isEqualTo(0)
        }

        @Test
        fun `过滤把节点全滤空时的文案要指向过滤规则`() {
            // 与「机场没给节点」是两个完全不同的问题，处置方式也不同
            val error = SubscriptionPipeline
                .process(subscription(remarksFilter = ".*"), body)
                .exceptionOrNull()

            assertThat(error).hasMessageThat().contains("过滤规则")
        }

        @Test
        fun `订阅本身没有可用节点时的文案指向订阅`() {
            val error = SubscriptionPipeline
                .process(subscription(), "trojan://这不是一条合法链接")
                .exceptionOrNull()

            assertThat(error).hasMessageThat().contains("订阅中没有可用节点")
        }
    }

    @Nested
    @DisplayName("分组元数据")
    inner class GroupMetadata {

        @Test
        fun `机场返回的名字只覆盖占位名`() {
            val placeholder = subscription(name = SubscriptionPipeline.DEFAULT_GROUP_NAME)
            assertThat(
                SubscriptionPipeline.process(placeholder, body, suggestedName = "机场 A")
                    .getOrThrow().group.name,
            ).isEqualTo("机场 A")

            // 用户自己起过名字就不能被覆盖
            assertThat(
                SubscriptionPipeline.process(subscription(), body, suggestedName = "机场 A")
                    .getOrThrow().group.name,
            ).isEqualTo("我的机场")
        }

        @Test
        fun `流量响应头写进分组`() {
            val outcome = SubscriptionPipeline.process(
                group = subscription(),
                body = body,
                userInfoHeader = "upload=1024; download=2048; total=10240; expire=1735689600",
            ).getOrThrow()

            assertThat(outcome.group.traffic?.totalBytes).isEqualTo(10240)
            assertThat(outcome.group.traffic?.usedBytes).isEqualTo(3072)
        }

        @Test
        fun `没有流量头时保留上一次的数值`() {
            // 不是每次响应都带这个头，覆盖成 0 会让界面上的剩余流量突然清零
            val previous = subscription().copy(traffic = SubscriptionTraffic(totalBytes = 42))
            val outcome = SubscriptionPipeline.process(previous, body).getOrThrow()

            assertThat(outcome.group.traffic?.totalBytes).isEqualTo(42)
        }

        @Test
        fun `更新成功后清掉上一次的错误`() {
            val previous = subscription().copy(lastError = "订阅返回 502")
            val outcome = SubscriptionPipeline.process(previous, body, now = 999).getOrThrow()

            assertThat(outcome.group.lastError).isNull()
            assertThat(outcome.group.lastUpdateAt).isEqualTo(999)
        }

        @Test
        fun `解析失败时不产生任何可落库的节点`() {
            // 调用方靠「失败就不调用 replaceGroupServers」保住旧节点，
            // 所以失败路径绝不能带出一个半成品结果
            assertThat(SubscriptionPipeline.process(subscription(), "完全不是订阅内容").isFailure)
                .isTrue()
        }
    }

    @Nested
    @DisplayName("自定义请求头")
    inner class ExtraHeaders {

        @Test
        fun `留空视为没有自定义头`() {
            assertThat(SubscriptionPipeline.parseExtraHeaders(null).getOrThrow()).isEmpty()
            assertThat(SubscriptionPipeline.parseExtraHeaders("   ").getOrThrow()).isEmpty()
        }

        @Test
        fun `合法 JSON 对象逐条转成请求头`() {
            val headers = SubscriptionPipeline
                .parseExtraHeaders("""{"Authorization":"Bearer abc","X-Client":"nice"}""")
                .getOrThrow()

            assertThat(headers).containsExactly("Authorization", "Bearer abc", "X-Client", "nice")
        }

        @Test
        fun `JSON 写错时明确失败而不是静默丢弃`() {
            // 之前是 getOrDefault(emptyMap())：请求头被悄悄丢掉，机场因此返回一个错误页，
            // 用户看到的是「无法识别的订阅格式」，跟真正的原因隔了十万八千里
            val error = SubscriptionPipeline.parseExtraHeaders("{Authorization: Bearer abc")
                .exceptionOrNull()

            assertThat(error).hasMessageThat().contains("自定义请求头")
        }

        @Test
        fun `值不是字符串时报出是哪个键`() {
            val error = SubscriptionPipeline
                .parseExtraHeaders("""{"X-Ok":"1","X-Bad":{"nested":true}}""")
                .exceptionOrNull()

            assertThat(error).hasMessageThat().contains("X-Bad")
        }
    }

    @Nested
    @DisplayName("错误文案")
    inner class ErrorDescription {

        @Test
        fun `不带消息的异常也要给出非空文案`() {
            // lastError 落成 null 的话，界面上就是「更新失败了但没有错误」，
            // 和 docs 里那个把明文 HTTP 拦截吞掉的空 catch 是同一类问题
            assertThat(SubscriptionPipeline.describe(IOException())).isNotEmpty()
            assertThat(SubscriptionPipeline.describe(IOException("   "))).isNotEmpty()
            assertThat(SubscriptionPipeline.describe(IOException("订阅返回 502")))
                .isEqualTo("订阅返回 502")
        }
    }
}
