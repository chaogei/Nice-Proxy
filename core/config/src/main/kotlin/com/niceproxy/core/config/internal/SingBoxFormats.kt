package com.niceproxy.core.config.internal

/**
 * sing-box 在**解析阶段**就会拒绝的字面量格式。
 *
 * 这些字段在我们的模型里都是 `String`，界面上填错不会有任何反馈；而内核解析
 * 配置用的是 `DisallowUnknownFields` 的严格解码器，一个 `hop_interval: "30秒"`
 * 会让整份配置连同其余全部节点一起加载失败。所以生成侧必须先自己判一遍，
 * 把它退化成「这一个节点不可用」。
 */
internal object SingBoxFormats {

    /**
     * `badoption.Duration`，由 sing 自己的 `my_time.ParseDuration` 解析：
     * Go 标准库那套 `300ms` / `1.5h` / `2h45m`，外加一个 `d`（天）单位。
     */
    private val DURATION = Regex("""^[+-]?(\d+(\.\d+)?(ns|us|µs|μs|ms|s|m|h|d))+$""")

    /** `server_ports` 里的单项：`443` 或 `20000:30000`。 */
    private val PORT_RANGE = Regex("""^\d{1,5}(:\d{1,5})?$""")

    private val UUID = Regex(
        """^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$""",
    )

    private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    /** 标准 Base64 表（WireGuard 密钥用的是这一套，不是 URL 安全表）。 */
    private val BASE64_STD = Regex("""^[A-Za-z0-9+/]+={0,2}$""")

    fun isDuration(value: String): Boolean = value == "0" || DURATION.matches(value)

    fun isPortRange(value: String): Boolean {
        if (!PORT_RANGE.matches(value)) return false
        val parts = value.split(':').map { it.toIntOrNull() ?: return false }
        if (parts.any { it !in 1..65535 }) return false
        return parts.size == 1 || parts[0] <= parts[1]
    }

    fun isUuid(value: String): Boolean = UUID.matches(value)

    /**
     * `netip.Prefix`：地址后面**必须**跟掩码位。
     *
     * 生态里的 WireGuard 配置经常只写 `10.0.0.2`（wg-quick 允许省略），
     * 照抄进 sing-box 会在解析阶段炸掉整份配置。
     */
    fun isCidrPrefix(value: String): Boolean {
        val slash = value.lastIndexOf('/')
        if (slash <= 0 || slash == value.length - 1) return false
        val address = value.substring(0, slash)
        val bits = value.substring(slash + 1).toIntOrNull() ?: return false
        return when {
            IPV4.matches(address) ->
                bits in 0..32 && address.split('.').all { (it.toIntOrNull() ?: 256) in 0..255 }
            address.contains(':') -> bits in 0..128 && isIpv6Literal(address)
            else -> false
        }
    }

    /** WireGuard 密钥恒为 32 字节，标准 Base64 之后固定 44 字符。 */
    fun isWireGuardKey(value: String): Boolean =
        value.length == WIREGUARD_KEY_LENGTH &&
            value.endsWith('=') &&
            !value.endsWith("==") &&
            BASE64_STD.matches(value)

    /**
     * 只做字符层面的判断：真正的 IPv6 解析交给内核。
     *
     * 这里的目标是拦住「明显不是地址」的输入（域名、空串、带端口的写法），
     * 而不是复刻一遍 RFC 4291 —— 后者写错的概率比它挡住的问题还高。
     */
    private fun isIpv6Literal(value: String): Boolean =
        value.isNotEmpty() &&
            value.count { it == ':' } in 2..7 &&
            value.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }

    private const val WIREGUARD_KEY_LENGTH = 44
}
