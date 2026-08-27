package com.niceproxy.core.service

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * 退避策略。
 *
 * 这段逻辑的两种错法都是静默的：退避太短会变成重试风暴，把电耗光却没有任何报错；
 * 退避算成 0 或负数则永远等不到重试，用户只看到代理再也起不来。
 */
internal class RetryPolicyTest {

    @Nested
    @DisplayName("退避曲线")
    inner class Backoff {

        @Test
        @DisplayName("从 1 秒开始指数增长")
        fun growsExponentially() {
            assertThat(RetryPolicy.delayFor(1)).isEqualTo(1_000)
            assertThat(RetryPolicy.delayFor(2)).isEqualTo(2_000)
            assertThat(RetryPolicy.delayFor(3)).isEqualTo(4_000)
            assertThat(RetryPolicy.delayFor(4)).isEqualTo(8_000)
            assertThat(RetryPolicy.delayFor(5)).isEqualTo(16_000)
        }

        @Test
        @DisplayName("封顶在 30 秒")
        fun capped() {
            // 第 6 次本该是 32 秒
            assertThat(RetryPolicy.delayFor(6)).isEqualTo(30_000)
            assertThat(RetryPolicy.delayFor(MAX_REASONABLE_ATTEMPT)).isEqualTo(30_000)
        }

        @Test
        @DisplayName("移位量再大也不会绕回一个极小值")
        fun noShiftOverflow() {
            // Kotlin 对 Long 的移位取低 6 位：1L shl 64 == 1。
            // 真让 attempt 涨到那个量级的话，退避会悄悄退化成 1 毫秒的重试风暴。
            listOf(64, 65, 100, Int.MAX_VALUE).forEach { attempt ->
                assertThat(RetryPolicy.delayFor(attempt)).isEqualTo(30_000)
            }
        }

        @Test
        @DisplayName("次数为 0 或负数时回落到首次间隔，不会得到 0")
        fun nonPositiveAttempt() {
            // 立即重试等于死循环，任何输入都不该导致 0
            assertThat(RetryPolicy.delayFor(0)).isEqualTo(1_000)
            assertThat(RetryPolicy.delayFor(-1)).isEqualTo(1_000)
        }

        @Test
        @DisplayName("整条曲线单调不减且恒为正")
        fun monotonicAndPositive() {
            var previous = 0L
            (1..MAX_REASONABLE_ATTEMPT).forEach { attempt ->
                val current = RetryPolicy.delayFor(attempt)
                assertThat(current).isAtLeast(previous)
                assertThat(current).isGreaterThan(0)
                previous = current
            }
        }
    }

    @Nested
    @DisplayName("是否重试")
    inner class Decision {

        @Test
        @DisplayName("在上限内的暂时性失败才重试")
        fun retriesWithinLimit() {
            (1..RetryPolicy.MAX_ATTEMPTS).forEach { attempt ->
                assertThat(
                    RetryPolicy.shouldRetry(attempt, FailureCause.CoreStartFailed, enabled = true),
                ).isTrue()
            }
        }

        @Test
        @DisplayName("超过上限就交给看门狗，不再原地重试")
        fun stopsAfterLimit() {
            assertThat(
                RetryPolicy.shouldRetry(
                    RetryPolicy.MAX_ATTEMPTS + 1,
                    FailureCause.CoreStartFailed,
                    enabled = true,
                ),
            ).isFalse()
        }

        @Test
        @DisplayName("确定性错误一次都不重试")
        fun neverRetriesDeterministicFailures() {
            // 配置不合法、被后台启动限制拦下这类错误，重试多少次都是同样的结果，
            // 只会让用户盯着一个永远在倒计时却永远失败的通知
            assertThat(RetryPolicy.shouldRetry(1, FailureCause.InvalidConfig, enabled = true))
                .isFalse()
            assertThat(
                RetryPolicy.shouldRetry(1, FailureCause.ForegroundStartBlocked, enabled = true),
            ).isFalse()
        }

        @Test
        @DisplayName("用户关掉自动重启后完全不重试")
        fun respectsUserSetting() {
            assertThat(RetryPolicy.shouldRetry(1, FailureCause.CoreStartFailed, enabled = false))
                .isFalse()
        }
    }

    @Nested
    @DisplayName("确定性成因的白名单")
    inner class DeterministicWhitelist {

        @Test
        @DisplayName("只有明确列入的两种才算确定性")
        fun onlyWhitelisted() {
            // 误判成确定性的代价是用户彻底失去自动恢复：不再退避重试，运行意图也被清掉，
            // 看门狗都不会再来救。所以这份名单必须由断言钉死，不能靠调用点随手传布尔值。
            val deterministic = FailureCause.entries.filter { it.deterministic }.toSet()

            assertThat(deterministic).containsExactly(
                FailureCause.InvalidConfig,
                FailureCause.ForegroundStartBlocked,
            )
        }

        @Test
        @DisplayName("分不清成因时按暂时性处理")
        fun unknownIsTransient() {
            assertThat(FailureCause.Unknown.deterministic).isFalse()
            assertThat(RetryPolicy.shouldRetry(1, FailureCause.Unknown, enabled = true)).isTrue()
        }

        @Test
        @DisplayName("只有确定性错误才连运行意图一起清掉")
        fun forgetsRunIntentOnlyWhenDeterministic() {
            // 次数耗尽属于「试过了、暂时不行」，那一位要留着 —— 网络说不定过一会儿
            // 就好了，15 分钟后的看门狗还有一次机会
            assertThat(RetryPolicy.shouldForgetRunIntent(FailureCause.InvalidConfig)).isTrue()
            assertThat(RetryPolicy.shouldForgetRunIntent(FailureCause.ForegroundStartBlocked))
                .isTrue()
            assertThat(RetryPolicy.shouldForgetRunIntent(FailureCause.CoreStartFailed)).isFalse()
            assertThat(RetryPolicy.shouldForgetRunIntent(FailureCause.CoreExitedRepeatedly))
                .isFalse()
            assertThat(RetryPolicy.shouldForgetRunIntent(FailureCause.Unknown)).isFalse()
        }
    }

    private companion object {
        /** 远超实际上限，用来确认封顶和溢出保护在极端输入下依然成立。 */
        const val MAX_REASONABLE_ATTEMPT = 40
    }
}
