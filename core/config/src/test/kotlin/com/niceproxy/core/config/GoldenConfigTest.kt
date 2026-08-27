package com.niceproxy.core.config

import com.google.common.truth.Truth.assertThat
import com.niceproxy.core.model.InboundAuth
import com.niceproxy.core.model.RoutingRule
import com.niceproxy.core.model.RuleAction
import com.niceproxy.core.model.RuleMatcher
import com.niceproxy.core.model.RuleSetRef
import com.niceproxy.core.model.WellKnownTag
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 快照测试：把一份「典型完整配置」的生成结果与 golden 文件逐字符比对。
 *
 * 单项断言只能覆盖被想到的字段，快照能兜住所有没想到的意外改动 ——
 * 比如某次重构顺手改了字段顺序或默认值。
 *
 * 生成器输出确实需要变更时，用 `-Dgolden.update=true` 重新生成，
 * 然后 **人工 review golden 文件的 diff**，再提交。
 */
class GoldenConfigTest {

    @Test
    fun `典型完整配置与快照一致`() {
        val actual = SingBoxConfigBuilder().build(fullFeaturedInput())
        assertThat(actual).isInstanceOf(ConfigResult.Success::class.java)
        val json = (actual as ConfigResult.Success).json.normalizeLineEndings()

        if (System.getProperty("golden.update") == "true") {
            File(GOLDEN_SOURCE_PATH).writeText(json)
            return
        }

        val expected = javaClass.getResourceAsStream(GOLDEN_RESOURCE)
            ?.bufferedReader()
            ?.readText()
            ?.normalizeLineEndings()
            ?: error("缺少快照文件 $GOLDEN_RESOURCE，请用 -Dgolden.update=true 生成")

        assertThat(json).isEqualTo(expected)
    }

    private fun fullFeaturedInput() = Fixtures.input(
        inbounds = listOf(
            Fixtures.mixedInbound("mix", 8080, InboundAuth("alice", "s3cret")),
        ),
        nodes = listOf(
            Fixtures.hysteria2(obfsType = "salamander", obfsPassword = "ob"),
            Fixtures.vlessReality(),
        ),
        rules = listOf(
            RoutingRule(
                id = "r1", name = "国内域名直连", sortOrder = 0,
                matcher = RuleMatcher(ruleSet = listOf("geosite-cn")),
                action = RuleAction.Route(WellKnownTag.DIRECT),
            ),
            RoutingRule(
                id = "r2", name = "国内 IP 直连", sortOrder = 1,
                matcher = RuleMatcher(ruleSet = listOf("geoip-cn")),
                action = RuleAction.Route(WellKnownTag.DIRECT),
            ),
        ),
        ruleSets = listOf(
            RuleSetRef("rs1", "geosite-cn", url = "https://example.com/geosite-cn.srs"),
            RuleSetRef(
                "rs2", "geoip-cn",
                url = "https://example.com/geoip-cn.srs",
                containsIpRules = true,
            ),
        ),
    )

    private companion object {
        const val GOLDEN_RESOURCE = "/golden/full-config.json"
        const val GOLDEN_SOURCE_PATH = "src/test/resources/golden/full-config.json"
    }
}

private fun String.normalizeLineEndings(): String = replace("\r\n", "\n").trimEnd()
