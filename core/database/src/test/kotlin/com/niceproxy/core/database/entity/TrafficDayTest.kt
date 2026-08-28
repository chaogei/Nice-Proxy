package com.niceproxy.core.database.entity

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * 日期键的算术。
 *
 * 值得单独测是因为它有两个很容易写错、错了又几乎看不出来的地方：跨月/跨年
 * 的减法，以及夏令时。算错的后果是流量账记到隔壁那一天去 —— 图表上不会
 * 报错，只是数字对不上，而用户会以为是应用在乱报。
 */
class TrafficDayTest {

    private val shanghai: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")

    /** 夏令时切换很频繁的时区，用来逼出「减去 86400000 毫秒」这类写法。 */
    private val newYork: TimeZone = TimeZone.getTimeZone("America/New_York")

    private fun millis(
        zone: TimeZone,
        year: Int,
        month: Int,
        day: Int,
        hour: Int = 12,
    ): Long = Calendar.getInstance(zone).apply {
        clear()
        set(year, month - 1, day, hour, 0, 0)
    }.timeInMillis

    @Test
    @DisplayName("时间戳按本地时区折成 yyyyMMdd")
    fun formatsAsYyyyMmDd() {
        assertThat(TrafficDay.of(millis(shanghai, 2026, 8, 28), shanghai)).isEqualTo(20260828)
    }

    @Test
    @DisplayName("一天的边界跟着本地时区走，不是 UTC")
    fun dayBoundaryFollowsLocalZone() {
        // 用户在北京时间 8 月 28 日凌晨 0:30 跑的流量，应当记在 28 日；
        // 按 UTC 折的话它是 27 日 16:30，图表上会整体错开一天
        val justAfterMidnight = millis(shanghai, 2026, 8, 28, hour = 0) + 30 * 60_000

        assertThat(TrafficDay.of(justAfterMidnight, shanghai)).isEqualTo(20260828)
        assertThat(TrafficDay.of(justAfterMidnight, TimeZone.getTimeZone("UTC")))
            .isEqualTo(20260827)
    }

    @Test
    @DisplayName("往前推能跨月")
    fun subtractsAcrossMonths() {
        // 20260301 减一天不是 20260300
        assertThat(TrafficDay.minusDays(20260301, 1, shanghai)).isEqualTo(20260228)
    }

    @Test
    @DisplayName("往前推能跨年")
    fun subtractsAcrossYears() {
        assertThat(TrafficDay.minusDays(20260101, 1, shanghai)).isEqualTo(20251231)
    }

    @Test
    @DisplayName("闰年的 2 月 29 日不会被跳过")
    fun handlesLeapDay() {
        assertThat(TrafficDay.minusDays(20240301, 1, shanghai)).isEqualTo(20240229)
    }

    @Test
    @DisplayName("夏令时切换那天，往前推一天仍然是前一个日历日")
    fun surviveDaylightSavingSwitch() {
        // 2026-03-08 是美东进入夏令时的日子，那一天只有 23 小时。
        // 用「减去 days * 86400000」实现的话这里会退回 3 月 7 日之前
        assertThat(TrafficDay.minusDays(20260309, 1, newYork)).isEqualTo(20260308)
        assertThat(TrafficDay.minusDays(20260308, 1, newYork)).isEqualTo(20260307)
    }

    @Test
    @DisplayName("推 0 天就是当天")
    fun zeroIsIdentity() {
        assertThat(TrafficDay.minusDays(20260828, 0, shanghai)).isEqualTo(20260828)
    }

    @Test
    @DisplayName("推 90 天的保留窗口算得对")
    fun ninetyDayWindow() {
        assertThat(TrafficDay.minusDays(20260828, 90, shanghai)).isEqualTo(20260530)
    }

    @Test
    @DisplayName("MIN / MAX 能框住任何真实日期")
    fun boundsCoverEveryRealDate() {
        // 「查全部」靠的是 BETWEEN MIN AND MAX，框不住就等于静默丢数据
        val today = TrafficDay.of(System.currentTimeMillis(), shanghai)

        assertThat(today).isGreaterThan(TrafficDay.MIN)
        assertThat(today).isLessThan(TrafficDay.MAX)
    }

    @Test
    @DisplayName("备份里的负数流量入库前会被夹到 0")
    fun negativeCountersAreClamped() {
        // 备份文件是用户能拿到手的，改过之后负数会累加进图表，
        // 而累加之后就再也分不出来了
        val record = TrafficDailyRecord(day = 20260828, tag = "proxy", up = -1, down = -2)

        val entity = record.toEntity(importedAt = 1_700_000_000_000)

        assertThat(entity.upload).isEqualTo(0)
        assertThat(entity.download).isEqualTo(0)
    }

    @Test
    @DisplayName("流量行导出再导入不丢日期和 tag")
    fun recordRoundTrips() {
        val entity = TrafficDailyEntity(
            day = 20260828,
            outboundTag = "proxy",
            upload = 123,
            download = 456,
            updatedAt = 1,
        )

        val restored = entity.toRecord().toEntity(importedAt = 2)

        assertThat(restored.day).isEqualTo(entity.day)
        assertThat(restored.outboundTag).isEqualTo(entity.outboundTag)
        assertThat(restored.upload).isEqualTo(entity.upload)
        assertThat(restored.download).isEqualTo(entity.download)
        // updated_at 是本机的写入时间，刻意不从备份里带过来
        assertThat(restored.updatedAt).isEqualTo(2)
    }
}
