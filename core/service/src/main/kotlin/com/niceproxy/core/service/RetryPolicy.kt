package com.niceproxy.core.service

/**
 * 内核启动失败后的退避策略。
 *
 * 抽成纯函数是为了能测：这段算术算错的后果一头是重试风暴（几毫秒一次，
 * 把电量和 CPU 烧光），另一头是永远等不到重试，两边都不会有任何报错，
 * 只能靠断言守住。
 */
internal object RetryPolicy {

    /** 到第 6 次时累计已等约 1 分钟，还起不来就不是暂时性故障了。 */
    const val MAX_ATTEMPTS = 6

    private const val INITIAL_DELAY_MS = 1_000L
    private const val MAX_DELAY_MS = 30_000L

    /**
     * 第 [attempt] 次重试前该等多久。attempt 从 1 开始。
     *
     * 指数增长并封顶：端口被别的应用长期占着这类情况，短期内注定失败，
     * 线性重试只是在无谓地耗电。
     */
    fun delayFor(attempt: Int): Long {
        if (attempt <= 1) return INITIAL_DELAY_MS
        // 不能直接用 shl：Kotlin 对 Long 的移位量取低 6 位，
        // attempt 一旦大到 65，1L shl 64 会绕回 1，退避悄悄退化成 1 毫秒。
        val shift = (attempt - 1).coerceAtMost(SAFE_MAX_SHIFT)
        return (INITIAL_DELAY_MS shl shift).coerceIn(INITIAL_DELAY_MS, MAX_DELAY_MS)
    }

    /**
     * @param attempt 这将是第几次重试，从 1 开始
     * @param retryable 确定性错误（配置不合法、后台启动被系统拦下）传 false
     * @param enabled 用户设置里的「内核异常时自动重启」
     */
    fun shouldRetry(attempt: Int, retryable: Boolean, enabled: Boolean): Boolean =
        retryable && enabled && attempt <= MAX_ATTEMPTS

    /** 移到这个位数时早已远超封顶值，再大没有意义且有溢出风险。 */
    private const val SAFE_MAX_SHIFT = 16
}
