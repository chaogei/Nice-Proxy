package com.niceproxy.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 一条路由分流规则。规则按 [sortOrder] 顺序匹配，命中即停。
 */
@Serializable
data class RoutingRule(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
    val matcher: RuleMatcher = RuleMatcher(),
    val action: RuleAction = RuleAction.Route(WellKnownTag.PROXY),
    /**
     * 套用分流模板时是否保留这条规则。
     *
     * 没有这个开关，「一键切换到绕过大陆」就会连带清掉用户精心写的自定义规则，
     * 用户因此不敢再碰模板 —— 模板也就失去了意义。
     */
    val locked: Boolean = false,
)

/**
 * 规则匹配条件。同一条规则内的多个字段之间是 **AND** 关系，
 * 单个字段内的多个值之间是 **OR** 关系 —— 与 sing-box 的语义一致。
 */
@Serializable
data class RuleMatcher(
    val domain: List<String> = emptyList(),
    val domainSuffix: List<String> = emptyList(),
    val domainKeyword: List<String> = emptyList(),
    val domainRegex: List<String> = emptyList(),
    val ipCidr: List<String> = emptyList(),
    /**
     * 按客户端来源 IP 分流 —— 网关形态独有的能力，
     * 可以让不同设备走不同节点。见 docs/DESIGN.md FR-4.6。
     */
    val sourceIpCidr: List<String> = emptyList(),
    val port: List<Int> = emptyList(),
    val portRange: List<String> = emptyList(),
    /** "tcp" | "udp" */
    val network: List<String> = emptyList(),
    /** 嗅探出的应用层协议："http" | "tls" | "quic" | "dns" | "stun" | "bittorrent" */
    val protocol: List<String> = emptyList(),
    /** 入站 tag，用于「某个端口进来的流量走某个出站」。 */
    val inbound: List<String> = emptyList(),
    val ruleSet: List<String> = emptyList(),
    /** 匹配私有地址（局域网、回环）。 */
    val ipIsPrivate: Boolean? = null,
    val invert: Boolean = false,
) {
    val isEmpty: Boolean
        get() = domain.isEmpty() && domainSuffix.isEmpty() && domainKeyword.isEmpty() &&
            domainRegex.isEmpty() && ipCidr.isEmpty() && sourceIpCidr.isEmpty() &&
            port.isEmpty() && portRange.isEmpty() && network.isEmpty() &&
            protocol.isEmpty() && inbound.isEmpty() && ruleSet.isEmpty() &&
            ipIsPrivate == null
}

/**
 * 规则命中后的动作。
 *
 * 对应 sing-box 1.11+ 的 `route.rules[].action`。注意 1.11 起
 * `block` / `dns` 类型的 outbound 已废弃，必须改用 [Reject] / [HijackDns]。
 * 见 docs/DESIGN.md §6.3 约束 C-2。
 */
@Serializable
sealed interface RuleAction {

    @Serializable
    @SerialName("route")
    data class Route(val outboundTag: String) : RuleAction

    @Serializable
    @SerialName("reject")
    data class Reject(
        /** "default"（返回 RST/ICMP 不可达） | "drop"（静默丢弃） */
        val method: String = "default",
    ) : RuleAction

    @Serializable
    @SerialName("hijack-dns")
    data object HijackDns : RuleAction

    @Serializable
    @SerialName("sniff")
    data class Sniff(
        val sniffers: List<String> = emptyList(),
        val timeout: String? = null,
    ) : RuleAction

    @Serializable
    @SerialName("resolve")
    data class Resolve(
        val strategy: String? = null,
        val server: String? = null,
    ) : RuleAction
}

/** 配置生成器保留的固定 tag，不允许被节点或入站占用。 */
object WellKnownTag {
    const val DIRECT = "direct"

    /** 用户可手动选择的策略组（selector）。 */
    const val PROXY = "proxy"

    /** 自动测速选优组（urltest）。 */
    const val AUTO = "auto"

    val ALL = setOf(DIRECT, PROXY, AUTO)
}

/**
 * 远程/本地规则集（geosite、geoip 等）。
 */
@Serializable
data class RuleSetRef(
    val id: String,
    val tag: String,
    val type: RuleSetType = RuleSetType.REMOTE,
    val format: RuleSetFormat = RuleSetFormat.BINARY,
    val url: String? = null,
    val path: String? = null,
    /** 下载规则集时使用的出站 tag。 */
    val downloadDetour: String = WellKnownTag.PROXY,
    val updateInterval: String = "7d",
    val enabled: Boolean = true,
    /**
     * 规则集是否包含 IP 类规则（geoip）。
     *
     * 引用它的路由规则必须先把域名解析成 IP 才可能命中，
     * 配置生成器据此决定在何处插入 `action: "resolve"`。
     */
    val containsIpRules: Boolean = false,
)

enum class RuleSetType { REMOTE, LOCAL }
enum class RuleSetFormat { BINARY, SOURCE }

/** 开箱即用的分流模式，对应 docs/DESIGN.md FR-4.5。 */
enum class RoutingMode(val displayName: String, val description: String) {
    GLOBAL_PROXY("全局代理", "所有流量都走上游节点"),
    BYPASS_MAINLAND("绕过大陆", "中国大陆网站与 IP 直连，其余走节点"),
    GLOBAL_DIRECT("全局直连", "不使用上游节点，等价于纯中继模式"),
    CUSTOM("自定义", "使用自定义规则列表"),
}
