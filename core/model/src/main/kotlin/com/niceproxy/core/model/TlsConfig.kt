package com.niceproxy.core.model

import kotlinx.serialization.Serializable

/**
 * 出站 TLS 配置，覆盖 sing-box 的 `tls` 字段。
 */
@Serializable
data class TlsConfig(
    val enabled: Boolean = true,
    /** SNI。为空时 sing-box 回退到出站 server 地址。 */
    val serverName: String? = null,
    /**
     * 跳过证书校验。安全风险高，UI 上必须显著警告。
     * 见 docs/DESIGN.md §9。
     */
    val insecure: Boolean = false,
    val alpn: List<String> = emptyList(),
    val minVersion: String? = null,
    val maxVersion: String? = null,
    val cipherSuites: List<String> = emptyList(),
    /** PEM 格式的自定义 CA 证书内容。 */
    val certificate: String? = null,
    val utls: UtlsConfig? = null,
    val reality: RealityConfig? = null,
    val ech: EchConfig? = null,
) {
    companion object {
        val FINGERPRINTS = listOf(
            "chrome", "firefox", "edge", "safari", "360", "qq",
            "ios", "android", "random", "randomized",
        )
    }
}

/** uTLS 客户端指纹伪装。REALITY 强制要求启用。 */
@Serializable
data class UtlsConfig(
    val enabled: Boolean = true,
    val fingerprint: String = "chrome",
)

@Serializable
data class RealityConfig(
    val enabled: Boolean = true,
    val publicKey: String,
    val shortId: String = "",
)

@Serializable
data class EchConfig(
    val enabled: Boolean = true,
    val config: List<String> = emptyList(),
)

/**
 * 多路复用。仅 Shadowsocks / VMess / VLESS / Trojan 支持。
 */
@Serializable
data class MultiplexConfig(
    val enabled: Boolean = true,
    /** "smux" | "yamux" | "h2mux" */
    val protocol: String = "h2mux",
    val maxConnections: Int? = null,
    val minStreams: Int? = null,
    val maxStreams: Int? = null,
    val padding: Boolean = false,
    val brutal: BrutalConfig? = null,
) {
    companion object {
        val PROTOCOLS = listOf("smux", "yamux", "h2mux")
    }
}

/** TCP Brutal 拥塞控制，需服务端配合。 */
@Serializable
data class BrutalConfig(
    val enabled: Boolean = true,
    val upMbps: Int,
    val downMbps: Int,
)
