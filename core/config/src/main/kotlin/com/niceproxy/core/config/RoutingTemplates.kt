package com.niceproxy.core.config

import com.niceproxy.core.model.RoutingMode
import com.niceproxy.core.model.RoutingRule
import com.niceproxy.core.model.RuleAction
import com.niceproxy.core.model.RuleMatcher
import com.niceproxy.core.model.RuleSetFormat
import com.niceproxy.core.model.RuleSetRef
import com.niceproxy.core.model.RuleSetType
import com.niceproxy.core.model.WellKnownTag

/**
 * 开箱即用的分流模板。
 *
 * 绝大多数用户不会去手写规则，模板决定了他们对这个应用「好不好用」的第一印象。
 * 见 docs/DESIGN.md FR-4.5。
 */
object RoutingTemplates {

    /** sing-box 官方维护的规则集，二进制格式体积小、加载快。 */
    private const val GEOSITE_BASE =
        "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set"
    private const val GEOIP_BASE =
        "https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set"

    val geositeCn = RuleSetRef(
        id = "geosite-cn",
        tag = "geosite-cn",
        type = RuleSetType.REMOTE,
        format = RuleSetFormat.BINARY,
        url = "$GEOSITE_BASE/geosite-cn.srs",
    )

    val geoipCn = RuleSetRef(
        id = "geoip-cn",
        tag = "geoip-cn",
        type = RuleSetType.REMOTE,
        format = RuleSetFormat.BINARY,
        url = "$GEOIP_BASE/geoip-cn.srs",
        containsIpRules = true,
    )

    val geositeAds = RuleSetRef(
        id = "geosite-category-ads-all",
        tag = "geosite-category-ads-all",
        type = RuleSetType.REMOTE,
        format = RuleSetFormat.BINARY,
        url = "$GEOSITE_BASE/geosite-category-ads-all.srs",
    )

    val allRuleSets = listOf(geositeCn, geoipCn, geositeAds)

    /**
     * 生成模板对应的规则列表。
     *
     * 顺序即优先级：广告拦截在最前（否则会被后面的直连规则截胡），
     * 域名规则先于 IP 规则（IP 规则需要先解析域名，代价更高）。
     */
    fun rulesFor(mode: RoutingMode): List<RoutingRule> = when (mode) {
        RoutingMode.GLOBAL_PROXY -> emptyList()

        RoutingMode.GLOBAL_DIRECT -> listOf(
            rule("all-direct", "全部直连", 0, RuleMatcher(network = listOf("tcp", "udp")),
                RuleAction.Route(WellKnownTag.DIRECT)),
        )

        RoutingMode.BYPASS_MAINLAND -> listOf(
            rule(
                "block-ads", "拦截广告域名", 0,
                RuleMatcher(ruleSet = listOf(geositeAds.tag)),
                RuleAction.Reject(),
            ),
            rule(
                "direct-private", "局域网直连", 1,
                RuleMatcher(ipIsPrivate = true),
                RuleAction.Route(WellKnownTag.DIRECT),
            ),
            rule(
                "direct-cn-site", "国内域名直连", 2,
                RuleMatcher(ruleSet = listOf(geositeCn.tag)),
                RuleAction.Route(WellKnownTag.DIRECT),
            ),
            rule(
                "direct-cn-ip", "国内 IP 直连", 3,
                RuleMatcher(ruleSet = listOf(geoipCn.tag)),
                RuleAction.Route(WellKnownTag.DIRECT),
            ),
        )

        RoutingMode.CUSTOM -> emptyList()
    }

    /** 模板所需的规则集。未被任何规则引用的规则集不必下载。 */
    fun ruleSetsFor(mode: RoutingMode): List<RuleSetRef> = when (mode) {
        RoutingMode.BYPASS_MAINLAND -> listOf(geositeAds, geositeCn, geoipCn)
        else -> emptyList()
    }

    private fun rule(
        id: String,
        name: String,
        order: Int,
        matcher: RuleMatcher,
        action: RuleAction,
    ) = RoutingRule(
        id = id,
        name = name,
        enabled = true,
        sortOrder = order,
        matcher = matcher,
        action = action,
    )
}
