package com.niceproxy.core.model

import kotlinx.serialization.Serializable

@Serializable
data class DnsSettings(
    /** 代理流量使用的 DNS，经由 [WellKnownTag.PROXY] 出站解析。 */
    val remoteServer: String = "https://1.1.1.1/dns-query",
    /** 直连流量使用的 DNS，经由 [WellKnownTag.DIRECT] 出站解析。 */
    val localServer: String = "223.5.5.5",
    /** "prefer_ipv4" | "prefer_ipv6" | "ipv4_only" | "ipv6_only" */
    val strategy: String = "prefer_ipv4",
    /** 对规则集判定为国内的域名使用本地 DNS，避免 DNS 污染同时保留 CDN 就近解析。 */
    val splitByRuleSet: Boolean = true,
    val disableCache: Boolean = false,
) {
    companion object {
        val STRATEGIES = listOf("prefer_ipv4", "prefer_ipv6", "ipv4_only", "ipv6_only")
    }
}

@Serializable
data class LogSettings(
    val level: LogLevel = LogLevel.INFO,
    val timestamp: Boolean = true,
    /** 写入文件而不仅仅是内存缓冲。 */
    val persist: Boolean = false,
    val maxBufferedLines: Int = 2000,
)

@Serializable
enum class LogLevel(val singBoxValue: String) {
    TRACE("trace"),
    DEBUG("debug"),
    INFO("info"),
    WARN("warn"),
    ERROR("error"),
    FATAL("fatal"),
    PANIC("panic"),
}

/**
 * Clash API 是 Kotlin 侧获取流量、连接、日志以及执行节点切换和测速的唯一通道，
 * 见 docs/DESIGN.md §6.9。
 *
 * [externalController] 恒定绑定回环地址，绝不允许暴露到局域网。
 */
@Serializable
data class ClashApiSettings(
    val port: Int,
    val secret: String,
) {
    val externalController: String get() = "$LOOPBACK:$port"

    val baseUrl: String get() = "http://$LOOPBACK:$port"

    companion object {
        const val LOOPBACK = "127.0.0.1"
    }
}

/**
 * 出站策略组设置。
 */
@Serializable
data class OutboundSettings(
    /** 当前 selector 选中的 tag，可以是节点 tag、[WellKnownTag.AUTO] 或 [WellKnownTag.DIRECT]。 */
    val selectedTag: String = WellKnownTag.AUTO,
    val urlTestUrl: String = "https://www.gstatic.com/generate_204",
    val urlTestInterval: String = "3m",
    val urlTestTolerance: Int = 50,
    /** 切换节点时是否中断已有连接。默认不中断，避免正在下载的任务被打断。 */
    val interruptExistConnections: Boolean = false,
)

/**
 * 出站流量走哪张网卡。
 *
 * sing-box 的 `bind_interface` 在非 root Android 上不可用（需 CAP_NET_RAW），
 * 因此由 Android 侧的 ConnectivityManager 实现，见 docs/DESIGN.md §6.7。
 */
enum class NetworkPreference(val displayName: String) {
    AUTO("自动（跟随系统）"),
    WIFI("Wi-Fi"),
    CELLULAR("蜂窝数据"),
    ETHERNET("以太网"),
}

@Serializable
data class ServiceSettings(
    val autoStartOnBoot: Boolean = false,
    val startOnAppLaunch: Boolean = false,
    /** 绑定的 IP 从设备上消失时自动停止服务，对齐 Every Proxy 的省电选项。 */
    val powerSave: Boolean = false,
    val keepWifiAwake: Boolean = true,
    val networkPreference: NetworkPreference = NetworkPreference.AUTO,
    val ipv6Enabled: Boolean = true,
    /**
     * 内核意外退出、或启动失败时自动重试。
     *
     * 默认开：一个网关服务「悄无声息地没了」是最糟的失败方式 ——
     * 用户往往要等到别的设备连不上网才发现，而那时早已过去很久。
     * 关掉它只在排查问题时有意义（想看清失败在哪一步，而不是被自动重启掩盖）。
     */
    val autoRestartOnFailure: Boolean = true,
    /**
     * 代理不可用时，允许 PAC 客户端直连。
     *
     * 默认关：打开它，代理一挂，Switch、PS5、电视盒子会**静默**回落到直连，
     * 而这类设备没有任何界面能提示「你现在没在走代理」——用户可能几周后
     * 才从别处发现。关掉它，代理挂了就是连不上网，那是一个看得见的失败。
     *
     * 留这个开关是因为确实存在「宁可不走代理也要能上网」的场景（比如家里
     * 只有这一条出口，断网影响的是全屋而不只是被代理的流量）。但那必须是
     * 用户明确知情后的选择，不能是默认。
     */
    val pacDirectFallback: Boolean = false,
)
