package com.niceproxy.core.service.pac

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class PacScriptTest {

    @Nested
    @DisplayName("代理串")
    inner class Proxies {

        @Test
        @DisplayName("同时提供 HTTP 与 SOCKS 时按优先级串联")
        fun proxyChainOrder() {
            val script = PacScript.build(
                PacScript.Options(host = "192.168.1.8", httpPort = 8080, socksPort = 1080),
            )
            assertThat(script).contains("PROXY 192.168.1.8:8080; SOCKS5 192.168.1.8:1080")
        }

        @Test
        @DisplayName("只有 SOCKS 时不写出 PROXY 条目")
        fun socksOnly() {
            val script = PacScript.build(
                PacScript.Options(host = "10.0.0.2", httpPort = null, socksPort = 1080),
            )
            assertThat(script).contains("SOCKS5 10.0.0.2:1080")
            assertThat(script).doesNotContain("PROXY ")
        }
    }

    @Nested
    @DisplayName("DIRECT 兜底")
    inner class DirectFallback {

        @Test
        @DisplayName("默认不兜底 —— 代理挂了要让客户端连不上，而不是静默裸奔")
        fun failsClosedByDefault() {
            // Switch / PS5 / 电视盒子没有任何界面能提示「你现在没在走代理」。
            // 断网是可见的失败，裸奔是不可见的失败，后者糟糕得多。
            val script = PacScript.build(
                PacScript.Options(host = "192.168.1.8", httpPort = 8080, socksPort = 1080),
            )
            assertThat(script).contains(
                """return "PROXY 192.168.1.8:8080; SOCKS5 192.168.1.8:1080";""",
            )
        }

        @Test
        @DisplayName("显式打开后才追加 DIRECT")
        fun optsIn() {
            val script = PacScript.build(
                PacScript.Options(
                    host = "192.168.1.8",
                    httpPort = 8080,
                    socksPort = null,
                    allowDirectFallback = true,
                ),
            )
            assertThat(script).contains("""return "PROXY 192.168.1.8:8080; DIRECT";""")
        }

        @Test
        @DisplayName("一个代理都没有时仍写出 DIRECT，否则是一份客户端解析不了的空规则")
        fun neverEmitsEmptyRule() {
            // 这时没有任何代理会「挂」，也就无所谓静默回落
            val script = PacScript.build(
                PacScript.Options(host = "h", httpPort = null, socksPort = null),
            )
            assertThat(script).contains("""return "DIRECT";""")
        }
    }

    @Nested
    @DisplayName("直连规则")
    inner class DirectPatterns {

        @Test
        @DisplayName("局域网与本机地址默认绕行")
        fun bypassesPrivateRanges() {
            // 不绕行的话，客户端访问网关自己或路由器管理页会绕一圈回到本机，
            // 轻则变慢，重则形成回环
            val script = PacScript.build(
                PacScript.Options(host = "192.168.1.8", httpPort = 8080, socksPort = null),
            )
            listOf("192.168.*", "10.*", "172.16.*", "127.0.0.1", "*.local").forEach {
                assertThat(script).contains("shExpMatch(host, \"$it\")")
            }
            assertThat(script).contains("isPlainHostName(host)")
        }

        @Test
        @DisplayName("额外直连域名被写入且去重")
        fun extraDirect() {
            val script = PacScript.build(
                PacScript.Options(
                    host = "h",
                    httpPort = 8080,
                    socksPort = null,
                    // 192.168.* 与默认项重复，不应出现两次
                    extraDirect = listOf("*.cn", "192.168.*", ""),
                ),
            )
            assertThat(script).contains("shExpMatch(host, \"*.cn\")")
            val occurrences = Regex("""shExpMatch\(host, "192\.168\.\*"\)""").findAll(script).count()
            assertThat(occurrences).isEqualTo(1)
        }

        @Test
        @DisplayName("模式串中的引号被转义，不会破坏脚本语法")
        fun escapesQuotes() {
            val script = PacScript.build(
                PacScript.Options(
                    host = "h",
                    httpPort = 8080,
                    socksPort = null,
                    extraDirect = listOf("""a"b"""),
                ),
            )
            assertThat(script).contains("""shExpMatch(host, "a\"b")""")
        }
    }

    @Nested
    @DisplayName("host 校验")
    inner class HostValidation {

        @Test
        @DisplayName("域名、IPv4、带方括号的 IPv6 都放行")
        fun acceptsRealHosts() {
            listOf(
                "192.168.1.8",
                "gateway.local",
                "nice-proxy_1",
                "[fe80::1]",
                "[fe80::1%wlan0]",
            ).forEach { assertThat(PacScript.isValidHost(it)).isTrue() }
        }

        @Test
        @DisplayName("裸 IPv6 被拒 —— PAC 里的冒号是端口分隔符，不加括号客户端会解析错")
        fun rejectsUnbracketedIpv6() {
            assertThat(PacScript.isValidHost("fe80::1")).isFalse()
        }

        @Test
        @DisplayName("能破坏脚本语法的 host 一律被拒")
        fun rejectsInjection() {
            // 这些字符串来自不可信的 Host 头。放进去会产出一份语法坏掉的 PAC，
            // 而客户端只会显示「代理配置无效」，没有任何线索指向真正的原因。
            listOf(
                "",
                "  ",
                """a"b""",
                "a;DIRECT",
                "a b",
                "a\nb",
                "a\\b",
                "<script>",
            ).forEach { assertThat(PacScript.isValidHost(it)).isFalse() }
        }

        @Test
        @DisplayName("即便真拼进去了，转义也保证脚本语法不坏")
        fun quotesEvenIfHostSlipsThrough() {
            // 校验与转义是两道独立的防线：漏掉哪一道都不该导致坏脚本
            val script = PacScript.build(
                PacScript.Options(host = """a"b""", httpPort = 8080, socksPort = null),
            )
            assertThat(script).contains("""return "PROXY a\"b:8080";""")
        }
    }
}
