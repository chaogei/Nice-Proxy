package com.niceproxy.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.system.measureNanoTime

class ServerGroupTest {

    private fun group(
        filter: String? = null,
        exclude: Boolean = true,
    ) = ServerGroup(
        id = "g",
        name = "订阅",
        type = GroupType.SUBSCRIPTION,
        remarksFilter = filter,
        filterExclude = exclude,
    )

    @Nested
    @DisplayName("节点名过滤")
    inner class Filtering {

        @Test
        fun `未配置过滤时全部保留`() {
            val g = group(filter = null)
            assertThat(g.accepts("香港 01")).isTrue()
            assertThat(g.accepts("剩余流量：100GB")).isTrue()
        }

        @Test
        fun `排除模式滤掉机场塞进来的公告条目`() {
            val g = group(filter = "剩余流量|到期|官网|续费")

            assertThat(g.accepts("剩余流量：100GB")).isFalse()
            assertThat(g.accepts("套餐到期：2026-12-31")).isFalse()
            assertThat(g.accepts("官网 example.com")).isFalse()
            assertThat(g.accepts("香港 01 | 1x")).isTrue()
            assertThat(g.accepts("日本 IEPL 专线")).isTrue()
        }

        @Test
        fun `保留模式只留下命中的节点`() {
            val g = group(filter = "香港|台湾", exclude = false)

            assertThat(g.accepts("香港 01")).isTrue()
            assertThat(g.accepts("台湾 02")).isTrue()
            assertThat(g.accepts("美国 03")).isFalse()
        }

        @Test
        fun `正则非法时视为不过滤而不是全部丢弃`() {
            // 用户可能随手输入一个不合法的正则。此时把节点全滤掉是最糟的结果：
            // 订阅看起来「空了」，用户完全不知道发生了什么。
            val g = group(filter = "[unclosed")

            assertThat(g.accepts("香港 01")).isTrue()
            assertThat(g.accepts("任意名称")).isTrue()
        }

        @Test
        fun `空白过滤串等同于未配置`() {
            assertThat(group(filter = "   ").accepts("剩余流量")).isTrue()
        }
    }

    @Nested
    @DisplayName("过滤开销")
    inner class FilterCost {

        /**
         * 机场动辄三千个节点，[ServerGroup.accepts] 每个节点各调一次，
         * 而 pattern 从头到尾没变过 —— 现编译就是三千次编译同一个正则。
         *
         * 断言写成「与逐个现编译的比值」而不是绝对耗时：后者会跟跑测试的
         * 机器性能绑死。这里只兜数量级，缓存住的话差距在两三个量级上。
         */
        @Test
        @DisplayName("逐个节点过滤时只编译一次正则")
        fun regexIsCompiledOnce() {
            val pattern = "公告".repeat(5_000)
            val g = group(filter = pattern)
            val names = List(2_000) { "香港 $it" }

            names.forEach { g.accepts(it) }
            val cached = measureNanoTime { names.forEach { g.accepts(it) } }
            val recompiled = measureNanoTime {
                names.forEach { Regex(pattern).containsMatchIn(it) }
            }

            assertThat(g.accepts("香港 01")).isTrue()
            assertThat(cached * 10).isLessThan(recompiled)
        }

        @Test
        @DisplayName("非法正则下反复过滤都放行")
        fun invalidRegexKeepsAccepting() {
            // 「编不出正则」这个结果同样要缓存住，否则每个节点都要触发一次
            // PatternSyntaxException 再吞掉 —— 异常路径比编译本身还贵
            val g = group(filter = "[unclosed")
            repeat(1_000) { assertThat(g.accepts("香港 $it")).isTrue() }
        }
    }

    @Nested
    @DisplayName("自动更新间隔")
    inner class UpdateInterval {

        @Test
        fun `低于 WorkManager 下限时抬到 15 分钟`() {
            val g = ServerGroup(
                id = "g", name = "n", type = GroupType.SUBSCRIPTION,
                updateIntervalMinutes = 5,
            )
            assertThat(g.effectiveIntervalMinutes).isEqualTo(15)
        }

        @Test
        fun `正常间隔原样返回`() {
            val g = ServerGroup(
                id = "g", name = "n", type = GroupType.SUBSCRIPTION,
                updateIntervalMinutes = 1440,
            )
            assertThat(g.effectiveIntervalMinutes).isEqualTo(1440)
        }
    }
}
