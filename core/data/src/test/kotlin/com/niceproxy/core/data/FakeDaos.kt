package com.niceproxy.core.data

import com.niceproxy.core.database.TransactionRunner
import com.niceproxy.core.database.dao.InboundDao
import com.niceproxy.core.database.dao.RoutingDao
import com.niceproxy.core.database.dao.ServerDao
import com.niceproxy.core.database.dao.ServerGroupDao
import com.niceproxy.core.database.entity.InboundEntity
import com.niceproxy.core.database.entity.RoutingRuleEntity
import com.niceproxy.core.database.entity.RuleSetEntity
import com.niceproxy.core.database.entity.ServerEntity
import com.niceproxy.core.database.entity.ServerGroupEntity
import com.niceproxy.core.model.GroupType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/*
 * 手写的内存 DAO，用来把 Repository 的判断逻辑放进普通 JUnit 里跑。
 *
 * 不引 Robolectric、不起真 Room：这几个 Repository 里值得测的东西
 * （判重、保留未测速节点、恢复顺序）都是纯逻辑，为它们拉起一整个
 * Android 运行时只会让测试慢上两个数量级。
 *
 * 但排序、外键、事务这三件事必须照着真实 DAO 复刻 —— 逻辑正是依赖它们的：
 * 判重靠 `getAll()` 的稳定顺序决定保留哪一条，备份恢复靠外键约束
 * 才能暴露「分组必须先于节点写入」这个要求，靠事务回滚才能验证
 * 「中途失败不会把用户数据清空」。
 */

/**
 * 能被整体快照与回滚的内存存储。
 *
 * 存在的唯一目的是让 [FakeTransactionRunner] 有东西可回滚。
 */
internal interface InMemoryStore {
    /** 记下当前内容，返回一个把它原样写回去的动作。 */
    fun capture(): () -> Unit
}

/**
 * 事务的测试替身：[block] 抛异常就把所有 fake DAO 的内容恢复到进入前。
 *
 * 真实的原子性由 Room 保证，这里复刻的是**语义**，用来钉住调用方确实把
 * 整段写操作放进了事务里 —— 而这一点恰恰是 `BackupRepository.restore`
 * 里最要命的一条：它先删后插，中途失败就是用户全部数据清零。
 */
internal class FakeTransactionRunner(
    private val stores: List<InMemoryStore>,
) : TransactionRunner {

    var transactionCount: Int = 0
        private set

    override suspend fun <R> withTransaction(block: suspend () -> R): R {
        transactionCount++
        val undo = stores.map { it.capture() }
        return try {
            block()
        } catch (t: Throwable) {
            undo.forEach { it() }
            throw t
        }
    }
}

internal class FakeServerGroupDao : ServerGroupDao, InMemoryStore {

    private val items = MutableStateFlow<List<ServerGroupEntity>>(emptyList())

    private fun sorted(list: List<ServerGroupEntity>) =
        list.sortedWith(compareBy({ it.sortOrder }, { it.name }))

    override fun observeAll(): Flow<List<ServerGroupEntity>> = items.map { sorted(it) }

    override suspend fun getAll(): List<ServerGroupEntity> = sorted(items.value)

    override suspend fun getById(id: String): ServerGroupEntity? =
        items.value.firstOrNull { it.id == id }

    override suspend fun getAutoUpdatable(): List<ServerGroupEntity> =
        items.value.filter { it.type == GroupType.SUBSCRIPTION && it.autoUpdate }

    override suspend fun upsert(group: ServerGroupEntity) {
        items.update { current -> current.filterNot { it.id == group.id } + group }
    }

    override suspend fun deleteById(id: String) {
        items.update { current -> current.filterNot { it.id == id } }
        cascade?.invoke(id)
    }

    override suspend fun count(): Int = items.value.size

    override fun capture(): () -> Unit {
        val saved = items.value
        return { items.value = saved }
    }

    /** 模拟 servers 表上的 ON DELETE CASCADE。 */
    var cascade: (suspend (String) -> Unit)? = null
}

internal class FakeServerDao(
    private val groups: FakeServerGroupDao? = null,
) : ServerDao, InMemoryStore {

    private val items = MutableStateFlow<List<ServerEntity>>(emptyList())

    init {
        groups?.cascade = { groupId -> deleteByGroup(groupId) }
    }

    private fun sorted(list: List<ServerEntity>) =
        list.sortedWith(compareBy({ it.sortOrder }, { it.name }))

    override fun observeAll(): Flow<List<ServerEntity>> = items.map { sorted(it) }

    override fun observeByGroup(groupId: String): Flow<List<ServerEntity>> =
        items.map { list -> sorted(list.filter { it.groupId == groupId }) }

    override suspend fun getAll(): List<ServerEntity> = sorted(items.value)

    override suspend fun getById(id: String): ServerEntity? = items.value.firstOrNull { it.id == id }

    override suspend fun upsert(server: ServerEntity) = upsertAll(listOf(server))

    override suspend fun upsertAll(servers: List<ServerEntity>) {
        if (groups != null) {
            servers.forEach { server ->
                checkNotNull(groups.getById(server.groupId)) {
                    "FOREIGN KEY constraint failed: 分组 ${server.groupId} 不存在"
                }
            }
        }
        val ids = servers.mapTo(mutableSetOf()) { it.id }
        items.update { current -> current.filterNot { it.id in ids } + servers }
    }

    override suspend fun deleteById(id: String) {
        items.update { current -> current.filterNot { it.id == id } }
        deleteCount++
    }

    override suspend fun deleteByGroup(groupId: String) {
        items.update { current -> current.filterNot { it.groupId == groupId } }
    }

    override suspend fun updateLatency(id: String, latencyMs: Int?, testedAt: Long) {
        items.update { current ->
            current.map { if (it.id == id) it.copy(latencyMs = latencyMs, lastTestedAt = testedAt) else it }
        }
    }

    override suspend fun clearAllLatency() {
        items.update { current -> current.map { it.copy(latencyMs = null, lastTestedAt = null) } }
    }

    override fun capture(): () -> Unit {
        val saved = items.value
        return { items.value = saved }
    }

    /** 单条删除的调用次数，用来观察批量操作的写放大。 */
    var deleteCount: Int = 0
        private set
}

internal class FakeInboundDao : InboundDao, InMemoryStore {

    private val items = MutableStateFlow<List<InboundEntity>>(emptyList())

    private fun sorted(list: List<InboundEntity>) =
        list.sortedWith(compareBy({ it.sortOrder }, { it.listenPort }))

    override fun observeAll(): Flow<List<InboundEntity>> = items.map { sorted(it) }

    override suspend fun getAll(): List<InboundEntity> = sorted(items.value)

    override suspend fun getById(id: String): InboundEntity? = items.value.firstOrNull { it.id == id }

    override suspend fun countByPort(port: Int, excludeId: String): Int =
        items.value.count { it.listenPort == port && it.id != excludeId && it.enabled }

    override suspend fun upsert(inbound: InboundEntity) = upsertAll(listOf(inbound))

    override suspend fun upsertAll(inbounds: List<InboundEntity>) {
        val ids = inbounds.mapTo(mutableSetOf()) { it.id }
        items.update { current -> current.filterNot { it.id in ids } + inbounds }
    }

    override suspend fun delete(inbound: InboundEntity) = deleteById(inbound.id)

    override suspend fun deleteById(id: String) {
        items.update { current -> current.filterNot { it.id == id } }
    }

    override suspend fun setEnabled(id: String, enabled: Boolean) {
        items.update { current -> current.map { if (it.id == id) it.copy(enabled = enabled) else it } }
    }

    override suspend fun count(): Int = items.value.size

    override fun capture(): () -> Unit {
        val saved = items.value
        return { items.value = saved }
    }
}

internal class FakeRoutingDao : RoutingDao, InMemoryStore {

    private val ruleStore = MutableStateFlow<List<RoutingRuleEntity>>(emptyList())
    private val ruleSetStore = MutableStateFlow<List<RuleSetEntity>>(emptyList())

    override fun observeRules(): Flow<List<RoutingRuleEntity>> =
        ruleStore.map { list -> list.sortedBy { it.sortOrder } }

    override suspend fun getRules(): List<RoutingRuleEntity> =
        ruleStore.value.sortedBy { it.sortOrder }

    override suspend fun upsertRule(rule: RoutingRuleEntity) = upsertRules(listOf(rule))

    override suspend fun upsertRules(rules: List<RoutingRuleEntity>) {
        val ids = rules.mapTo(mutableSetOf()) { it.id }
        ruleStore.update { current -> current.filterNot { it.id in ids } + rules }
    }

    override suspend fun deleteRule(id: String) {
        ruleStore.update { current -> current.filterNot { it.id == id } }
    }

    override suspend fun deleteAllRules() {
        ruleStore.value = emptyList()
    }

    override fun observeRuleSets(): Flow<List<RuleSetEntity>> =
        ruleSetStore.map { list -> list.sortedBy { it.tag } }

    override suspend fun getRuleSets(): List<RuleSetEntity> = ruleSetStore.value.sortedBy { it.tag }

    override suspend fun upsertRuleSet(ruleSet: RuleSetEntity) = upsertRuleSets(listOf(ruleSet))

    override suspend fun upsertRuleSets(ruleSets: List<RuleSetEntity>) {
        val ids = ruleSets.mapTo(mutableSetOf()) { it.id }
        ruleSetStore.update { current -> current.filterNot { it.id in ids } + ruleSets }
    }

    override suspend fun deleteRuleSet(id: String) {
        ruleSetStore.update { current -> current.filterNot { it.id == id } }
    }

    override suspend fun deleteUnlockedRules() {
        ruleStore.update { current -> current.filter { it.locked } }
    }

    override fun capture(): () -> Unit {
        val savedRules = ruleStore.value
        val savedRuleSets = ruleSetStore.value
        return {
            ruleStore.value = savedRules
            ruleSetStore.value = savedRuleSets
        }
    }
}
