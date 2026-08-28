package com.niceproxy.core.data

import com.google.common.truth.Truth.assertThat
import com.niceproxy.core.database.entity.TrafficDay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * 流量记账（FR-6.4 的后端）。
 *
 * 数据源是 Clash API 的 `/traffic`，一条**每秒推一次**的 WebSocket。
 * 这里值得钉死的从来不是「加法算得对」，而是三条约束：写入必须有界、
 * 必须能合并、以及失败时字节数不能凭空消失。
 */
internal class TrafficRepositoryTest {

    private val dao = FakeTrafficDao()
    private val repository = TrafficRepository(dao, Dispatchers.Unconfined)

    private companion object {
        val ZONE: TimeZone = TimeZone.getDefault()

        /** 某一天的正午，避开时区换算把它推到隔壁那一天。 */
        fun noon(year: Int, month: Int, day: Int): Long = Calendar.getInstance(ZONE).apply {
            clear()
            set(year, month - 1, day, 12, 0, 0)
        }.timeInMillis

        val DAY_1 = noon(2026, 8, 27)
        val DAY_2 = noon(2026, 8, 28)
    }

    private suspend fun rows() = dao.getRange(TrafficDay.MIN, TrafficDay.MAX)

    @Nested
    @DisplayName("有界与合并")
    inner class Bounded {

        @Test
        @DisplayName("同一天同一个 tag 的增量在内存里合并成一行")
        fun coalescesIntoOneRow() = runTest {
            // 一天 86400 次推送写 86400 行是不可接受的：那不只是写放大，
            // 还会让 WAL 一直长，抢占用户真正在等的那些查询
            repeat(100) { repository.record("proxy", 1, 2, DAY_1) }
            repository.flush(DAY_1)

            assertThat(rows()).hasSize(1)
            assertThat(rows().single().upload).isEqualTo(100)
            assertThat(rows().single().download).isEqualTo(200)
        }

        @Test
        @DisplayName("一百次记账只落一次库")
        fun oneHundredRecordsOneWrite() = runTest {
            repeat(100) { repository.record("proxy", 1, 1, DAY_1) }
            repository.flush(DAY_1)

            assertThat(dao.writes).isEqualTo(1)
        }

        @Test
        @DisplayName("攒够阈值就自己落库，不必等到有人来 flush")
        fun flushesWhenBytesPileUp() = runTest {
            // 代理可能跑一整夜没人碰应用。全靠外部 flush 的话，
            // 进程一被系统回收，攒着的那一大坨就没了
            repository.record("proxy", TrafficRepository.FLUSH_BYTES, 0, DAY_1)

            assertThat(dao.writes).isEqualTo(1)
            assertThat(repository.pendingBytes()).isEqualTo(0)
        }

        @Test
        @DisplayName("攒够时间也会落库")
        fun flushesWhenTimePasses() = runTest {
            repository.record("proxy", 1, 1, DAY_1)
            assertThat(dao.writes).isEqualTo(0)

            repository.record("proxy", 1, 1, DAY_1 + TrafficRepository.FLUSH_INTERVAL_MS)

            assertThat(dao.writes).isEqualTo(1)
        }

        @Test
        @DisplayName("tag 太多时折进兜底桶，一天的行数不会和节点数成正比")
        fun tooManyTagsFoldIntoOverflow() = runTest {
            // 上千节点的订阅配上 urltest 自动切换，一天能产生几百个 tag。
            // 内存那层只管得住自己攒着的那一批（每满 32 个就落一次），
            // 磁盘上的行数要靠 DAO 那层再折一次才封得住
            repeat(TrafficRepository.MAX_PENDING_KEYS * 3) {
                repository.record("node-$it", 1, 0, DAY_1)
            }
            repository.flush(DAY_1)

            assertThat(rows().size).isAtMost(TrafficRepository.MAX_PENDING_KEYS + 1)
            assertThat(rows().map { it.outboundTag }).contains(TrafficRepository.OVERFLOW_TAG)
        }

        @Test
        @DisplayName("已经有行的 tag 不会因为溢出而被折走")
        fun activeTagsKeepTheirRow() = runTest {
            // 折走的话，一个长期活跃的节点会在某天溢出之后再也回不到自己那一行
            repository.record("常用节点", 1, 0, DAY_1)
            repository.flush(DAY_1)
            repeat(TrafficRepository.MAX_PENDING_KEYS * 3) {
                repository.record("node-$it", 1, 0, DAY_1)
            }
            repository.flush(DAY_1)
            repository.record("常用节点", 9, 0, DAY_1)
            repository.flush(DAY_1)

            assertThat(rows().single { it.outboundTag == "常用节点" }.upload).isEqualTo(10)
        }

        @Test
        @DisplayName("折叠时一个字节都不丢")
        fun overflowLosesNothing() = runTest {
            // 分类不准可以接受，总量不准不行 —— 用户是拿它对账的
            val count = TrafficRepository.MAX_PENDING_KEYS * 3
            repeat(count) { repository.record("node-$it", 7, 3, DAY_1) }
            repository.flush(DAY_1)

            assertThat(rows().sumOf { it.upload }).isEqualTo(7L * count)
            assertThat(rows().sumOf { it.download }).isEqualTo(3L * count)
        }

        @Test
        @DisplayName("跨天的增量各归各天")
        fun daysAreSeparate() = runTest {
            repository.record("proxy", 10, 0, DAY_1)
            repository.record("proxy", 20, 0, DAY_2)
            repository.flush(DAY_2)

            assertThat(rows().map { it.day to it.upload })
                .containsExactly(
                    TrafficDay.of(DAY_1, ZONE) to 10L,
                    TrafficDay.of(DAY_2, ZONE) to 20L,
                )
        }
    }

    @Nested
    @DisplayName("只认增量")
    inner class DeltasOnly {

        @Test
        @DisplayName("负数被忽略，那是内核重启的信号")
        fun negativeDeltasAreIgnored() = runTest {
            // Clash API 给的是「自内核启动以来的累计值」，内核一重启就归零。
            // 调用方换算出来的差值会变成一个大负数
            repository.record("proxy", 100, 100, DAY_1)
            repository.record("proxy", -100_000, -100_000, DAY_1)
            repository.flush(DAY_1)

            assertThat(rows().single().upload).isEqualTo(100)
            assertThat(rows().single().download).isEqualTo(100)
        }

        @Test
        @DisplayName("零增量不产生任何行")
        fun zeroIsANoOp() = runTest {
            // 代理挂着没人用的时候，/traffic 每秒都会推一条全零
            repeat(100) { repository.record("proxy", 0, 0, DAY_1) }
            repository.flush(DAY_1)

            assertThat(rows()).isEmpty()
            assertThat(dao.writes).isEqualTo(0)
        }

        @Test
        @DisplayName("拿不到 tag 时归到「未归属」，不丢字节")
        fun blankTagIsAttributedNotDropped() = runTest {
            repository.record("   ", 5, 5, DAY_1)
            repository.flush(DAY_1)

            assertThat(rows().single().outboundTag).isEqualTo(TrafficRepository.UNATTRIBUTED_TAG)
            assertThat(rows().single().upload).isEqualTo(5)
        }
    }

    @Nested
    @DisplayName("落库失败")
    inner class WriteFailure {

        @Test
        @DisplayName("写失败时增量还回内存，下一次 flush 一起写")
        fun failedDeltasAreReturned() = runTest {
            // 直接吞掉的话这段流量就永久消失了，而失败的典型原因
            //（磁盘暂时写满、库正被迁移占着）都是会过去的
            repository.record("proxy", 50, 50, DAY_1)
            dao.failNextWrite = true
            repository.flush(DAY_1)

            assertThat(rows()).isEmpty()
            assertThat(repository.pendingBytes()).isEqualTo(100)

            repository.flush(DAY_1)
            assertThat(rows().single().upload).isEqualTo(50)
        }

        @Test
        @DisplayName("写失败不会把异常抛给记账的调用方")
        fun failureNeverPropagates() = runTest {
            // 记账是挂在代理运行热路径上的，它失败不该把网关带崩
            dao.failNextWrite = true

            repository.record("proxy", TrafficRepository.FLUSH_BYTES, 0, DAY_1)

            assertThat(repository.pendingBytes()).isEqualTo(TrafficRepository.FLUSH_BYTES)
        }
    }

    @Nested
    @DisplayName("查询")
    inner class Queries {

        @Test
        @DisplayName("按日汇总把同一天的各个 tag 加在一起")
        fun sumsByDay() = runTest {
            repository.record("a", 1, 0, DAY_2)
            repository.record("b", 2, 0, DAY_2)
            repository.flush(DAY_2)

            val totals = repository.dailyTotals(days = 7, now = DAY_2)

            assertThat(totals).hasSize(1)
            assertThat(totals.single().upload).isEqualTo(3)
        }

        @Test
        @DisplayName("按节点汇总，用量大的排前面")
        fun ranksByTag() = runTest {
            repository.record("small", 1, 0, DAY_2)
            repository.record("big", 100, 0, DAY_2)
            repository.flush(DAY_2)

            assertThat(repository.totalsByTag(days = 7, now = DAY_2).map { it.outboundTag })
                .containsExactly("big", "small").inOrder()
        }

        @Test
        @DisplayName("窗口是「含今天往前数 N 天」")
        fun windowIncludesToday() = runTest {
            repository.record("proxy", 1, 0, DAY_1)
            repository.record("proxy", 1, 0, DAY_2)
            repository.flush(DAY_2)

            // days = 1 就是只看今天
            assertThat(repository.dailyTotals(days = 1, now = DAY_2)).hasSize(1)
            assertThat(repository.dailyTotals(days = 2, now = DAY_2)).hasSize(2)
        }
    }

    @Nested
    @DisplayName("清理")
    inner class Cleanup {

        @Test
        @DisplayName("保留窗口之外的日期删掉，之内的留着")
        fun dropsOldDays() = runTest {
            repository.record("proxy", 1, 0, noon(2026, 1, 1))
            repository.record("proxy", 1, 0, DAY_1)
            repository.record("proxy", 1, 0, DAY_2)
            repository.flush(DAY_2)

            repository.cleanup(retainDays = 2, now = DAY_2)

            assertThat(rows().map { it.day })
                .containsExactly(TrafficDay.of(DAY_1, ZONE), TrafficDay.of(DAY_2, ZONE))
        }

        @Test
        @DisplayName("久不打开的设备不会被一次清空")
        fun keepsRecentDaysEvenIfAncient() = runTest {
            // 装完半年没打开过：全部的账都落在「很久以前」那几天上。
            // 按日历年龄删的话，第一次清理就把它清光了
            repository.record("proxy", 1, 0, noon(2025, 1, 1))
            repository.record("proxy", 1, 0, noon(2025, 1, 2))
            repository.flush(noon(2025, 1, 2))

            repository.cleanup(retainDays = 30, now = DAY_2)

            assertThat(rows()).hasSize(2)
        }

        @Test
        @DisplayName("时钟跳到未来时写下的行会被清掉，不会挤占真实的日子")
        fun futureRowsAreDropped() = runTest {
            // 留着的话，一行 2030 年的账会永远占着「最近」的位置，
            // 把真实的那些日子一天天挤出去
            repository.record("proxy", 1, 0, noon(2030, 1, 1))
            repository.record("proxy", 1, 0, DAY_2)
            repository.flush(DAY_2)

            repository.cleanup(retainDays = 30, now = DAY_2)

            assertThat(rows().map { it.day }).containsExactly(TrafficDay.of(DAY_2, ZONE))
        }

        @Test
        @DisplayName("清理前会先把攒着的落库，用户不会看到刚删掉的又冒出来")
        fun flushesBeforeCleaning() = runTest {
            repository.record("proxy", 1, 0, DAY_2)

            repository.cleanup(retainDays = 30, now = DAY_2)

            assertThat(rows()).hasSize(1)
        }

        @Test
        @DisplayName("清空会连内存里攒着的一起丢")
        fun clearAllDropsPending() = runTest {
            repository.record("proxy", 1, 0, DAY_2)

            repository.clearAll()
            repository.flush(DAY_2)

            assertThat(rows()).isEmpty()
        }

        @Test
        @DisplayName("保留天数至少是 1，不接受把今天也删掉")
        fun refusesZeroRetention() = runTest {
            val failure = runCatching { repository.cleanup(retainDays = 0, now = DAY_2) }
            assertThat(failure.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        }
    }
}
