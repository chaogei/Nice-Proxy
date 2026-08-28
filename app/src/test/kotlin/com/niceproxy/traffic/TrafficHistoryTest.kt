package com.niceproxy.traffic

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TrafficHistoryTest {

    @Test
    fun `keeps only the most recent samples once full`() {
        val history = TrafficHistory(capacity = 3)
        repeat(5) { history.record(it.toLong(), it * 10L) }

        val samples = history.snapshot()

        assertThat(samples.upload).containsExactly(2L, 3L, 4L).inOrder()
        assertThat(samples.download).containsExactly(20L, 30L, 40L).inOrder()
    }

    /**
     * 快照必须是拷贝。之前直接把内部的 ArrayDeque 交出去时，下一次 record
     * 会原地改掉 Compose 已经持有的那份数据 —— 实例没变，界面不刷新。
     */
    @Test
    fun `snapshot does not change when more samples arrive`() {
        val history = TrafficHistory(capacity = 4)
        history.record(1, 1)
        history.record(2, 2)

        val taken = history.snapshot()
        history.record(999, 999)

        assertThat(taken.upload).containsExactly(1L, 2L).inOrder()
        assertThat(taken.peak).isEqualTo(2L)
    }

    /**
     * 上下行共用一把纵向标尺，所以 peak 取两条曲线的整体最大值。
     * 各取各的最大值会把 1 KB/s 的上传画得和 1 MB/s 的下载一样高。
     */
    @Test
    fun `peak spans both directions`() {
        val history = TrafficHistory(capacity = 4)
        history.record(uploadBytesPerSecond = 10, downloadBytesPerSecond = 4000)
        history.record(uploadBytesPerSecond = 20, downloadBytesPerSecond = 30)

        assertThat(history.snapshot().peak).isEqualTo(4000L)
    }

    /** 内核重启后计数回绕会算出负速率，按 0 记而不是把曲线画到 x 轴下面。 */
    @Test
    fun `negative rates are clamped to zero`() {
        val history = TrafficHistory(capacity = 2)
        history.record(-500, -1)

        val samples = history.snapshot()
        assertThat(samples.upload).containsExactly(0L)
        assertThat(samples.download).containsExactly(0L)
        assertThat(samples.peak).isEqualTo(0L)
    }

    @Test
    fun `peak is zero while idle`() {
        val history = TrafficHistory(capacity = 3)
        repeat(3) { history.record(0, 0) }

        assertThat(history.snapshot().peak).isEqualTo(0L)
    }

    @Test
    fun `clear drops everything`() {
        val history = TrafficHistory(capacity = 3)
        history.record(1, 1)
        history.clear()

        val samples = history.snapshot()
        assertThat(samples.upload).isEmpty()
        assertThat(samples.download).isEmpty()
        assertThat(samples.isPlottable).isFalse()
    }

    @Test
    fun `a single point is not plottable`() {
        val history = TrafficHistory(capacity = 3)
        history.record(1, 1)
        assertThat(history.snapshot().isPlottable).isFalse()

        history.record(2, 2)
        assertThat(history.snapshot().isPlottable).isTrue()
    }

    @Test
    fun `an empty snapshot is not plottable`() {
        assertThat(TrafficSamples().isPlottable).isFalse()
    }

    @Test
    fun `rejects a capacity too small to draw a line`() {
        assertThrows<IllegalArgumentException> { TrafficHistory(capacity = 1) }
    }
}
