package com.niceproxy.core.service.pac

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class PacScriptTest {

    @Test
    @DisplayName("同时提供 HTTP 与 SOCKS 时按优先级串联，并以 DIRECT 兜底")
    fun proxyChainOrder() {
        val script = PacScript.build(
            PacScript.Options(host = "192.168.1.8", httpPort = 8080, socksPort = 1080),
        )
        assertThat(script).contains("PROXY 192.168.1.8:8080; SOCKS5 192.168.1.8:1080; DIRECT")
    }

    @Test
    @DisplayName("只有 SOCKS 时不写出 PROXY 条目")
    fun socksOnly() {
        val script = PacScript.build(
            PacScript.Options(host = "10.0.0.2", httpPort = null, socksPort = 1080),
        )
        assertThat(script).contains("SOCKS5 10.0.0.2:1080; DIRECT")
        assertThat(script).doesNotContain("PROXY ")
    }

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
