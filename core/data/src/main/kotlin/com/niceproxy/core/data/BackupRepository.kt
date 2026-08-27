package com.niceproxy.core.data

import com.niceproxy.core.common.Dispatcher
import com.niceproxy.core.common.NiceDispatcher
import com.niceproxy.core.config.backup.BackupCrypto
import com.niceproxy.core.config.backup.BackupError
import com.niceproxy.core.config.backup.BackupException
import com.niceproxy.core.config.backup.BackupPayload
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
     * 导出备份。
     *
     * 凭据解不开的节点会被**排除**，不写进备份。
     * 它们的 `toDomain()` 结果里密码是空占位符（见 `ServerEntity`），
     * 照原样导出等于把一个永久损坏的节点固化进备份文件 ——
     * 用户当时看不出异常，等到换机恢复才发现节点连不上，而那时数据库里
     * 原本还能抢救的密文早就没了。宁可少导出，也不能导出坏数据。
     */
    suspend fun export(password: CharArray): Result<ByteArray> = withContext(ioDispatcher) {
        runCatching {
            val payload = BackupPayload(
                createdAt = System.currentTimeMillis(),
                inbounds = inboundDao.getAll()
                    .filterNot { it.hasUnreadableAuth }
                    .map { it.toDomain() },
                groups = groupDao.getAll().map { it.toDomain() },
                servers = serverDao.getAll()
                    .filterNot { it.hasUnreadableCredentials }
                    .map { it.toDomain() },
                rules = routingDao.getRules().map { it.toDomain() },
                ruleSets = routingDao.getRuleSets().map { it.toDomain() },
            )
            BackupCrypto.encrypt(
                json.encodeToString(BackupPayload.serializer(), payload),
                password,
            )
        }
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
