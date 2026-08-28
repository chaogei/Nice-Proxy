package com.niceproxy.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * 保活记账。
 *
 * 这套东西算错了不会报任何错，只会安静地误报 —— 而误报的方向决定了用户会去做什么：
 * 虚报中断会让他跑去折腾一堆本来就没问题的系统设置，漏报则让真正的杀后台被掩盖。
 */
internal class KeepAliveStatsTest {

    @Nested
    @DisplayName("哪些算「被中断」")
    inner class Involuntary {

        @Test
        fun `用户手动启动和开机自启都不算中断`() {
            // 设备重启是用户自己的操作，开机自启是他自己配的。
            // 把这两种记成中断，界面上就会出现「你的手机在杀后台」的假象
            assertThat(StartReason.USER.involuntary).isFalse()
            assertThat(StartReason.BOOT.involuntary).isFalse()
        }

        @Test
        fun `四条恢复路径全部算中断`() {
            // 它们的共同点是：用户没要求，是某样东西死了之后被救回来的
            val recoveries = setOf(
                StartReason.CORE_REVIVE,
                StartReason.STICKY_RESTART,
                StartReason.WATCHDOG,
                StartReason.COLD_START,
            )
            assertThat(StartReason.entries.filter { it.involuntary }.toSet())
                .isEqualTo(recoveries)
        }

        @Test
        fun `每一种来源都有给人看的文案`() {
            // 界面上直接显示 enum 名字等于没显示
            StartReason.entries.forEach { assertThat(it.label).isNotEmpty() }
        }
    }

    @Nested
    @DisplayName("统计口径")
    inner class Counting {

        private val now = 1_700_000_000_000L
        private val day = 24 * 60 * 60 * 1000L

        private fun stats(vararg agoDays: Double) = KeepAliveStats(
            interruptions = agoDays.map {
                InterruptionRecord(
                    atMillis = now - (it * day).toLong(),
                    recovery = StartReason.WATCHDOG,
                )
            },
        )

        @Test
        fun `本轮会话只数会话开始之后的`() {
            // 上一轮的中断不该算到这一轮头上，否则用户重启一次代理，
            // 「期间自动恢复 N 次」会带着历史包袱一直挂在首页
            val sessionStart = now - day
            val subject = stats(0.5, 2.0)

            assertThat(subject.interruptionsSince(sessionStart)).isEqualTo(1)
        }

        @Test
        fun `没在运行时本轮计数为零`() {
            assertThat(stats(0.5).interruptionsSince(null)).isEqualTo(0)
        }

        @Test
        fun `七天窗口按时间截断`() {
            val subject = stats(1.0, 3.0, 10.0)

            assertThat(subject.interruptionsWithin(7 * day, now)).isEqualTo(2)
        }

        @Test
        fun `没有记录时一切为零，不抛异常`() {
            val empty = KeepAliveStats()

            assertThat(empty.interruptionsWithin(7 * day, now)).isEqualTo(0)
            assertThat(empty.interruptionsSince(now)).isEqualTo(0)
        }
    }
}
