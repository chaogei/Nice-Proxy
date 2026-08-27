package com.niceproxy.core.model

import kotlinx.serialization.Serializable

/**
 * 一个本地监听的入站服务。这是本应用区别于普通代理客户端的核心——
 * 监听在 `0.0.0.0` 时，局域网内的其他设备可以直接把它当代理用。
 */
@Serializable
data class InboundService(
    val id: String,
    val type: InboundType,
    val listen: String = LISTEN_ALL,
    val listenPort: Int,
    /** null 表示免认证。 */
    val auth: InboundAuth? = null,
    val udpEnabled: Boolean = true,
    val tcpFastOpen: Boolean = false,
    val udpTimeout: String = "5m",
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
) {
    val tag: String get() = "$TAG_PREFIX$id"

    /**
     * 监听在所有接口且未启用认证 —— 同网段任何设备都能白嫖这个代理。
     * UI 必须为此显示警告并要求二次确认，见 docs/DESIGN.md §8.2。
     */
    val isExposedWithoutAuth: Boolean
        get() = listen != LISTEN_LOOPBACK && auth == null

    companion object {
        const val TAG_PREFIX = "in-"
        const val LISTEN_ALL = "0.0.0.0"
        const val LISTEN_LOOPBACK = "127.0.0.1"

        /** 低于 1024 的端口在非 root Android 上无法绑定，见 docs/DESIGN.md §10 P-3。 */
        val PORT_RANGE = 1025..65535

        const val DEFAULT_MIXED_PORT = 8080
        const val DEFAULT_HTTP_PORT = 8118
        const val DEFAULT_SOCKS_PORT = 1080
        const val DEFAULT_PAC_PORT = 8090
    }
}

@Serializable
enum class InboundType(
    val singBoxType: String?,
    val displayName: String,
    val description: String,
    val defaultPort: Int,
) {
    /** 单端口同时接受 HTTP / SOCKS4 / SOCKS4a / SOCKS5，对齐 Every Proxy 的自动协商行为。 */
    MIXED("mixed", "混合代理", "单端口同时支持 HTTP 与 SOCKS4/4a/5", InboundService.DEFAULT_MIXED_PORT),

    HTTP("http", "HTTP 代理", "标准 HTTP/HTTPS 代理", InboundService.DEFAULT_HTTP_PORT),

    SOCKS("socks", "SOCKS 代理", "SOCKS4/4a/5，支持 UDP 转发", InboundService.DEFAULT_SOCKS_PORT),

    /** 由应用自身的 Kotlin HTTP 服务提供，不经过 sing-box。见 docs/DESIGN.md §6.5。 */
    PAC(null, "PAC 服务", "为客户端提供自动代理配置脚本", InboundService.DEFAULT_PAC_PORT),
    ;

    val isSingBoxManaged: Boolean get() = singBoxType != null
}

@Serializable
data class InboundAuth(
    val username: String,
    val password: String,
)
