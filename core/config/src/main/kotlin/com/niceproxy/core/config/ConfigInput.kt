package com.niceproxy.core.config

import com.niceproxy.core.model.ClashApiSettings
import com.niceproxy.core.model.DnsSettings
import com.niceproxy.core.model.InboundService
import com.niceproxy.core.model.LogSettings
import com.niceproxy.core.model.OutboundSettings
import com.niceproxy.core.model.RoutingRule
import com.niceproxy.core.model.RuleSetRef
import com.niceproxy.core.model.ServerProfile

/**
 * 生成一份完整 sing-box 配置所需的全部输入。
 *
 * 由 Repository 层从数据库与 DataStore 聚合而来，本模块不感知数据来源。
 */
data class ConfigInput(
    val inbounds: List<InboundService>,
    val nodes: List<ServerProfile>,
    val rules: List<RoutingRule>,
    val ruleSets: List<RuleSetRef> = emptyList(),
    val outbound: OutboundSettings = OutboundSettings(),
    val dns: DnsSettings = DnsSettings(),
    val log: LogSettings = LogSettings(),
    val clashApi: ClashApiSettings,
    /** 内核工作目录，用于 cache.db 与规则集缓存。 */
    val workDir: String,
    val ipv6Enabled: Boolean = true,
)

/**
 * 配置生成失败的原因。生成器不抛异常，而是返回 [ConfigResult]，
 * 便于 UI 把问题精确地指回具体的节点或入站。
 */
sealed interface ConfigError {
    val message: String

    data class NoEnabledInbound(
        override val message: String = "至少需要启用一个入站服务",
    ) : ConfigError

    data class DuplicatePort(
        val port: Int,
        override val message: String = "端口 $port 被多个入站服务占用",
    ) : ConfigError

    data class InvalidPort(
        val port: Int,
        override val message: String =
            "端口 $port 超出可用范围 ${InboundService.PORT_RANGE.first}-${InboundService.PORT_RANGE.last}",
    ) : ConfigError

    data class InvalidNode(
        val nodeId: String,
        val nodeName: String,
        val reason: String,
    ) : ConfigError {
        override val message: String get() = "节点「$nodeName」配置无效：$reason"
    }

    /**
     * 用户配了节点，却没有一个能用。
     *
     * 之所以是 error 而不是把每个节点各记一条 [InvalidNode] 警告了事：这种局面
     * 生成出来的配置**完全合法**，内核照常加载，客户端照常上网，只是 100% 流量
     * 走了直连 —— 没有任何异常表现，用户可能数天都不知道自己在裸奔。
     *
     * 而「一次性挂掉全部节点」几乎只有一个成因：Keystore 密钥失效（恢复出厂、
     * 换机还原、改锁屏）让整张表的凭据同时解不开。此时逐个节点的「缺少密码」
     * 帮不到用户，他需要知道的是「去重新导入」。
     *
     * 与「一个节点都没配」严格区分（判据是 [ConfigInput.nodes] 空不空）：
     * 那是全新安装，退化成纯中继是预期行为。
     */
    data class NoUsableNode(
        val configuredCount: Int,
        val unreadableCount: Int = 0,
    ) : ConfigError {
        override val message: String
            get() = buildString {
                append("配置了 $configuredCount 个节点，但没有一个可用")
                if (unreadableCount > 0) {
                    append("：其中 $unreadableCount 个的凭据已无法解密")
                    append("（恢复出厂设置、换机还原、修改锁屏都会导致），请重新导入订阅或节点")
                } else {
                    append("，请检查节点参数或重新导入")
                }
                append("。继续启动会让全部流量以直连发出，因此已拒绝生成配置")
            }
    }

    /**
     * 该节点关掉了 TLS 证书校验。
     *
     * 不阻断启动 —— 用户确实可能在连自签证书的自建服务；但必须留痕：中间人
     * 可以解密经它转发的全部流量，包括局域网里每一台客户端设备的。
     */
    data class InsecureTls(
        val nodeId: String,
        val nodeName: String,
    ) : ConfigError {
        override val message: String
            get() = "节点「$nodeName」已关闭 TLS 证书校验，经它转发的流量可被中间人解密"
    }

    data class ReservedTag(
        val tag: String,
        override val message: String = "标签 $tag 与内置策略组冲突",
    ) : ConfigError

    /**
     * 规则指向了一个不存在的出站。
     *
     * sing-box 装配阶段就会因为找不到这个 tag 而拒绝整份配置，
     * 表现是「改了个不相干的地方，代理突然起不来了」。
     */
    data class DanglingOutbound(
        val ruleName: String,
        val tag: String,
    ) : ConfigError {
        override val message: String get() = "规则「$ruleName」指向的出站 $tag 不存在，已改为默认出站"
    }

    /** 规则没有任何匹配条件。留着会命中全部流量，把后面的规则全部截胡。 */
    data class EmptyRule(val ruleName: String) : ConfigError {
        override val message: String get() = "规则「$ruleName」没有任何匹配条件，已跳过"
    }

    data class InvalidRuleSet(
        val tag: String,
        val reason: String,
    ) : ConfigError {
        override val message: String get() = "规则集「$tag」不可用：$reason"
    }

    /**
     * 两个入站用了同一个 tag。
     *
     * `option.checkInbounds` 见到重复 tag 直接报 `duplicate inbound tag` 并拒绝
     * 整份配置。入站 tag 由 id 派生，正常路径不会撞，但备份恢复与外部导入不受
     * 主键保护 —— 而端口查重挡不住它们（两个 tag 相同、端口不同的入站）。
     */
    data class DuplicateInboundTag(
        val tag: String,
        override val message: String = "入站标签 $tag 重复",
    ) : ConfigError

    /**
     * 两个节点算出了同一个出站 tag。
     *
     * outbound 与 endpoint 在内核里共用一个 tag 命名空间
     * （`checkOutbounds` 是合起来查重的），撞上同样是整份配置被拒。
     */
    data class DuplicateOutboundTag(
        val tag: String,
        override val message: String = "出站标签 $tag 重复，已只保留第一个",
    ) : ConfigError

    /**
     * 入站的 `udp_timeout` 不是合法时长。
     *
     * 它是 `badoption.Duration`，解析失败发生在读配置的第一步，整份配置作废。
     */
    data class InvalidUdpTimeout(val tag: String, val value: String) : ConfigError {
        override val message: String
            get() = "入站「$tag」的 UDP 超时「$value」不是合法时长，应形如 5m"
    }

    /**
     * 生成结果没通过自检，见 [SingBoxSelfCheck]。
     *
     * 走到这里说明生成器自己有 bug。宁可在这里失败：让内核去拒绝的话，
     * 用户看到的是一句没有上下文的 Go 报错，而我们连是哪个环节出的问题都不知道。
     */
    data class SelfCheckFailed(val reason: String) : ConfigError {
        override val message: String get() = "配置自检未通过：$reason"
    }
}

/**
 * 一次生成的完整结论，供 UI 一次性呈现。
 *
 * 原先 `Success.warnings` 与 `Failure.errors` 是两个互不相干的列表，界面上要么
 * 只看得到「启动失败」要么只看得到「有几个节点被跳过」，用户没法在一个地方
 * 弄清楚「我这份配置到底哪里不对」。这里把两边收敛成同一个结构，并按用户
 * 真正关心的维度分好类。
 */
data class ConfigDiagnostics(
    /** 阻断生成的问题。非空即表示这次没有产出配置。 */
    val blocking: List<ConfigError> = emptyList(),
    /** 不阻断，但用户应当知道的问题。 */
    val warnings: List<ConfigError> = emptyList(),
) {
    val isUsable: Boolean get() = blocking.isEmpty()

    /** 被跳过的节点，UI 可以逐条指回节点详情页。 */
    val skippedNodes: List<ConfigError.InvalidNode>
        get() = (blocking + warnings).filterIsInstance<ConfigError.InvalidNode>()

    /** 关掉了证书校验的节点。 */
    val insecureNodes: List<ConfigError.InsecureTls>
        get() = (blocking + warnings).filterIsInstance<ConfigError.InsecureTls>()

    /** 被剔除或被降级的路由/规则集问题。 */
    val routingIssues: List<ConfigError>
        get() = (blocking + warnings).filter {
            it is ConfigError.EmptyRule ||
                it is ConfigError.DanglingOutbound ||
                it is ConfigError.InvalidRuleSet
        }

    val isEmpty: Boolean get() = blocking.isEmpty() && warnings.isEmpty()

    /** 给通知栏 / 弹窗用的单段文案，按严重程度排序，条数有上限。 */
    fun summary(limit: Int = DEFAULT_SUMMARY_LIMIT): String =
        (blocking + warnings).take(limit).joinToString("；") { it.message }

    private companion object {
        const val DEFAULT_SUMMARY_LIMIT = 5
    }
}

sealed interface ConfigResult {

    /** 无论成功失败都能拿到的结构化诊断，见 [ConfigDiagnostics]。 */
    val diagnostics: ConfigDiagnostics

    data class Success(
        val json: String,
        /** 内容哈希，用于判断是否需要重启内核，见 docs/DESIGN.md §6.3。 */
        val fingerprint: String,
        /** 被跳过的无效节点，UI 可提示用户但不阻断启动。 */
        val warnings: List<ConfigError> = emptyList(),
    ) : ConfigResult {
        override val diagnostics: ConfigDiagnostics
            get() = ConfigDiagnostics(warnings = warnings)
    }

    data class Failure(
        val errors: List<ConfigError>,
        /** 失败时同样可能攒下了一批非阻断问题，别丢掉。 */
        val warnings: List<ConfigError> = emptyList(),
    ) : ConfigResult {
        override val diagnostics: ConfigDiagnostics
            get() = ConfigDiagnostics(blocking = errors, warnings = warnings)
    }
}
