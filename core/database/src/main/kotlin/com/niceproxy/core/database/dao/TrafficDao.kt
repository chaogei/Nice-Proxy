package com.niceproxy.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.niceproxy.core.database.entity.TrafficDailyEntity
import com.niceproxy.core.database.entity.TrafficDayTotal
import com.niceproxy.core.database.entity.TrafficTagTotal
import com.niceproxy.core.database.entity.TrafficTags
import kotlinx.coroutines.flow.Flow

/**
 * 一次待累加的增量。DAO 层刻意只认「增量」，不认「总量」：
 * Clash API 给的是自内核启动以来的累计值，内核一重启就归零，
 * 把它当总量写进来会让当天的账整个塌掉。换算成增量是调用方的责任，
 * 见 `TrafficRepository`。
 */
data class TrafficDelta(
    val day: Int,
    val outboundTag: String,
    val upload: Long,
    val download: Long,
)

@Dao
interface TrafficDao {

    // ------------------------------------------------------------ 写入

    /**
     * 把一批增量累加进 `traffic_daily`，整批一个事务。
     *
     * 批量入口是唯一推荐的写法：调用方先在内存里合并，再一次性落库。
     * 每来一个数据包写一行是不可接受的 —— 那不仅是写放大，还会让 WAL
     * 无限膨胀，而这张表的价值只是画一张图。
     *
     * **磁盘上的行数在这里封顶**，而不是在调用方。调用方能约束的只是自己
     * 内存里攒着的那一批，它不知道这一天之前已经落过多少个 tag —— 于是
     * 「每攒满 32 个就落一次」在一天里照样能堆出几百行。要判断一个 tag
     * 是不是新的，只能在看得见已落库内容的地方做。
     */
    @Transaction
    suspend fun accumulateAll(
        deltas: List<TrafficDelta>,
        maxTagsPerDay: Int = TrafficTags.MAX_PER_DAY,
    ) {
        val known = mutableMapOf<Int, MutableSet<String>>()
        deltas.forEach { delta ->
            val tags = known.getOrPut(delta.day) { tagsOn(delta.day).toMutableSet() }
            // 已经有行的 tag 永远能继续累加，只有**新** tag 会被折叠 ——
            // 否则一个长期活跃的节点会在某天溢出之后再也回不到自己那一行
            val tag = if (delta.outboundTag in tags || tags.size < maxTagsPerDay) {
                delta.outboundTag
            } else {
                TrafficTags.OVERFLOW
            }
            tags += tag
            accumulate(if (tag == delta.outboundTag) delta else delta.copy(outboundTag = tag))
        }
    }

    @Query("SELECT outbound_tag FROM traffic_daily WHERE day = :day")
    suspend fun tagsOn(day: Int): List<String>

    /**
     * 「有则加、无则插」。
     *
     * 不用 SQLite 的 UPSERT（`ON CONFLICT ... DO UPDATE`）：它要 SQLite 3.24，
     * 对应 Android API 30，而本项目 minSdk 是 24 且用的是系统自带的 SQLite。
     * 在 API 24–29 上那条语句是语法错误，会在运行时抛 `SQLiteException`。
     * Room 的 `@Upsert` 也帮不上忙 —— 它是「插不进去就整行替换」，
     * 而这里要的是**累加**。
     *
     * 先 UPDATE 再按需 INSERT 的顺序不能反：反过来的话每次累加都要先撞一次
     * 主键冲突，而冲突在 SQLite 里是要回滚语句的，比一次没命中的 UPDATE 贵。
     */
    @Transaction
    suspend fun accumulate(delta: TrafficDelta) {
        val at = System.currentTimeMillis()
        if (addToExisting(delta.day, delta.outboundTag, delta.upload, delta.download, at) > 0) {
            return
        }
        val row = TrafficDailyEntity(
            day = delta.day,
            outboundTag = delta.outboundTag,
            upload = delta.upload,
            download = delta.download,
            updatedAt = at,
        )
        // IGNORE 而不是 REPLACE：REPLACE 会把并发写入者刚插进去的行整个盖掉，
        // 那一份增量就凭空消失了。插不进去说明行已存在，回头再 UPDATE 一次。
        if (insertIfAbsent(row) == -1L) {
            addToExisting(delta.day, delta.outboundTag, delta.upload, delta.download, at)
        }
    }

    @Query(
        """
        UPDATE traffic_daily
        SET upload = upload + :upload,
            download = download + :download,
            updated_at = :at
        WHERE day = :day AND outbound_tag = :outboundTag
        """,
    )
    suspend fun addToExisting(
        day: Int,
        outboundTag: String,
        upload: Long,
        download: Long,
        at: Long,
    ): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(row: TrafficDailyEntity): Long

    /** 备份恢复用：整行覆盖。日常记账**不要**用它，它不累加。 */
    @Upsert
    suspend fun upsertAll(rows: List<TrafficDailyEntity>)

    // ------------------------------------------------------------ 查询

    @Query(
        "SELECT * FROM traffic_daily WHERE day BETWEEN :fromDay AND :toDay " +
            "ORDER BY day ASC, outbound_tag ASC",
    )
    suspend fun getRange(fromDay: Int, toDay: Int): List<TrafficDailyEntity>

    @Query(
        "SELECT * FROM traffic_daily WHERE day BETWEEN :fromDay AND :toDay " +
            "ORDER BY day ASC, outbound_tag ASC",
    )
    fun observeRange(fromDay: Int, toDay: Int): Flow<List<TrafficDailyEntity>>

    /**
     * 按日汇总。聚合在 SQL 里做，不要取回全部行再在 Kotlin 里 `groupBy` ——
     * 90 天 × 几十个 tag 是几千行，为画一条 90 个点的曲线全部搬进内存
     * 是纯浪费，而这个查询会跑在每次打开统计页时。
     */
    @Query(
        """
        SELECT day, SUM(upload) AS upload, SUM(download) AS download
        FROM traffic_daily
        WHERE day BETWEEN :fromDay AND :toDay
        GROUP BY day
        ORDER BY day ASC
        """,
    )
    suspend fun sumByDay(fromDay: Int, toDay: Int): List<TrafficDayTotal>

    @Query(
        """
        SELECT day, SUM(upload) AS upload, SUM(download) AS download
        FROM traffic_daily
        WHERE day BETWEEN :fromDay AND :toDay
        GROUP BY day
        ORDER BY day ASC
        """,
    )
    fun observeSumByDay(fromDay: Int, toDay: Int): Flow<List<TrafficDayTotal>>

    /** 按出站 tag 汇总，用量大的排前面 —— 排行榜没人从最小的看起。 */
    @Query(
        """
        SELECT outbound_tag, SUM(upload) AS upload, SUM(download) AS download
        FROM traffic_daily
        WHERE day BETWEEN :fromDay AND :toDay
        GROUP BY outbound_tag
        ORDER BY SUM(upload) + SUM(download) DESC
        """,
    )
    suspend fun sumByTag(fromDay: Int, toDay: Int): List<TrafficTagTotal>

    @Query("SELECT COUNT(*) FROM traffic_daily")
    suspend fun count(): Int

    /**
     * 分页取全表，供备份导出分块读。
     *
     * 排序键必须和主键一致且唯一，否则翻页时行的相对顺序没有保证，
     * 会出现同一行导出两次、另一行一次都没导出。
     */
    @Query("SELECT * FROM traffic_daily ORDER BY day ASC, outbound_tag ASC LIMIT :limit OFFSET :offset")
    suspend fun page(limit: Int, offset: Int): List<TrafficDailyEntity>

    // ------------------------------------------------------------ 清理

    /**
     * 删掉日期晚于 [today] 的账，返回删了多少行。
     *
     * 这些行只可能来自一次时钟跳变：用户手动把日期调到 2030 年，或者一台
     * RTC 掉电的设备开机时先报了个未来时间。它们的破坏力在于
     * [trimToRecentDays] 是按「最近的 N 个日期」留的 —— 一行 2030 年的账会
     * 永远占着「最近」的位置，把真实的那些日子一天天挤出去。
     */
    @Query("DELETE FROM traffic_daily WHERE day > :today")
    suspend fun deleteAfter(today: Int): Int

    /**
     * 只保留最近 [keepDays] 个**有记录的**日期。
     *
     * 保留策略刻意不按日历年龄算（「删掉 90 天前的」）：那会让一台装完
     * 半年没打开过的设备在第一次清理时被清空，而它全部的账恰恰都落在那些
     * 「很久以前」的日期上。按记录天数留则和设备的使用频率无关，
     * 上界同样是 N 天的数据量。
     */
    @Query(
        """
        DELETE FROM traffic_daily
        WHERE day NOT IN (
            SELECT day FROM traffic_daily GROUP BY day ORDER BY day DESC LIMIT :keepDays
        )
        """,
    )
    suspend fun trimToRecentDays(keepDays: Int): Int

    @Query("DELETE FROM traffic_daily")
    suspend fun deleteAll()
}
