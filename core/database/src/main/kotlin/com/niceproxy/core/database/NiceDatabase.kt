package com.niceproxy.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.niceproxy.core.database.crypto.SecretTextConverter
import com.niceproxy.core.database.dao.InboundDao
import com.niceproxy.core.database.dao.RoutingDao
import com.niceproxy.core.database.dao.ServerDao
import com.niceproxy.core.database.dao.ServerGroupDao
import com.niceproxy.core.database.dao.TrafficDao
import com.niceproxy.core.database.entity.InboundEntity
import com.niceproxy.core.database.entity.RoutingRuleEntity
import com.niceproxy.core.database.entity.RuleSetEntity
import com.niceproxy.core.database.entity.ServerEntity
import com.niceproxy.core.database.entity.ServerGroupEntity
import com.niceproxy.core.database.entity.TrafficDailyEntity
import com.niceproxy.core.model.GroupType
import com.niceproxy.core.model.InboundType
import com.niceproxy.core.model.ProxyProtocol
import com.niceproxy.core.model.RuleSetFormat
import com.niceproxy.core.model.RuleSetType

/**
 * 应用已经公开发布，这个库里装着用户唯一无法凭记忆重建的东西：
 * 节点凭据、订阅 token、手写的分流规则。从此以后**任何** schema 变更都必须
 * 附带一条能走通的迁移路径，`DatabaseModule` 不再有清库兜底。
 *
 * 改 schema 时的约束：
 *
 * - **新增列**：可空，或者带 `@ColumnInfo(defaultValue = ...)`。缺了默认值的
 *   NOT NULL 列自动迁移生成不出来，Room 会在编译期报错。
 * - **加表 / 加列**：升 `version` 并补一条 `@AutoMigration(from = N, to = N+1)`
 *   即可，Room 会照着 `schemas/` 下的两份 JSON 自己生成迁移。
 * - **改索引**：自动迁移能生成，但生成出来的是「新建影子表 → 全量拷贝 →
 *   `DROP TABLE` → 改名」。对 `servers` 这种装着上千条不可重建凭据的表，
 *   那个风险不值得，手写 `DROP INDEX` / `CREATE INDEX` 即可。见
 *   [NiceMigrations.MIGRATION_2_3]。
 * - **改列类型、改可空性、改主键、删列、改表名**：自动迁移做不到（或者会
 *   悄悄丢数据），必须手写 `Migration`。
 * - 手写的迁移一律挂在 [NiceMigrations.ALL] 上，由 `DatabaseModule`
 *   通过 `addMigrations()` 注册；每条都要在 `MigrationTest` 里对着导出的
 *   schema JSON 校验，那是它唯一的安全网。
 * - `schemas/` 下每个版本的 JSON 都必须提交进仓库。缺一份，对应版本的用户
 *   就永远升不上来了。
 *
 * 这些约束也是敏感字段加密选择「信封前缀 + 不动 schema」的原因，
 * 见 `crypto/SecretEnvelope`。
 */
@Database(
    entities = [
        InboundEntity::class,
        ServerEntity::class,
        ServerGroupEntity::class,
        RoutingRuleEntity::class,
        RuleSetEntity::class,
        TrafficDailyEntity::class,
    ],
    // v2 → v3 走手写的 NiceMigrations.MIGRATION_2_3，不是自动迁移：
    // 这一版要换 servers 上的索引，而自动迁移会为此把整张表重建一遍。
    version = 3,
    exportSchema = true,
)
@TypeConverters(NiceTypeConverters::class, SecretTextConverter::class)
abstract class NiceDatabase : RoomDatabase() {
    abstract fun inboundDao(): InboundDao
    abstract fun serverDao(): ServerDao
    abstract fun serverGroupDao(): ServerGroupDao
    abstract fun routingDao(): RoutingDao
    abstract fun trafficDao(): TrafficDao

    companion object {
        const val NAME = "nice-proxy.db"
    }
}

/**
 * 枚举一律按名称存字符串而不是序号。
 *
 * 序号会随枚举项的增删顺序变化而错位，是一类很难排查的数据损坏；
 * 名称多占几个字节，但增删枚举项时旧数据依然正确。
 *
 * **读的方向一律不抛异常。** 这些转换器是在 Room 读游标的过程中被调用的，
 * 早于 `toDomain()`，上层任何 `runCatching` 都够不着；一个认不出来的字符串
 * 会让 `observeAll()` 整条 Flow 失败，用户看到的是一整页空白。
 * 同一件事 `SettingsDataStore` 早就是这么做的，这里只是补齐。
 *
 * 兜底值的选取原则和 `ServerEntity` 里的凭据占位符一致：**绝不能退化成一个
 * 能正常工作的直连**。宁可让这条记录用不了，也不能让用户以为在走代理。
 */
class NiceTypeConverters {
    @TypeConverter fun fromInboundType(value: InboundType): String = value.name

    /**
     * 兜底选 [InboundType.PAC]：它是唯一 `isSingBoxManaged == false` 的类型，
     * 内核不会为它监听端口。退化成 MIXED 的话，一个认不出类型的入站会变成
     * 挂在 `0.0.0.0` 上的混合代理，同网段谁都能白嫖。
     */
    @TypeConverter fun toInboundType(value: String): InboundType =
        runCatching { InboundType.valueOf(value) }.getOrDefault(InboundType.PAC)

    @TypeConverter fun fromProtocol(value: ProxyProtocol): String = value.name

    /**
     * 兜底选 [ProxyProtocol.TROJAN]，绝不能是 DIRECT。
     *
     * 认不出协议名，通常意味着这一行是更新版本的应用写的，那么它的
     * `params_json` 多半也带着一个本版本不认识的类型判别符，会一并退化成
     * `ProtocolParams.Trojan("")` —— 两者凑在一起时 `OutboundFactory` 的
     * Trojan 分支会因为密码为空而判定 `Invalid`，节点被挡在配置之外。
     * 换成 DIRECT 则会生成一个可用的直连出站，流量全部裸奔。
     */
    @TypeConverter fun toProtocol(value: String): ProxyProtocol =
        runCatching { ProxyProtocol.valueOf(value) }.getOrDefault(ProxyProtocol.TROJAN)

    @TypeConverter fun fromGroupType(value: GroupType): String = value.name

    /**
     * 兜底选 [GroupType.MANUAL]：最坏结果是这个组不再自动更新，节点都还在。
     * 反过来把一个手动组当成订阅组，会让自动更新任务反复拿着空地址失败。
     */
    @TypeConverter fun toGroupType(value: String): GroupType =
        runCatching { GroupType.valueOf(value) }.getOrDefault(GroupType.MANUAL)

    @TypeConverter fun fromRuleSetType(value: RuleSetType): String = value.name

    // 规则集没有「退化成直连」这种风险：类型认不出来时它要么下载不到、
    // 要么读不到本地文件，配置生成器会把它连同引用它的规则一起丢掉并给出警告。
    @TypeConverter fun toRuleSetType(value: String): RuleSetType =
        runCatching { RuleSetType.valueOf(value) }.getOrDefault(RuleSetType.REMOTE)

    @TypeConverter fun fromRuleSetFormat(value: RuleSetFormat): String = value.name

    @TypeConverter fun toRuleSetFormat(value: String): RuleSetFormat =
        runCatching { RuleSetFormat.valueOf(value) }.getOrDefault(RuleSetFormat.BINARY)
}
