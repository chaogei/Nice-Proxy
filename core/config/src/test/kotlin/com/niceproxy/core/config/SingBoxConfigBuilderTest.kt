package com.niceproxy.core.config

import com.google.common.truth.Truth.assertThat
import com.niceproxy.core.model.InboundAuth
import com.niceproxy.core.model.InboundType
import com.niceproxy.core.model.ProtocolParams
import com.niceproxy.core.model.ProxyProtocol
import com.niceproxy.core.model.RoutingRule
import com.niceproxy.core.model.RuleAction
import com.niceproxy.core.model.RuleMatcher
import com.niceproxy.core.model.RuleSetRef
import com.niceproxy.core.model.ServerProfile
import com.niceproxy.core.model.WellKnownTag
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * 守护 docs/DESIGN.md §6.3 中 C-1 ~ C-8 的生成器约束。
 * 这些断言一旦失败，说明生成的配置会被 sing-box 拒绝加载或行为不符预期。
 */
class SingBoxConfigBuilderTest {

    private val builder = SingBoxConfigBuilder()

    private fun buildOk(input: ConfigInput): JsonObject {
        val result = builder.build(input)
        assertTrue(result is ConfigResult.Success) { "构建失败：$result" }
        return Json.parseToJsonElement((result as ConfigResult.Success).json).jsonObject
    }

    @Nested
    @DisplayName("生成器硬性约束")
    inner class Constraints {

        @Test
        @DisplayName("C-1 入站不得出现 1.13 已移除的 sniff / domain_strategy 字段")
        fun noDeprecatedInboundFields() {
            val root = buildOk(Fixtures.input(nodes = listOf(Fixtures.hysteria2())))
            root["inbounds"]!!.jsonArray.forEach { inbound ->
                val keys = inbound.jsonObject.keys
                assertThat(keys).doesNotContain("sniff")
                assertThat(keys).doesNotContain("sniff_override_destination")
                assertThat(keys).doesNotContain("sniff_timeout")
                assertThat(keys).doesNotContain("domain_strategy")
                assertThat(keys).doesNotContain("udp_disable_domain_unmapping")
            }
            // 嗅探必须以路由 action 的形式出现
            val actions = root.routeRules().mapNotNull { it["action"]?.jsonPrimitive?.contentOrNull }
            assertThat(actions).contains("sniff")
        }

        @Test
        @DisplayName("C-2 不得生成已废弃的 block / dns 类型出站")
        fun noDeprecatedOutboundTypes() {
            val rules = listOf(
                RoutingRule(
                    id = "r1",
                    name = "拦截广告",
                    matcher = RuleMatcher(domainSuffix = listOf("ads.example.com")),
                    action = RuleAction.Reject(),
                ),
            )
            val root = buildOk(Fixtures.input(nodes = listOf(Fixtures.hysteria2()), rules = rules))
            val types = root["outbounds"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content }
            assertThat(types).doesNotContain("block")
            assertThat(types).doesNotContain("dns")

            val rejectRule = root.routeRules().single { it["action"]?.jsonPrimitive?.contentOrNull == "reject" }
            assertThat(rejectRule["domain_suffix"]!!.jsonArray).hasSize(1)
        }

        @Test
        @DisplayName("C-3 auto_detect_interface 恒为 false")
        fun autoDetectInterfaceDisabled() {
            val root = buildOk(Fixtures.input(nodes = listOf(Fixtures.hysteria2())))
            assertThat(root["route"]!!.jsonObject["auto_detect_interface"]!!.jsonPrimitive.content)
                .isEqualTo("false")
        }

        @Test
        @DisplayName("C-4 必须提供 default_domain_resolver，否则 1.12+ 启动失败")
        fun defaultDomainResolverPresent() {
            val root = buildOk(Fixtures.input(nodes = listOf(Fixtures.hysteria2())))
            val resolver = root["route"]!!.jsonObject["default_domain_resolver"]!!.jsonPrimitive.content
            val dnsTags = root["dns"]!!.jsonObject["servers"]!!.jsonArray
                .map { it.jsonObject["tag"]!!.jsonPrimitive.content }
            assertThat(dnsTags).contains(resolver)
        }

        @Test
        @DisplayName("C-5 所有 tag 全局唯一且不与保留 tag 冲突")
        fun tagsAreUniqueAndNamespaced() {
            val nodes = listOf(Fixtures.hysteria2("a"), Fixtures.vlessReality("b"), Fixtures.vmessWs("c"))
            val inbounds = listOf(
                Fixtures.mixedInbound("m1", port = 8080),
                Fixtures.mixedInbound("m2", port = 1080),
            )
            val root = buildOk(Fixtures.input(inbounds = inbounds, nodes = nodes))

            val outboundTags = root["outbounds"]!!.jsonArray.map { it.jsonObject["tag"]!!.jsonPrimitive.content }
            assertThat(outboundTags).containsNoDuplicates()

            val inboundTags = root["inbounds"]!!.jsonArray.map { it.jsonObject["tag"]!!.jsonPrimitive.content }
            assertThat(inboundTags).containsNoDuplicates()

            nodes.forEach { assertThat(outboundTags).contains(it.outboundTag) }
            assertThat(outboundTags).containsAtLeastElementsIn(WellKnownTag.ALL)
        }

        @Test
        @DisplayName("C-6 无节点时不生成空的 urltest / selector")
        fun noEmptyPolicyGroups() {
            val root = buildOk(Fixtures.input(nodes = emptyList()))
            val types = root["outbounds"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content }
            assertThat(types).doesNotContain("urltest")
            assertThat(types).doesNotContain("selector")
            assertThat(types).containsExactly("direct")
        }

        @Test
        @DisplayName("C-7 无节点时退化为纯中继：final 与指向 proxy 的规则都落到 direct")
        fun degradesToRelayModeWithoutNodes() {
            val rules = listOf(
                RoutingRule(
                    id = "r1",
                    name = "走代理",
                    matcher = RuleMatcher(domainSuffix = listOf("example.com")),
                    action = RuleAction.Route(WellKnownTag.PROXY),
                ),
            )
            val root = buildOk(Fixtures.input(nodes = emptyList(), rules = rules))

            assertThat(root["route"]!!.jsonObject["final"]!!.jsonPrimitive.content)
                .isEqualTo(WellKnownTag.DIRECT)

            // 引用不存在的 outbound 会让内核拒绝加载整份配置。
            // 多条规则指向同一出站是正常的，所以按集合而不是多重集比较。
            val referenced = root.routeRules()
                .mapNotNull { it["outbound"]?.jsonPrimitive?.contentOrNull }
                .toSet()
            val declared = root["outbounds"]!!.jsonArray
                .map { it.jsonObject["tag"]!!.jsonPrimitive.content }
                .toSet()
            assertThat(declared).containsAtLeastElementsIn(referenced)
        }

        @Test
        @DisplayName("C-9 Clash API 只允许绑定回环地址")
        fun clashApiBoundToLoopback() {
            val root = buildOk(Fixtures.input(nodes = listOf(Fixtures.hysteria2())))
            val controller = root["experimental"]!!.jsonObject["clash_api"]!!
                .jsonObject["external_controller"]!!.jsonPrimitive.content
            assertThat(controller).startsWith("127.0.0.1:")
        }

        @Test
        @DisplayName("C-10 DNS 服务器不得 detour 到 direct")
        fun dnsNeverDetoursToDirect() {
            // 1.12+ 的类型化 DNS 服务器默认就用空 direct 出站作为拨号器，
            // 再显式写 detour: direct 会让内核在启动时报
            // "detour to an empty direct outbound makes no sense" 并拒绝启动。
            //
            // 这个错误只在 Start() 阶段出现，内核契约测试（走 box.New 装配）
            // 抓不到，所以必须在生成侧守住。这是实测中真实踩到过的坑。
            listOf(
                Fixtures.input(nodes = listOf(Fixtures.hysteria2())),
                Fixtures.input(nodes = emptyList()),
            ).forEach { input ->
                val root = buildOk(input)
                root["dns"]!!.jsonObject["servers"]!!.jsonArray.forEach { server ->
                    val detour = server.jsonObject["detour"]?.jsonPrimitive?.contentOrNull
                    assertThat(detour).isNotEqualTo(WellKnownTag.DIRECT)
                }
            }
        }
    }

    @Nested
    @DisplayName("入站")
    inner class Inbounds {

        @Test
        fun `mixed 入站带认证时生成 users 数组`() {
            val inbound = Fixtures.mixedInbound(auth = InboundAuth("alice", "s3cret"))
            val root = buildOk(Fixtures.input(inbounds = listOf(inbound)))
            val users = root["inbounds"]!!.jsonArray.single().jsonObject["users"]!!.jsonArray
            assertThat(users.single().jsonObject["username"]!!.jsonPrimitive.content).isEqualTo("alice")
        }

        @Test
        fun `关闭 UDP 的入站通过路由规则拒绝 UDP`() {
            val inbound = Fixtures.mixedInbound(udpEnabled = false)
            val root = buildOk(Fixtures.input(inbounds = listOf(inbound)))
            val udpReject = root.routeRules().single {
                it["action"]?.jsonPrimitive?.contentOrNull == "reject" &&
                    it["network"]?.jsonArray?.map { n -> n.jsonPrimitive.content } == listOf("udp")
            }
            assertThat(udpReject["inbound"]!!.jsonArray.single().jsonPrimitive.content)
                .isEqualTo(inbound.tag)
        }

        @Test
        fun `PAC 入站不进入 sing-box 配置`() {
            val pac = Fixtures.mixedInbound("pac", port = 8090).copy(type = InboundType.PAC)
            val root = buildOk(Fixtures.input(inbounds = listOf(Fixtures.mixedInbound(), pac)))
            assertThat(root["inbounds"]!!.jsonArray).hasSize(1)
        }

        @Test
        fun `端口重复时构建失败`() {
            val result = builder.build(
                Fixtures.input(
                    inbounds = listOf(
                        Fixtures.mixedInbound("a", port = 8080),
                        Fixtures.mixedInbound("b", port = 8080),
                    ),
                ),
            )
            assertThat(result).isInstanceOf(ConfigResult.Failure::class.java)
            assertThat((result as ConfigResult.Failure).errors)
                .contains(ConfigError.DuplicatePort(8080))
        }

        @Test
        fun `低于 1025 的端口被拒绝`() {
            val result = builder.build(
                Fixtures.input(inbounds = listOf(Fixtures.mixedInbound(port = 80))),
            )
            assertThat(result).isInstanceOf(ConfigResult.Failure::class.java)
        }

        @Test
        fun `没有启用任何入站时构建失败`() {
            val result = builder.build(
                Fixtures.input(inbounds = listOf(Fixtures.mixedInbound(enabled = false))),
            )
            assertThat(result).isInstanceOf(ConfigResult.Failure::class.java)
            assertThat((result as ConfigResult.Failure).errors)
                .contains(ConfigError.NoEnabledInbound())
        }
    }

    @Nested
    @DisplayName("出站协议映射")
    inner class Outbounds {

        @Test
        fun `Hysteria2 生成扁平结构与 obfs 子对象`() {
            val node = Fixtures.hysteria2(obfsType = "salamander", obfsPassword = "ob")
            val root = buildOk(Fixtures.input(nodes = listOf(node)))
            val out = root.outbound(node.outboundTag)

            assertThat(out["type"]!!.jsonPrimitive.content).isEqualTo("hysteria2")
            assertThat(out["up_mbps"]!!.jsonPrimitive.content).isEqualTo("100")
            assertThat(out["obfs"]!!.jsonObject["type"]!!.jsonPrimitive.content).isEqualTo("salamander")
            // 未显式指定 ALPN 时补上 h3
            assertThat(out["tls"]!!.jsonObject["alpn"]!!.jsonArray.map { it.jsonPrimitive.content })
                .containsExactly("h3")
        }

        @Test
        fun `Hysteria2 缺少混淆密码时降级为警告而非失败`() {
            val bad = Fixtures.hysteria2(obfsType = "salamander", obfsPassword = null)
            val result = builder.build(Fixtures.input(nodes = listOf(bad, Fixtures.vmessWs())))
            assertThat(result).isInstanceOf(ConfigResult.Success::class.java)
            val warnings = (result as ConfigResult.Success).warnings
            assertThat(warnings).hasSize(1)
            assertThat(warnings.single()).isInstanceOf(ConfigError.InvalidNode::class.java)
        }

        @Test
        fun `端口跳跃格式非法时该节点被跳过`() {
            val bad = Fixtures.hysteria2(serverPorts = listOf("not-a-range"))
            val result = builder.build(Fixtures.input(nodes = listOf(bad)))
            assertThat((result as ConfigResult.Success).warnings).hasSize(1)
        }

        @Test
        fun `VLESS REALITY 自动补齐 uTLS 指纹`() {
            val node = Fixtures.vlessReality()
            val root = buildOk(Fixtures.input(nodes = listOf(node)))
            val tls = root.outbound(node.outboundTag)["tls"]!!.jsonObject

            assertThat(tls["reality"]!!.jsonObject["public_key"]!!.jsonPrimitive.content)
                .isEqualTo(Fixtures.REALITY_PUBLIC_KEY)
            assertThat(tls["utls"]!!.jsonObject["fingerprint"]!!.jsonPrimitive.content).isEqualTo("chrome")
            assertThat(tls["server_name"]!!.jsonPrimitive.content).isEqualTo("www.microsoft.com")
        }

        @Test
        fun `VMess WebSocket 生成 transport 子对象`() {
            val node = Fixtures.vmessWs()
            val root = buildOk(Fixtures.input(nodes = listOf(node)))
            val transport = root.outbound(node.outboundTag)["transport"]!!.jsonObject

            assertThat(transport["type"]!!.jsonPrimitive.content).isEqualTo("ws")
            assertThat(transport["path"]!!.jsonPrimitive.content).isEqualTo("/ws")
        }

        @Test
        fun `Shadowsocks 不接受传输层配置`() {
            val node = Fixtures.shadowsocks().copy(
                transport = com.niceproxy.core.model.TransportConfig.WebSocket(),
            )
            val result = builder.build(Fixtures.input(nodes = listOf(node)))
            assertThat((result as ConfigResult.Success).warnings).hasSize(1)
        }

        @Test
        fun `要求 TLS 的协议未启用 TLS 时被跳过`() {
            val node = Fixtures.hysteria2().copy(tls = null)
            val result = builder.build(Fixtures.input(nodes = listOf(node)))
            assertThat((result as ConfigResult.Success).warnings).hasSize(1)
        }

        @Test
        fun `节点 IP 地址不作为 SNI 写入`() {
            val node = ServerProfile(
                id = "t", groupId = "g", name = "T",
                protocol = ProxyProtocol.TROJAN,
                server = "203.0.113.9", serverPort = 443,
                params = ProtocolParams.Trojan("pw"),
                tls = com.niceproxy.core.model.TlsConfig(enabled = true, serverName = null),
            )
            val root = buildOk(Fixtures.input(nodes = listOf(node)))
            assertThat(root.outbound(node.outboundTag)["tls"]!!.jsonObject).doesNotContainKey("server_name")
        }
    }

    @Nested
    @DisplayName("路由")
    inner class Routing {

        @Test
        fun `IP 类规则之前插入 resolve 动作`() {
            val rules = listOf(
                RoutingRule(
                    id = "r1", name = "域名规则", sortOrder = 0,
                    matcher = RuleMatcher(domainSuffix = listOf("example.com")),
                    action = RuleAction.Route(WellKnownTag.PROXY),
                ),
                RoutingRule(
                    id = "r2", name = "IP 规则", sortOrder = 1,
                    matcher = RuleMatcher(ipCidr = listOf("203.0.113.0/24")),
                    action = RuleAction.Route(WellKnownTag.DIRECT),
                ),
            )
            val root = buildOk(Fixtures.input(nodes = listOf(Fixtures.hysteria2()), rules = rules))
            val list = root.routeRules()

            val resolveIndex = list.indexOfFirst { it["action"]?.jsonPrimitive?.contentOrNull == "resolve" }
            val ipRuleIndex = list.indexOfFirst { it.containsKey("ip_cidr") }
            val domainRuleIndex = list.indexOfFirst { it.containsKey("domain_suffix") }

            assertThat(resolveIndex).isGreaterThan(domainRuleIndex)
            assertThat(resolveIndex).isLessThan(ipRuleIndex)
        }

        @Test
        fun `纯域名规则不会触发 resolve`() {
            val rules = listOf(
                RoutingRule(
                    id = "r1", name = "域名规则",
                    matcher = RuleMatcher(domainSuffix = listOf("example.com")),
                    action = RuleAction.Route(WellKnownTag.PROXY),
                ),
            )
            val root = buildOk(Fixtures.input(nodes = listOf(Fixtures.hysteria2()), rules = rules))
            val actions = root.routeRules().mapNotNull { it["action"]?.jsonPrimitive?.contentOrNull }
            assertThat(actions).doesNotContain("resolve")
        }

        @Test
        fun `按客户端来源 IP 分流`() {
            val node = Fixtures.hysteria2()
            val rules = listOf(
                RoutingRule(
                    id = "r1", name = "笔记本走节点",
                    matcher = RuleMatcher(sourceIpCidr = listOf("192.168.1.100/32")),
                    action = RuleAction.Route(node.outboundTag),
                ),
            )
            val root = buildOk(Fixtures.input(nodes = listOf(node), rules = rules))
            val rule = root.routeRules().single { it.containsKey("source_ip_cidr") }
            assertThat(rule["outbound"]!!.jsonPrimitive.content).isEqualTo(node.outboundTag)
        }

        @Test
        fun `远程规则集在无节点时改用 direct 下载`() {
            val ruleSets = listOf(
                RuleSetRef(
                    id = "rs1", tag = "geosite-cn",
                    url = "https://example.com/geosite-cn.srs",
                ),
            )
            val root = buildOk(Fixtures.input(nodes = emptyList(), ruleSets = ruleSets))
            val rs = root["route"]!!.jsonObject["rule_set"]!!.jsonArray.single().jsonObject
            assertThat(rs["download_detour"]!!.jsonPrimitive.content).isEqualTo(WellKnownTag.DIRECT)
        }

        @Test
        fun `局域网流量默认直连`() {
            val root = buildOk(Fixtures.input(nodes = listOf(Fixtures.hysteria2())))
            val privateRule = root.routeRules().single { it.containsKey("ip_is_private") }
            assertThat(privateRule["outbound"]!!.jsonPrimitive.content).isEqualTo(WellKnownTag.DIRECT)
        }
    }

    @Nested
    @DisplayName("指纹")
    inner class Fingerprint {

        @Test
        fun `相同输入产生相同指纹`() {
            val input = Fixtures.input(nodes = listOf(Fixtures.hysteria2()))
            val a = builder.build(input) as ConfigResult.Success
            val b = builder.build(input) as ConfigResult.Success
            assertThat(a.fingerprint).isEqualTo(b.fingerprint)
        }

        @Test
        fun `端口变化会改变指纹`() {
            val a = builder.build(Fixtures.input(inbounds = listOf(Fixtures.mixedInbound(port = 8080))))
            val b = builder.build(Fixtures.input(inbounds = listOf(Fixtures.mixedInbound(port = 8081))))
            assertThat((a as ConfigResult.Success).fingerprint)
                .isNotEqualTo((b as ConfigResult.Success).fingerprint)
        }
    }

    /**
     * 这一组都是「界面上看不出任何问题、内核却直接拒绝加载」的输入组合。
     * 表现全都是用户改了个不相干的地方之后代理突然起不来，且错误信息指不到根因。
     */
    @Nested
    @DisplayName("会让内核拒绝加载的输入组合")
    inner class InvalidCombinations {

        @Test
        fun `规则引用了被停用的规则集时剔除该引用`() {
            // 用户在规则集管理页把 geosite-cn 关掉，模板留下的规则还在引用它。
            // 引用一个未声明的 rule_set，内核会拒绝加载整份配置。
            val rules = listOf(
                RoutingRule(
                    id = "r1", name = "国内直连",
                    matcher = RuleMatcher(
                        domainSuffix = listOf("cn"),
                        ruleSet = listOf("geosite-cn"),
                    ),
                    action = RuleAction.Route(WellKnownTag.DIRECT),
                ),
            )
            val ruleSets = listOf(
                RuleSetRef("rs1", "geosite-cn", url = "https://x/geosite-cn.srs", enabled = false),
            )
            val root = buildOk(
                Fixtures.input(nodes = listOf(Fixtures.hysteria2()), rules = rules, ruleSets = ruleSets),
            )

            val rule = root.routeRules().single { it.containsKey("domain_suffix") }
            assertThat(rule).doesNotContainKey("rule_set")
            assertThat(root["route"]!!.jsonObject).doesNotContainKey("rule_set")
        }

        @Test
        fun `规则的条件只剩规则集且规则集不存在时整条丢弃`() {
            // 剔除引用之后如果条件为空，这条规则就变成了「匹配全部流量」，
            // 会把后面所有规则截胡 —— 比启动失败更隐蔽
            val rules = listOf(
                RoutingRule(
                    id = "r1", name = "国内直连",
                    matcher = RuleMatcher(ruleSet = listOf("geosite-cn")),
                    action = RuleAction.Route(WellKnownTag.DIRECT),
                ),
            )
            val result = builder.build(Fixtures.input(nodes = listOf(Fixtures.hysteria2()), rules = rules))
            val root = buildOk(Fixtures.input(nodes = listOf(Fixtures.hysteria2()), rules = rules))

            assertThat((result as ConfigResult.Success).warnings.map { it::class })
                .contains(ConfigError.EmptyRule::class)
            // 剩下的规则里不能出现「没有任何条件、却指定了出站」的兜底规则
            assertThat(root.routeRules().none { it.keys == setOf("outbound") }).isTrue()
        }

        @Test
        fun `没填任何条件就保存的新规则被跳过`() {
            // RoutingRepository.newRule 建出来的就是这个形状，用户点保存即可复现
            val rules = listOf(RoutingRule(id = "r1", name = "新规则"))
            val result = builder.build(Fixtures.input(nodes = listOf(Fixtures.hysteria2()), rules = rules))

            assertThat((result as ConfigResult.Success).warnings)
                .contains(ConfigError.EmptyRule("新规则"))
        }

        @Test
        fun `规则指向已被删除的节点时回落到策略组`() {
            // 用户删掉一个节点，但按来源 IP 分流的规则还指着它的 tag
            val node = Fixtures.hysteria2()
            val rules = listOf(
                RoutingRule(
                    id = "r1", name = "笔记本走香港",
                    matcher = RuleMatcher(sourceIpCidr = listOf("192.168.1.100/32")),
                    action = RuleAction.Route("node-已删除"),
                ),
            )
            val input = Fixtures.input(nodes = listOf(node), rules = rules)
            val result = builder.build(input) as ConfigResult.Success
            val root = buildOk(input)

            assertThat(result.warnings.map { it::class }).contains(ConfigError.DanglingOutbound::class)
            val rule = root.routeRules().single { it.containsKey("source_ip_cidr") }
            assertThat(rule["outbound"]!!.jsonPrimitive.content).isEqualTo(WellKnownTag.PROXY)
        }

        @Test
        fun `规则集标签重复时只声明一次`() {
            // 在规则集管理页把同一个 geosite-cn 添加两遍，内核会因 tag 冲突拒绝加载
            val rules = listOf(
                RoutingRule(
                    id = "r1", name = "国内直连",
                    matcher = RuleMatcher(ruleSet = listOf("geosite-cn")),
                    action = RuleAction.Route(WellKnownTag.DIRECT),
                ),
            )
            val ruleSets = listOf(
                RuleSetRef("rs1", "geosite-cn", url = "https://x/a.srs"),
                RuleSetRef("rs2", "geosite-cn", url = "https://x/b.srs"),
            )
            val input = Fixtures.input(nodes = listOf(Fixtures.hysteria2()), rules = rules, ruleSets = ruleSets)
            val root = buildOk(input)

            val tags = root["route"]!!.jsonObject["rule_set"]!!.jsonArray
                .map { it.jsonObject["tag"]!!.jsonPrimitive.content }
            assertThat(tags).containsExactly("geosite-cn")
            assertThat((builder.build(input) as ConfigResult.Success).warnings.map { it::class })
                .contains(ConfigError.InvalidRuleSet::class)
        }

        @Test
        fun `远程规则集缺少 URL 时降级为警告而不是抛异常`() {
            // 之前 requireNotNull 会把异常直接抛出 build()，
            // ConfigRepository 拿不到 ConfigResult.Failure，整个启动流程崩在这里
            val ruleSets = listOf(RuleSetRef("rs1", "geosite-cn", url = null))
            val attempt = runCatching {
                builder.build(Fixtures.input(nodes = listOf(Fixtures.hysteria2()), ruleSets = ruleSets))
            }

            assertThat(attempt.isSuccess).isTrue()
            val result = attempt.getOrThrow()
            assertThat(result).isInstanceOf(ConfigResult.Success::class.java)
            assertThat((result as ConfigResult.Success).warnings.map { it::class })
                .contains(ConfigError.InvalidRuleSet::class)
        }

        @Test
        fun `重复的节点 id 不会生成重复的出站 tag`() {
            val node = Fixtures.hysteria2("dup")
            val root = buildOk(Fixtures.input(nodes = listOf(node, node.copy(name = "另一个名字"))))
            val tags = root["outbounds"]!!.jsonArray.map { it.jsonObject["tag"]!!.jsonPrimitive.content }
            assertThat(tags).containsNoDuplicates()
        }

        @Test
        fun `任何输入下被引用的 tag 都必须已声明`() {
            // 把上面几种问题揉在一起跑一遍总不变式：
            // 悬空的 outbound 或 rule_set 引用都会让内核拒绝加载整份配置
            val node = Fixtures.hysteria2()
            val rules = listOf(
                RoutingRule(
                    id = "r1", name = "引用了停用的规则集", sortOrder = 0,
                    matcher = RuleMatcher(domain = listOf("a.com"), ruleSet = listOf("geosite-off")),
                    action = RuleAction.Route("node-不存在"),
                ),
                RoutingRule(
                    id = "r2", name = "引用了缺 URL 的规则集", sortOrder = 1,
                    matcher = RuleMatcher(ruleSet = listOf("geoip-broken")),
                    action = RuleAction.Route(WellKnownTag.DIRECT),
                ),
                RoutingRule(
                    id = "r3", name = "空规则", sortOrder = 2,
                    action = RuleAction.Reject(),
                ),
            )
            val ruleSets = listOf(
                RuleSetRef("rs1", "geosite-off", url = "https://x/a.srs", enabled = false),
                RuleSetRef("rs2", "geoip-broken", url = " ", containsIpRules = true),
            )
            val root = buildOk(Fixtures.input(nodes = listOf(node), rules = rules, ruleSets = ruleSets))

            val declaredOutbounds = root["outbounds"]!!.jsonArray
                .map { it.jsonObject["tag"]!!.jsonPrimitive.content }
                .toSet()
            val referencedOutbounds = root.routeRules()
                .mapNotNull { it["outbound"]?.jsonPrimitive?.contentOrNull }
                .toSet() + root["route"]!!.jsonObject["final"]!!.jsonPrimitive.content
            assertThat(declaredOutbounds).containsAtLeastElementsIn(referencedOutbounds)

            val declaredRuleSets = root["route"]!!.jsonObject["rule_set"]
                ?.jsonArray
                ?.map { it.jsonObject["tag"]!!.jsonPrimitive.content }
                ?.toSet()
                .orEmpty()
            val referencedRuleSets = (root.routeRules() + root.dnsRules())
                .flatMap { rule -> rule["rule_set"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty() }
                .toSet()
            assertThat(declaredRuleSets).containsAtLeastElementsIn(referencedRuleSets)
        }
    }
}

private fun JsonObject.routeRules(): List<JsonObject> =
    (this["route"]!!.jsonObject["rules"] as JsonArray).map { it.jsonObject }

private fun JsonObject.dnsRules(): List<JsonObject> =
    (this["dns"]!!.jsonObject["rules"] as? JsonArray)?.map { it.jsonObject }.orEmpty()

private fun JsonObject.outbound(tag: String): JsonObject =
    this["outbounds"]!!.jsonArray
        .map { it.jsonObject }
        .single { it["tag"]!!.jsonPrimitive.content == tag }
