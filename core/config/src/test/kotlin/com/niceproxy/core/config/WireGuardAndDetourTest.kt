package com.niceproxy.core.config

import com.google.common.truth.Truth.assertThat
import com.niceproxy.core.model.ProtocolParams
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
 * FR-2.2 的 WireGuard 出站与 FR-2.10 的节点链式代理。
 *
 * 这两件事放在一起测是因为它们在生成侧是耦合的：WireGuard 是 1.13 里唯一
 * 写进 `endpoints` 的出站，而 endpoint 与 outbound 共用 tag 命名空间、
 * 一样能作为 detour 的目标，也一样能被策略组引用。
 */
class WireGuardAndDetourTest {

    private val builder = SingBoxConfigBuilder()

    private fun buildOk(input: ConfigInput): JsonObject {
        val result = builder.build(input)
        assertTrue(result is ConfigResult.Success) { "构建失败：$result" }
        return Json.parseToJsonElement((result as ConfigResult.Success).json).jsonObject
    }

    /** 单个坏节点的跳过理由。配一个好节点，避免落到「无可用节点」的硬失败。 */
    private fun skipReasonOf(bad: ServerProfile): String {
        val result = builder.build(Fixtures.input(nodes = listOf(bad, Fixtures.vmessWs())))
        assertTrue(result is ConfigResult.Success) { "本应只跳过坏节点：$result" }
        return (result as ConfigResult.Success).warnings
            .filterIsInstance<ConfigError.InvalidNode>()
            .single { it.nodeId == bad.id }
            .reason
    }

    @Nested
    @DisplayName("WireGuard endpoint")
    inner class WireGuard {

        /**
         * 1.11 把 WireGuard 的 outbound 形态标记为废弃，1.13.0 正式移除。
         * 写成 outbound 的话内核报 `unknown outbound type: wireguard`，
         * 拒绝的是整份配置 —— 所有节点跟着一起失效。
         */
        @Test
        fun `写进 endpoints 而不是 outbounds`() {
            val node = Fixtures.wireGuard()
            val root = buildOk(Fixtures.input(nodes = listOf(node)))

            val outboundTypes = root["outbounds"]!!.jsonArray
                .map { it.jsonObject["type"]!!.jsonPrimitive.content }
            assertThat(outboundTypes).doesNotContain("wireguard")

            val endpoint = root["endpoints"]!!.jsonArray.single().jsonObject
            assertThat(endpoint["type"]!!.jsonPrimitive.content).isEqualTo("wireguard")
            assertThat(endpoint["tag"]!!.jsonPrimitive.content).isEqualTo(node.outboundTag)
        }

        @Test
        fun `没有 WireGuard 节点时不写出空的 endpoints`() {
            val root = buildOk(Fixtures.input(nodes = listOf(Fixtures.vmessWs())))

            assertThat(root).doesNotContainKey("endpoints")
        }

        /** 字段名与 outbound 形态完全不同：peer 的信息全部下沉到 `peers[]` 里。 */
        @Test
        fun `按 endpoint 的字段布局写出 peer`() {
            val node = Fixtures.wireGuard(
                params = ProtocolParams.WireGuard(
                    privateKey = Fixtures.WG_PRIVATE_KEY,
                    peerPublicKey = Fixtures.WG_PEER_PUBLIC_KEY,
                    preSharedKey = Fixtures.WG_PRE_SHARED_KEY,
                    localAddress = listOf("172.16.0.2/32", "fd01::2/128"),
                    reserved = listOf(209, 98, 59),
                    mtu = 1408,
                    persistentKeepaliveInterval = 25,
                ),
            )
            val endpoint = buildOk(Fixtures.input(nodes = listOf(node)))
                .endpoints().single()

            assertThat(endpoint["private_key"]!!.jsonPrimitive.content)
                .isEqualTo(Fixtures.WG_PRIVATE_KEY)
            assertThat(endpoint["address"]!!.jsonArray.map { it.jsonPrimitive.content })
                .containsExactly("172.16.0.2/32", "fd01::2/128")
            assertThat(endpoint["mtu"]!!.jsonPrimitive.content).isEqualTo("1408")
            // 已移除的 outbound 形态才有这些扁平字段
            assertThat(endpoint.keys).containsNoneOf("local_address", "peer_public_key", "server")

            val peer = endpoint["peers"]!!.jsonArray.single().jsonObject
            assertThat(peer["address"]!!.jsonPrimitive.content).isEqualTo(node.server)
            assertThat(peer["port"]!!.jsonPrimitive.content).isEqualTo("51820")
            assertThat(peer["public_key"]!!.jsonPrimitive.content)
                .isEqualTo(Fixtures.WG_PEER_PUBLIC_KEY)
            assertThat(peer["pre_shared_key"]!!.jsonPrimitive.content)
                .isEqualTo(Fixtures.WG_PRE_SHARED_KEY)
            assertThat(peer["persistent_keepalive_interval"]!!.jsonPrimitive.content).isEqualTo("25")
            assertThat(peer["reserved"]!!.jsonArray.map { it.jsonPrimitive.content })
                .containsExactly("209", "98", "59")
        }

        /**
         * `allowed_ips` 留空在 wg-quick 里等价于「不转发任何流量」，
         * 而用户把节点加进代理列表的意图恰恰相反。补全量路由。
         */
        @Test
        fun `allowed_ips 留空时补全量路由`() {
            val endpoint = buildOk(Fixtures.input(nodes = listOf(Fixtures.wireGuard())))
                .endpoints().single()
            val peer = endpoint["peers"]!!.jsonArray.single().jsonObject

            assertThat(peer["allowed_ips"]!!.jsonArray.map { it.jsonPrimitive.content })
                .containsExactly("0.0.0.0/0", "::/0")
        }

        @Test
        fun `能被策略组选中`() {
            val node = Fixtures.wireGuard()
            val root = buildOk(Fixtures.input(nodes = listOf(node)))

            val selector = root.outbound(WellKnownTag.PROXY)["outbounds"]!!.jsonArray
                .map { it.jsonPrimitive.content }
            val urltest = root.outbound(WellKnownTag.AUTO)["outbounds"]!!.jsonArray
                .map { it.jsonPrimitive.content }

            assertThat(selector).contains(node.outboundTag)
            assertThat(urltest).contains(node.outboundTag)
        }

        @Test
        fun `私钥格式非法时该节点不可用`() {
            val bad = Fixtures.wireGuard(
                params = ProtocolParams.WireGuard(
                    privateKey = "not-a-key",
                    peerPublicKey = Fixtures.WG_PEER_PUBLIC_KEY,
                    localAddress = listOf("172.16.0.2/32"),
                ),
            )

            assertThat(skipReasonOf(bad)).contains("私钥")
        }

        /**
         * `Address = 10.0.0.2` 在 wg-quick 里合法，WARP 的配置导出也这么写；
         * 而 sing-box 用 `netip.ParsePrefix` 解析，少了掩码位就在读配置的
         * 第一步失败，拒绝整份配置。
         */
        @Test
        fun `本地地址缺掩码位时该节点不可用`() {
            val bad = Fixtures.wireGuard(
                params = ProtocolParams.WireGuard(
                    privateKey = Fixtures.WG_PRIVATE_KEY,
                    peerPublicKey = Fixtures.WG_PEER_PUBLIC_KEY,
                    localAddress = listOf("172.16.0.2"),
                ),
            )

            assertThat(skipReasonOf(bad)).contains("掩码位")
        }

        @Test
        fun `完全没有本地地址时该节点不可用`() {
            val bad = Fixtures.wireGuard(
                params = ProtocolParams.WireGuard(
                    privateKey = Fixtures.WG_PRIVATE_KEY,
                    peerPublicKey = Fixtures.WG_PEER_PUBLIC_KEY,
                ),
            )

            assertThat(skipReasonOf(bad)).contains("本地地址")
        }

        @Test
        fun `reserved 不是 3 个字节时该节点不可用`() {
            val bad = Fixtures.wireGuard(
                params = ProtocolParams.WireGuard(
                    privateKey = Fixtures.WG_PRIVATE_KEY,
                    peerPublicKey = Fixtures.WG_PEER_PUBLIC_KEY,
                    localAddress = listOf("172.16.0.2/32"),
                    reserved = listOf(1, 2),
                ),
            )

            assertThat(skipReasonOf(bad)).contains("reserved")
        }

        /** 内核里是显式互斥的：`listen_port` is conflict with `detour`。 */
        @Test
        fun `listen_port 与链式代理互斥`() {
            val bad = Fixtures.wireGuard(
                params = ProtocolParams.WireGuard(
                    privateKey = Fixtures.WG_PRIVATE_KEY,
                    peerPublicKey = Fixtures.WG_PEER_PUBLIC_KEY,
                    localAddress = listOf("172.16.0.2/32"),
                    listenPort = 51820,
                ),
            ).copy(detour = WellKnownTag.DIRECT)

            assertThat(skipReasonOf(bad)).contains("互斥")
        }

        /**
         * WireGuard 的 option 结构体没有内嵌 TLS 容器。给它写一个 `tls` 对象
         * 不是被忽略，而是解析阶段的未知字段，整份配置作废。
         */
        @Test
        fun `带上 TLS 配置时该节点不可用`() {
            val bad = Fixtures.wireGuard()
                .copy(tls = com.niceproxy.core.model.TlsConfig(enabled = true))

            assertThat(skipReasonOf(bad)).contains("tls")
        }
    }

    @Nested
    @DisplayName("链式代理 detour")
    inner class Detour {

        @Test
        fun `写出指向另一个节点的 detour`() {
            val relay = Fixtures.vmessWs("relay")
            val exit = Fixtures.hysteria2("exit").copy(detour = relay.outboundTag)
            val root = buildOk(Fixtures.input(nodes = listOf(relay, exit)))

            assertThat(root.outbound(exit.outboundTag)["detour"]!!.jsonPrimitive.content)
                .isEqualTo(relay.outboundTag)
            // 中转节点自己不该多出一个 detour
            assertThat(root.outbound(relay.outboundTag)).doesNotContainKey("detour")
        }

        @Test
        fun `WireGuard endpoint 也能挂 detour`() {
            val relay = Fixtures.vmessWs("relay")
            val wg = Fixtures.wireGuard("wg").copy(detour = relay.outboundTag)
            val root = buildOk(Fixtures.input(nodes = listOf(relay, wg)))

            assertThat(root.endpoints().single()["detour"]!!.jsonPrimitive.content)
                .isEqualTo(relay.outboundTag)
        }

        @Test
        fun `detour 指向 direct 是允许的`() {
            val node = Fixtures.hysteria2("h").copy(detour = WellKnownTag.DIRECT)
            val root = buildOk(Fixtures.input(nodes = listOf(node)))

            assertThat(root.outbound(node.outboundTag)["detour"]!!.jsonPrimitive.content)
                .isEqualTo(WellKnownTag.DIRECT)
        }

        @Test
        fun `自指的节点不可用`() {
            val bad = Fixtures.hysteria2("self")
            assertThat(skipReasonOf(bad.copy(detour = bad.outboundTag))).contains("自己")
        }

        /**
         * `proxy` / `auto` 的候选里就包含本节点，指过去等于绕了个圈。
         * 这种环在内核里是懒解析的，装配阶段察觉不到，跑起来第一次拨号才炸。
         */
        @Test
        fun `detour 指向策略组的节点不可用`() {
            val bad = Fixtures.hysteria2("g").copy(detour = WellKnownTag.PROXY)

            assertThat(skipReasonOf(bad)).contains("策略组")
        }

        @Test
        fun `成环的一整条链都被剔除`() {
            val a = Fixtures.hysteria2("a")
            val b = Fixtures.vmessWs("b")
            val c = Fixtures.shadowsocks("c")
            val healthy = Fixtures.vlessReality("ok")
            val cycle = listOf(
                a.copy(detour = b.outboundTag),
                b.copy(detour = c.outboundTag),
                c.copy(detour = a.outboundTag),
                healthy,
            )

            val result = builder.build(Fixtures.input(nodes = cycle)) as ConfigResult.Success
            val root = Json.parseToJsonElement(result.json).jsonObject
            val tags = root["outbounds"]!!.jsonArray
                .mapNotNull { it.jsonObject["tag"]?.jsonPrimitive?.contentOrNull }

            assertThat(tags).contains(healthy.outboundTag)
            assertThat(tags).containsNoneOf(a.outboundTag, b.outboundTag, c.outboundTag)
            assertThat(result.warnings.filterIsInstance<ConfigError.InvalidNode>()).hasSize(3)
            assertThat(result.warnings.first().message).contains("成环")
        }

        /**
         * 指向一个不存在的 tag 会让内核拒绝整份配置。
         * 剔除这个节点而不是「忽略 detour 直接出站」—— 用户配链式代理通常是
         * 因为落地机只接受中转机的 IP，直连过去要么连不上，要么在对端留下
         * 一条本不该出现的记录。
         */
        @Test
        fun `指向不存在的节点时该节点被剔除`() {
            val bad = Fixtures.hysteria2("orphan").copy(detour = "node-已删除")

            assertThat(skipReasonOf(bad)).contains("不存在")
        }

        /** 中转机自己没能生成出来，挂在它后面的节点也一起悬空。 */
        @Test
        fun `中转节点不可用时挂在它后面的节点一并剔除`() {
            val brokenRelay = Fixtures.hysteria2("relay", obfsType = "salamander", obfsPassword = null)
            val exit = Fixtures.vmessWs("exit").copy(detour = brokenRelay.outboundTag)
            val healthy = Fixtures.vlessReality("ok")

            val result = builder.build(
                Fixtures.input(nodes = listOf(brokenRelay, exit, healthy)),
            ) as ConfigResult.Success
            val root = Json.parseToJsonElement(result.json).jsonObject
            val tags = root["outbounds"]!!.jsonArray
                .mapNotNull { it.jsonObject["tag"]?.jsonPrimitive?.contentOrNull }

            assertThat(tags).contains(healthy.outboundTag)
            assertThat(tags).containsNoneOf(brokenRelay.outboundTag, exit.outboundTag)
        }

        /** 多级链路要跑到不动点：剔掉 A 之后，指着 A 的 B 也跟着悬空。 */
        @Test
        fun `悬空沿着链路向后传播`() {
            val a = Fixtures.hysteria2("a").copy(detour = "node-不存在")
            val b = Fixtures.vmessWs("b").copy(detour = a.outboundTag)
            val c = Fixtures.shadowsocks("c").copy(detour = b.outboundTag)
            val healthy = Fixtures.vlessReality("ok")

            val result = builder.build(
                Fixtures.input(nodes = listOf(a, b, c, healthy)),
            ) as ConfigResult.Success
            val tags = Json.parseToJsonElement(result.json).jsonObject["outbounds"]!!.jsonArray
                .mapNotNull { it.jsonObject["tag"]?.jsonPrimitive?.contentOrNull }

            assertThat(tags).containsNoneOf(a.outboundTag, b.outboundTag, c.outboundTag)
            assertThat(tags).contains(healthy.outboundTag)
        }

        /**
         * 链路断掉之后一个节点都不剩，就不能悄悄生成一份「全直连」的配置。
         * 那份配置完全合法、内核照常加载、用户照常上网，而 100% 流量在裸奔。
         */
        @Test
        fun `链路全断且无其他节点时 fail-closed`() {
            val a = Fixtures.hysteria2("a")
            val b = Fixtures.vmessWs("b")
            val result = builder.build(
                Fixtures.input(
                    nodes = listOf(
                        a.copy(detour = b.outboundTag),
                        b.copy(detour = a.outboundTag),
                    ),
                ),
            )

            assertThat(result).isInstanceOf(ConfigResult.Failure::class.java)
            assertThat((result as ConfigResult.Failure).errors.map { it::class })
                .contains(ConfigError.NoUsableNode::class)
        }

        @Test
        fun `没有 detour 的配置不写出这个字段`() {
            val node = Fixtures.hysteria2()
            val root = buildOk(Fixtures.input(nodes = listOf(node)))

            assertThat(root.outbound(node.outboundTag)).doesNotContainKey("detour")
        }
    }
}

private fun JsonObject.endpoints(): List<JsonObject> =
    (this["endpoints"] as? JsonArray)?.map { it.jsonObject }.orEmpty()

private fun JsonObject.outbound(tag: String): JsonObject =
    this["outbounds"]!!.jsonArray
        .map { it.jsonObject }
        .single { it["tag"]!!.jsonPrimitive.content == tag }
