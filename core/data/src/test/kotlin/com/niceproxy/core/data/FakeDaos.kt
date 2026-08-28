package com.niceproxy.core.data

import com.niceproxy.core.database.TransactionRunner
import com.niceproxy.core.database.dao.InboundDao
import com.niceproxy.core.database.dao.RoutingDao
import com.niceproxy.core.database.dao.ServerDao
import com.niceproxy.core.database.dao.ServerGroupDao
import com.niceproxy.core.database.dao.TrafficDao
import com.niceproxy.core.database.dao.TrafficDelta
import com.niceproxy.core.database.entity.InboundEntity
import com.niceproxy.core.database.entity.RoutingRuleEntity
import com.niceproxy.core.database.entity.RuleSetEntity
import com.niceproxy.core.database.entity.ServerEntity
import com.niceproxy.core.database.entity.ServerGroupEntity
import com.niceproxy.core.database.entity.TrafficDailyEntity
import com.niceproxy.core.database.entity.TrafficDay
import com.niceproxy.core.database.entity.TrafficDayTotal
import com.niceproxy.core.database.entity.TrafficTagTotal
import com.niceproxy.core.database.entity.TrafficTags
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

    override suspend fun deleteAll() {
        val ids = items.value.map { it.id }
        items.value = emptyList()
        ids.forEach { cascade?.invoke(it) }
    }

    override suspend fun count(): Int = items.value.size

    override suspend fun firstManual(): ServerGroupEntity? =
        sorted(items.value).firstOrNull { it.type == GroupType.MANUAL }

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
        servers.forEach { failOnUpsert?.invoke(it) }
        // 真 Room 的 @Upsert 对同一批里 id 重复的行是「后写的赢」，
        // 而不是留下两行同 id 的记录 —— 主键不允许那样
        val ids = servers.mapTo(mutableSetOf()) { it.id }
        val deduped = servers.associateBy { it.id }.values
        items.update { current -> current.filterNot { it.id in ids } + deduped }
        upsertBatches += servers.size
    }

    override suspend fun deleteById(id: String) {
        items.update { current -> current.filterNot { it.id == id } }
        deleteCount++
    }

    override suspend fun deleteChunk(ids: List<String>) {
        check(ids.size <= ServerDao.DELETE_CHUNK) {
            "SQLITE_MAX_VARIABLE_NUMBER：一次 IN (...) 塞了 ${ids.size} 个绑定变量"
        }
        items.update { current -> current.filterNot { it.id in ids } }
        deleteCount++
    }

    override suspend fun deleteByGroup(groupId: String) {
        items.update { current -> current.filterNot { it.groupId == groupId } }
    }

    override suspend fun deleteAll() {
        items.value = emptyList()
    }

    override suspend fun count(): Int = items.value.size

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

    /** 删除语句的执行次数，用来观察批量操作的写放大。 */
    var deleteCount: Int = 0
        private set

    /** 写入过的行数总计。 */
    var upsertBatches: Int = 0
        private set

    /**
     * 写入故障注入。
     *
     * 「中途失败必须整体回滚」是备份恢复里最要命的一条语义，而恢复本身已经
     * 会在写库之前把明显有问题的备份规整干净（见 `BackupRepository.plan`）。
     * 于是要触发一次真实的中途失败，就只能模拟那些规整不掉的原因：
     * 磁盘写满、恢复途中被系统杀掉、SQLite 临时 I/O 错误。
     */
    var failOnUpsert: ((ServerEntity) -> Unit)? = null
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

    override suspend fun deleteAll() {
        items.value = emptyList()
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

    override suspend fun deleteAllRuleSets() {
        ruleSetStore.value = emptyList()
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

/**
 * 内存版的流量表。
 *
 * 复刻的是三件真 SQLite 会做、而「一个 MutableList」不会做的事：主键
 * `(day, outbound_tag)` 的唯一性、`accumulate` 的**累加**语义（不是覆盖），
 * 以及聚合查询按 SQL 的方式分组求和。这三件恰恰是记账逻辑赖以成立的前提。
 */
internal class FakeTrafficDao : TrafficDao, InMemoryStore {

    private val table = MutableStateFlow<Map<Pair<Int, String>, TrafficDailyEntity>>(emptyMap())

    /** 落库的次数。合并写入的全部意义就是让这个数远小于 record 的调用次数。 */
    var writes: Int = 0
        private set

    /** 下一次 [accumulateAll] 抛异常，用来验证失败的增量会被还回去。 */
    var failNextWrite: Boolean = false

    override suspend fun accumulateAll(deltas: List<TrafficDelta>, maxTagsPerDay: Int) {
        if (failNextWrite) {
            failNextWrite = false
            throw IllegalStateException("disk I/O error")
        }
        writes++
        // 复刻真 DAO 的默认实现：磁盘上的行数在这一层封顶
        val known = mutableMapOf<Int, MutableSet<String>>()
        deltas.forEach { delta ->
            val tags = known.getOrPut(delta.day) { tagsOn(delta.day).toMutableSet() }
            val tag = if (delta.outboundTag in tags || tags.size < maxTagsPerDay) {
                delta.outboundTag
            } else {
                TrafficTags.OVERFLOW
            }
            tags += tag
            accumulate(delta.copy(outboundTag = tag))
        }
    }

    override suspend fun tagsOn(day: Int): List<String> =
        table.value.values.filter { it.day == day }.map { it.outboundTag }

    override suspend fun accumulate(delta: TrafficDelta) {
        val key = delta.day to delta.outboundTag
        table.update { current ->
            val existing = current[key]
            current + (
                key to TrafficDailyEntity(
                    day = delta.day,
                    outboundTag = delta.outboundTag,
                    upload = (existing?.upload ?: 0) + delta.upload,
                    download = (existing?.download ?: 0) + delta.download,
                    updatedAt = 0,
                )
                )
        }
    }

    override suspend fun addToExisting(
        day: Int,
        outboundTag: String,
        upload: Long,
        download: Long,
        at: Long,
    ): Int = throw UnsupportedOperationException("走 accumulate")

    override suspend fun insertIfAbsent(row: TrafficDailyEntity): Long =
        throw UnsupportedOperationException("走 accumulate")

    override suspend fun upsertAll(rows: List<TrafficDailyEntity>) {
        table.update { current -> current + rows.associateBy { it.day to it.outboundTag } }
    }

    private fun inRange(from: Int, to: Int) =
        table.value.values.filter { it.day in from..to }.sortedWith(
            compareBy({ it.day }, { it.outboundTag }),
        )

    override suspend fun getRange(fromDay: Int, toDay: Int) = inRange(fromDay, toDay)

    override fun observeRange(fromDay: Int, toDay: Int): Flow<List<TrafficDailyEntity>> =
        table.map { inRange(fromDay, toDay) }

    private fun byDay(from: Int, to: Int) = inRange(from, to)
        .groupBy { it.day }
        .map { (day, entries) ->
            TrafficDayTotal(day, entries.sumOf { it.upload }, entries.sumOf { it.download })
        }
        .sortedBy { it.day }

    override suspend fun sumByDay(fromDay: Int, toDay: Int) = byDay(fromDay, toDay)

    override fun observeSumByDay(fromDay: Int, toDay: Int): Flow<List<TrafficDayTotal>> =
        table.map { byDay(fromDay, toDay) }

    override suspend fun sumByTag(fromDay: Int, toDay: Int): List<TrafficTagTotal> =
        inRange(fromDay, toDay)
            .groupBy { it.outboundTag }
            .map { (tag, entries) ->
                TrafficTagTotal(tag, entries.sumOf { it.upload }, entries.sumOf { it.download })
            }
            .sortedByDescending { it.upload + it.download }

    override suspend fun count(): Int = table.value.size

    override suspend fun page(limit: Int, offset: Int): List<TrafficDailyEntity> =
        inRange(TrafficDay.MIN, TrafficDay.MAX).drop(offset).take(limit)

    override suspend fun deleteAfter(today: Int): Int {
        val doomed = table.value.filterValues { it.day > today }
        table.update { current -> current - doomed.keys }
        return doomed.size
    }

    override suspend fun trimToRecentDays(keepDays: Int): Int {
        val kept = table.value.values.map { it.day }.distinct().sortedDescending().take(keepDays)
        val doomed = table.value.filterValues { it.day !in kept }
        table.update { current -> current - doomed.keys }
        return doomed.size
    }

    override suspend fun deleteAll() {
        table.value = emptyMap()
    }

    override fun capture(): () -> Unit {
        val saved = table.value
        return { table.value = saved }
    }
}
