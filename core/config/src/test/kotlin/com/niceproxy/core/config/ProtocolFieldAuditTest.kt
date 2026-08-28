package com.niceproxy.core.config

import com.google.common.truth.Truth.assertThat
import com.niceproxy.core.model.BrutalConfig
import com.niceproxy.core.model.MultiplexConfig
import com.niceproxy.core.model.ProtocolParams
import com.niceproxy.core.model.ProxyProtocol
import com.niceproxy.core.model.ServerProfile
import com.niceproxy.core.model.TlsConfig
import com.niceproxy.core.model.TransportConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * 逐协议对照 sing-box 1.13 的 option 结构体。
 *
 * 两个方向都要守：**该写的字段没写**，用户在界面上配的东西静默失效；
 * **不该写的字段写了**，内核用的是 `DisallowUnknownFields` 的解码器，
 * 多一个字段不是被忽略，而是拒绝**整份**配置 —— 一个节点的笔误让所有节点一起失效。
 *
 * 取值同理：`congestion_control: "bbr2"`、`hop_interval: "30秒"` 这类东西
 * 界面上看不出问题，内核却会在读配置的第一步就罢工。这些一律要在生成侧
 * 退化成「这一个节点不可用」。
 */
class ProtocolFieldAuditTest {

    private val builder = SingBoxConfigBuilder()

    /** 单节点生成结果里那一个 outbound。 */
    private fun outboundOf(node: ServerProfile): JsonObject {
        val result = builder.build(Fixtures.input(nodes = listOf(node)))
        assertTrue(result is ConfigResult.Success) { "构建失败：$result" }
        return Json.parseToJsonElement((result as ConfigResult.Success).json)
            .jsonObject["outbounds"]!!
            .jsonArray
            .map { it.jsonObject }
            .single { it["tag"]!!.jsonPrimitive.content == node.outboundTag }
    }

    /** 单个坏节点的跳过理由。配个好节点，避免落到「无可用节点」的硬失败。 */
    private fun skipReasonOf(bad: ServerProfile): String {
        val result = builder.build(Fixtures.input(nodes = listOf(bad, Fixtures.vmessWs())))
        assertTrue(result is ConfigResult.Success) { "本应只跳过坏节点：$result" }
        return (result as ConfigResult.Success).warnings
            .filterIsInstance<ConfigError.InvalidNode>()
            .single { it.nodeId == bad.id }
            .reason
    }

    private fun node(
        protocol: ProxyProtocol,
        params: ProtocolParams,
        tls: TlsConfig? = null,
        transport: TransportConfig? = null,
        multiplex: MultiplexConfig? = null,
        id: String = "n",
    ) = ServerProfile(
        id = id,
        groupId = "g1",
        name = "测试节点",
        protocol = protocol,
        server = "node.example.com",
        serverPort = 443,
        params = params,
        transport = transport,
        tls = tls,
        multiplex = multiplex,
    )

    private val tlsOn = TlsConfig(enabled = true, serverName = "node.example.com")

    @Nested
    @DisplayName("Hysteria v1")
    inner class HysteriaV1 {

        private fun hysteria(params: ProtocolParams.Hysteria, id: String = "hy") =
            node(ProxyProtocol.HYSTERIA, params, tls = tlsOn, id = id)

        @Test
        fun `带单位的带宽、混淆与端口跳跃都写进配置`() {
            val out = outboundOf(
                hysteria(
                    ProtocolParams.Hysteria(
                        authString = "auth-pw",
                        up = "100 Mbps",
                        down = "300 Mbps",
                        obfs = "obfs-pw",
                        serverPorts = listOf("20000:30000"),
                        hopInterval = "30s",
                        disableMtuDiscovery = true,
                    ),
                ),
            )

            assertThat(out["type"]!!.jsonPrimitive.content).isEqualTo("hysteria")
            assertThat(out["up"]!!.jsonPrimitive.content).isEqualTo("100 Mbps")
            assertThat(out["down"]!!.jsonPrimitive.content).isEqualTo("300 Mbps")
            assertThat(out["auth_str"]!!.jsonPrimitive.content).isEqualTo("auth-pw")
            assertThat(out["obfs"]!!.jsonPrimitive.content).isEqualTo("obfs-pw")
            assertThat(out["server_ports"]!!.jsonArray.map { it.jsonPrimitive.content })
                .containsExactly("20000:30000")
            assertThat(out["hop_interval"]!!.jsonPrimitive.content).isEqualTo("30s")
            assertThat(out["disable_mtu_discovery"]!!.jsonPrimitive.content).isEqualTo("true")
        }

        @Test
        fun `纯数字带宽走 up_mbps 与 down_mbps`() {
            val out = outboundOf(
                hysteria(ProtocolParams.Hysteria(authString = "a", upMbps = 50, downMbps = 200)),
            )

            assertThat(out["up_mbps"]!!.jsonPrimitive.content).isEqualTo("50")
            assertThat(out["down_mbps"]!!.jsonPrimitive.content).isEqualTo("200")
            assertThat(out.keys).containsNoneOf("up", "down")
        }

        /** v1 没有 BBR 自适应，带宽是握手的一部分，缺了内核直接报错。 */
        @Test
        fun `缺少带宽时该节点不可用`() {
            val reason = skipReasonOf(hysteria(ProtocolParams.Hysteria(authString = "a"), "bad"))

            assertThat(reason).contains("带宽")
        }

        @Test
        fun `缺少认证串时该节点不可用`() {
            val reason = skipReasonOf(
                hysteria(ProtocolParams.Hysteria(upMbps = 10, downMbps = 10), "bad"),
            )

            assertThat(reason).contains("认证串")
        }

        @Test
        fun `Base64 认证串写成 auth 而不是 auth_str`() {
            val out = outboundOf(
                hysteria(
                    ProtocolParams.Hysteria(authBase64 = "cHc=", upMbps = 10, downMbps = 10),
                ),
            )

            assertThat(out["auth"]!!.jsonPrimitive.content).isEqualTo("cHc=")
            assertThat(out).doesNotContainKey("auth_str")
        }

        @Test
        fun `未指定 ALPN 时补上 h3`() {
            val out = outboundOf(
                hysteria(ProtocolParams.Hysteria(authString = "a", upMbps = 10, downMbps = 10)),
            )

            assertThat(out["tls"]!!.jsonObject["alpn"]!!.jsonArray.map { it.jsonPrimitive.content })
                .containsExactly("h3")
        }
    }

    @Nested
    @DisplayName("Hysteria2")
    inner class HysteriaV2 {

        @Test
        fun `端口跳跃与 brutal 调试开关`() {
            val out = outboundOf(
                node(
                    ProxyProtocol.HYSTERIA2,
                    ProtocolParams.Hysteria2(
                        password = "pw",
                        serverPorts = listOf("443", "20000:30000"),
                        hopInterval = "1m",
                        brutalDebug = true,
                    ),
                    tls = tlsOn,
                ),
            )

            assertThat(out["server_ports"]!!.jsonArray.map { it.jsonPrimitive.content })
                .containsExactly("443", "20000:30000")
            assertThat(out["hop_interval"]!!.jsonPrimitive.content).isEqualTo("1m")
            assertThat(out["brutal_debug"]!!.jsonPrimitive.content).isEqualTo("true")
        }

        /** 1.13 只实现了 salamander，gecko 要等到 1.14。 */
        @Test
        fun `未知的混淆方式让该节点不可用`() {
            val reason = skipReasonOf(
                node(
                    ProxyProtocol.HYSTERIA2,
                    ProtocolParams.Hysteria2(password = "pw", obfsType = "gecko", obfsPassword = "x"),
                    tls = tlsOn,
                    id = "bad",
                ),
            )

            assertThat(reason).contains("混淆")
        }

        @Test
        fun `跳跃间隔不是合法时长时该节点不可用`() {
            val reason = skipReasonOf(
                node(
                    ProxyProtocol.HYSTERIA2,
                    ProtocolParams.Hysteria2(
                        password = "pw",
                        serverPorts = listOf("20000:30000"),
                        hopInterval = "30秒",
                    ),
                    tls = tlsOn,
                    id = "bad",
                ),
            )

            assertThat(reason).contains("时长")
        }

        /** 起点大于终点的范围内核直接拒绝。 */
        @Test
        fun `倒置的端口范围让该节点不可用`() {
            val reason = skipReasonOf(
                node(
                    ProxyProtocol.HYSTERIA2,
                    ProtocolParams.Hysteria2(password = "pw", serverPorts = listOf("30000:20000")),
                    tls = tlsOn,
                    id = "bad",
                ),
            )

            assertThat(reason).contains("端口跳跃")
        }
    }

    @Nested
    @DisplayName("TUIC")
    inner class Tuic {

        private val uuid = "11111111-2222-3333-4444-555555555555"

        private fun tuic(params: ProtocolParams.Tuic, id: String = "tuic") =
            node(ProxyProtocol.TUIC, params, tls = tlsOn, id = id)

        @Test
        fun `拥塞控制、零 RTT 与心跳都写进配置`() {
            val out = outboundOf(
                tuic(
                    ProtocolParams.Tuic(
                        uuid = uuid,
                        password = "pw",
                        congestionControl = "bbr",
                        udpRelayMode = "quic",
                        zeroRttHandshake = true,
                        heartbeat = "10s",
                    ),
                ),
            )

            assertThat(out["uuid"]!!.jsonPrimitive.content).isEqualTo(uuid)
            assertThat(out["congestion_control"]!!.jsonPrimitive.content).isEqualTo("bbr")
            assertThat(out["udp_relay_mode"]!!.jsonPrimitive.content).isEqualTo("quic")
            assertThat(out["zero_rtt_handshake"]!!.jsonPrimitive.content).isEqualTo("true")
            assertThat(out["heartbeat"]!!.jsonPrimitive.content).isEqualTo("10s")
        }

        /**
         * 内核里这两个字段是显式互斥的
         * （`udp_over_stream is conflict with udp_relay_mode`），
         * 而 `udp_relay_mode` 在模型里有默认值，一起写必然撞上。
         */
        @Test
        fun `启用 udp_over_stream 时不写 udp_relay_mode`() {
            val out = outboundOf(
                tuic(ProtocolParams.Tuic(uuid = uuid, password = "pw", udpOverStream = true)),
            )

            assertThat(out["udp_over_stream"]!!.jsonPrimitive.content).isEqualTo("true")
            assertThat(out).doesNotContainKey("udp_relay_mode")
        }

        /** TUIC 是唯一一个严格解析 UUID 的协议，解析失败即报错。 */
        @Test
        fun `UUID 格式非法时该节点不可用`() {
            val reason = skipReasonOf(
                tuic(ProtocolParams.Tuic(uuid = "not-a-uuid", password = "pw"), "bad"),
            )

            assertThat(reason).contains("UUID")
        }

        @Test
        fun `未知的拥塞控制让该节点不可用`() {
            val reason = skipReasonOf(
                tuic(
                    ProtocolParams.Tuic(uuid = uuid, password = "pw", congestionControl = "bbr2"),
                    "bad",
                ),
            )

            assertThat(reason).contains("拥塞控制")
        }
    }

    @Nested
    @DisplayName("VMess / VLESS")
    inner class VmessVless {

        private val uuid = "11111111-2222-3333-4444-555555555555"

        @Test
        fun `VMess 的 packet_encoding 与 AEAD 开关`() {
            val out = outboundOf(
                node(
                    ProxyProtocol.VMESS,
                    ProtocolParams.VMess(
                        uuid = uuid,
                        security = "aes-128-gcm",
                        alterId = 0,
                        globalPadding = true,
                        authenticatedLength = true,
                        packetEncoding = "xudp",
                    ),
                ),
            )

            assertThat(out["security"]!!.jsonPrimitive.content).isEqualTo("aes-128-gcm")
            assertThat(out["global_padding"]!!.jsonPrimitive.content).isEqualTo("true")
            assertThat(out["authenticated_length"]!!.jsonPrimitive.content).isEqualTo("true")
            assertThat(out["packet_encoding"]!!.jsonPrimitive.content).isEqualTo("xudp")
            // alter_id 为 0 是 AEAD 模式，写出来是多余的
            assertThat(out).doesNotContainKey("alter_id")
        }

        @Test
        fun `未知的 packet_encoding 让该节点不可用`() {
            val reason = skipReasonOf(
                node(
                    ProxyProtocol.VLESS,
                    ProtocolParams.VLess(uuid = uuid, packetEncoding = "xudp2"),
                    id = "bad",
                ),
            )

            assertThat(reason).contains("packet_encoding")
        }

        @Test
        fun `未知的 VMess 加密方式让该节点不可用`() {
            val reason = skipReasonOf(
                node(ProxyProtocol.VMESS, ProtocolParams.VMess(uuid = uuid, security = "rc4"), id = "bad"),
            )

            assertThat(reason).contains("加密方式")
        }

        /**
         * Vision 是在 TLS 记录层之上做的，没有 TLS 就没有可以「看穿」的东西。
         * 服务端会在握手阶段直接断开，表现是「节点显示已连接但一个字节都过不去」。
         */
        @Test
        fun `Vision 流控缺少 TLS 时该节点不可用`() {
            val reason = skipReasonOf(
                node(
                    ProxyProtocol.VLESS,
                    ProtocolParams.VLess(uuid = uuid, flow = "xtls-rprx-vision"),
                    id = "bad",
                ),
            )

            assertThat(reason).contains("TLS")
        }

        @Test
        fun `未知的 flow 让该节点不可用`() {
            val reason = skipReasonOf(
                node(
                    ProxyProtocol.VLESS,
                    ProtocolParams.VLess(uuid = uuid, flow = "xtls-rprx-direct"),
                    tls = tlsOn,
                    id = "bad",
                ),
            )

            assertThat(reason).contains("flow")
        }
    }

    @Nested
    @DisplayName("AnyTLS / ShadowTLS / SSH")
    inner class MiscProtocols {

        @Test
        fun `AnyTLS 的空闲会话参数`() {
            val out = outboundOf(
                node(
                    ProxyProtocol.ANYTLS,
                    ProtocolParams.AnyTls(
                        password = "pw",
                        idleSessionCheckInterval = "30s",
                        idleSessionTimeout = "5m",
                        minIdleSession = 2,
                    ),
                    tls = tlsOn,
                ),
            )

            assertThat(out["idle_session_check_interval"]!!.jsonPrimitive.content).isEqualTo("30s")
            assertThat(out["idle_session_timeout"]!!.jsonPrimitive.content).isEqualTo("5m")
            assertThat(out["min_idle_session"]!!.jsonPrimitive.content).isEqualTo("2")
        }

        @Test
        fun `AnyTLS 的时长写错时该节点不可用`() {
            val reason = skipReasonOf(
                node(
                    ProxyProtocol.ANYTLS,
                    ProtocolParams.AnyTls(password = "pw", idleSessionTimeout = "5 分钟"),
                    tls = tlsOn,
                    id = "bad",
                ),
            )

            assertThat(reason).contains("时长")
        }

        @Test
        fun `ShadowTLS v3 写出版本与密码`() {
            val out = outboundOf(
                node(
                    ProxyProtocol.SHADOWTLS,
                    ProtocolParams.ShadowTls(version = 3, password = "pw"),
                    tls = TlsConfig(enabled = true, serverName = "www.microsoft.com"),
                ),
            )

            assertThat(out["version"]!!.jsonPrimitive.content).isEqualTo("3")
            assertThat(out["password"]!!.jsonPrimitive.content).isEqualTo("pw")
        }

        /** v2 / v3 靠这个密码做 HMAC，缺了必然握手失败。 */
        @Test
        fun `ShadowTLS v3 缺少密码时该节点不可用`() {
            val reason = skipReasonOf(
                node(
                    ProxyProtocol.SHADOWTLS,
                    ProtocolParams.ShadowTls(version = 3, password = null),
                    tls = tlsOn,
                    id = "bad",
                ),
            )

            assertThat(reason).contains("密码")
        }

        @Test
        fun `ShadowTLS 未知版本让该节点不可用`() {
            val reason = skipReasonOf(
                node(
                    ProxyProtocol.SHADOWTLS,
                    ProtocolParams.ShadowTls(version = 4, password = "pw"),
                    tls = tlsOn,
                    id = "bad",
                ),
            )

            assertThat(reason).contains("版本")
        }

        /** 整个协议就是伪装成一次真实的 TLS 握手，没有 TLS 无从谈起。 */
        @Test
        fun `ShadowTLS 缺少 TLS 时该节点不可用`() {
            val reason = skipReasonOf(
                node(
                    ProxyProtocol.SHADOWTLS,
                    ProtocolParams.ShadowTls(version = 3, password = "pw"),
                    id = "bad",
                ),
            )

            assertThat(reason).contains("TLS")
        }

        @Test
        fun `SSH 写出主机公钥、算法与客户端版本`() {
            val out = outboundOf(
                node(
                    ProxyProtocol.SSH,
                    ProtocolParams.Ssh(
                        user = "root",
                        password = "pw",
                        hostKey = listOf("ssh-ed25519 AAAAC3Nz"),
                        hostKeyAlgorithms = listOf("ssh-ed25519"),
                        clientVersion = "SSH-2.0-OpenSSH_9.6",
                    ),
                ),
            )

            assertThat(out["user"]!!.jsonPrimitive.content).isEqualTo("root")
            assertThat(out["host_key"]!!.jsonArray).hasSize(1)
            assertThat(out["host_key_algorithms"]!!.jsonArray).hasSize(1)
            assertThat(out["client_version"]!!.jsonPrimitive.content)
                .isEqualTo("SSH-2.0-OpenSSH_9.6")
        }

        @Test
        fun `SSH 既没有密码也没有私钥时该节点不可用`() {
            val reason = skipReasonOf(
                node(ProxyProtocol.SSH, ProtocolParams.Ssh(user = "root"), id = "bad"),
            )

            assertThat(reason).contains("私钥")
        }

        /**
         * SSH 的 option 结构体没有内嵌 TLS 容器 —— 机场的 Clash 模板给
         * 非 TLS 协议顺手写上 `tls: true` 是常事，照搬过来整份配置就作废了。
         */
        @Test
        fun `SSH 带 TLS 配置时该节点不可用`() {
            val reason = skipReasonOf(
                node(
                    ProxyProtocol.SSH,
                    ProtocolParams.Ssh(user = "root", password = "pw"),
                    tls = tlsOn,
                    id = "bad",
                ),
            )

            assertThat(reason).contains("tls")
        }
    }

    @Nested
    @DisplayName("传输层")
    inner class Transports {

        private val uuid = "11111111-2222-3333-4444-555555555555"

        private fun vless(transport: TransportConfig, tls: TlsConfig? = null, id: String = "t") =
            node(ProxyProtocol.VLESS, ProtocolParams.VLess(uuid = uuid), tls, transport, id = id)

        @Test
        fun `WebSocket 的早期数据字段`() {
            val out = outboundOf(
                vless(
                    TransportConfig.WebSocket(
                        path = "/ws",
                        headers = mapOf("Host" to "cdn.example.com"),
                        maxEarlyData = 2048,
                        earlyDataHeaderName = "Sec-WebSocket-Protocol",
                    ),
                ),
            )
            val t = out["transport"]!!.jsonObject

            assertThat(t["type"]!!.jsonPrimitive.content).isEqualTo("ws")
            assertThat(t["max_early_data"]!!.jsonPrimitive.content).isEqualTo("2048")
            assertThat(t["early_data_header_name"]!!.jsonPrimitive.content)
                .isEqualTo("Sec-WebSocket-Protocol")
            assertThat(t["headers"]!!.jsonObject["Host"]!!.jsonPrimitive.content)
                .isEqualTo("cdn.example.com")
        }

        @Test
        fun `gRPC 的空闲与心跳超时`() {
            val out = outboundOf(
                vless(
                    TransportConfig.Grpc(
                        serviceName = "GunService",
                        idleTimeout = "15s",
                        pingTimeout = "15s",
                        permitWithoutStream = true,
                    ),
                ),
            )
            val t = out["transport"]!!.jsonObject

            assertThat(t["service_name"]!!.jsonPrimitive.content).isEqualTo("GunService")
            assertThat(t["idle_timeout"]!!.jsonPrimitive.content).isEqualTo("15s")
            assertThat(t["ping_timeout"]!!.jsonPrimitive.content).isEqualTo("15s")
            assertThat(t["permit_without_stream"]!!.jsonPrimitive.content).isEqualTo("true")
        }

        @Test
        fun `HTTP 传输层的 host 数组与方法`() {
            val out = outboundOf(
                vless(
                    TransportConfig.Http(
                        host = listOf("a.example.com", "b.example.com"),
                        path = "/h2",
                        method = "PUT",
                        headers = mapOf("X-Real-IP" to "1.2.3.4"),
                    ),
                ),
            )
            val t = out["transport"]!!.jsonObject

            assertThat(t["host"]!!.jsonArray).hasSize(2)
            assertThat(t["method"]!!.jsonPrimitive.content).isEqualTo("PUT")
            assertThat(t["headers"]!!.jsonObject).hasSize(1)
        }

        @Test
        fun `httpupgrade 的 host 与 path`() {
            val out = outboundOf(
                vless(TransportConfig.HttpUpgrade(host = "cdn.example.com", path = "/up")),
            )
            val t = out["transport"]!!.jsonObject

            assertThat(t["type"]!!.jsonPrimitive.content).isEqualTo("httpupgrade")
            assertThat(t["host"]!!.jsonPrimitive.content).isEqualTo("cdn.example.com")
            assertThat(t["path"]!!.jsonPrimitive.content).isEqualTo("/up")
        }

        /** QUIC 传输层跑在 TLS 之上，没有 TLS 内核报 ErrTLSRequired。 */
        @Test
        fun `QUIC 传输层缺少 TLS 时该节点不可用`() {
            val reason = skipReasonOf(vless(TransportConfig.Quic, id = "bad"))

            assertThat(reason).contains("QUIC")
        }

        @Test
        fun `gRPC 的超时写错时该节点不可用`() {
            val reason = skipReasonOf(
                vless(TransportConfig.Grpc(serviceName = "s", idleTimeout = "15 秒"), id = "bad"),
            )

            assertThat(reason).contains("时长")
        }
    }

    @Nested
    @DisplayName("多路复用")
    inner class Multiplex {

        private fun withMux(mux: MultiplexConfig, id: String = "m") = node(
            ProxyProtocol.TROJAN,
            ProtocolParams.Trojan(password = "pw"),
            tls = tlsOn,
            multiplex = mux,
            id = id,
        )

        @Test
        fun `yamux 与 brutal 一起写出`() {
            val out = outboundOf(
                withMux(
                    MultiplexConfig(
                        enabled = true,
                        protocol = "yamux",
                        maxConnections = 4,
                        minStreams = 4,
                        padding = true,
                        brutal = BrutalConfig(upMbps = 50, downMbps = 100),
                    ),
                ),
            )
            val mux = out["multiplex"]!!.jsonObject

            assertThat(mux["protocol"]!!.jsonPrimitive.content).isEqualTo("yamux")
            assertThat(mux["max_connections"]!!.jsonPrimitive.content).isEqualTo("4")
            assertThat(mux["min_streams"]!!.jsonPrimitive.content).isEqualTo("4")
            assertThat(mux["padding"]!!.jsonPrimitive.content).isEqualTo("true")
            assertThat(mux["brutal"]!!.jsonObject["up_mbps"]!!.jsonPrimitive.content).isEqualTo("50")
        }

        @Test
        fun `smux 与 h2mux 都被接受`() {
            listOf("smux", "h2mux").forEach { protocol ->
                val out = outboundOf(
                    withMux(MultiplexConfig(enabled = true, protocol = protocol), id = protocol),
                )

                assertThat(out["multiplex"]!!.jsonObject["protocol"]!!.jsonPrimitive.content)
                    .isEqualTo(protocol)
            }
        }

        @Test
        fun `未知的多路复用协议让该节点不可用`() {
            val reason = skipReasonOf(
                withMux(MultiplexConfig(enabled = true, protocol = "mux.cool"), "bad"),
            )

            assertThat(reason).contains("多路复用")
        }

        /**
         * sing-mux 对低于 64 KB/s 的带宽直接报 `brutal: invalid upload speed`，
         * 而 1 Mbps = 125 KB/s，按 Mbps 算就是「必须 ≥ 1」。
         */
        @Test
        fun `brutal 带宽为零时该节点不可用`() {
            val reason = skipReasonOf(
                withMux(
                    MultiplexConfig(
                        enabled = true,
                        protocol = "yamux",
                        brutal = BrutalConfig(upMbps = 0, downMbps = 0),
                    ),
                    "bad",
                ),
            )

            assertThat(reason).contains("Brutal")
        }
    }

    @Nested
    @DisplayName("SOCKS / HTTP")
    inner class Basic {

        @Test
        fun `SOCKS 写出版本与 udp_over_tcp`() {
            val out = outboundOf(
                node(
                    ProxyProtocol.SOCKS,
                    ProtocolParams.Socks(version = "4a", username = "u", udpOverTcp = true),
                ),
            )

            assertThat(out["version"]!!.jsonPrimitive.content).isEqualTo("4a")
            assertThat(out["udp_over_tcp"]!!.jsonPrimitive.content).isEqualTo("true")
        }

        @Test
        fun `未知的 SOCKS 版本让该节点不可用`() {
            val reason = skipReasonOf(
                node(ProxyProtocol.SOCKS, ProtocolParams.Socks(version = "6"), id = "bad"),
            )

            assertThat(reason).contains("SOCKS")
        }

        @Test
        fun `HTTP 写出 path 与自定义请求头`() {
            val out = outboundOf(
                node(
                    ProxyProtocol.HTTP,
                    ProtocolParams.Http(
                        username = "u",
                        password = "p",
                        path = "/proxy",
                        headers = mapOf("X-Token" to "abc"),
                    ),
                ),
            )

            assertThat(out["path"]!!.jsonPrimitive.content).isEqualTo("/proxy")
            assertThat(out["headers"]!!.jsonObject["X-Token"]!!.jsonPrimitive.content).isEqualTo("abc")
        }
    }
}
