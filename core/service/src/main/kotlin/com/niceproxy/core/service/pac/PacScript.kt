package com.niceproxy.core.service.pac

/**
 * 生成 PAC（Proxy Auto-Config）脚本。
 *
 * PAC 的价值在于客户端只需填一个 URL，之后代理地址、端口、绕行规则的变化
 * 都由服务端下发 —— 相比手动在每台设备上填 IP 和端口，改一次就全网生效。
 */
object PacScript {

    /**
     * 默认绕行的目标。
     *
     * 局域网地址必须绕行：客户端访问网关自己、路由器管理页、NAS 时如果走代理，
     * 会绕一圈回到本机，轻则变慢，重则形成回环。
     */
    private val DEFAULT_DIRECT_PATTERNS = listOf(
        "localhost",
        "127.0.0.1",
        "*.local",
        "10.*",
        "172.16.*", "172.17.*", "172.18.*", "172.19.*",
        "172.20.*", "172.21.*", "172.22.*", "172.23.*",
        "172.24.*", "172.25.*", "172.26.*", "172.27.*",
        "172.28.*", "172.29.*", "172.30.*", "172.31.*",
        "192.168.*",
    )

    data class Options(
        /** 客户端应当连接的主机名或 IP。 */
        val host: String,
        /** HTTP 代理端口，null 表示不提供 HTTP 代理。 */
        val httpPort: Int?,
        /** SOCKS5 代理端口，null 表示不提供。 */
        val socksPort: Int?,
        /** 额外的直连域名/通配符。 */
        val extraDirect: List<String> = emptyList(),
    )

    fun build(options: Options): String {
        val proxies = buildList {
            // HTTP 排在前面：多数客户端对 HTTP 代理的支持比 SOCKS 更成熟；
            // 末尾的 DIRECT 是兜底，代理不可用时不至于完全断网。
            options.httpPort?.let { add("PROXY ${options.host}:$it") }
            options.socksPort?.let { add("SOCKS5 ${options.host}:$it") }
            add("DIRECT")
        }.joinToString("; ")

        val directPatterns = (DEFAULT_DIRECT_PATTERNS + options.extraDirect)
            .filter { it.isNotBlank() }
            .distinct()

        val shExpChecks = directPatterns.joinToString("\n") { pattern ->
            "    if (shExpMatch(host, ${pattern.jsQuote()})) return \"DIRECT\";"
        }

        return """
            |function FindProxyForURL(url, host) {
            |    host = host.toLowerCase();
            |
            |    // 无点主机名一般是内网短名，直连
            |    if (isPlainHostName(host)) return "DIRECT";
            |
            |$shExpChecks
            |
            |    return "$proxies";
            |}
        """.trimMargin()
    }

    /** PAC 脚本是 JavaScript，模式串里的引号和反斜杠必须转义。 */
    private fun String.jsQuote(): String =
        "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
