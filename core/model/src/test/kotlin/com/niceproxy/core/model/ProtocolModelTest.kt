package com.niceproxy.core.model

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * 模型层的两类约束：
 *
 * 1. **向后兼容**。备份文件与数据库里的 `params_json` 都是历史某个版本序列化出来的，
 *    新增字段必须能从缺这些字段的旧 JSON 里读出来。读不出来的后果不是「少一个字段」，
 *    而是整条记录反序列化抛异常、节点凭空消失。
 * 2. **协议能力表**。`requiresTls` / `supportsTls` / `isEndpoint` 这几个开关直接决定
 *    配置生成器写不写某个字段，而写错一个字段 sing-box 就拒绝整份配置。
 */
class ProtocolModelTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Nested
    @DisplayName("序列化兼容")
    inner class Compatibility {

        /** 旧备份里的节点没有 `detour`，也没有任何新增的协议字段。 */
        private val legacyJson = """
            {
              "id": "n1",
              "groupId": "g1",
              "name": "旧节点",
              "protocol": "trojan",
              "server": "old.example.com",
              "serverPort": 443,
              "params": { "type": "trojan", "password": "pw" },
              "tls": { "enabled": true, "serverName": "old.example.com" },
              "createdAt": 1,
              "updatedAt": 2
            }
        """.trimIndent()

        @Test
        fun `没有 detour 字段的旧节点仍能读出来`() {
            val node = json.decodeFromString(ServerProfile.serializer(), legacyJson)

            assertThat(node.detour).isNull()
            assertThat(node.protocol).isEqualTo(ProxyProtocol.TROJAN)
            assertThat(node.outboundTag).isEqualTo("node-n1")
        }

        @Test
        fun `旧的 hysteria 参数缺少新增字段也能读出来`() {
            val legacy = """
                {"type":"hysteria","authString":"pw","up":"100 Mbps","down":"100 Mbps"}
            """.trimIndent()

            val params = json.decodeFromString(ProtocolParams.serializer(), legacy)
                as ProtocolParams.Hysteria

            assertThat(params.authBase64).isNull()
            assertThat(params.serverPorts).isEmpty()
            assertThat(params.hopInterval).isNull()
        }

        @Test
        fun `旧的 ssh 参数缺少 host_key 也能读出来`() {
            val legacy = """{"type":"ssh","user":"root","password":"pw"}"""

            val params = json.decodeFromString(ProtocolParams.serializer(), legacy)
                as ProtocolParams.Ssh

            assertThat(params.hostKey).isEmpty()
            assertThat(params.clientVersion).isNull()
        }

        @Test
        fun `WireGuard 节点能完整往返`() {
            val node = ServerProfile(
                id = "wg1",
                groupId = "g1",
                name = "WARP",
                protocol = ProxyProtocol.WIREGUARD,
                server = "engage.cloudflareclient.com",
                serverPort = 2408,
                params = ProtocolParams.WireGuard(
                    privateKey = "bmljZS1wcm94eS1wcml2YXRlLWtleS1maXh0dXJlLTE=",
                    peerPublicKey = "bmljZS1wcm94eS1wZWVyLXB1YmxpYy1rZXktZml4LTI=",
                    localAddress = listOf("172.16.0.2/32"),
                    reserved = listOf(1, 2, 3),
                    mtu = 1408,
                ),
                detour = "node-relay",
            )

            val restored = json.decodeFromString(
                ServerProfile.serializer(),
                json.encodeToString(ServerProfile.serializer(), node),
            )

            assertThat(restored).isEqualTo(node)
        }

        /**
         * `credentialState` 是读库时派生的运行时状态，带进备份没有意义 ——
         * 恢复到新设备时凭据会用那台设备的密钥重新加密，状态必然是 OK。
         */
        @Test
        fun `credentialState 不进序列化结果`() {
            val node = ServerProfile(
                id = "n1",
                groupId = "g1",
                name = "n",
                protocol = ProxyProtocol.TROJAN,
                server = "a.com",
                serverPort = 443,
                params = ProtocolParams.Trojan(password = "pw"),
                credentialState = CredentialState.UNREADABLE,
            )

            assertThat(json.encodeToString(ServerProfile.serializer(), node))
                .doesNotContain("credentialState")
        }
    }

    @Nested
    @DisplayName("协议能力表")
    inner class Capabilities {

        /**
         * 只有 sing-box 的 option 结构体内嵌了 TLS 容器的协议才有 `tls` 字段。
         * 多写一个 `tls` 对象不是被忽略，而是解析阶段的未知字段，整份配置作废。
         */
        @Test
        fun `强制 TLS 的协议一定支持 TLS`() {
            ProxyProtocol.entries.filter { it.requiresTls }.forEach {
                assertThat(it.supportsTls).isTrue()
            }
        }

        @Test
        fun `没有 tls 字段的协议列表与 sing-box 一致`() {
            val withoutTls = ProxyProtocol.entries.filterNot { it.supportsTls }

            assertThat(withoutTls).containsExactly(
                ProxyProtocol.DIRECT,
                ProxyProtocol.SOCKS,
                ProxyProtocol.SHADOWSOCKS,
                ProxyProtocol.SSH,
                ProxyProtocol.WIREGUARD,
            )
        }

        /** QUIC 自带传输层，再叠一层 v2ray transport 会被内核判为非法出站。 */
        @Test
        fun `QUIC 系协议不支持传输层`() {
            ProxyProtocol.entries.filter { it.isQuicBased }.forEach {
                assertThat(it.supportsTransport).isFalse()
            }
        }

        /** 1.13 移除了 wireguard outbound，只剩 endpoint 一种写法。 */
        @Test
        fun `只有 WireGuard 是 endpoint`() {
            assertThat(ProxyProtocol.entries.filter { it.isEndpoint })
                .containsExactly(ProxyProtocol.WIREGUARD)
        }

        @Test
        fun `singBoxType 唯一且可反查`() {
            val types = ProxyProtocol.entries.map { it.singBoxType }

            assertThat(types).containsNoDuplicates()
            ProxyProtocol.entries.forEach {
                assertThat(ProxyProtocol.fromSingBoxType(it.singBoxType)).isEqualTo(it)
            }
        }
    }
}
