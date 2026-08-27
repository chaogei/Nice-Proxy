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

    /**
     * 允许出现在代理地址里的写法：加了方括号的 IPv6 字面量，或者不含冒号的
     * 域名 / IPv4。
     *
     * 用白名单而不是黑名单：host 来自请求方的 `Host` 头，是完全不可信的输入，
     * 而它会被原样拼进一段 JavaScript 字符串。黑名单漏掉一个字符就是一份语法坏掉的
     * PAC，客户端那边只会显示「代理配置无效」，没有任何线索指向是谁发的畸形请求。
     *
     * 裸的 IPv6（`::1`）会被拒绝而不是放行：PAC 的 `PROXY host:port` 里冒号是端口
     * 分隔符，不加方括号的话客户端解析出来的端口是错的。加括号是调用方的责任。
     */
    private val ALLOWED_HOST = Regex("""^(?:\[[0-9A-Za-z:.%_-]+]|[0-9A-Za-z._-]+)$""")

    data class Options(
        /** 客户端应当连接的主机名或 IP。非法字符会被 [isValidHost] 挡下。 */
        val host: String,
        /** HTTP 代理端口，null 表示不提供 HTTP 代理。 */
        val httpPort: Int?,
        /** SOCKS5 代理端口，null 表示不提供。 */
        val socksPort: Int?,
        /** 额外的直连域名/通配符。 */
        val extraDirect: List<String> = emptyList(),
        /**
         * 代理都不可用时是否允许客户端直连。
         *
         * **默认关闭，这是一个安全决定而不是功能取舍。** 打开它，代理一挂，
         * Switch、PS5、电视盒子就会静默地裸奔出去 —— 而这类设备没有任何界面能提示
         * 「你现在没在走代理」，用户不会察觉，可能几周之后才从别的地方发现。
         * 关掉它，代理挂了就是连不上网：这是一个**看得见**的失败，用户会立刻去查。
         *
         * 断网是可见的失败，裸奔是不可见的失败，后者要糟糕得多。
         */
        val allowDirectFallback: Boolean = false,
    )

    /**
     * host 是否可以安全地拼进脚本。
     *
     * 交给调用方判断而不是在 [build] 里悄悄替换：只有拿着 socket 的那一方才知道
     * 该回落到哪个地址，这里换成一个猜的地址等于给客户端一个连不上的代理。
     */
    fun isValidHost(host: String): Boolean =
        host.isNotBlank() && ALLOWED_HOST.matches(host)

    fun build(options: Options): String {
        val proxies = buildList {
            // HTTP 排在前面：多数客户端对 HTTP 代理的支持比 SOCKS 更成熟
            options.httpPort?.let { add("PROXY ${options.host}:$it") }
            options.socksPort?.let { add("SOCKS5 ${options.host}:$it") }
            // 一个代理都没配的时候，「fail-closed」没有意义可言 —— 没有代理会挂，
            // 也就谈不上静默回落。返回空串反而是一份客户端无法解析的坏 PAC。
            if (options.allowDirectFallback || isEmpty()) add("DIRECT")
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
            |    return ${proxies.jsQuote()};
            |}
        """.trimMargin()
    }

    /** PAC 脚本是 JavaScript，写进字符串字面量的内容里，引号和反斜杠必须转义。 */
    private fun String.jsQuote(): String =
        "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
