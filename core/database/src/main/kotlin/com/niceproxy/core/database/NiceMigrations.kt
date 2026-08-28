package com.niceproxy.core.database

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * 手写的数据库迁移。
 *
 * [NiceDatabase] 的类注释说「加表、加索引升个版本挂上 `@AutoMigration` 即可」，
 * 那句话对**加表**成立，对**改索引**不成立：Room 处理任何索引变动的办法都是
 * 新建一张影子表、`INSERT ... SELECT` 全量拷贝、`DROP TABLE servers`、再改名。
 * 结果在功能上无损，但它要在升级路径上把用户全部节点搬一遍，而 `servers`
 * 里装着这个应用唯一无法凭记忆重建的东西。同样的效果手写只要两条
 * `DROP INDEX` / `CREATE INDEX` —— 索引是派生数据，重建它一行都不会碰。
 *
 * 手写迁移的代价是它和 `schemas/` 下的 JSON 之间没有编译期约束：迁移做出来的
 * 结构和实体声明的结构一旦对不上，Room 会在**用户设备上**开库时才报
 * `IllegalStateException`，而 `DatabaseModule` 对那个异常的处置是删库重建。
 * 所以这里的每条 DDL 都由 `MigrationTest` 逐字对着导出的 schema JSON 校验。
 */
object NiceMigrations {

    /**
     * v2 → v3。
     *
     * 1. 新增 `traffic_daily`（FR-6.4，按日 × 出站 tag 的流量账）。
     * 2. `servers` 上的 `group_id` 单列索引换成 `(group_id, sort_order, name)`。
     *
     * 全程没有一条 `DROP TABLE` / `DELETE` / `UPDATE`，也没有任何一列被改动。
     * 这是硬要求，`MigrationTest` 会拿关键字去查。
     */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(connection: SQLiteConnection) {
            STATEMENTS_2_3.forEach(connection::execSQL)
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_2_3)

    /**
     * `MIGRATION_2_3` 的语句原文，单独抽出来是为了能在普通 JUnit 里逐条比对。
     *
     * 建表语句必须和 Room 导出的 `schemas/3.json` 里的 `createSql` 一字不差
     * （除了它那个 `${'$'}{TABLE_NAME}` 占位符）—— 差一个空格或者列顺序，
     * Room 开库时的 schema 校验就会失败。
     */
    val STATEMENTS_2_3: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `traffic_daily` (" +
            "`day` INTEGER NOT NULL, " +
            "`outbound_tag` TEXT NOT NULL, " +
            "`upload` INTEGER NOT NULL, " +
            "`download` INTEGER NOT NULL, " +
            "`updated_at` INTEGER NOT NULL, " +
            "PRIMARY KEY(`day`, `outbound_tag`))",
        "CREATE INDEX IF NOT EXISTS `index_traffic_daily_outbound_tag` " +
            "ON `traffic_daily` (`outbound_tag`)",
        // 先删后建。反过来也能跑，但会短暂地同时存在两条覆盖同一前缀的索引，
        // 而这一步是在迁移事务里，磁盘紧张的设备上没必要多占那一份。
        "DROP INDEX IF EXISTS `index_servers_group_id`",
        "CREATE INDEX IF NOT EXISTS `index_servers_group_id_sort_order_name` " +
            "ON `servers` (`group_id`, `sort_order`, `name`)",
    )
}
