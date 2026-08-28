package com.niceproxy.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import kotlinx.serialization.Serializable
import java.util.Calendar
import java.util.TimeZone

/**
 * 按「日 × 出站 tag」聚合的流量账。
 *
 * docs/DESIGN.md §9 里的草案只有 `traffic_daily(day, upload, download)`，
 * 而 FR-6.4 要的是「按日、**按节点**的流量统计图表」—— 少了 tag 这一维，
 * 图表就只剩一条总量曲线，用户没法回答「这个月哪个节点吃掉了套餐」。
 * tag 进主键而不是单开一张表：查询永远是「某段日期内的分布」，
 * 复合主键 `(day, outbound_tag)` 的前缀正好就是日期范围扫描要的索引。
 *
 * **这张表是可丢弃的。** 它不像节点凭据那样无法重建 —— 丢了只是图表断一截。
 * 这决定了两件事：清理策略可以激进（见 `TrafficDao.deleteBefore`），
 * 以及备份恢复时它绝不能成为整份备份失败的理由。
 *
 * 计数用 `Long`：`Int` 在 2 GB 就溢出了，而一天跑 2 GB 对代理网关是常态。
 */
@Entity(
    tableName = "traffic_daily",
    primaryKeys = ["day", "outbound_tag"],
    // 主键的前缀索引只覆盖「按日期范围查」。「某个节点一直以来用了多少」
    // 要跨全部日期按 tag 找，没有这条索引就是全表扫。
    indices = [Index("outbound_tag")],
)
data class TrafficDailyEntity(
    /**
     * 本地时区的 `yyyyMMdd`，例如 20260828。
     *
     * 存成整数而不是时间戳，是因为「按日聚合」的分界必须跟用户看到的日历
     * 一致：用时间戳就得在每次查询里做时区换算，而用户换时区（出差、改系统
     * 设置）之后历史数据的归属会整体漂移。整数日期是既成事实，不会再变。
     */
    val day: Int,
    /**
     * 出站 tag。空串代表「无法归属」，见 `TrafficRepository.UNATTRIBUTED_TAG`。
     *
     * 存 tag 而不是节点 id：节点会被订阅更新整组换掉（`replaceGroupServers`
     * 是先删后插，id 全变），外键会连带把历史流量 CASCADE 掉 —— 那恰恰是
     * 用户最想看的那段历史。tag 是稳定的展示名，代价是改名之后旧账对不上，
     * 但那是「看起来是两个节点」，不是「数据没了」。
     */
    @ColumnInfo(name = "outbound_tag") val outboundTag: String,
    val upload: Long,
    val download: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/**
 * 两个有特殊含义的出站 tag。
 *
 * 放在实体这一层而不是仓库层，是因为 `TrafficDao` 在落库时也要用到
 * [OVERFLOW]：磁盘上的行数同样要有上界，而那个折叠只能在能看到「这一天
 * 已经有哪些 tag」的地方做，也就是 DAO 里。
 */
object TrafficTags {

    /** 拿不到出站 tag 时的归属。宁可归到这里，也不能丢掉字节数。 */
    const val UNATTRIBUTED = ""

    /**
     * 一天里 tag 太多时的兜底桶。
     *
     * 上千节点的订阅配上 urltest 自动切换，一天下来能产生几百个不同的 tag。
     * 那些只用过几 KB 的节点单独占一行既没有意义，又让这张表的行数变成
     * 「和节点数成正比」—— 而它本该只和天数成正比。折叠而不是丢弃：
     * 分类不准可以接受，总量不准不行，用户是拿它对账的。
     */
    const val OVERFLOW = "其他"

    /** 一天里最多留多少个独立 tag，超出的折进 [OVERFLOW]。 */
    const val MAX_PER_DAY = 32
}

/** 按日汇总的投影，供图表按时间轴取数。 */
data class TrafficDayTotal(
    val day: Int,
    val upload: Long,
    val download: Long,
)

/** 按出站 tag 汇总的投影，供图表按节点排行取数。 */
data class TrafficTagTotal(
    @ColumnInfo(name = "outbound_tag") val outboundTag: String,
    val upload: Long,
    val download: Long,
)

/**
 * 备份文件里的一行流量账。
 *
 * 刻意不直接序列化 [TrafficDailyEntity]：备份文件是要跨版本读的，
 * 把它和 Room 实体绑在一起，以后加一列就等于悄悄改了文件格式。
 * 这里的字段名一旦发布就不能改，实体那边可以随便重构。
 */
@Serializable
data class TrafficDailyRecord(
    val day: Int,
    val tag: String,
    val up: Long,
    val down: Long,
)

fun TrafficDailyEntity.toRecord(): TrafficDailyRecord =
    TrafficDailyRecord(day = day, tag = outboundTag, up = upload, down = download)

/**
 * @param importedAt 恢复的时刻。`updated_at` 不从备份里带过来 —— 那是本机的
 *   写入时间戳，只用来判断哪些行是刚写的，跨设备搬过来毫无意义。
 */
fun TrafficDailyRecord.toEntity(importedAt: Long): TrafficDailyEntity = TrafficDailyEntity(
    day = day,
    outboundTag = tag,
    // 备份被手工改过时可能是负数。负流量会让图表出现向下的柱子，
    // 而且一旦累加进去就再也分不出来了，入库前直接夹到 0。
    upload = up.coerceAtLeast(0),
    download = down.coerceAtLeast(0),
    updatedAt = importedAt,
)

/**
 * `yyyyMMdd` 日期键的计算。
 *
 * 用 [Calendar] 而不是 `java.time`：minSdk 是 24，而 `java.time` 要 API 26，
 * 本项目没有开 core library desugaring（见 app/build.gradle.kts）。
 * 在 API 24/25 上一个 `LocalDate.now()` 就是 `NoClassDefFoundError`，
 * 而流量记账跑在代理运行的热路径上，崩在那里等于网关整个挂掉。
 */
object TrafficDay {

    /** 一个不可能存在的日期键，用作「查全部」的下界。 */
    const val MIN = 0

    /** 同上，用作上界。9999-12-31。 */
    const val MAX = 99991231

    fun of(epochMillis: Long, zone: TimeZone = TimeZone.getDefault()): Int =
        calendar(epochMillis, zone).toKey()

    /**
     * [day] 往前推 [days] 天。
     *
     * 走 [Calendar] 而不是「减去 days * 86400000」：后者在夏令时切换日会差一
     * 小时，跨月/跨年时更是直接算错（20260301 减一天不是 20260300）。
     */
    fun minusDays(day: Int, days: Int, zone: TimeZone = TimeZone.getDefault()): Int {
        require(days >= 0) { "只支持往前推：days=$days" }
        val calendar = Calendar.getInstance(zone).apply {
            clear()
            set(day / 10000, (day / 100) % 100 - 1, day % 100)
        }
        calendar.add(Calendar.DAY_OF_MONTH, -days)
        return calendar.toKey()
    }

    private fun calendar(epochMillis: Long, zone: TimeZone) =
        Calendar.getInstance(zone).apply { timeInMillis = epochMillis }

    private fun Calendar.toKey(): Int =
        get(Calendar.YEAR) * 10000 + (get(Calendar.MONTH) + 1) * 100 + get(Calendar.DAY_OF_MONTH)
}
