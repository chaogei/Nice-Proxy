package com.niceproxy.core.data

import com.niceproxy.core.common.Dispatcher
import com.niceproxy.core.common.NiceDispatcher
import com.niceproxy.core.config.backup.BackupCrypto
import com.niceproxy.core.config.backup.BackupError
import com.niceproxy.core.config.backup.BackupException
import com.niceproxy.core.config.backup.BackupPayload
import com.niceproxy.core.database.TransactionRunner
import com.niceproxy.core.database.dao.InboundDao
import com.niceproxy.core.database.dao.RoutingDao
import com.niceproxy.core.database.dao.ServerDao
import com.niceproxy.core.database.dao.ServerGroupDao
import com.niceproxy.core.database.entity.toDomain
import com.niceproxy.core.database.entity.toEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupRepository @Inject constructor(
    private val inboundDao: InboundDao,
    private val serverDao: ServerDao,
    private val groupDao: ServerGroupDao,
    private val routingDao: RoutingDao,
    private val transactions: TransactionRunner,
    @Dispatcher(NiceDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    data class RestoreSummary(
        val inbounds: Int,
        val groups: Int,
        val servers: Int,
        val rules: Int,
    )

    /**
     * 导出时被跳过的条目数。
     *
     * 存在的意义只有一个：让「宁可少导出」这个策略**不再是静默的**。
     * 跳过本身是对的（理由见 [export]），但用户必须在点导出之前就知道
     * 这份备份是不完整的，否则他会以为一切安好，直到换机恢复才发现少了东西。
     */
    data class ExportSkips(
        val inbounds: Int,
        val groups: Int,
        val servers: Int,
    ) {
        val isEmpty: Boolean get() = inbounds == 0 && groups == 0 && servers == 0
        val total: Int get() = inbounds + groups + servers
    }

    /**
     * 在真正导出之前算一遍会跳过多少条，供 UI 做提示与二次确认。
     *
     * 和 [export] 走同一条筛选逻辑，不会出现「提示说不跳，导出却跳了」。
     */
    suspend fun inspectExport(): ExportSkips = withContext(ioDispatcher) { select().skips }

    /**
     * 导出备份。
     *
     * 读不完整的记录会被**排除**，不写进备份。它们 `toDomain()` 出来的是占位值
     * （空密码、顶替上去的 TLS，见 `ServerEntity`），照原样导出等于把一个永久
     * 损坏的节点固化进备份文件 —— 用户当时看不出异常，等到换机恢复才发现节点
     * 连不上，而那时数据库里原本还能抢救的密文早就没了。宁可少导出，也不能
     * 导出坏数据；跳过了什么由 [inspectExport] 告诉用户。
     */
    suspend fun export(password: CharArray): Result<ByteArray> = withContext(ioDispatcher) {
        runCatching {
            BackupCrypto.encrypt(
                json.encodeToString(BackupPayload.serializer(), select().payload),
                password,
            )
        }
    }

    private class Selection(val payload: BackupPayload, val skips: ExportSkips)

    private suspend fun select(): Selection {
        val allInbounds = inboundDao.getAll()
        val allGroups = groupDao.getAll()
        val allServers = serverDao.getAll()

        val inbounds = allInbounds.filterNot { it.hasUnreadableAuth }
        // 分组过去从不过滤，于是订阅地址解不开时会导出一个没有 url 的订阅组：
        // 恢复之后它被判定成「不是订阅」，用户永远刷不了新，而 token 恰恰是
        // 最不可能靠记忆重建的东西。
        val groups = allGroups.filterNot { it.hasUnreadableSecrets }
        val keptGroupIds = groups.mapTo(mutableSetOf()) { it.id }
        val servers = allServers
            .filter { it.isFullyDecodable }
            // 丢掉分组就必须连它的节点一起丢：servers.group_id 上有外键，
            // 留下孤儿节点会让恢复在插入第一条时整体失败 —— 现在恢复是一个
            // 事务，那等于这份备份彻底恢复不了。
            .filter { it.groupId in keptGroupIds }

        val payload = BackupPayload(
            createdAt = System.currentTimeMillis(),
            inbounds = inbounds.map { it.toDomain() },
            groups = groups.map { it.toDomain() },
            servers = servers.map { it.toDomain() },
            rules = routingDao.getRules().map { it.toDomain() },
            ruleSets = routingDao.getRuleSets().map { it.toDomain() },
        )
        return Selection(
            payload = payload,
            skips = ExportSkips(
                inbounds = allInbounds.size - inbounds.size,
                groups = allGroups.size - groups.size,
                servers = allServers.size - servers.size,
            ),
        )
    }

    /**
     * 恢复备份。
     *
     * **整体替换**而不是合并：合并会产生大量重复节点与冲突的入站端口，
     * 而用户点「恢复」的预期就是「回到备份时的状态」。UI 必须在此前确认。
     */
    suspend fun restore(data: ByteArray, password: CharArray): Result<RestoreSummary> =
        withContext(ioDispatcher) {
            val plaintext = BackupCrypto.decrypt(data, password)
                .getOrElse { return@withContext Result.failure(it) }

            val payload = runCatching {
                json.decodeFromString(BackupPayload.serializer(), plaintext)
            }.getOrElse {
                return@withContext Result.failure(BackupException(BackupError.NotABackup))
            }

            if (payload.version > BackupPayload.CURRENT_VERSION) {
                return@withContext Result.failure(
                    BackupException(BackupError.UnsupportedVersion(payload.version)),
                )
            }

            runCatching {
                // 整段必须在一个事务里。先删后插之间任何一次失败都会让用户
                // 什么都不剩：删分组会顺着外键 CASCADE 带走全部节点，而备份
                // 也没恢复上。最现实的触发是备份里的节点指向一个不在备份里的
                // 分组（备份被手工改过，或跨版本导出），`upsertAll` 撞上外键
                // 约束抛异常；磁盘写满、恢复中途被系统杀进程同理。
                // 这是整套流程里唯一能一次性清空用户全部数据的路径，而备份
                // 恰恰是其他所有故障的逃生舱。同样的道理 `ServerDao` 早就为
                // 订阅整组替换写过一次了。
                transactions.withTransaction {
                    // 分组必须先于节点写入：servers 表对 group_id 有外键约束
                    inboundDao.getAll().forEach { inboundDao.deleteById(it.id) }
                    groupDao.getAll().forEach { groupDao.deleteById(it.id) }
                    routingDao.deleteAllRules()
                    // 规则集同样要清空。只清规则的话，备份里没有的旧规则集会残留下来，
                    // 恢复出的状态既不是备份时的也不是恢复前的
                    routingDao.getRuleSets().forEach { routingDao.deleteRuleSet(it.id) }

                    payload.groups.forEach { groupDao.upsert(it.toEntity()) }
                    if (payload.servers.isNotEmpty()) {
                        serverDao.upsertAll(payload.servers.map { it.toEntity() })
                    }
                    if (payload.inbounds.isNotEmpty()) {
                        inboundDao.upsertAll(payload.inbounds.map { it.toEntity() })
                    }
                    if (payload.rules.isNotEmpty()) {
                        routingDao.upsertRules(payload.rules.map { it.toEntity() })
                    }
                    if (payload.ruleSets.isNotEmpty()) {
                        routingDao.upsertRuleSets(payload.ruleSets.map { it.toEntity() })
                    }

                    RestoreSummary(
                        inbounds = payload.inbounds.size,
                        groups = payload.groups.size,
                        servers = payload.servers.size,
                        rules = payload.rules.size,
                    )
                }
            }
        }
}
