package com.niceproxy.feature.routing

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.niceproxy.R
import com.niceproxy.core.model.RoutingMode
import com.niceproxy.core.model.RuleAction
import com.niceproxy.core.model.RuleMatcher
import com.niceproxy.core.model.WellKnownTag

/**
 * 把规则的匹配条件与动作翻译成界面文案。
 *
 * `RoutingMode.displayName` / `RuleAction` 的人类可读名字原本散落在各个
 * Composable 里，直接拼中文字面量。而 `RoutingMode` 自带的 `displayName`
 * 又是 `core:model` 里写死的中文常量 —— 那一层没有 `Context`，也不该有。
 * 所以映射只能落在 app 这一侧：枚举进来，字符串资源 ID 出去。
 *
 * 选「哪条资源、带什么参数」这一步刻意做成不依赖 `Context` 的纯函数
 * （[summaryParts] / [labelRes]），这样它能在 JVM 单测里被直接断言；
 * 真正取字符串的那一步才需要 Compose。
 */
internal data class RulePart(@StringRes val labelRes: Int, val value: String? = null)

/**
 * 列表里一条规则只有两行高，塞不下一个引用了三十个域名的匹配条件。
 *
 * @param sampleLimit 每类条件最多列几个具体值。超出的部分不做省略号提示 ——
 *        这里的目标是让用户认出「是哪一条」，看全部得点进编辑页。
 */
internal fun RuleMatcher.summaryParts(sampleLimit: Int = SAMPLE_LIMIT): List<RulePart> = buildList {
    if (ruleSet.isNotEmpty()) {
        add(RulePart(R.string.routing_match_ruleset, ruleSet.joinToString()))
    }
    if (domainSuffix.isNotEmpty()) {
        add(RulePart(R.string.routing_match_domain_suffix, domainSuffix.sample(sampleLimit)))
    }
    if (domain.isNotEmpty()) {
        add(RulePart(R.string.routing_match_domain, domain.sample(sampleLimit)))
    }
    if (domainKeyword.isNotEmpty()) {
        add(RulePart(R.string.routing_match_keyword, domainKeyword.sample(sampleLimit)))
    }
    if (ipCidr.isNotEmpty()) {
        add(RulePart(R.string.routing_match_ip, ipCidr.sample(sampleLimit)))
    }
    if (sourceIpCidr.isNotEmpty()) {
        add(RulePart(R.string.routing_match_source, sourceIpCidr.sample(sampleLimit)))
    }
    if (port.isNotEmpty()) {
        add(RulePart(R.string.routing_match_port, port.joinToString()))
    }
    if (ipIsPrivate == true) {
        add(RulePart(R.string.routing_match_private))
    }
    if (protocol.isNotEmpty()) {
        add(RulePart(R.string.routing_match_protocol, protocol.joinToString()))
    }
}

/**
 * 动作的可读名字。
 *
 * `direct` / `proxy` 是配置生成器保留的固定 tag，用户在界面上看到的应该是
 * 「直连」「代理」；其余 tag 是用户自己给节点起的名字，原样显示 —— 翻译
 * 用户输入的内容只会让他们认不出自己写的东西。
 */
internal fun RuleAction.label(): RulePart = when (this) {
    is RuleAction.Route -> when (outboundTag) {
        WellKnownTag.DIRECT -> RulePart(R.string.routing_action_direct)
        WellKnownTag.PROXY -> RulePart(R.string.routing_action_proxy)
        // 资源 ID 为 0 表示「没有资源，直接用 value」，由 resolve 处理
        else -> RulePart(0, outboundTag)
    }
    is RuleAction.Reject -> RulePart(R.string.routing_action_reject)
    RuleAction.HijackDns -> RulePart(R.string.routing_action_hijack_dns)
    is RuleAction.Sniff -> RulePart(R.string.routing_action_sniff)
    is RuleAction.Resolve -> RulePart(R.string.routing_action_resolve)
}

@StringRes
internal fun RoutingMode.labelRes(): Int = when (this) {
    RoutingMode.GLOBAL_PROXY -> R.string.routing_mode_global_proxy
    RoutingMode.BYPASS_MAINLAND -> R.string.routing_mode_bypass_mainland
    RoutingMode.GLOBAL_DIRECT -> R.string.routing_mode_global_direct
    RoutingMode.CUSTOM -> R.string.routing_mode_custom
}

@StringRes
internal fun RoutingMode.descriptionRes(): Int = when (this) {
    RoutingMode.GLOBAL_PROXY -> R.string.routing_mode_global_proxy_desc
    RoutingMode.BYPASS_MAINLAND -> R.string.routing_mode_bypass_mainland_desc
    RoutingMode.GLOBAL_DIRECT -> R.string.routing_mode_global_direct_desc
    RoutingMode.CUSTOM -> R.string.routing_mode_custom_desc
}

@Composable
internal fun RulePart.resolve(): String = when {
    labelRes == 0 -> value.orEmpty()
    value == null -> stringResource(labelRes)
    else -> stringResource(labelRes, value)
}

@Composable
internal fun RuleMatcher.summary(): String {
    val parts = summaryParts()
    if (parts.isEmpty()) return stringResource(R.string.routing_match_all)
    // joinToString 的 lambda 不是 @Composable，先逐个取好再拼。
    val resolved = parts.map { it.resolve() }
    // 分隔符本身也得跟着语言走：中文用全角逗号，英文用「, 」。
    return resolved.joinToString(stringResource(R.string.routing_match_separator))
}

private fun <T> List<T>.sample(limit: Int): String = take(limit).joinToString()

private const val SAMPLE_LIMIT = 2
