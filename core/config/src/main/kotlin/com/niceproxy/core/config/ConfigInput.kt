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
}

sealed interface ConfigResult {
    data class Success(
        val json: String,
        /** 内容哈希，用于判断是否需要重启内核，见 docs/DESIGN.md §6.3。 */
        val fingerprint: String,
        /** 被跳过的无效节点，UI 可提示用户但不阻断启动。 */
        val warnings: List<ConfigError> = emptyList(),
    ) : ConfigResult

    data class Failure(val errors: List<ConfigError>) : ConfigResult
}
