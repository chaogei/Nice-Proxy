package com.niceproxy.feature.routing

import com.google.common.truth.Truth.assertThat
import com.niceproxy.R
import com.niceproxy.core.model.RoutingMode
import com.niceproxy.core.model.RuleAction
import com.niceproxy.core.model.RuleMatcher
import com.niceproxy.core.model.WellKnownTag
import org.junit.jupiter.api.Test

/**
 * 断言的是「选了哪条资源、带了什么参数」，不是渲染出来的字符串 ——
 * 后者要 Context，而挑资源这一步本来就该独立于 Context 才对。
 */
class RuleLabelsTest {

    @Test
    fun `an empty matcher produces no parts so the caller can say all traffic`() {
        assertThat(RuleMatcher().summaryParts()).isEmpty()
    }

    @Test
    fun `each populated field contributes one part`() {
        val parts = RuleMatcher(
            domainSuffix = listOf("google.com"),
            ipCidr = listOf("8.8.8.0/24"),
        ).summaryParts()

        assertThat(parts).containsExactly(
            RulePart(R.string.routing_match_domain_suffix, "google.com"),
            RulePart(R.string.routing_match_ip, "8.8.8.0/24"),
        ).inOrder()
    }

    /** 列表里一条规则只有两行高，塞不下引用了三十个域名的匹配条件。 */
    @Test
    fun `long value lists are sampled`() {
        val parts = RuleMatcher(
            domain = listOf("a.com", "b.com", "c.com", "d.com"),
        ).summaryParts(sampleLimit = 2)

        assertThat(parts).containsExactly(RulePart(R.string.routing_match_domain, "a.com, b.com"))
    }

    /** ipIsPrivate 是三态的，false 表示「明确不匹配私有地址」，不该显示成「局域网地址」。 */
    @Test
    fun `private address part only appears when the flag is true`() {
        assertThat(RuleMatcher(ipIsPrivate = true).summaryParts())
            .containsExactly(RulePart(R.string.routing_match_private))
        assertThat(RuleMatcher(ipIsPrivate = false).summaryParts()).isEmpty()
        assertThat(RuleMatcher(ipIsPrivate = null).summaryParts()).isEmpty()
    }

    @Test
    fun `part with no value carries no argument`() {
        val part = RuleMatcher(ipIsPrivate = true).summaryParts().single()
        assertThat(part.value).isNull()
    }

    @Test
    fun `reserved outbound tags become translatable labels`() {
        assertThat(RuleAction.Route(WellKnownTag.DIRECT).label())
            .isEqualTo(RulePart(R.string.routing_action_direct))
        assertThat(RuleAction.Route(WellKnownTag.PROXY).label())
            .isEqualTo(RulePart(R.string.routing_action_proxy))
    }

    /**
     * 节点名字是用户自己起的，翻译它只会让人认不出自己写的东西。
     * labelRes 为 0 是「没有资源，原样用 value」的约定。
     */
    @Test
    fun `a user node tag is passed through verbatim`() {
        val part = RuleAction.Route("hk-01").label()

        assertThat(part.labelRes).isEqualTo(0)
        assertThat(part.value).isEqualTo("hk-01")
    }

    @Test
    fun `every action maps to a distinct label`() {
        val actions = listOf(
            RuleAction.Reject(),
            RuleAction.HijackDns,
            RuleAction.Sniff(),
            RuleAction.Resolve(),
        )

        val ids = actions.map { it.label().labelRes }
        assertThat(ids).containsNoDuplicates()
        assertThat(ids).doesNotContain(0)
    }

    /**
     * 少一个分支，那个模式的 chip 就会在界面上变成空白 —— 而 when 是穷尽的，
     * 编译期就会拦下。这里守的是「两个模式指到同一条资源」这种复制粘贴错误。
     */
    @Test
    fun `every routing mode has its own label and description`() {
        val labels = RoutingMode.entries.map { it.labelRes() }
        val descriptions = RoutingMode.entries.map { it.descriptionRes() }

        assertThat(labels).containsNoDuplicates()
        assertThat(descriptions).containsNoDuplicates()
        assertThat(labels).containsNoneIn(descriptions)
    }
}
