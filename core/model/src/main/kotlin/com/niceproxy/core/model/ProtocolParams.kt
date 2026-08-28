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

    /**
     * Hysteria v1。
     *
     * 带宽是**必填**的：v1 没有 BBR 自适应，`hysteria.NewClient` 拿到 0 会直接
     * 报错，而报错的后果是内核拒绝整份配置。两种写法二选一即可 ——
     * [up] / [down] 是带单位的字符串（`100 Mbps`），[upMbps] / [downMbps] 是纯数字。
     */
    @Serializable
    @SerialName("hysteria")
    data class Hysteria(
        val authString: String? = null,
        /** Base64 形式的认证串，对应 sing-box 的 `auth`。与 [authString] 二选一。 */
        val authBase64: String? = null,
        val up: String? = null,
        val down: String? = null,
        val upMbps: Int? = null,
        val downMbps: Int? = null,
        val obfs: String? = null,
        /** 端口跳跃，1.12+ 起 v1 也支持，形如 ["20000:30000"]。 */
        val serverPorts: List<String> = emptyList(),
        val hopInterval: String? = null,
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
    ) : ProtocolParams {
        companion object {
            /** sing-box 1.13 的 `obfs.type` 只认这一个值，gecko 要到 1.14。 */
            val OBFS_TYPES = listOf("salamander")
        }
    }

    @Serializable
    @SerialName("tuic")
    data class Tuic(
        val uuid: String,
        val password: String,
        /** "cubic" | "new_reno" | "bbr" */
        val congestionControl: String = "cubic",
        /** "native" | "quic" */
        val udpRelayMode: String = "native",
        /**
         * 把 UDP 全部塞进一条 QUIC 流。
         *
         * 与 [udpRelayMode] 互斥：sing-box 见到两者同时出现会直接报
         * `udp_over_stream is conflict with udp_relay_mode`。
         */
        val udpOverStream: Boolean = false,
        val zeroRttHandshake: Boolean = false,
        val heartbeat: String? = null,
    ) : ProtocolParams {
        companion object {
            val CONGESTION_CONTROLS = listOf("cubic", "new_reno", "bbr")
            val UDP_RELAY_MODES = listOf("native", "quic")
        }
    }

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
    ) : ProtocolParams {
        companion object {
            /** sing-box 只实现了 v1 / v2 / v3，别的值会让握手函数为空。 */
            val VERSIONS = listOf(1, 2, 3)
        }
    }

    @Serializable
    @SerialName("ssh")
    data class Ssh(
        val user: String,
        val password: String? = null,
        val privateKey: String? = null,
        val privateKeyPassphrase: String? = null,
        /** 固定的服务端主机公钥（authorized_keys 行格式），留空表示不校验。 */
        val hostKey: List<String> = emptyList(),
        val hostKeyAlgorithms: List<String> = emptyList(),
        /** 伪装成特定 SSH 客户端，形如 "SSH-2.0-OpenSSH_9.6"。 */
        val clientVersion: String? = null,
    ) : ProtocolParams

    /**
     * WireGuard。
     *
     * 字段名沿用生态里通用的 outbound 叫法（[localAddress]、[peerPublicKey]），
     * 而不是 sing-box 1.13 endpoint 的 `address` / `peers[].public_key`：
     * 分享链接、Clash YAML、WARP 配置导出用的全是前者，模型层贴着数据来源命名，
     * 由配置生成器负责翻译成 endpoint 形态。
     */
    @Serializable
    @SerialName("wireguard")
    data class WireGuard(
        /** 本端私钥，标准 Base64 编码的 32 字节。 */
        val privateKey: String,
        /** 对端公钥，标准 Base64 编码的 32 字节。 */
        val peerPublicKey: String,
        /** 可选的对称预共享密钥，同样是 32 字节 Base64。 */
        val preSharedKey: String? = null,
        /**
         * 分配给本地 WireGuard 接口的地址，**必须**是 CIDR 形式（`10.0.0.2/32`）。
         *
         * sing-box 用 `netip.ParsePrefix` 解析，少了掩码位就整份配置解析失败。
         */
        val localAddress: List<String> = emptyList(),
        /** 经该 peer 转发的目标网段，留空时生成器按全量路由处理。 */
        val allowedIps: List<String> = emptyList(),
        /** Cloudflare WARP 等实现要求的 3 字节 reserved。 */
        val reserved: List<Int> = emptyList(),
        val mtu: Int? = null,
        /** 单位秒，用于打洞保活。 */
        val persistentKeepaliveInterval: Int? = null,
        /** 本地 UDP 监听端口，通常不需要指定。 */
        val listenPort: Int? = null,
    ) : ProtocolParams {
        companion object {
            /** WireGuard 密钥恒为 32 字节，Base64 之后固定 44 字符（含一个填充符）。 */
            const val KEY_BASE64_LENGTH = 44

            /** [allowedIps] 留空时的默认值：全部流量都交给这条隧道。 */
            val DEFAULT_ALLOWED_IPS = listOf("0.0.0.0/0", "::/0")

            const val RESERVED_SIZE = 3
        }
    }
}
