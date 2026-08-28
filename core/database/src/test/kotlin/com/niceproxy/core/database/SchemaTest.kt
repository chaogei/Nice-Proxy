package com.niceproxy.core.database

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 数据库结构的回归网。
 *
 * `DatabaseModule` 已经不再有清库兜底，所以「升级路径走不通」在用户设备上
 * 的表现是开库失败，而对开库失败的处置**就是**删库重建 —— 一次迁移写错
 * 等于把用户的节点、订阅 token、手写规则全部抹掉。这几条测试要在提交之前
 * 就把那种错拦下来。
 *
 * 校验的是 Room 导出到 `schemas/` 下的 JSON。它不是文档，是 Room 自己在
 * 生成迁移和运行期校验时读的那份结构定义，因此拿它当基准是有效的。
 */
class SchemaTest {

    private companion object {
        val json = Json { ignoreUnknownKeys = true }

        /**
         * 测试的工作目录是模块根，Gradle 对 `Test` 任务的默认约定如此。
         * 走相对路径而不是 classpath：schema JSON 不是资源，也不该被打包。
         */
        val SCHEMA_DIR = File("schemas/com.niceproxy.core.database.NiceDatabase")

        /** 版本号 → 该版本的 `database` 对象。 */
        val versions: Map<Int, JsonObject> by lazy {
            SCHEMA_DIR.listFiles { file -> file.extension == "json" }
                .orEmpty()
                .associate { file ->
                    val database = json.parseToJsonElement(file.readText())
                        .jsonObject.getValue("database").jsonObject
                    file.nameWithoutExtension.toInt() to database
                }
                .toSortedMap()
        }

        fun JsonObject.tables(): Map<String, JsonObject> =
            getValue("entities").jsonArray.associate { entity ->
                entity.jsonObject.getValue("tableName").jsonPrimitive.content to entity.jsonObject
            }

        fun JsonObject.columns(): Map<String, JsonObject> =
            getValue("fields").jsonArray.associate { field ->
                field.jsonObject.getValue("columnName").jsonPrimitive.content to field.jsonObject
            }

        fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    }

    @Test
    @DisplayName("代码里的 version 和导出的 schema 对得上")
    fun exportedSchemaMatchesDeclaredVersion() {
        // 忘了导出（或忘了提交）新版本的 JSON，Room 就生成不出迁移，
        // 而这件事在构建时是静悄悄的
        assertThat(versions.keys).contains(CURRENT_VERSION)
        assertThat(versions.getValue(CURRENT_VERSION)["version"]?.jsonPrimitive?.int)
            .isEqualTo(CURRENT_VERSION)
    }

    @Test
    @DisplayName("版本号连续，中间不缺任何一份 JSON")
    fun versionsAreContiguous() {
        // 缺一份，停留在那个版本的用户就永远升不上来
        assertThat(versions.keys.toList()).isEqualTo((1..CURRENT_VERSION).toList())
    }

    @Test
    @DisplayName("升级不会删表：每一版的表在下一版里都还在")
    fun noTableIsEverDropped() {
        eachUpgrade { from, to, previous, current ->
            assertWithMessage("v$from → v$to 之后仍然存在的表")
                .that(current.tables().keys)
                .containsAtLeastElementsIn(previous.tables().keys)
        }
    }

    @Test
    @DisplayName("升级不会删列、改类型、改可空性")
    fun columnsAreOnlyEverAdded() {
        eachUpgrade { from, to, previous, current ->
            val currentTables = current.tables()
            previous.tables().forEach { (table, before) ->
                val after = currentTables.getValue(table).columns()
                before.columns().forEach { (column, definition) ->
                    val updated = after[column]
                    assertWithMessage("v$from → v$to：$table.$column").that(updated).isNotNull()
                    // 改类型会让 SQLite 按亲和性悄悄转换已有的值（TEXT 存的
                    // 密文变成 INTEGER 就是 0），改可空性则会让迁移直接失败
                    assertWithMessage("v$from → v$to：$table.$column 的类型")
                        .that(updated!!.str("affinity"))
                        .isEqualTo(definition.str("affinity"))
                    assertWithMessage("v$from → v$to：$table.$column 的可空性")
                        .that(updated["notNull"]?.jsonPrimitive?.boolean)
                        .isEqualTo(definition["notNull"]?.jsonPrimitive?.boolean)
                }
            }
        }
    }

    @Test
    @DisplayName("升级不会改主键")
    fun primaryKeysAreStable() {
        eachUpgrade { from, to, previous, current ->
            val currentTables = current.tables()
            previous.tables().forEach { (table, before) ->
                val beforeKey = before["primaryKey"]?.jsonObject?.get("columnNames")
                val afterKey = currentTables.getValue(table)["primaryKey"]?.jsonObject?.get("columnNames")
                assertWithMessage("v$from → v$to：$table 的主键").that(afterKey).isEqualTo(beforeKey)
            }
        }
    }

    @Test
    @DisplayName("v3 带上了流量表，且按日期范围查询有索引可用")
    fun v3HasTrafficTable() {
        val traffic = versions.getValue(3).tables()["traffic_daily"]
        assertThat(traffic).isNotNull()

        // 复合主键的**前缀**就是 day，日期范围扫描直接吃主键索引；
        // 顺序写反的话 `WHERE day BETWEEN ?` 会退化成全表扫
        assertThat(
            traffic!!.getValue("primaryKey").jsonObject.getValue("columnNames")
                .jsonArray.map { it.jsonPrimitive.content },
        ).containsExactly("day", "outbound_tag").inOrder()

        // 「某个节点一直以来用了多少」跨全部日期按 tag 找，吃不到主键前缀
        assertThat(
            traffic.getValue("indices").jsonArray.flatMap { index ->
                index.jsonObject.getValue("columnNames").jsonArray.map { it.jsonPrimitive.content }
            },
        ).contains("outbound_tag")

        // 计数必须是 64 位：Int 在 2 GB 就溢出，而一天跑 2 GB 是常态
        val columns = traffic.columns()
        assertThat(columns.getValue("upload").str("affinity")).isEqualTo("INTEGER")
        assertThat(columns.getValue("download").str("affinity")).isEqualTo("INTEGER")
    }

    @Test
    @DisplayName("servers 的排序查询有覆盖索引")
    fun serversAreIndexedForGroupedOrdering() {
        // observeByGroup 是 `WHERE group_id = ? ORDER BY sort_order, name`，
        // 上千节点的订阅每次数据库变更都会重跑它
        val indices = versions.getValue(CURRENT_VERSION).tables().getValue("servers")
            .getValue("indices").jsonArray
            .map { index ->
                index.jsonObject.getValue("columnNames").jsonArray.map { it.jsonPrimitive.content }
            }

        assertThat(indices).contains(listOf("group_id", "sort_order", "name"))
    }

    private fun eachUpgrade(
        assertion: (from: Int, to: Int, previous: JsonObject, current: JsonObject) -> Unit,
    ) {
        versions.keys.zipWithNext { from, to ->
            assertion(from, to, versions.getValue(from), versions.getValue(to))
        }
    }
}

/** 和 `NiceDatabase` 的 `@Database(version = ...)` 保持一致。 */
private const val CURRENT_VERSION = 3
