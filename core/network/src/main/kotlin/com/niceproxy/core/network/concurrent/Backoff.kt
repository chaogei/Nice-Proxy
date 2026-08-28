package com.niceproxy.core.network.concurrent

import kotlin.random.Random

/**
 * 带抖动的指数退避。
 *
 * 抖动不是装饰。多条流（流量、日志、连接列表）是在内核停止的**同一瞬间**一起断开的，
 * 没有抖动的话它们此后每一次重连都严格同步 —— 内核重启时会被三条连接在同一毫秒
 * 同时敲门，而这三次里最多只有一次能成功，剩下两次的失败又把三条流的退避一起翻倍。
 * 多订阅并发刷新撞上机场限流时是同一个故事。
 *
 * 采用「完全抖动」（full jitter）：实际等待是 `[floor, ceiling]` 区间内的均匀取值，
 * 而不是在标称值上下浮动一点。这是分散重连时刻最有效的形式。
 */
class ExponentialBackoff(
    private val baseMillis: Long,
    private val maxMillis: Long,
    /** 下界占标称值的比例。0 就是完全抖动，1 则退化为无抖动。 */
    private val floorRatio: Double = DEFAULT_FLOOR_RATIO,
    private val random: Random = Random.Default,
) {

    init {
        require(baseMillis > 0) { "退避基准必须为正数" }
        require(maxMillis >= baseMillis) { "退避上限不能小于基准" }
        require(floorRatio in 0.0..1.0) { "抖动下界比例必须落在 0..1" }
    }

    /**
     * 第 [attempt] 次失败之后该等多久。[attempt] 从 1 开始。
     *
     * 位移**之前**先判断会不会溢出，而不是移完再夹。`Long` 左移 64 位在 JVM 上等于
     * 左移 0 位，移到六十几位则直接翻成负数 —— 两种情况下退避曲线都会**绕回**基准值，
     * 从此变成忙等。这条路径要连续失败几十次才走得到，灰度根本碰不着。
     */
    fun delayMillis(attempt: Int): Long {
        val shift = (attempt - 1).coerceIn(0, MAX_SHIFT)
        val ceiling = if (baseMillis > (maxMillis shr shift)) {
            maxMillis
        } else {
            (baseMillis shl shift).coerceIn(baseMillis, maxMillis)
        }
        val floor = (ceiling * floorRatio).toLong().coerceIn(0, ceiling)
        if (floor >= ceiling) return ceiling
        return floor + random.nextLong(ceiling - floor + 1)
    }

    companion object {
        /** 62 已经远超 `Long` 溢出所需，再大没有意义。 */
        private const val MAX_SHIFT = 62

        /**
         * 留一半做下界而不是完全抖动。
         *
         * 完全抖动在「本机内核确实停了」这个最常见的场景里会退化：随机值取到接近 0
         * 的那些次数等于没有退避，忙等就是这么回来的。保住一半标称值，
         * 既压住了重试频率，又足够把几条流错开。
         */
        const val DEFAULT_FLOOR_RATIO = 0.5
    }
}
