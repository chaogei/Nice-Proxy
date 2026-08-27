package com.niceproxy.core.config.share

import com.google.common.truth.Truth.assertThat
import com.niceproxy.core.model.ProtocolParams
import com.niceproxy.core.model.ProxyProtocol
import com.niceproxy.core.model.RealityConfig
import com.niceproxy.core.model.ServerProfile
import com.niceproxy.core.model.TlsConfig
import com.niceproxy.core.model.TransportConfig
import com.niceproxy.core.model.UtlsConfig
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll

/**
 * 往返测试：节点 → 分享链接 → 节点。
 *
 * 这比单独测某一个方向强得多。导出和解析是一对互逆操作，任何一边引入
 * 不对称的改动（字段名写错、编码方式不一致、默认值处理不同）都会在这里暴露，
 * 而单向测试很容易两边一起写错却互相「验证通过」。
 */
class ShareLinkRoundTripTest {

    private fun node(
        protocol: ProxyProtocol,
        params: ProtocolParams,
        transport: TransportConfig? = null,
        tls: TlsConfig? = null,
        name: String = "测试节点 🚀",
    ) = ServerProfile(
        id = "id",
        groupId = "g",
        name = name,
        protocol = protocol,
        server = "example.com",
        serverPort = 443,
        params = params,
        transport = transport,
        tls = tls,
    )

    private fun roundTrip(original: ServerProfile): ServerProfile {
        val link = ShareLinkExporter.export(original)
        assertThat(link).isNotNull()
        return ShareLinkParsers.parse(link!!).getOrThrow()
    }

    private fun assertCore(original: ServerProfile, parsed: ServerProfile) = assertAll(
        { assertThat(parsed.protocol).isEqualTo(original.protocol) },
        { assertThat(parsed.server).isEqualTo(original.server) },
        { assertThat(parsed.serverPort).isEqualTo(original.serverPort) },
        { assertThat(parsed.name).isEqualTo(original.name) },
    )

    @Test
    @DisplayName("Shadowsocks 往返一致")
    fun shadowsocks() {
        val original = node(
            ProxyProtocol.SHADOWSOCKS,
            ProtocolParams.Shadowsocks(method = "aes-256-gcm", password = "p@ss:word"),
        )
        val parsed = roundTrip(original)
        assertCore(original, parsed)

        val params = parsed.params as ProtocolParams.Shadowsocks
        assertThat(params.method).isEqualTo("aes-256-gcm")
        assertThat(params.password).isEqualTo("p@ss:word")
    }

    @Test
    @DisplayName("VMess + WebSocket + TLS 往返一致")
    fun vmess() {
        val original = node(
            ProxyProtocol.VMESS,
            ProtocolParams.VMess(uuid = "11111111-2222-3333-4444-555555555555"),
            transport = TransportConfig.WebSocket(
                path = "/ray",
                headers = mapOf("Host" to "cdn.example.com"),
            ),
            tls = TlsConfig(
                enabled = true,
                serverName = "example.com",
                utls = UtlsConfig(fingerprint = "chrome"),
            ),
        )
        val parsed = roundTrip(original)
        assertCore(original, parsed)

        assertThat((parsed.params as ProtocolParams.VMess).uuid)
            .isEqualTo("11111111-2222-3333-4444-555555555555")
        val ws = parsed.transport as TransportConfig.WebSocket
        assertThat(ws.path).isEqualTo("/ray")
        assertThat(ws.headers["Host"]).isEqualTo("cdn.example.com")
        assertThat(parsed.tls?.serverName).isEqualTo("example.com")
        assertThat(parsed.tls?.utls?.fingerprint).isEqualTo("chrome")
    }

    @Test
    @DisplayName("VLESS + REALITY + Vision 往返一致")
    fun vlessReality() {
        val original = node(
            ProxyProtocol.VLESS,
            ProtocolParams.VLess(
                uuid = "11111111-2222-3333-4444-555555555555",
                flow = "xtls-rprx-vision",
            ),
            tls = TlsConfig(
                enabled = true,
                serverName = "www.microsoft.com",
                utls = UtlsConfig(fingerprint = "chrome"),
                reality = RealityConfig(
                    publicKey = "jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0",
                    shortId = "ab12",
                ),
            ),
        )
        val parsed = roundTrip(original)
        assertCore(original, parsed)

        assertThat((parsed.params as ProtocolParams.VLess).flow).isEqualTo("xtls-rprx-vision")
        assertThat(parsed.tls?.reality?.publicKey)
            .isEqualTo("jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0")
        assertThat(parsed.tls?.reality?.shortId).isEqualTo("ab12")
    }

    @Test
    @DisplayName("Trojan + gRPC 往返一致")
    fun trojanGrpc() {
        val original = node(
            ProxyProtocol.TROJAN,
            ProtocolParams.Trojan(password = "secret"),
            transport = TransportConfig.Grpc(serviceName = "my-svc"),
            tls = TlsConfig(enabled = true, serverName = "example.com"),
        )
        val parsed = roundTrip(original)
        assertCore(original, parsed)

        assertThat((parsed.params as ProtocolParams.Trojan).password).isEqualTo("secret")
        assertThat((parsed.transport as TransportConfig.Grpc).serviceName).isEqualTo("my-svc")
    }

    @Test
    @DisplayName("Hysteria2 含混淆与端口跳跃往返一致")
    fun hysteria2() {
        val original = node(
            ProxyProtocol.HYSTERIA2,
            ProtocolParams.Hysteria2(
                password = "hy-pass",
                obfsType = "salamander",
                obfsPassword = "ob-pw",
                serverPorts = listOf("20000:30000"),
            ),
            tls = TlsConfig(enabled = true, serverName = "example.com", insecure = true),
        )
        val parsed = roundTrip(original)
        assertCore(original, parsed)

        val params = parsed.params as ProtocolParams.Hysteria2
        assertThat(params.password).isEqualTo("hy-pass")
        assertThat(params.obfsType).isEqualTo("salamander")
        assertThat(params.obfsPassword).isEqualTo("ob-pw")
        // 导出时写成 20000-30000，解析回来要还原成 sing-box 的冒号写法
        assertThat(params.serverPorts).containsExactly("20000:30000")
        assertThat(parsed.tls?.insecure).isTrue()
    }

    @Test
    @DisplayName("TUIC 往返一致")
    fun tuic() {
        val original = node(
            ProxyProtocol.TUIC,
            ProtocolParams.Tuic(
                uuid = "uuid-abc",
                password = "pass-xyz",
                congestionControl = "bbr",
            ),
            tls = TlsConfig(enabled = true, serverName = "example.com"),
        )
        val parsed = roundTrip(original)
        assertCore(original, parsed)

        val params = parsed.params as ProtocolParams.Tuic
        assertThat(params.uuid).isEqualTo("uuid-abc")
        assertThat(params.password).isEqualTo("pass-xyz")
        assertThat(params.congestionControl).isEqualTo("bbr")
    }

    @Test
    @DisplayName("SOCKS5 带认证往返一致")
    fun socks() {
        val original = node(
            ProxyProtocol.SOCKS,
            ProtocolParams.Socks(username = "alice", password = "s3cret"),
            name = "SOCKS 节点",
        )
        val parsed = roundTrip(original)
        assertCore(original, parsed)

        val params = parsed.params as ProtocolParams.Socks
        assertThat(params.username).isEqualTo("alice")
        assertThat(params.password).isEqualTo("s3cret")
    }

    @Test
    @DisplayName("节点名含空格与 emoji 时不丢失")
    fun nameEncoding() {
        val original = node(
            ProxyProtocol.TROJAN,
            ProtocolParams.Trojan(password = "pw"),
            tls = TlsConfig(enabled = true),
            name = "香港 01 | 1x 倍率 🇭🇰",
        )
        assertThat(roundTrip(original).name).isEqualTo("香港 01 | 1x 倍率 🇭🇰")
    }
}
