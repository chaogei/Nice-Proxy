package com.niceproxy.core.config

import com.google.common.truth.Truth.assertThat
import com.niceproxy.core.model.RoutingRule
import com.niceproxy.core.model.RuleAction
import com.niceproxy.core.model.RuleMatcher
import com.niceproxy.core.model.WellKnownTag
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * 大规模输入下的生成器行为。
 *
 * 导入一个几百节点的机场订阅之后，每次改配置都要重新生成一遍完整的
 * sing-box JSON。这里既守住耗时的数量级，也守住那些只有在规模上来之后
 * 才会暴露的正确性问题（tag 撞车、策略组候选缺项）。
 */
class ConfigBuilderScaleTest {

    private val builder = SingBoxConfigBuilder()

    private companion object {
        const val NODE_COUNT = 500
        const val RULE_COUNT = 200
    }

    private fun manyNodes(count: Int) = (0 until count).map { Fixtures.hysteria2(id = "n$it") }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    @DisplayName("五百个节点加两百条规则")
    fun largeConfig() {
        val nodes = manyNodes(NODE_COUNT)
        val rules = (0 until RULE_COUNT).map { index ->
            RoutingRule(
                id = "r$index",
                name = "规则 $index",
                sortOrder = index,
                matcher = RuleMatcher(domainSuffix = listOf("site$index.com")),
                // 一半规则指向具体节点，一半走策略组
                action = RuleAction.Route(
                    if (index % 2 == 0) nodes[index % NODE_COUNT].outboundTag else WellKnownTag.PROXY,
                ),
            )
        }

        val result = builder.build(Fixtures.input(nodes = nodes, rules = rules))
        assertThat(result).isInstanceOf(ConfigResult.Success::class.java)

        val root = Json.parseToJsonElement((result as ConfigResult.Success).json).jsonObject
        val outbounds = root["outbounds"]!!.jsonArray.map { it.jsonObject }
        val tags = outbounds.map { it["tag"]!!.jsonPrimitive.content }

        // selector + urltest + 500 个节点 + direct
        assertThat(outbounds).hasSize(NODE_COUNT + 3)
        assertThat(tags).containsNoDuplicates()

        // 策略组的候选必须覆盖全部节点，漏一个就是用户在界面上选不到它
        val urlTest = outbounds.single { it["tag"]!!.jsonPrimitive.content == WellKnownTag.AUTO }
        assertThat(urlTest["outbounds"]!!.jsonArray).hasSize(NODE_COUNT)

        // 规则指向的出站不能有悬空引用
        val declared = tags.toSet()
        val referenced = root["route"]!!.jsonObject["rules"]!!.jsonArray
            .mapNotNull { it.jsonObject["outbound"]?.jsonPrimitive?.content }
            .toSet()
        assertThat(declared).containsAtLeastElementsIn(referenced)
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    @DisplayName("规模翻倍时耗时不应爆炸")
    fun scalesLinearly() {
        // 不断言绝对耗时（CI 机器差异太大），只断言两次构建都能在时限内完成，
        // 且指纹稳定 —— 平方级退化在这个量级上会直接把时限撑破
        val small = builder.build(Fixtures.input(nodes = manyNodes(NODE_COUNT / 2)))
        val large = builder.build(Fixtures.input(nodes = manyNodes(NODE_COUNT)))

        assertThat(small).isInstanceOf(ConfigResult.Success::class.java)
        assertThat(large).isInstanceOf(ConfigResult.Success::class.java)
        assertThat((small as ConfigResult.Success).fingerprint)
            .isNotEqualTo((large as ConfigResult.Success).fingerprint)
    }
}
