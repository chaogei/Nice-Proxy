package com.niceproxy.core.config

import com.google.common.truth.Truth.assertThat
import com.niceproxy.core.model.InboundAuth
import com.niceproxy.core.model.ProtocolParams
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
        assertMatchesGolden(fullFeaturedInput(), "full-config.json")
    }

    /**
     * WireGuard 与链式代理单独一份快照，不并进 `full-config.json`。
     *
     * 这两件事改动的是配置的**顶层结构**：多出一个与 `outbounds` 平级的
     * `endpoints` 数组，出站里多出 `detour` 字段。混进原快照的话，往后任何
     * 一次改动的 diff 都要在两百多行里辨认「这行是 WireGuard 的还是别的」。
     */
    @Test
    fun `WireGuard 与链式代理配置与快照一致`() {
        assertMatchesGolden(chainedWireGuardInput(), "wireguard-chain.json")
    }

    private fun assertMatchesGolden(input: ConfigInput, fileName: String) {
        val actual = SingBoxConfigBuilder().build(input)
        assertThat(actual).isInstanceOf(ConfigResult.Success::class.java)
        val json = (actual as ConfigResult.Success).json.normalizeLineEndings()

        if (System.getProperty("golden.update") == "true") {
            File("$GOLDEN_SOURCE_DIR/$fileName").writeText(json)
            return
        }

        val resource = "$GOLDEN_RESOURCE_DIR/$fileName"
        val expected = javaClass.getResourceAsStream(resource)
            ?.bufferedReader()
            ?.readText()
            ?.normalizeLineEndings()
            ?: error("缺少快照文件 $resource，请用 -Dgolden.update=true 生成")

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

    /**
     * 中转机在墙内、落地机只接受中转机的 IP —— 链式代理最典型的用法，
     * 再挂一个走同一条链路出去的 WireGuard endpoint。
     */
    private fun chainedWireGuardInput(): ConfigInput {
        val relay = Fixtures.vmessWs("relay")
        return Fixtures.input(
            nodes = listOf(
                relay,
                Fixtures.hysteria2("exit").copy(detour = relay.outboundTag),
                Fixtures.wireGuard("wg", WIREGUARD_PARAMS).copy(detour = relay.outboundTag),
            ),
        )
    }

    private companion object {
        const val GOLDEN_RESOURCE_DIR = "/golden"
        const val GOLDEN_SOURCE_DIR = "src/test/resources/golden"

        val WIREGUARD_PARAMS = ProtocolParams.WireGuard(
            privateKey = Fixtures.WG_PRIVATE_KEY,
            peerPublicKey = Fixtures.WG_PEER_PUBLIC_KEY,
            preSharedKey = Fixtures.WG_PRE_SHARED_KEY,
            localAddress = listOf("172.16.0.2/32", "fd01::2/128"),
            reserved = listOf(209, 98, 59),
            mtu = 1408,
            persistentKeepaliveInterval = 25,
        )
    }
}

private fun String.normalizeLineEndings(): String = replace("\r\n", "\n").trimEnd()
