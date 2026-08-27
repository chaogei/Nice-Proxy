package com.niceproxy.core.data

import com.niceproxy.core.common.Dispatcher
import com.niceproxy.core.common.NiceDispatcher
import com.niceproxy.core.config.share.ShareLinkParsers
import com.niceproxy.core.database.dao.ServerDao
import com.niceproxy.core.database.dao.ServerGroupDao
import com.niceproxy.core.database.entity.toDomain
import com.niceproxy.core.database.entity.toEntity
import com.niceproxy.core.model.GroupType
import com.niceproxy.core.model.ServerGroup
import com.niceproxy.core.model.ServerProfile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServerRepository @Inject constructor(
    private val serverDao: ServerDao,
    private val groupDao: ServerGroupDao,
    @Dispatcher(NiceDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * `flowOn` 不能省：`toDomain()` 要把 `params` / `transport` / `tls` 三个字段
     * 从 JSON 反序列化出来，而下游是 `stateIn(viewModelScope)`，默认在主线程做。
     * 订阅动辄几百个节点，等于每次数据库变更都在主线程上做上千次反序列化。
     */
    val servers: Flow<List<ServerProfile>> =
        serverDao.observeAll().map { list -> list.map { it.toDomain() } }.flowOn(ioDispatcher)

    val groups: Flow<List<ServerGroup>> =
        groupDao.observeAll().map { list -> list.map { it.toDomain() } }.flowOn(ioDispatcher)

    fun serversInGroup(groupId: String): Flow<List<ServerProfile>> =
        serverDao.observeByGroup(groupId)
            .map { list -> list.map { it.toDomain() } }
            .flowOn(ioDispatcher)

    suspend fun getAll(): List<ServerProfile> = withContext(ioDispatcher) {
        serverDao.getAll().map { it.toDomain() }
    }

    suspend fun get(id: String): ServerProfile? = withContext(ioDispatcher) {
        serverDao.getById(id)?.toDomain()
    }

    suspend fun save(server: ServerProfile) = withContext(ioDispatcher) {
        serverDao.upsert(server.copy(updatedAt = System.currentTimeMillis()).toEntity())
    }

    suspend fun delete(id: String) = withContext(ioDispatcher) {
        serverDao.deleteById(id)
    }

    suspend fun updateLatency(id: String, latencyMs: Int?) = withContext(ioDispatcher) {
        serverDao.updateLatency(id, latencyMs, System.currentTimeMillis())
    }

    suspend fun clearAllLatency() = withContext(ioDispatcher) {
        serverDao.clearAllLatency()
    }

    /**
     * 删除重复节点，每组保留最早创建的一个。
     *
     * 判重用「协议 + 地址 + 端口 + 协议参数」的组合，而不是节点名 ——
     * 同一个节点在不同订阅里往往叫不同的名字，按名字判重会漏掉一大半。
     * 反过来，同名但参数不同的节点是真的两个节点，不能合并。
     */
    suspend fun deleteDuplicates(groupId: String? = null): Int = withContext(ioDispatcher) {
        val candidates = serverDao.getAll()
            .filter { groupId == null || it.groupId == groupId }
        val removable = candidates
            .groupBy { listOf(it.protocol, it.server, it.serverPort, it.paramsJson) }
            .values
            .filter { it.size > 1 }
            .flatMap { group -> group.sortedBy { it.createdAt }.drop(1) }

        removable.forEach { serverDao.deleteById(it.id) }
        removable.size
    }

    /**
     * 删除测速失败的节点。
     *
     * 只处理已测过的：从未测速的节点 latency 为 null，不能当成无效 ——
     * 那会把用户刚导入还没来得及测的节点全删掉。
     */
    suspend fun deleteInvalid(groupId: String? = null): Int = withContext(ioDispatcher) {
        val removable = serverDao.getAll()
            .filter { groupId == null || it.groupId == groupId }
            .filter { it.latencyMs == ServerProfile.LATENCY_TIMEOUT }

        removable.forEach { serverDao.deleteById(it.id) }
        removable.size
    }

    // ------------------------------------------------------------ 分组

    suspend fun getGroups(): List<ServerGroup> = withContext(ioDispatcher) {
        groupDao.getAll().map { it.toDomain() }
    }

    suspend fun getGroup(id: String): ServerGroup? = withContext(ioDispatcher) {
        groupDao.getById(id)?.toDomain()
    }

    suspend fun saveGroup(group: ServerGroup) = withContext(ioDispatcher) {
        groupDao.upsert(group.toEntity())
    }

    suspend fun deleteGroup(id: String) = withContext(ioDispatcher) {
        // servers 表对 group_id 设了 CASCADE 外键，组内节点会一并删除
        groupDao.deleteById(id)
    }

    /**
     * 保证存在一个默认的手动分组。
     *
     * 从剪贴板或二维码导入的节点需要一个归属，不能要求用户先去建分组 ——
     * 那是把内部数据模型的约束暴露给了用户。
     */
    suspend fun ensureDefaultGroup(): String = withContext(ioDispatcher) {
        val existing = groupDao.getAll().firstOrNull { it.type == GroupType.MANUAL }
        if (existing != null) return@withContext existing.id

        val group = ServerGroup(
            id = UUID.randomUUID().toString(),
            name = "我的节点",
            type = GroupType.MANUAL,
        )
        groupDao.upsert(group.toEntity())
        group.id
    }

    // ------------------------------------------------------------ 导入

    /** 从剪贴板等文本批量导入分享链接。 */
    suspend fun importFromText(text: String, groupId: String? = null): ImportOutcome =
        withContext(ioDispatcher) {
            val target = groupId ?: ensureDefaultGroup()
            val parsed = ShareLinkParsers.parseMany(text, target)
            if (parsed.nodes.isNotEmpty()) {
                serverDao.upsertAll(parsed.nodes.map { it.toEntity() })
            }
            ImportOutcome(imported = parsed.nodes.size, failed = parsed.failedLines.size)
        }

    /** 订阅更新：整组替换。 */
    suspend fun replaceGroupServers(groupId: String, servers: List<ServerProfile>) =
        withContext(ioDispatcher) {
            serverDao.replaceGroupServers(groupId, servers.map { it.toEntity() })
        }

    data class ImportOutcome(val imported: Int, val failed: Int)
}
