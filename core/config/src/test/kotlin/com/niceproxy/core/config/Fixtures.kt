package com.niceproxy.core.config

import com.niceproxy.core.model.ClashApiSettings
import com.niceproxy.core.model.CredentialState
import com.niceproxy.core.model.InboundAuth
import com.niceproxy.core.model.InboundService
import com.niceproxy.core.model.InboundType
import com.niceproxy.core.model.ProtocolParams
import com.niceproxy.core.model.ProxyProtocol
import com.niceproxy.core.model.RealityConfig
import com.niceproxy.core.model.ServerProfile
import com.niceproxy.core.model.TlsConfig
import com.niceproxy.core.model.TransportConfig

internal object Fixtures {

    /**
     * REALITY 公钥是 32 字节 X25519，base64url 无填充编码后固定 43 字符。
     * 内核会真的解码校验（`invalid public_key`），不能用占位字符串。
     */
    const val REALITY_PUBLIC_KEY = "jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0"

    val clashApi = ClashApiSettings(port = 19090, secret = "test-secret")

    fun input(
        inbounds: List<InboundService> = listOf(mixedInbound()),
        nodes: List<ServerProfile> = emptyList(),
        rules: List<com.niceproxy.core.model.RoutingRule> = emptyList(),
        ruleSets: List<com.niceproxy.core.model.RuleSetRef> = emptyList(),
    ) = ConfigInput(
        inbounds = inbounds,
        nodes = nodes,
        rules = rules,
        ruleSets = ruleSets,
        clashApi = clashApi,
        workDir = "/data/user/0/com.niceproxy/files",
    )

    fun mixedInbound(
        id: String = "mix",
        port: Int = 8080,
        auth: InboundAuth? = null,
        udpEnabled: Boolean = true,
        enabled: Boolean = true,
    ) = InboundService(
        id = id,
        type = InboundType.MIXED,
        listenPort = port,
        auth = auth,
        udpEnabled = udpEnabled,
        enabled = enabled,
    )

    fun hysteria2(
        id: String = "h2",
        password: String = "pw",
        obfsType: String? = null,
        obfsPassword: String? = null,
        serverPorts: List<String> = emptyList(),
    ) = ServerProfile(
        id = id,
        groupId = "g1",
        name = "HY2 节点",
        protocol = ProxyProtocol.HYSTERIA2,
        server = "hy2.example.com",
        serverPort = 443,
        params = ProtocolParams.Hysteria2(
            password = password,
            upMbps = 100,
            downMbps = 300,
            obfsType = obfsType,
            obfsPassword = obfsPassword,
            serverPorts = serverPorts,
        ),
        tls = TlsConfig(enabled = true, serverName = "hy2.example.com"),
    )

    fun vlessReality(id: String = "vl") = ServerProfile(
        id = id,
        groupId = "g1",
        name = "VLESS REALITY",
        protocol = ProxyProtocol.VLESS,
        server = "1.2.3.4",
        serverPort = 443,
        params = ProtocolParams.VLess(
            uuid = "11111111-2222-3333-4444-555555555555",
            flow = "xtls-rprx-vision",
        ),
        tls = TlsConfig(
            enabled = true,
            serverName = "www.microsoft.com",
            reality = RealityConfig(publicKey = REALITY_PUBLIC_KEY, shortId = "ab12"),
        ),
    )

    fun vmessWs(id: String = "vm") = ServerProfile(
        id = id,
        groupId = "g1",
        name = "VMess WS",
        protocol = ProxyProtocol.VMESS,
        server = "vm.example.com",
        serverPort = 443,
        params = ProtocolParams.VMess(uuid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),
        transport = TransportConfig.WebSocket(path = "/ws", headers = mapOf("Host" to "vm.example.com")),
        tls = TlsConfig(enabled = true, serverName = "vm.example.com"),
    )

    /**
     * 凭据解不开的节点，形状与 `ServerEntity.toDomain()` 的降级结果一致：
     * 参数被换成一个一定构建失败的占位符，其余字段（含 TLS）原样保留 ——
     * 只有 params 那一列是加密的。
     */
    fun unreadableCredentials(id: String = "dead") = ServerProfile(
        id = id,
        groupId = "g1",
        name = "香港 01",
        protocol = ProxyProtocol.TROJAN,
        server = "hk.example.com",
        serverPort = 443,
        params = ProtocolParams.Trojan(password = ""),
        tls = TlsConfig(enabled = true, serverName = "hk.example.com"),
        credentialState = CredentialState.UNREADABLE,
    )

    fun shadowsocks(id: String = "ss") = ServerProfile(
        id = id,
        groupId = "g1",
        name = "SS 节点",
        protocol = ProxyProtocol.SHADOWSOCKS,
        server = "ss.example.com",
        serverPort = 8388,
        params = ProtocolParams.Shadowsocks(
            method = "2022-blake3-aes-128-gcm",
            password = "c2VjcmV0",
        ),
    )
}
