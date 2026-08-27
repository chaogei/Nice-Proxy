package com.niceproxy.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

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
