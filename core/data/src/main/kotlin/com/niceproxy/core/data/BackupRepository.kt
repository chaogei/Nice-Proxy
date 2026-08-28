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
import com.niceproxy.core.database.dao.TrafficDao
import com.niceproxy.core.database.entity.InboundEntity
import com.niceproxy.core.database.entity.ServerEntity
import com.niceproxy.core.database.entity.ServerGroupEntity
import com.niceproxy.core.database.entity.TrafficDailyRecord
import com.niceproxy.core.database.entity.toDomain
import com.niceproxy.core.database.entity.toEntity
import com.niceproxy.core.database.entity.toRecord
import com.niceproxy.core.model.InboundService
import com.niceproxy.core.model.RoutingRule
import com.niceproxy.core.model.RuleSetRef
import com.niceproxy.core.model.ServerGroup
import com.niceproxy.core.model.ServerProfile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupRepository @Inject constructor(
    private val inboundDao: InboundDao,
    private val serverDao: ServerDao,
    private val groupDao: ServerGroupDao,
    private val routingDao: RoutingDao,
    private val trafficDao: TrafficDao,
    private val transactions: TransactionRunner,
    @Dispatcher(NiceDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    data class RestoreSummary(
        val inbounds: Int,
        val groups: Int,
        val servers: Int,
        val rules: Int,
        val trafficRows: Int = 0,
        /** 恢复时按冲突策略处置掉的条目，见 [RestoreConflicts]。 */
        val conflicts: RestoreConflicts = RestoreConflicts(),
    )

    /**
     * 恢复时按策略丢弃的条目。
     *
     * 存在的理由和 [ExportSkips] 一样：策略本身是对的，但**不能是静默的**。
     * 一份被手工改过或跨版本导出的备份，用户有权知道它进来之后少了什么。
     */
    data class RestoreConflicts(
        /** 备份内部 id 重复，保留第一条。 */
        val duplicateIds: List<String> = emptyList(),
        /** 节点指向一个不在备份里的分组，只能连节点一起丢。 */
        val orphanedServers: List<String> = emptyList(),
    ) {
        val isEmpty: Boolean get() = duplicateIds.isEmpty() && orphanedServers.isEmpty()
        val total: Int get() = duplicateIds.size + orphanedServers.size
    }

    /**
     * 导出时被跳过的条目。
     *
     * 存在的意义只有一个：让「宁可少导出」这个策略**不再是静默的**。
     * 跳过本身是对的（理由见 [export]），但用户必须在点导出之前就知道
     * 这份备份是不完整的，否则他会以为一切安好，直到换机恢复才发现少了东西。
     *
     * 从「只有计数」升级成**带名字**：一句「跳过了 3 个节点」用户是没法处置的，
     * 他既不知道跳的是哪几个，也就无从判断要不要现在就去重新导入。名字是
     * 唯一能让这份提示变成一个可执行动作的东西。
     */
    data class ExportSkips(
        val inbounds: List<SkippedItem> = emptyList(),
        val groups: List<SkippedItem> = emptyList(),
        val servers: List<SkippedItem> = emptyList(),
    ) {
        val isEmpty: Boolean get() = inbounds.isEmpty() && groups.isEmpty() && servers.isEmpty()
        val total: Int get() = inbounds.size + groups.size + servers.size
        val all: List<SkippedItem> get() = inbounds + groups + servers
    }

    data class SkippedItem(val id: String, val name: String, val reason: SkipReason)

    enum class SkipReason {
        /** 密文解不开：换过 Keystore 密钥、恢复出厂设置、或者这一行被改过。 */
        UNREADABLE_SECRET,

        /** 密文是好的，是里面的 JSON 读不懂了。成因通常是代码改动而非数据损坏。 */
        UNDECODABLE_JSON,

        /** 它本身没问题，是它所属的分组被跳过了。 */
        GROUP_SKIPPED,
    }

    /**
     * 在真正导出之前算一遍会跳过什么，供 UI 做提示与二次确认。
     *
     * 和 [export] 走同一条筛选逻辑，不会出现「提示说不跳，导出却跳了」。
     * 但**不会**顺带把整份 payload 构建出来：那意味着把全部节点再映射一遍
     * 领域对象，一次纯粹的 UI 预检查凭空多占一份峰值内存。
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
            BackupCrypto.encrypt(json.encodeToString(JsonObject.serializer(), buildPayload()), password)
        }
    }

    private class Selection(
        val inbounds: List<InboundEntity>,
        val groups: List<ServerGroupEntity>,
        val servers: List<ServerEntity>,
        val skips: ExportSkips,
    )

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
        val decodableServers = allServers.filter { it.isFullyDecodable }
        val servers = decodableServers
            // 丢掉分组就必须连它的节点一起丢：servers.group_id 上有外键，
            // 留下孤儿节点会让恢复在插入第一条时整体失败 —— 现在恢复是一个
            // 事务，那等于这份备份彻底恢复不了。
            .filter { it.groupId in keptGroupIds }

        val decodableIds = decodableServers.mapTo(mutableSetOf()) { it.id }
        return Selection(
            inbounds = inbounds,
            groups = groups,
            servers = servers,
            skips = ExportSkips(
                inbounds = allInbounds.filter { it.hasUnreadableAuth }
                    .map { SkippedItem(it.id, it.tag(), SkipReason.UNREADABLE_SECRET) },
                groups = allGroups.filter { it.hasUnreadableSecrets }
                    .map { SkippedItem(it.id, it.name, SkipReason.UNREADABLE_SECRET) },
                servers = allServers
                    .filterNot { it.id in decodableIds && it.groupId in keptGroupIds }
                    .map {
                        SkippedItem(
                            id = it.id,
                            name = it.name,
                            reason = when {
                                it.hasUnreadableCredentials -> SkipReason.UNREADABLE_SECRET
                                it.id !in decodableIds -> SkipReason.UNDECODABLE_JSON
                                else -> SkipReason.GROUP_SKIPPED
                            },
                        )
                    },
            ),
        )
    }

    /**
     * 构建备份的 JSON。
     *
     * 流量表**不进 [BackupPayload]**，而是作为一个并列的顶层键挂在旁边。
     * 这不是偷懒：`BackupPayload` 是 `core:config` 里的公开文件格式，
     * 而流量统计是 `core:data` 的内部结构。挂成旁支的好处是双向兼容 ——
     * 旧版本应用读到这份备份时，`ignoreUnknownKeys` 会原样忽略这个键，
     * 而不是撞上一个读不懂的字段把整份备份判成「不是备份文件」。
     */
    private suspend fun buildPayload(): JsonObject {
        val selection = select()
        val payload = BackupPayload(
            createdAt = System.currentTimeMillis(),
            inbounds = selection.inbounds.map { it.toDomain() },
            groups = selection.groups.map { it.toDomain() },
            servers = selection.servers.map { it.toDomain() },
            rules = routingDao.getRules().map { it.toDomain() },
            ruleSets = routingDao.getRuleSets().map { it.toDomain() },
        )
        val root = json.encodeToJsonElement(BackupPayload.serializer(), payload).jsonObject
        return JsonObject(root + (TRAFFIC_KEY to encodeTraffic()))
    }

    /**
     * 分页读流量表。
     *
     * 一张 90 天 × 几十个 tag 的表是几千行，`getRange` 一把梭当然也读得下来；
     * 分页是为了让这条路径的峰值内存与表的大小**无关** —— 备份是其他所有
     * 故障的逃生舱，它自己不该有一个「数据攒够多就 OOM」的隐藏上限。
     * 同样的道理，超过 [MAX_TRAFFIC_ROWS] 就不再往下读：图表要的是最近几十
     * 天的形状，而不是一份完整的历史账本。
     */
    private suspend fun encodeTraffic(): JsonElement {
        val records = mutableListOf<TrafficDailyRecord>()
        var offset = 0
        while (records.size < MAX_TRAFFIC_ROWS) {
            val page = trafficDao.page(TRAFFIC_PAGE, offset)
            if (page.isEmpty()) break
            page.forEach { records += it.toRecord() }
            offset += page.size
            if (page.size < TRAFFIC_PAGE) break
        }
        return json.encodeToJsonElement(
            ListSerializer(TrafficDailyRecord.serializer()),
            records.take(MAX_TRAFFIC_ROWS),
        )
    }

    /**
     * 恢复备份。
     *
     * **整体替换**而不是合并，冲突策略据此展开：
     *
     * - **恢复前已有的数据**：全部清空。合并会产生大量重复节点与冲突的入站
     *   端口，而用户点「恢复」的预期就是「回到备份时的状态」。UI 必须在此前确认。
     * - **备份内部 id 重复**：保留第一条。这只可能出现在被手工改过或跨版本
     *   导出的文件里；真让它们都写进去，`@Upsert` 的行为是后写的盖掉先写的，
     *   于是「保留哪一条」变成了一件取决于 map 迭代顺序的事。显式定死第一条，
     *   并且报出来。
     * - **节点指向备份里没有的分组**：连节点一起丢，并报出来。放行的话第一条
     *   就会撞上 `servers.group_id` 的外键约束，而恢复是一个事务 ——
     *   那等于这份备份彻底恢复不了，一个手工改坏的分组能连累整份备份。
     * - **备份里的分组和现存的重名**：不做任何特殊处理。现存的已经被清空了，
     *   重名无从谈起。
     */
    suspend fun restore(data: ByteArray, password: CharArray): Result<RestoreSummary> =
        withContext(ioDispatcher) {
            val plaintext = BackupCrypto.decrypt(data, password)
                .getOrElse { return@withContext Result.failure(it) }

            val root = runCatching { json.parseToJsonElement(plaintext).jsonObject }
                .getOrElse { return@withContext Result.failure(BackupException(BackupError.NotABackup)) }

            val payload = runCatching {
                json.decodeFromJsonElement(BackupPayload.serializer(), root)
            }.getOrElse {
                return@withContext Result.failure(BackupException(BackupError.NotABackup))
            }

            if (payload.version > BackupPayload.CURRENT_VERSION) {
                return@withContext Result.failure(
                    BackupException(BackupError.UnsupportedVersion(payload.version)),
                )
            }

            // 流量表读不出来绝不能让整份备份失败：它是这份文件里唯一可丢弃的
            // 部分，而节点凭据不是。旧版本导出的备份根本没有这个键。
            val traffic = runCatching {
                root[TRAFFIC_KEY]?.let {
                    json.decodeFromJsonElement(ListSerializer(TrafficDailyRecord.serializer()), it)
                }.orEmpty()
            }.getOrDefault(emptyList())

            val plan = plan(payload, traffic)

            runCatching {
                // 整段必须在一个事务里。先删后插之间任何一次失败都会让用户
                // 什么都不剩：删分组会顺着外键 CASCADE 带走全部节点，而备份
                // 也没恢复上。磁盘写满、恢复中途被系统杀进程都会走到这里。
                // 这是整套流程里唯一能一次性清空用户全部数据的路径，而备份
                // 恰恰是其他所有故障的逃生舱。同样的道理 `ServerDao` 早就为
                // 订阅整组替换写过一次了。
                transactions.withTransaction {
                    // 逐条 deleteById 是 N 条语句，而清空只要一条。恢复一份
                    // 上千节点的备份时，那是上千次没有必要的往返。
                    inboundDao.deleteAll()
                    // 分组必须先于节点写入：servers 表对 group_id 有外键约束。
                    // 删分组时顺序相反 —— CASCADE 会带走节点。
                    groupDao.deleteAll()
                    routingDao.deleteAllRules()
                    // 规则集同样要清空。只清规则的话，备份里没有的旧规则集会残留下来，
                    // 恢复出的状态既不是备份时的也不是恢复前的
                    routingDao.deleteAllRuleSets()
                    trafficDao.deleteAll()

                    plan.groups.forEach { groupDao.upsert(it.toEntity()) }
                    // 分块写入：单次 upsertAll 的入参列表越长，Room 生成的
                    // 绑定与 SQLite 的语句缓存压力就越大，而这一段跑在事务里，
                    // 峰值内存是「已解码的领域对象 + 映射出的实体」两份。
                    plan.servers.chunked(WRITE_CHUNK).forEach { chunk ->
                        serverDao.upsertAll(chunk.map { it.toEntity() })
                    }
                    plan.inbounds.chunked(WRITE_CHUNK).forEach { chunk ->
                        inboundDao.upsertAll(chunk.map { it.toEntity() })
                    }
                    plan.rules.chunked(WRITE_CHUNK).forEach { chunk ->
                        routingDao.upsertRules(chunk.map { it.toEntity() })
                    }
                    plan.ruleSets.chunked(WRITE_CHUNK).forEach { chunk ->
                        routingDao.upsertRuleSets(chunk.map { it.toEntity() })
                    }
                    val importedAt = System.currentTimeMillis()
                    plan.traffic.chunked(WRITE_CHUNK).forEach { chunk ->
                        trafficDao.upsertAll(chunk.map { it.toEntity(importedAt) })
                    }

                    RestoreSummary(
                        inbounds = plan.inbounds.size,
                        groups = plan.groups.size,
                        servers = plan.servers.size,
                        rules = plan.rules.size,
                        trafficRows = plan.traffic.size,
                        conflicts = plan.conflicts,
                    )
                }
            }
        }

    private class RestorePlan(
        val inbounds: List<InboundService>,
        val groups: List<ServerGroup>,
        val servers: List<ServerProfile>,
        val rules: List<RoutingRule>,
        val ruleSets: List<RuleSetRef>,
        val traffic: List<TrafficDailyRecord>,
        val conflicts: RestoreConflicts,
    )

    /**
     * 在**碰数据库之前**把备份规整成一份一定写得进去的计划。
     *
     * 顺序很关键：清空是不可逆的，所以任何「这份备份有问题」的判断都必须
     * 发生在第一条 DELETE 之前。事务回滚是安全网，不是第一道防线 ——
     * 靠回滚兜住的每一次失败，对用户来说都是一次「恢复失败」。
     */
    private fun plan(payload: BackupPayload, traffic: List<TrafficDailyRecord>): RestorePlan {
        val duplicates = mutableListOf<String>()
        val groups = payload.groups.distinctById({ it.id }, duplicates)
        val groupIds = groups.mapTo(mutableSetOf()) { it.id }
        val servers = payload.servers.distinctById({ it.id }, duplicates)
        val (kept, orphaned) = servers.partition { it.groupId in groupIds }

        return RestorePlan(
            inbounds = payload.inbounds.distinctById({ it.id }, duplicates),
            groups = groups,
            servers = kept,
            rules = payload.rules.distinctById({ it.id }, duplicates),
            ruleSets = payload.ruleSets.distinctById({ it.id }, duplicates),
            // 流量行的主键是 (day, tag)，重复的最后一条赢即可 —— 它是可丢弃
            // 数据，为它单独报一次冲突只会淹没真正要紧的那两类
            traffic = traffic.distinctBy { it.day to it.tag }.take(MAX_TRAFFIC_ROWS),
            conflicts = RestoreConflicts(
                duplicateIds = duplicates,
                orphanedServers = orphaned.map { it.id },
            ),
        )
    }

    private fun <T> List<T>.distinctById(id: (T) -> String, into: MutableList<String>): List<T> {
        val seen = mutableSetOf<String>()
        return filter { item ->
            if (seen.add(id(item))) true else { into += id(item); false }
        }
    }

    private fun InboundEntity.tag(): String =
        "${type.name}:$listenPort"

    private companion object {
        /** 流量表在备份 JSON 里的顶层键。改它等于改文件格式。 */
        const val TRAFFIC_KEY = "trafficDaily"

        /** 分页读流量表时每页多少行。 */
        const val TRAFFIC_PAGE = 500

        /**
         * 备份里最多带多少行流量账。
         *
         * 90 天 × 32 个 tag 不到 3000 行，两万留了足够余量，同时给
         * 「备份文件被人为放大」画了一条线：它是可丢弃数据，不值得为它
         * 让恢复过程 OOM。
         */
        const val MAX_TRAFFIC_ROWS = 20_000

        /** 恢复时每批写多少行。 */
        const val WRITE_CHUNK = 500
    }
}
