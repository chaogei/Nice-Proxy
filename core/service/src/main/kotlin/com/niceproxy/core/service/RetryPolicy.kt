package com.niceproxy.core.service

/**
 * 一次失败的成因分类。
 *
 * 以前这里是个裸的 `retryable: Boolean` 参数，散落在各个调用点上。问题在于
 * 「不可重试」这个判断的代价是不对称的：**误判成可重试**只是多试几次，
 * **误判成不可重试**会让代理彻底躺平，而且（见 [deterministic] 的说明）现在还会连带
 * 清掉运行意图 —— 看门狗都不会再来救。所以把它收成一份枚举白名单：只有明确列在
 * 这里、并且真的想清楚了的成因才配拿到 `deterministic = true`，其余一律按暂时性处理。
 */
internal enum class FailureCause(
    /**
     * 确定性错误：同样的输入重试多少次都是同样的结果。
     *
     * 判为 true 意味着两件事 —— 不再原地退避重试，以及**清掉落盘的运行意图**。
     * 后者是为了让用户能把它关掉：终态失败时界面上只剩「启动」按钮，如果运行意图还
     * 留着，看门狗就会每 15 分钟醒来、失败一次、弹一次通知，而用户根本找不到能让它
     * 安静下来的地方。
     */
    val deterministic: Boolean,
) {
    /** 配置本身不合法。用户不改配置，重试一万次也是同一条报错。 */
    InvalidConfig(deterministic = true),

    /**
     * Android 12+ 拦下了后台启动前台服务。
     *
     * 出路是让用户去关电池优化（官方豁免项之一），不是重试 —— 系统的判定在这一次
     * 启动周期内不会改变。
     */
    ForegroundStartBlocked(deterministic = true),

    /**
     * 内核没起来。
     *
     * 归为暂时性是有依据的：这里最常见的三种成因（切 Wi-Fi 时地址还没就绪、上一个
     * 内核实例的端口还没释放、刚开机时网络栈没起来）全都会自己好。
     */
    CoreStartFailed(deterministic = false),

    /** 内核起来了又立刻退出。可能是节点抽风，也可能是端口被别人抢走，都会变。 */
    CoreExitedRepeatedly(deterministic = false),

    /** 兜底。分不清成因时一律按暂时性处理，宁可多试几次也不要把用户锁死。 */
    Unknown(deterministic = false),
}

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
     * @param cause 失败成因，见 [FailureCause.deterministic]
     * @param enabled 用户设置里的「内核异常时自动重启」
     */
    fun shouldRetry(attempt: Int, cause: FailureCause, enabled: Boolean): Boolean =
        !cause.deterministic && enabled && attempt <= MAX_ATTEMPTS

    /**
     * 放弃重试时，是否连「代理本该在跑」这个落盘的意图也一起清掉。
     *
     * 只有确定性错误才清。次数耗尽属于「试过了、暂时不行」，那一位要留着 ——
     * 网络可能过一会儿就好了，15 分钟后的看门狗还有一次机会。
     */
    fun shouldForgetRunIntent(cause: FailureCause): Boolean = cause.deterministic

    /** 移到这个位数时早已远超封顶值，再大没有意义且有溢出风险。 */
    private const val SAFE_MAX_SHIFT = 16
}
