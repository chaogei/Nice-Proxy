package com.niceproxy.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.niceproxy.core.database.entity.RoutingRuleEntity
import com.niceproxy.core.database.entity.RuleSetEntity
import com.niceproxy.core.database.entity.ServerEntity
import com.niceproxy.core.database.entity.ServerGroupEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerDao {

    @Query("SELECT * FROM servers ORDER BY sort_order ASC, name ASC")
    fun observeAll(): Flow<List<ServerEntity>>

    @Query("SELECT * FROM servers WHERE group_id = :groupId ORDER BY sort_order ASC, name ASC")
    fun observeByGroup(groupId: String): Flow<List<ServerEntity>>

    @Query("SELECT * FROM servers ORDER BY sort_order ASC, name ASC")
    suspend fun getAll(): List<ServerEntity>

    @Query("SELECT * FROM servers WHERE id = :id")
    suspend fun getById(id: String): ServerEntity?

    @Upsert
    suspend fun upsert(server: ServerEntity)

    @Upsert
    suspend fun upsertAll(servers: List<ServerEntity>)

    @Query("DELETE FROM servers WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM servers WHERE id IN (:ids)")
    suspend fun deleteChunk(ids: List<String>)

    /**
     * 批量删除，整批一个事务。
     *
     * 逐条 `deleteById` 在 SQLite 里是逐条事务：一次「删除重复节点」在上千
     * 节点的订阅上能删掉几百条，那就是几百次 fsync，几秒钟卡住不说，中途
     * 被系统杀掉还会留下一个删了一半的列表。
     *
     * 分块是因为 `IN (...)` 里每个 id 都是一个绑定变量，而 SQLite 的
     * `SQLITE_MAX_VARIABLE_NUMBER` 在旧版本上就是 999，超了直接抛异常。
     * 留出余量取 500。
     */
    @Transaction
    suspend fun deleteByIds(ids: List<String>) {
        ids.chunked(DELETE_CHUNK).forEach { deleteChunk(it) }
    }

    @Query("DELETE FROM servers WHERE group_id = :groupId")
    suspend fun deleteByGroup(groupId: String)

    @Query("DELETE FROM servers")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM servers")
    suspend fun count(): Int

    @Query("UPDATE servers SET latency_ms = :latencyMs, last_tested_at = :testedAt WHERE id = :id")
    suspend fun updateLatency(id: String, latencyMs: Int?, testedAt: Long)

    @Query("UPDATE servers SET latency_ms = NULL, last_tested_at = NULL")
    suspend fun clearAllLatency()

    /**
     * 凭据解不开的节点，用于给用户一个「重新导入」的入口。
     *
     * 只能在 Kotlin 侧过滤：能否解密不是 SQL 能表达的谓词。开销可以忽略 ——
     * 解密本身已经由 TypeConverter 在读游标时做完了，这里只判断一下类型。
     */
    suspend fun getUnreadable(): List<ServerEntity> =
        getAll().filter { it.hasUnreadableCredentials }

    /**
     * 订阅更新：整组替换。
     *
     * 用事务包住删除与插入，避免中途失败留下一个空组 ——
     * 那会让正在运行的内核失去全部出站。
     */
    @Transaction
    suspend fun replaceGroupServers(groupId: String, servers: List<ServerEntity>) {
        deleteByGroup(groupId)
        upsertAll(servers)
    }

    companion object {
        /** 见 [deleteByIds]：SQLite 的绑定变量上限是 999，留余量。 */
        const val DELETE_CHUNK = 500
    }
}

@Dao
interface ServerGroupDao {

    @Query("SELECT * FROM server_groups ORDER BY sort_order ASC, name ASC")
    fun observeAll(): Flow<List<ServerGroupEntity>>

    @Query("SELECT * FROM server_groups ORDER BY sort_order ASC, name ASC")
    suspend fun getAll(): List<ServerGroupEntity>

    @Query("SELECT * FROM server_groups WHERE id = :id")
    suspend fun getById(id: String): ServerGroupEntity?

    @Query("SELECT * FROM server_groups WHERE type = 'SUBSCRIPTION' AND auto_update = 1")
    suspend fun getAutoUpdatable(): List<ServerGroupEntity>

    @Upsert
    suspend fun upsert(group: ServerGroupEntity)

    @Query("DELETE FROM server_groups WHERE id = :id")
    suspend fun deleteById(id: String)

    /**
     * 清空全部分组。`servers.group_id` 上的 CASCADE 会一并带走全部节点 ——
     * 这正是备份恢复要的语义，但也意味着调用它之前必须已经在事务里。
     */
    @Query("DELETE FROM server_groups")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM server_groups")
    suspend fun count(): Int

    /**
     * 第一个手动分组，供「从剪贴板导入的节点该放哪」用。
     *
     * 有专门的查询是因为调用方原本是 `getAll().firstOrNull { ... }`：
     * 那会把每个分组的订阅 URL 都过一遍 Keystore 解密，只为了挑出一个
     * 根本没有 URL 的手动分组。
     */
    @Query("SELECT * FROM server_groups WHERE type = 'MANUAL' ORDER BY sort_order ASC, name ASC LIMIT 1")
    suspend fun firstManual(): ServerGroupEntity?
}

@Dao
interface RoutingDao {

    @Query("SELECT * FROM routing_rules ORDER BY sort_order ASC")
    fun observeRules(): Flow<List<RoutingRuleEntity>>

    @Query("SELECT * FROM routing_rules ORDER BY sort_order ASC")
    suspend fun getRules(): List<RoutingRuleEntity>

    @Upsert
    suspend fun upsertRule(rule: RoutingRuleEntity)

    @Upsert
    suspend fun upsertRules(rules: List<RoutingRuleEntity>)

    @Query("DELETE FROM routing_rules WHERE id = :id")
    suspend fun deleteRule(id: String)

    @Query("DELETE FROM routing_rules")
    suspend fun deleteAllRules()

    @Query("SELECT * FROM rule_sets ORDER BY tag ASC")
    fun observeRuleSets(): Flow<List<RuleSetEntity>>

    @Query("SELECT * FROM rule_sets ORDER BY tag ASC")
    suspend fun getRuleSets(): List<RuleSetEntity>

    @Upsert
    suspend fun upsertRuleSet(ruleSet: RuleSetEntity)

    @Upsert
    suspend fun upsertRuleSets(ruleSets: List<RuleSetEntity>)

    @Query("DELETE FROM rule_sets WHERE id = :id")
    suspend fun deleteRuleSet(id: String)

    @Query("DELETE FROM rule_sets")
    suspend fun deleteAllRuleSets()

    @Query("DELETE FROM routing_rules WHERE locked = 0")
    suspend fun deleteUnlockedRules()

    /**
     * 应用分流模板：替换未锁定的规则，保留用户标记为锁定的自定义规则。
     *
     * 锁定的规则排在模板规则之前 —— 用户手写的规则通常是要覆盖默认行为的，
     * 排在后面会被模板里的宽泛规则（如「国内域名直连」）截胡。
     */
    @Transaction
    suspend fun applyTemplateRules(templateRules: List<RoutingRuleEntity>) {
        val kept = getRules().filter { it.locked }
        deleteUnlockedRules()
        val reordered = kept.mapIndexed { index, rule -> rule.copy(sortOrder = index) } +
            templateRules.mapIndexed { index, rule ->
                rule.copy(sortOrder = kept.size + index)
            }
        upsertRules(reordered)
    }
}
