package com.niceproxy.core.config.share

/**
 * 一条没能解析成节点的输入，连同**为什么**。
 *
 * 原先失败明细只是一段被截断的原文。用户拿到「3 条失败」和三串看不懂的
 * Base64，既不知道是自己复制漏了字符，还是机场用了我们不认识的协议，
 * 也没法向任何人求助 —— 而这两种情况的处理方式完全相反。
 *
 * [entry] 会被截断且**不包含**完整凭据：失败明细会出现在界面上，也可能被
 * 用户直接截图发到群里求助。
 */
data class ParseFailure(
    /** 出问题的条目，链接取前缀、订阅节点取名字。 */
    val entry: String,
    /** 人话原因，例如「wireguard 缺少对端公钥」。 */
    val reason: String,
) {
    val message: String get() = "$entry：$reason"

    internal companion object {
        private const val MAX_ENTRY_LENGTH = 60

        fun of(entry: String, error: Throwable): ParseFailure = ParseFailure(
            entry = truncate(entry),
            reason = error.message?.takeIf { it.isNotBlank() } ?: "无法识别的格式",
        )

        fun of(entry: String, reason: String): ParseFailure =
            ParseFailure(truncate(entry), reason)

        private fun truncate(entry: String): String =
            if (entry.length <= MAX_ENTRY_LENGTH) entry else entry.take(MAX_ENTRY_LENGTH) + "…"
    }
}
