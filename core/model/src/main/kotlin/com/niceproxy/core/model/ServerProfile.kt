package com.niceproxy.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * 凭据的可读状态。
 *
 * 做成独立状态位而不是往名称里塞标记文本：标记文本会被「复制链接」「导出备份」
 * 一起带走，也会污染搜索与排序，而且没法在界面上单独渲染成一个可操作的入口。
 */
enum class CredentialState { OK, UNREADABLE }

/**
 * 一个上游节点。
 */
@Serializable
data class ServerProfile(
    val id: String,
    val groupId: String,
    val name: String,
    val protocol: ProxyProtocol,
    val server: String,
    val serverPort: Int,
    val params: ProtocolParams,
    val transport: TransportConfig? = null,
    val tls: TlsConfig? = null,
    val multiplex: MultiplexConfig? = null,
    /**
     * 链式代理：本节点的流量先经由这个 tag 指向的出站发出（FR-2.10）。
     *
     * 取值是另一个节点的 [outboundTag]，或 [WellKnownTag.DIRECT]。**不能**指向
     * `proxy` / `auto` —— 那两个策略组的候选里就包含本节点，会绕成一个环。
     *
     * 默认 null（直接出站）。配置生成器会校验自指、成环、指向不存在或不可用的
     * 节点这三种情况，任何一种都让本节点不可用，而不是悄悄退化成不走链路。
     */
    val detour: String? = null,
    val sortOrder: Int = 0,
    /** 最近一次测速结果，单位毫秒。null = 未测试；[LATENCY_TIMEOUT] = 超时。 */
    val latencyMs: Int? = null,
    val lastTestedAt: Long? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    /**
     * 凭据是否还读得出来。
     *
     * 存在 [CredentialState.UNREADABLE] 这种状态，是因为凭据在库里是用
     * Android Keystore 里的密钥加密的，而那把密钥会在一些设备侧事件后失效：
     * 恢复出厂设置、从备份还原到新机、用户改了锁屏方式。此时密文还在，
     * 但永远解不开了，节点只能重新导入。
     *
     * `@Transient`：这是读库时派生出来的运行时状态，不是节点自身的数据。
     * 备份里带上它没有意义 —— 恢复时凭据会用当时那台设备的密钥重新加密，
     * 状态必然是 [CredentialState.OK]。
     */
    @Transient
    val credentialState: CredentialState = CredentialState.OK,
) {
    /**
     * sing-box 配置中使用的出站 tag。
     *
     * 刻意不使用 [name]：用户可编辑的名称可能重复、含空格或非 ASCII 字符，
     * 而 tag 需要全局唯一且被 Clash API 的 URL 路径引用。
     * 见 docs/DESIGN.md §6.3 约束 C-5。
     */
    val outboundTag: String
        get() = "$TAG_PREFIX$id"

    val latencyState: LatencyState
        get() = when {
            latencyMs == null -> LatencyState.UNKNOWN
            latencyMs == LATENCY_TIMEOUT -> LatencyState.TIMEOUT
            latencyMs < 200 -> LatencyState.GOOD
            latencyMs < 500 -> LatencyState.FAIR
            else -> LatencyState.POOR
        }

    companion object {
        const val TAG_PREFIX = "node-"
        const val LATENCY_TIMEOUT = -1

        /** 从出站 tag 反查节点 id，用于消费 Clash API 返回的结果。 */
        fun idFromTag(tag: String): String? =
            tag.removePrefix(TAG_PREFIX).takeIf { it != tag && it.isNotEmpty() }
    }
}

enum class LatencyState { UNKNOWN, GOOD, FAIR, POOR, TIMEOUT }

/**
 * 节点分组。手动组由用户维护，订阅组由远端 URL 同步。
 */
@Serializable
data class ServerGroup(
    val id: String,
    val name: String,
    val type: GroupType,
    val url: String? = null,
    val userAgent: String? = null,
    val autoUpdate: Boolean = false,
    val updateIntervalMinutes: Int = 1440,
    val lastUpdateAt: Long? = null,
    val lastError: String? = null,
    /** 来自订阅响应头 `subscription-userinfo`。 */
    val traffic: SubscriptionTraffic? = null,
    val sortOrder: Int = 0,
    /**
     * 节点名过滤正则。
     *
     * 机场普遍会在订阅里塞「剩余流量」「官网地址」「续费提醒」这类
     * 伪装成节点的公告条目。没有过滤手段的话，它们会混在节点列表里，
     * 还会被自动测速当成真节点反复尝试。
     *
     * 留空表示不过滤。[filterExclude] 决定命中的是排除还是保留。
     */
    val remarksFilter: String? = null,
    val filterExclude: Boolean = true,
    /** 附加请求头，部分机场按自定义头返回不同格式。JSON 对象字符串。 */
    val extraHeaders: String? = null,
) {
    /**
     * 编译好的 [remarksFilter]，null 表示不过滤。
     *
     * [accepts] 是按节点逐个调用的，机场动辄三千个节点，现编译就是三千次编译
     * 同一个 pattern。缓存在实例上：一次订阅更新自始至终用的是同一个分组对象。
     *
     * 委托属性没有 backing field，kotlinx.serialization 一律跳过，备份格式不受影响；
     * 也正因为如此不能加 `@Transient`，插件会判定它多余而报错。
     */
    private val compiledFilter: Regex? by lazy {
        remarksFilter
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Regex(it) }.getOrNull() }
    }

    /** 按 [remarksFilter] 判断某个节点名是否应当保留。正则非法时视为不过滤。 */
    fun accepts(nodeName: String): Boolean {
        val regex = compiledFilter ?: return true
        val matched = regex.containsMatchIn(nodeName)
        return if (filterExclude) !matched else matched
    }

    /** 自动更新的最小间隔。低于此值 WorkManager 也不会真正执行。 */
    val effectiveIntervalMinutes: Int
        get() = updateIntervalMinutes.coerceAtLeast(MIN_UPDATE_INTERVAL_MINUTES)

    companion object {
        const val MIN_UPDATE_INTERVAL_MINUTES = 15
    }
}

enum class GroupType { MANUAL, SUBSCRIPTION }

/**
 * 机场流量信息，解析自响应头：
 * `subscription-userinfo: upload=1234; download=5678; total=107374182400; expire=1735689600`
 */
@Serializable
data class SubscriptionTraffic(
    val uploadBytes: Long = 0,
    val downloadBytes: Long = 0,
    val totalBytes: Long = 0,
    /** Unix 秒。0 表示无到期时间。 */
    val expireAtSeconds: Long = 0,
) {
    val usedBytes: Long get() = uploadBytes + downloadBytes
    val remainingBytes: Long get() = (totalBytes - usedBytes).coerceAtLeast(0)
    val usedRatio: Float
        get() = if (totalBytes <= 0) 0f else (usedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
}
