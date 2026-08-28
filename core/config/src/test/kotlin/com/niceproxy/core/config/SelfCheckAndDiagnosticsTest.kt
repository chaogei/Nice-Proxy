package com.niceproxy.core.config

import com.google.common.truth.Truth.assertThat
import com.niceproxy.core.config.internal.SingBoxSelfCheck
import com.niceproxy.core.model.RoutingRule
import com.niceproxy.core.model.RuleAction
import com.niceproxy.core.model.RuleMatcher
import com.niceproxy.core.model.WellKnownTag
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * 生成后的只读自检与结构化诊断。
 *
 * 自检故意不复用生成侧的任何代码：它复刻的是 sing-box `option.checkOutbounds`
 * 与装配阶段的判据，只看产物不看过程。生成逻辑分散在十几个方法里，任何一次
 * 重构都可能让某条收敛悄悄失效，而失效的表现是内核吐一句
 * `outbound not found: node-xxx` 就退出 —— 用户只看到「启动失败」。
 */
class SelfCheckAndDiagnosticsTest {

    private val builder = SingBoxConfigBuilder()

    private fun root(json: String): JsonObject =
        Json.parseToJsonElement(json) as JsonObject

    @Nested
    @DisplayName("只读自检")
    inner class SelfCheck {

        @Test
        fun `正常生成的配置通过自检`() {
            val result = builder.build(
                Fixtures.input(
                    nodes = listOf(Fixtures.hysteria2("a"), Fixtures.wireGuard("wg")),
                    rules = listOf(
                        RoutingRule(
                            id = "r1", name = "直连",
                            matcher = RuleMatcher(domainSuffix = listOf("cn")),
                            action = RuleAction.Route(WellKnownTag.DIRECT),
                        ),
                    ),
                ),
            ) as ConfigResult.Success

            assertThat(SingBoxSelfCheck.verify(root(result.json))).isEmpty()
        }

        @Test
        fun `抓得出重复的出站 tag`() {
            val problems = SingBoxSelfCheck.verify(
                root(
                    """
                    {
                      "inbounds": [{"type":"mixed","tag":"in-a"}],
                      "outbounds": [
                        {"type":"trojan","tag":"node-a"},
                        {"type":"vmess","tag":"node-a"},
                        {"type":"direct","tag":"direct"}
                      ]
                    }
                    """.trimIndent(),
                ),
            )

            assertThat(problems).hasSize(1)
            assertThat(problems.single()).contains("node-a")
        }

        /** outbound 与 endpoint 在内核里共用一个 tag 命名空间。 */
        @Test
        fun `outbound 与 endpoint 之间的 tag 冲突也算重复`() {
            val problems = SingBoxSelfCheck.verify(
                root(
                    """
                    {
                      "endpoints": [{"type":"wireguard","tag":"node-a"}],
                      "outbounds": [
                        {"type":"trojan","tag":"node-a"},
                        {"type":"direct","tag":"direct"}
                      ]
                    }
                    """.trimIndent(),
                ),
            )

            assertThat(problems.single()).contains("node-a")
        }

        @Test
        fun `抓得出悬空的出站引用`() {
            val problems = SingBoxSelfCheck.verify(
                root(
                    """
                    {
                      "outbounds": [{"type":"direct","tag":"direct"}],
                      "route": {"final":"proxy","rules":[{"outbound":"node-x"}]}
                    }
                    """.trimIndent(),
                ),
            )

            assertThat(problems).hasSize(2)
            assertThat(problems.joinToString()).contains("node-x")
        }

        @Test
        fun `抓得出指向自己的链式代理`() {
            val problems = SingBoxSelfCheck.verify(
                root(
                    """
                    {
                      "outbounds": [
                        {"type":"trojan","tag":"node-a","detour":"node-a"},
                        {"type":"direct","tag":"direct"}
                      ]
                    }
                    """.trimIndent(),
                ),
            )

            assertThat(problems.single()).contains("指向了自己")
        }

        /**
         * 空的策略组是最阴的一种：内核照常加载，界面上策略组也在，只是一个候选
         * 都没有 —— 流量到了这里无处可去，全部连接直接失败，日志里没有任何一行
         * 说明原因。
         */
        @Test
        fun `抓得出没有候选的策略组`() {
            val problems = SingBoxSelfCheck.verify(
                root(
                    """
                    {
                      "outbounds": [
                        {"type":"selector","tag":"proxy","outbounds":[]},
                        {"type":"direct","tag":"direct"}
                      ]
                    }
                    """.trimIndent(),
                ),
            )

            assertThat(problems.single()).contains("proxy")
        }

        @Test
        fun `抓得出未声明的规则集引用`() {
            val problems = SingBoxSelfCheck.verify(
                root(
                    """
                    {
                      "outbounds": [{"type":"direct","tag":"direct"}],
                      "route": {"final":"direct","rules":[{"rule_set":["geosite-cn"],"outbound":"direct"}]}
                    }
                    """.trimIndent(),
                ),
            )

            assertThat(problems.single()).contains("geosite-cn")
        }

        /** C-10：显式 `detour: direct` 会让内核在启动阶段直接罢工。 */
        @Test
        fun `抓得出 DNS detour 到 direct`() {
            val problems = SingBoxSelfCheck.verify(
                root(
                    """
                    {
                      "dns": {"servers":[{"tag":"dns-local","detour":"direct"}]},
                      "outbounds": [{"type":"direct","tag":"direct"}]
                    }
                    """.trimIndent(),
                ),
            )

            assertThat(problems.single()).contains("dns-local")
        }

        /** NFR-9：Clash API 一旦漏到局域网，任何设备都能改节点、读全部连接明细。 */
        @Test
        fun `抓得出绑定到非回环地址的 Clash API`() {
            val problems = SingBoxSelfCheck.verify(
                root(
                    """
                    {
                      "outbounds": [{"type":"direct","tag":"direct"}],
                      "experimental": {"clash_api":{"external_controller":"0.0.0.0:9090"}}
                    }
                    """.trimIndent(),
                ),
            )

            assertThat(problems.single()).contains("0.0.0.0:9090")
        }

        /** C-2：1.13 已经把 block / dns 这两种出站类型删掉了。 */
        @Test
        fun `抓得出 1_13 已移除的出站类型`() {
            val problems = SingBoxSelfCheck.verify(
                root(
                    """
                    {"outbounds":[{"type":"block","tag":"block"},{"type":"direct","tag":"direct"}]}
                    """.trimIndent(),
                ),
            )

            assertThat(problems.single()).contains("block")
        }
    }

    @Nested
    @DisplayName("入站 tag 与 UDP 超时")
    inner class InboundValidation {

        /**
         * 端口查重挡不住这个：两个入站可以 tag 相同而端口不同，
         * 而 `checkInbounds` 见到重复 tag 会拒绝整份配置。
         */
        @Test
        fun `tag 相同端口不同的两个入站会被拦下`() {
            val a = Fixtures.mixedInbound("same", port = 8080)
            val b = Fixtures.mixedInbound("same", port = 1080)
            val result = builder.build(Fixtures.input(inbounds = listOf(a, b)))

            assertThat(result).isInstanceOf(ConfigResult.Failure::class.java)
            assertThat((result as ConfigResult.Failure).errors.map { it::class })
                .contains(ConfigError.DuplicateInboundTag::class)
        }

        @Test
        fun `udp_timeout 不是合法时长时构建失败`() {
            val inbound = Fixtures.mixedInbound().copy(udpTimeout = "5 分钟")
            val result = builder.build(Fixtures.input(inbounds = listOf(inbound)))

            assertThat(result).isInstanceOf(ConfigResult.Failure::class.java)
            assertThat((result as ConfigResult.Failure).errors.map { it::class })
                .contains(ConfigError.InvalidUdpTimeout::class)
        }

        @Test
        fun `合法的 udp_timeout 照常写进配置`() {
            val inbound = Fixtures.mixedInbound().copy(udpTimeout = "10m", tcpFastOpen = true)
            val result = builder.build(Fixtures.input(inbounds = listOf(inbound)))

            assertThat(result).isInstanceOf(ConfigResult.Success::class.java)
            assertThat((result as ConfigResult.Success).json).contains("\"udp_timeout\": \"10m\"")
        }
    }

    @Nested
    @DisplayName("结构化诊断")
    inner class Diagnostics {

        @Test
        fun `成功时把警告按维度分好类`() {
            val insecure = Fixtures.hysteria2("ins").let { it.copy(tls = it.tls!!.copy(insecure = true)) }
            val broken = Fixtures.hysteria2("bad", obfsType = "salamander", obfsPassword = null)
            val rules = listOf(RoutingRule(id = "r1", name = "空规则"))

            val diagnostics = builder
                .build(Fixtures.input(nodes = listOf(insecure, broken), rules = rules))
                .diagnostics

            assertThat(diagnostics.isUsable).isTrue()
            assertThat(diagnostics.skippedNodes.map { it.nodeId }).containsExactly("bad")
            assertThat(diagnostics.insecureNodes.map { it.nodeId }).containsExactly("ins")
            assertThat(diagnostics.routingIssues.map { it::class })
                .containsExactly(ConfigError.EmptyRule::class)
        }

        @Test
        fun `失败时同样能拿到诊断，且标记为不可用`() {
            val diagnostics = builder
                .build(Fixtures.input(nodes = listOf(Fixtures.unreadableCredentials("a"))))
                .diagnostics

            assertThat(diagnostics.isUsable).isFalse()
            assertThat(diagnostics.blocking.map { it::class }).contains(ConfigError.NoUsableNode::class)
        }

        /** 生成失败时攒下的非阻断问题不能丢：它们往往才是根因。 */
        @Test
        fun `失败时不丢掉已攒下的警告`() {
            val diagnostics = builder
                .build(Fixtures.input(nodes = listOf(Fixtures.hysteria2("a").copy(tls = null))))
                .diagnostics

            assertThat(diagnostics.isUsable).isFalse()
            assertThat(diagnostics.skippedNodes).isNotEmpty()
        }

        @Test
        fun `没有任何问题时诊断为空`() {
            val diagnostics = builder
                .build(Fixtures.input(nodes = listOf(Fixtures.hysteria2())))
                .diagnostics

            assertThat(diagnostics.isEmpty).isTrue()
            assertThat(diagnostics.summary()).isEmpty()
        }

        /**
         * 通知栏的文案是把这些 message 直接 join 起来的。密钥失效时几千个节点会
         * 给出几千条同因的记录，拼起来能把通知撑爆。
         */
        @Test
        fun `摘要有条数上限`() {
            val nodes = (0 until 20).map { Fixtures.hysteria2("n$it").copy(tls = null) } +
                Fixtures.vmessWs("ok")
            val diagnostics = builder.build(Fixtures.input(nodes = nodes)).diagnostics

            assertThat(diagnostics.summary(limit = 3).split("；")).hasSize(3)
        }
    }
}
