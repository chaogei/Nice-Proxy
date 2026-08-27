package com.niceproxy.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 各协议独有的参数。
 *
 * 与 [ServerProfile] 分离存储（数据库中序列化为 JSON 字符串），
 * 这样新增协议不需要迁移数据库 schema。
 */
@Serializable
sealed interface ProtocolParams {

    @Serializable
    @SerialName("direct")
    data object Direct : ProtocolParams

    @Serializable
    @SerialName("http")
    data class Http(
        val username: String? = null,
        val password: String? = null,
        val path: String? = null,
        val headers: Map<String, String> = emptyMap(),
    ) : ProtocolParams

    @Serializable
    @SerialName("socks")
    data class Socks(
        /** sing-box 取值："4" | "4a" | "5"，默认 5。 */
        val version: String = "5",
        val username: String? = null,
        val password: String? = null,
        val udpOverTcp: Boolean = false,
    ) : ProtocolParams

    @Serializable
    @SerialName("shadowsocks")
    data class Shadowsocks(
        val method: String,
        val password: String,
        val plugin: String? = null,
        val pluginOpts: String? = null,
        val udpOverTcp: Boolean = false,
    ) : ProtocolParams {
        companion object {
            /** sing-box 支持的加密方式，用于 UI 下拉与导入校验。 */
            val METHODS = listOf(
                "2022-blake3-aes-128-gcm",
                "2022-blake3-aes-256-gcm",
                "2022-blake3-chacha20-poly1305",
                "aes-128-gcm",
                "aes-192-gcm",
                "aes-256-gcm",
                "chacha20-ietf-poly1305",
                "xchacha20-ietf-poly1305",
                "none",
            )
        }
    }

    @Serializable
    @SerialName("vmess")
    data class VMess(
        val uuid: String,
        val security: String = "auto",
        val alterId: Int = 0,
        val globalPadding: Boolean = false,
        val authenticatedLength: Boolean = false,
        val packetEncoding: String? = null,
    ) : ProtocolParams {
        companion object {
            val SECURITIES = listOf(
                "auto", "none", "zero",
                "aes-128-gcm", "chacha20-poly1305", "aes-128-cfb",
            )
        }
    }

    @Serializable
    @SerialName("vless")
    data class VLess(
        val uuid: String,
        /** 目前唯一有效值为 "xtls-rprx-vision"，为空表示不启用。 */
        val flow: String? = null,
        /** "xudp" | "packetaddr"，为空使用 sing-box 默认。 */
        val packetEncoding: String? = null,
    ) : ProtocolParams

    @Serializable
    @SerialName("trojan")
    data class Trojan(
        val password: String,
    ) : ProtocolParams

    @Serializable
    @SerialName("hysteria")
    data class Hysteria(
        val authString: String? = null,
        val up: String? = null,
        val down: String? = null,
        val obfs: String? = null,
        val recvWindowConn: Long? = null,
        val recvWindow: Long? = null,
        val disableMtuDiscovery: Boolean = false,
    ) : ProtocolParams

    @Serializable
    @SerialName("hysteria2")
    data class Hysteria2(
        val password: String,
        /** 单位 Mbps，null 表示交由 BBR 自适应（推荐）。 */
        val upMbps: Int? = null,
        val downMbps: Int? = null,
        /** 目前仅支持 "salamander"。 */
        val obfsType: String? = null,
        val obfsPassword: String? = null,
        /** 端口跳跃，形如 ["20000:30000", "443"]。 */
        val serverPorts: List<String> = emptyList(),
        /** 端口跳跃间隔，形如 "30s"。 */
        val hopInterval: String? = null,
        val brutalDebug: Boolean = false,
    ) : ProtocolParams

    @Serializable
    @SerialName("tuic")
    data class Tuic(
        val uuid: String,
        val password: String,
        /** "cubic" | "new_reno" | "bbr" */
        val congestionControl: String = "cubic",
        /** "native" | "quic" */
        val udpRelayMode: String = "native",
        val udpOverStream: Boolean = false,
        val zeroRttHandshake: Boolean = false,
        val heartbeat: String? = null,
    ) : ProtocolParams

    @Serializable
    @SerialName("anytls")
    data class AnyTls(
        val password: String,
        val idleSessionCheckInterval: String? = null,
        val idleSessionTimeout: String? = null,
        val minIdleSession: Int? = null,
    ) : ProtocolParams

    @Serializable
    @SerialName("shadowtls")
    data class ShadowTls(
        val version: Int = 3,
        val password: String? = null,
    ) : ProtocolParams

    @Serializable
    @SerialName("ssh")
    data class Ssh(
        val user: String,
        val password: String? = null,
        val privateKey: String? = null,
        val privateKeyPassphrase: String? = null,
        val hostKeyAlgorithms: List<String> = emptyList(),
    ) : ProtocolParams
}
