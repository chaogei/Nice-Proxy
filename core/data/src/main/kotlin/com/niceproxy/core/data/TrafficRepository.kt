package com.niceproxy.core.data

import com.niceproxy.core.common.Dispatcher
import com.niceproxy.core.common.NiceDispatcher
import com.niceproxy.core.database.dao.TrafficDao
import com.niceproxy.core.database.dao.TrafficDelta
import com.niceproxy.core.database.entity.TrafficDailyEntity
import com.niceproxy.core.database.entity.TrafficDay
import com.niceproxy.core.database.entity.TrafficDayTotal
import com.niceproxy.core.database.entity.TrafficTagTotal
import com.niceproxy.core.database.entity.TrafficTags
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 按日 × 出站 tag 的流量统计（FR-6.4 的后端）。
 *
 * ## 为什么写入要先在内存里攒
 *
 * 数据来源是 Clash API 的 `/traffic`，那是一条**每秒推一次**的 WebSocket。
 * 每收到一条就写一行的话，一天是 86400 次事务，而代理跑一整天是常态 ——
 * 那不只是写放大，还会让 WAL 一直长、让 SQLite 的 checkpoint 抢占用户
 * 真正在等的那些查询（节点列表、配置生成）。而这张表的全部价值只是画一张
 * 每天一个点的图。
 *
 * 所以这里的写入是「有界 + 可合并」的：
 *
 * - **可合并**：同一天同一个 tag 的增量在内存里累加，落库时是一行。
 * - **有界（内存）**：待落库的键最多 [MAX_PENDING_KEYS] 个，超了就把新来的
 *   折进 [OVERFLOW_TAG]。折叠而不是丢弃 —— 总量不能因为节点多就不准。
 * - **有界（时间/体量）**：任一条件满足就落库，见 [shouldFlush]。
 * - **有界（磁盘）**：见 [cleanup]。
 *
 * ## 为什么只收增量
 *
 * Clash API 给的是「自内核启动以来的累计值」，内核一重启就归零。把它当总量
 * 写进来，重启当天的账会先冲高再塌掉。换算成增量是调用方的事，这里只认增量，
 * 并且负数直接丢（那正是内核重启的信号）。
 *
 * ## 和 UI 的关系
 *
 * 这一层刻意不认识任何 UI 概念，只提供 [observeDailyTotals] / [totalsByTag]
 * 这类查询。聚合全部在 SQL 里做，不把几千行搬进内存再 `groupBy`。
 */
@Singleton
class TrafficRepository @Inject constructor(
    private val dao: TrafficDao,
    @Dispatcher(NiceDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * 待落库的增量。key 是 (day, tag)。
     *
     * 用 `LinkedHashMap` 保持插入顺序，[OVERFLOW_TAG] 的折叠才是可预期的
     * （先来的 tag 保住自己的行，后来的合并进兜底桶），而不是随哈希顺序抽签。
     */
    private val pending = LinkedHashMap<Key, Counter>()
    private val lock = Mutex()

    private var pendingBytes = 0L
    private var lastFlushAt = 0L

    private data class Key(val day: Int, val tag: String)

    private class Counter(var upload: Long = 0, var download: Long = 0)

    // ------------------------------------------------------------ 写入

    /**
     * 记一笔增量。**不保证立刻落库**，见类注释。
     *
     * @param outboundTag 出站 tag。空白一律折成 [UNATTRIBUTED_TAG]：数据宁可
     *   归到「未归属」也不能丢，总量对不上比分类不准严重得多。
     * @param uploadBytes 自上次记账以来新增的上行字节。负数忽略（内核重启）。
     * @param at 这笔流量发生的时刻，决定它算在哪一天。
     */
    suspend fun record(
        outboundTag: String,
        uploadBytes: Long,
        downloadBytes: Long,
        at: Long = System.currentTimeMillis(),
    ) {
        val upload = uploadBytes.coerceAtLeast(0)
        val download = downloadBytes.coerceAtLeast(0)
        if (upload == 0L && download == 0L) return

        val ready = lock.withLock {
            val day = TrafficDay.of(at)
            val tag = outboundTag.takeIf { it.isNotBlank() } ?: UNATTRIBUTED_TAG
            val key = Key(day, tag)
            // 已经在表里的键永远能继续累加，只有**新**键会被挡在门外 ——
            // 否则一个长期活跃的 tag 会在某次溢出后永远进不来
            val counter = pending.getOrPut(
                if (pending.size >= MAX_PENDING_KEYS && key !in pending) {
                    Key(day, OVERFLOW_TAG)
                } else {
                    key
                },
            ) { Counter() }
            counter.upload += upload
            counter.download += download
            pendingBytes += upload + download

            if (lastFlushAt == 0L) lastFlushAt = at
            if (shouldFlush(at)) drain(at) else null
        }
        ready?.let { writeThrough(it) }
    }

    /**
     * 把攒着的增量立刻落库。
     *
     * 代理停止、应用进后台、跨过零点时都该调一次 —— 攒在内存里的那部分
     * 会随进程一起消失，而进程消失恰恰是这个应用的常态（省电模式、
     * 国产 ROM 的后台清理）。
     */
    suspend fun flush(now: Long = System.currentTimeMillis()) {
        val ready = lock.withLock { drain(now) } ?: return
        writeThrough(ready)
    }

    /** 待落库但尚未写入的字节数，供测试与诊断观察合并是否真的生效。 */
    suspend fun pendingBytes(): Long = lock.withLock { pendingBytes }

    private fun shouldFlush(now: Long): Boolean =
        pendingBytes >= FLUSH_BYTES ||
            now - lastFlushAt >= FLUSH_INTERVAL_MS ||
            // 攒的键已经到上限，再攒下去只会全部折进兜底桶，分类就白记了
            pending.size >= MAX_PENDING_KEYS

    /** 取走全部待落库项并清空。必须在 [lock] 内调用。 */
    private fun drain(now: Long): List<TrafficDelta>? {
        if (pending.isEmpty()) return null
        val deltas = pending.map { (key, counter) ->
            TrafficDelta(key.day, key.tag, counter.upload, counter.download)
        }
        pending.clear()
        pendingBytes = 0
        lastFlushAt = now
        return deltas
    }

    /**
     * 落库失败就把增量还回去。
     *
     * 直接吞掉的话这一段流量就永久消失了；而失败的典型原因（磁盘暂时写满、
     * 库正被迁移占着）都是会过去的。还回去意味着下一次 flush 会连本次一起写。
     *
     * 还的时候要合并进当前 pending 而不是覆盖 —— 归还期间可能已经有新的
     * 增量进来了。
     */
    private suspend fun writeThrough(deltas: List<TrafficDelta>) {
        runCatching { withContext(ioDispatcher) { dao.accumulateAll(deltas) } }
            .onFailure {
                lock.withLock {
                    deltas.forEach { delta ->
                        val counter = pending.getOrPut(Key(delta.day, delta.outboundTag)) { Counter() }
                        counter.upload += delta.upload
                        counter.download += delta.download
                        pendingBytes += delta.upload + delta.download
                    }
                }
            }
    }

    // ------------------------------------------------------------ 查询

    /**
     * @param days 往前看多少天，含今天。
     */
    suspend fun dailyTotals(
        days: Int = DEFAULT_WINDOW_DAYS,
        now: Long = System.currentTimeMillis(),
    ): List<TrafficDayTotal> = withContext(ioDispatcher) {
        val (from, to) = window(days, now)
        dao.sumByDay(from, to)
    }

    fun observeDailyTotals(
        days: Int = DEFAULT_WINDOW_DAYS,
        now: Long = System.currentTimeMillis(),
    ): Flow<List<TrafficDayTotal>> {
        val (from, to) = window(days, now)
        return dao.observeSumByDay(from, to).flowOn(ioDispatcher)
    }

    suspend fun totalsByTag(
        days: Int = DEFAULT_WINDOW_DAYS,
        now: Long = System.currentTimeMillis(),
    ): List<TrafficTagTotal> = withContext(ioDispatcher) {
        val (from, to) = window(days, now)
        dao.sumByTag(from, to)
    }

    /** 明细。图表点开某一天时用，平时不要调 —— 它不做聚合。 */
    suspend fun detail(fromDay: Int, toDay: Int): List<TrafficDailyEntity> =
        withContext(ioDispatcher) { dao.getRange(fromDay, toDay) }

    private fun window(days: Int, now: Long): Pair<Int, Int> {
        val today = TrafficDay.of(now)
        // days = 1 就是「只看今天」，所以减的是 days - 1
        return TrafficDay.minusDays(today, (days - 1).coerceAtLeast(0)) to today
    }

    // ------------------------------------------------------------ 清理

    /**
     * 只保留最近 [retainDays] 个**有记录的**日期，其余删掉。返回删掉的行数。
     *
     * 保留策略按记录天数而不是日历年龄算，理由见 `TrafficDao.trimToRecentDays`。
     * 时钟跳到未来时写下的行要先单独清掉，否则它们会永远占着「最近」的位置。
     *
     * 清理前会先 [flush] —— 否则内存里攒着的那部分会在清理之后才落盘，
     * 用户点完「清理」还能看到刚被删掉的那几天。
     */
    suspend fun cleanup(
        retainDays: Int = DEFAULT_RETENTION_DAYS,
        now: Long = System.currentTimeMillis(),
    ): Int {
        require(retainDays >= 1) { "至少要保留一天：retainDays=$retainDays" }
        flush(now)
        return withContext(ioDispatcher) {
            dao.deleteAfter(TrafficDay.of(now)) + dao.trimToRecentDays(retainDays)
        }
    }

    /** 用户在设置页点「清空流量统计」。攒在内存里的也要一起丢，否则它会再冒出来。 */
    suspend fun clearAll() {
        lock.withLock { drain(System.currentTimeMillis()) }
        withContext(ioDispatcher) { dao.deleteAll() }
    }

    companion object {
        /** 拿不到出站 tag 时的归属。宁可归到这里，也不能丢掉字节数。 */
        const val UNATTRIBUTED_TAG = TrafficTags.UNATTRIBUTED

        /**
         * 待落库的 tag 太多时的兜底桶。
         *
         * 内存这一层和 `TrafficDao` 那一层各折叠一次，管的不是同一件事：
         * 这里管的是**内存**（攒着的 map 不能无界增长），那里管的是**磁盘**
         * （一天的行数不能和节点数成正比）。少任何一层都会漏。
         */
        const val OVERFLOW_TAG = TrafficTags.OVERFLOW

        /**
         * 内存里最多攒多少个 (day, tag)。
         *
         * 上千节点的订阅理论上能产生上千个 tag，但实际同时有流量的只有当前
         * 选中的那几个。它防的是「异常情况下内存无界增长」而不是正常场景。
         */
        const val MAX_PENDING_KEYS = TrafficTags.MAX_PER_DAY

        /** 攒够这么多字节就落一次，防止长时间大流量下丢得太多。 */
        const val FLUSH_BYTES = 8L * 1024 * 1024

        /** 或者攒够这么久。两者取先到的那个。 */
        const val FLUSH_INTERVAL_MS = 60_000L

        /** 图表默认看最近 30 天。 */
        const val DEFAULT_WINDOW_DAYS = 30

        /**
         * 默认保留 90 天。
         *
         * 一天几十个 tag、90 天也就几千行，几百 KB 量级；再长的历史对
         * 「这个月套餐用得怎么样」没有帮助，而这张表本来就是可丢弃的。
         */
        const val DEFAULT_RETENTION_DAYS = 90
    }
}
