package com.niceproxy.core.network.concurrent

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.random.Random

class ExponentialBackoffTest {

    @Test
    @DisplayName("无抖动时严格按 2 的幂增长，并在上限处停住")
    fun growsExponentiallyAndCaps() {
        val backoff = ExponentialBackoff(baseMillis = 100, maxMillis = 800, floorRatio = 1.0)

        val delays = (1..8).map { backoff.delayMillis(it) }

        assertThat(delays).containsExactly(100L, 200L, 400L, 800L, 800L, 800L, 800L, 800L).inOrder()
    }

    @Test
    @DisplayName("抖动后仍落在 [标称值一半, 标称值] 之内")
    fun jitterStaysWithinBounds() {
        // 完全抖动（下界 0）在「内核确实停了」这个最常见的场景里会退化：
        // 随机取到接近 0 的那几次等于没有退避，忙等就是这么回来的
        val backoff = ExponentialBackoff(baseMillis = 1_000, maxMillis = 8_000)

        repeat(500) {
            val delay = backoff.delayMillis(3)
            assertThat(delay).isAtLeast(2_000L)
            assertThat(delay).isAtMost(4_000L)
        }
    }

    @Test
    @DisplayName("抖动确实在起作用，而不是每次都返回同一个数")
    fun jitterActuallyVaries() {
        // 多条流是在内核停止的**同一瞬间**一起断开的。没有抖动，它们此后每一次重连
        // 都严格同步，内核重启时会被三条连接在同一毫秒同时敲门
        val backoff = ExponentialBackoff(baseMillis = 1_000, maxMillis = 60_000)

        val samples = (1..200).map { backoff.delayMillis(5) }.toSet()

        assertThat(samples.size).isGreaterThan(10)
    }

    @Test
    @DisplayName("尝试次数极大时不会绕回，退避不会变成忙等")
    fun neverWrapsAroundOnHugeAttempts() {
        // `Long` 左移 64 位在 JVM 上等于左移 0 位 —— 退避曲线会在第 65 次失败时
        // 绕回基准值，从此变成忙等。这条路径要跑一整天才走得到，灰度根本碰不着
        val backoff = ExponentialBackoff(baseMillis = 300, maxMillis = 60_000, floorRatio = 1.0)

        listOf(64, 65, 1_000, Int.MAX_VALUE).forEach { attempt ->
            assertThat(backoff.delayMillis(attempt)).isEqualTo(60_000L)
        }
    }

    @Test
    @DisplayName("第 0 次与负数次都按第一次算，不会返回 0")
    fun clampsNonPositiveAttempts() {
        val backoff = ExponentialBackoff(baseMillis = 300, maxMillis = 60_000, floorRatio = 1.0)

        assertThat(backoff.delayMillis(0)).isEqualTo(300L)
        assertThat(backoff.delayMillis(-5)).isEqualTo(300L)
    }

    @Test
    @DisplayName("固定随机源下结果可复现，便于排查线上退避节奏")
    fun isDeterministicWithSeededRandom() {
        fun samples() = ExponentialBackoff(500, 8_000, random = Random(42))
            .let { backoff -> (1..6).map { backoff.delayMillis(it) } }

        assertThat(samples()).isEqualTo(samples())
    }
}
