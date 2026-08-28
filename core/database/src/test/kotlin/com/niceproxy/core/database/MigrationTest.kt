package com.niceproxy.core.database

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 手写迁移的安全网。
 *
 * 自动迁移是 Room 照着两份 schema JSON 生成的，写错不了；手写迁移没有这层
 * 保障 —— 它做出来的结构和实体声明的结构一旦对不上，Room 会在**用户设备上**
 * 开库时才抛 `IllegalStateException`，而 `DatabaseModule` 对那个异常的处置
 * 是删库重建。也就是说一条写歪的 DDL 直接等于用户的节点和订阅 token 全没。
 *
 * 这里用两种方式钉它：
 *
 * 1. 建表 / 建索引语句和 Room 导出的 `schemas/3.json` **逐字**比对。那份 JSON
 *    里的 `createSql` 正是 Room 在运行期做 schema 校验时期待的原文。
 * 2. 整条迁移跑一遍（用 [RecordingConnection]），确认它发出的语句里没有任何
 *    一条会动到用户数据。
 */
class MigrationTest {

    private companion object {
        val json = Json { ignoreUnknownKeys = true }

        val schemaV3: JsonObject by lazy {
            json.parseToJsonElement(
                File("schemas/com.niceproxy.core.database.NiceDatabase/3.json").readText(),
            ).jsonObject.getValue("database").jsonObject
        }

        /**
         * Room 在 `createSql` 里用 `${'$'}{TABLE_NAME}` 占位，运行期才替换成真表名。
         * 手写的迁移里没有这个占位符，比对前先还原。
         */
        fun createSqlFor(table: String): String =
            schemaV3.getValue("entities").jsonArray
                .map { it.jsonObject }
                .single { it.getValue("tableName").jsonPrimitive.content == table }
                .getValue("createSql").jsonPrimitive.content
                .replace("\${TABLE_NAME}", table)

        fun indexSqlFor(table: String, index: String): String =
            schemaV3.getValue("entities").jsonArray
                .map { it.jsonObject }
                .single { it.getValue("tableName").jsonPrimitive.content == table }
                .getValue("indices").jsonArray
                .map { it.jsonObject }
                .single { it.getValue("name").jsonPrimitive.content == index }
                .getValue("createSql").jsonPrimitive.content
                .replace("\${TABLE_NAME}", table)
    }

    @Test
    @DisplayName("迁移覆盖的版本区间是 2 → 3")
    fun coversTheRightVersions() {
        assertThat(NiceMigrations.MIGRATION_2_3.startVersion).isEqualTo(2)
        assertThat(NiceMigrations.MIGRATION_2_3.endVersion).isEqualTo(3)
    }

    @Test
    @DisplayName("每一个版本间隔都有迁移可走，没有断档")
    fun everyGapIsCovered() {
        // 断一档，停在那个版本的用户升级时就是「找不到迁移路径」，
        // 而那条路的终点是删库重建
        val covered = NiceMigrations.ALL.map { it.startVersion to it.endVersion }.toSet()
        val required = (2 until 3).map { it to it + 1 }

        assertThat(covered).containsAtLeastElementsIn(required)
    }

    @Test
    @DisplayName("建表语句和 Room 导出的 schema 一字不差")
    fun createTableMatchesExportedSchema() {
        // 差一个空格、少一个反引号、列顺序换一下，Room 的运行期校验都会失败
        val statement = NiceMigrations.STATEMENTS_2_3.single { it.startsWith("CREATE TABLE") }

        assertThat(statement).isEqualTo(createSqlFor("traffic_daily"))
    }

    @Test
    @DisplayName("建索引语句和 Room 导出的 schema 一字不差")
    fun createIndexMatchesExportedSchema() {
        val expected = listOf(
            indexSqlFor("traffic_daily", "index_traffic_daily_outbound_tag"),
            indexSqlFor("servers", "index_servers_group_id_sort_order_name"),
        )

        assertThat(NiceMigrations.STATEMENTS_2_3.filter { it.startsWith("CREATE INDEX") })
            .containsExactlyElementsIn(expected)
    }

    @Test
    @DisplayName("v3 声明的每一条索引，迁移里都建了")
    fun everyDeclaredIndexIsCreated() {
        // 漏建一条索引和写错一条建表语句的后果一样：schema 校验不过，删库重建。
        // 逐条列举容易漏，这里直接拿导出的 schema 反过来查。
        val executed = executeMigration()
        schemaV3.getValue("entities").jsonArray.map { it.jsonObject }.forEach { entity ->
            val table = entity.getValue("tableName").jsonPrimitive.content
            entity["indices"]?.jsonArray.orEmpty().forEach { index ->
                val name = index.jsonObject.getValue("name").jsonPrimitive.content
                val alreadyExisted = name in INDICES_ALREADY_IN_V2
                val created = executed.any { it.contains("`$name`") && it.startsWith("CREATE INDEX") }
                assertWithMessage("$table 上的索引 $name")
                    .that(created || alreadyExisted)
                    .isTrue()
            }
        }
    }

    @Test
    @DisplayName("迁移里没有一条语句会动到用户数据")
    fun migrationTouchesNoUserRow() {
        // 这是这次改动的硬要求。Room 的自动迁移为了换一条索引会走
        // 「新建影子表 → 全量拷贝 → DROP TABLE servers → 改名」，
        // 手写这条迁移的全部理由就是避开那次全表搬运。
        val executed = executeMigration()

        assertThat(executed).isNotEmpty()
        executed.forEach { statement ->
            val verb = statement.substringBefore(' ') + " " + statement.split(' ')[1]
            assertWithMessage("迁移语句「$statement」")
                .that(verb)
                .isAnyOf("CREATE TABLE", "CREATE INDEX", "DROP INDEX")
        }
    }

    @Test
    @DisplayName("被复合索引取代的旧索引会被删掉，不留一条白占写放大的冗余索引")
    fun redundantIndexIsDropped() {
        assertThat(executeMigration())
            .contains("DROP INDEX IF EXISTS `index_servers_group_id`")
    }

    @Test
    @DisplayName("建表用 IF NOT EXISTS，重复执行不会炸")
    fun migrationIsIdempotent() {
        // 迁移在极端情况下会被重放（上一次跑到一半掉电、WAL 没提交），
        // 第二次撞上「表已存在」就是升级永久失败
        executeMigration().forEach { statement ->
            assertThat(statement).containsMatch("IF (NOT )?EXISTS")
        }
    }

    private fun executeMigration(): List<String> {
        val connection = RecordingConnection()
        NiceMigrations.MIGRATION_2_3.migrate(connection)
        return connection.executed
    }
}

/** v2 就已经存在、v3 没有改动的索引，迁移自然不必再建一遍。 */
private val INDICES_ALREADY_IN_V2 = emptySet<String>()
