package com.niceproxy.core.data

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * 规则集的裁剪。
 *
 * 每个 .srs 都是几百 KB，内核启动时会挨个去下载，下不来就卡在启动阶段。
 * 所以「哪些规则集真的需要」这个判断直接影响冷启动体验。
 */
internal class ConfigRepositoryTest {

    @Test
    fun `只保留被规则引用到的规则集`() {
        val ruleSets = listOf(ruleSet("geosite-cn"), ruleSet("geoip-cn"), ruleSet("geosite-ads"))
        val rules = listOf(rule("r1", ruleSets = listOf("geosite-cn")))

        assertThat(ruleSets.filterReferencedBy(rules).map { it.tag })
            .containsExactly("geosite-cn")
    }

    @Test
    fun `停用的规则不算引用`() {
        // 用户把「拦截广告」这条规则关掉之后，就没必要再下载广告规则集了
        val ruleSets = listOf(ruleSet("geosite-ads"))
        val rules = listOf(rule("r1", enabled = false, ruleSets = listOf("geosite-ads")))

        assertThat(ruleSets.filterReferencedBy(rules)).isEmpty()
    }

    @Test
    fun `停用的规则集即使被引用也不保留`() {
        // 否则会生成一条指向未声明 tag 的规则，内核拒绝加载整份配置
        val ruleSets = listOf(ruleSet("geosite-cn", enabled = false))
        val rules = listOf(rule("r1", ruleSets = listOf("geosite-cn")))

        assertThat(ruleSets.filterReferencedBy(rules)).isEmpty()
    }

    @Test
    fun `同一个规则集被多条规则引用只出现一次`() {
        val ruleSets = listOf(ruleSet("geosite-cn"))
        val rules = listOf(
            rule("r1", ruleSets = listOf("geosite-cn")),
            rule("r2", ruleSets = listOf("geosite-cn")),
        )

        assertThat(ruleSets.filterReferencedBy(rules)).hasSize(1)
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @DisplayName("规则数量上千时不退化成乘积复杂度")
    fun scalesWithManyRules() {
        // 每次生成配置都会跑一遍这个裁剪，逐个规则集去遍历全部规则会退化成 O(规则数 × 规则集数)
        val ruleSets = (0 until 200).map { ruleSet("rs-$it") }
        val rules = (0 until 5000).map { rule("r-$it", ruleSets = listOf("rs-${it % 200}")) }

        assertThat(ruleSets.filterReferencedBy(rules)).hasSize(200)
    }
}
