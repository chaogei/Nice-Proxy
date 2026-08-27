package com.niceproxy.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.niceproxy.core.database.crypto.SecretText
import com.niceproxy.core.database.crypto.asSecret
import com.niceproxy.core.database.crypto.readableOrNull
import com.niceproxy.core.model.GroupType
import com.niceproxy.core.model.MultiplexConfig
import com.niceproxy.core.model.CredentialState
import com.niceproxy.core.model.ProtocolParams
import com.niceproxy.core.model.ProxyProtocol
import com.niceproxy.core.model.ServerGroup
import com.niceproxy.core.model.ServerProfile
import com.niceproxy.core.model.SubscriptionTraffic
import com.niceproxy.core.model.TlsConfig
import com.niceproxy.core.model.TransportConfig
import kotlinx.serialization.json.Json

/**
 * 嵌套的协议参数以 JSON 列存储，而不是拆成几十个可空字段。
 *
 * 代价是这些字段无法参与 SQL 查询，但我们从来不按「加密方式」或「SNI」检索节点；
 * 收益是新增一个协议只用改 [ProtocolParams] 的密封类，不需要数据库迁移。
 */
internal val entityJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Entity(
    tableName = "servers",
    foreignKeys = [
        ForeignKey(
            entity = ServerGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["group_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("group_id")],
)
data class ServerEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "group_id") val groupId: String,
    val name: String,
    val protocol: ProxyProtocol,
    val server: String,
    @ColumnInfo(name = "server_port") val serverPort: Int,
    /**
     * 节点凭据的唯一落脚点：密码、UUID、Shadowsocks 密钥、Hysteria2 混淆密码、
     * SSH 私钥都在这里，所以整个 JSON 都加密而不是挑字段加密。
     *
     * 拆开只加密其中的密码字段没有意义：剩下的「加密方式」「flow」「拥塞控制」
     * 单独拿出来既没有价值也无法使用，而逐字段加密要为每个协议写一份映射。
     */
    @ColumnInfo(name = "params_json") val paramsJson: SecretText,
    @ColumnInfo(name = "transport_json") val transportJson: String?,
    @ColumnInfo(name = "tls_json") val tlsJson: String?,
    @ColumnInfo(name = "multiplex_json") val multiplexJson: String?,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    @ColumnInfo(name = "latency_ms") val latencyMs: Int?,
    @ColumnInfo(name = "last_tested_at") val lastTestedAt: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
    /**
     * 密文存在但解不开。没有 backing field，Room 不会把它当成一列。
     *
     * 提供它是为了让上层能枚举出「需要重新导入」的节点并给用户一个恢复入口，
     * 而不是只能在列表里看到一堆带警告前缀的名字。
     */
    val hasUnreadableCredentials: Boolean
        get() = paramsJson is SecretText.Unreadable
}

/**
 * 凭据解不开时顶替上去的协议参数。
 *
 * 必须挑一个**校验一定不通过**的类型：`OutboundFactory` 是按 `params` 的类型
 * 分派的，Trojan 分支无条件要求密码非空，于是不论这个节点原本是什么协议，
 * 它都会被 `OutboundResult.Invalid("缺少密码")` 挡下来 —— 节点还在列表里，
 * 但不会进入生成的 sing-box 配置，也不会让整份配置构建失败（那是 warning 不是 error）。
 *
 * 绝对不能用 [ProtocolParams.Direct]：那是唯一不做任何校验的分支，会被原样
 * 生成成一个可用的直连出站，用户以为在走代理，实际流量全部裸奔出去。
 */
private val UNREADABLE_PARAMS = ProtocolParams.Trojan(password = "")

fun ServerEntity.toDomain(): ServerProfile {
    val params = paramsJson.readableOrNull?.let(::decodeParamsOrNull)
    return ServerProfile(
        id = id,
        groupId = groupId,
        name = name,
        credentialState = if (params == null) CredentialState.UNREADABLE else CredentialState.OK,
        protocol = protocol,
        server = server,
        serverPort = serverPort,
        params = params ?: UNREADABLE_PARAMS,
        transport = transportJson?.let { entityJson.decodeFromString(TransportConfig.serializer(), it) },
        tls = tlsJson?.let { entityJson.decodeFromString(TlsConfig.serializer(), it) },
        multiplex = multiplexJson?.let { entityJson.decodeFromString(MultiplexConfig.serializer(), it) },
        sortOrder = sortOrder,
        latencyMs = latencyMs,
        lastTestedAt = lastTestedAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

/**
 * 反序列化失败也按「读不出来」处理，而不是让异常冒到调用方。
 *
 * `ServerRepository.servers` 是 `list.map { it.toDomain() }`，一条记录抛异常
 * 会让整条 Flow 失败、整个节点页变空白。单个节点的 JSON 损坏不该有这么大的爆炸半径。
 */
private fun decodeParamsOrNull(json: String): ProtocolParams? =
    runCatching { entityJson.decodeFromString(ProtocolParams.serializer(), json) }.getOrNull()

fun ServerProfile.toEntity(): ServerEntity = ServerEntity(
    id = id,
    groupId = groupId,
    name = name,
    protocol = protocol,
    server = server,
    serverPort = serverPort,
    paramsJson = entityJson.encodeToString(ProtocolParams.serializer(), params).asSecret(),
    transportJson = transport?.let { entityJson.encodeToString(TransportConfig.serializer(), it) },
    tlsJson = tls?.let { entityJson.encodeToString(TlsConfig.serializer(), it) },
    multiplexJson = multiplex?.let { entityJson.encodeToString(MultiplexConfig.serializer(), it) },
    sortOrder = sortOrder,
    latencyMs = latencyMs,
    lastTestedAt = lastTestedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

@Entity(tableName = "server_groups")
data class ServerGroupEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: GroupType,
    /**
     * 订阅地址。加密的原因是它几乎总是形如
     * `https://…/subscribe?token=…`，那个 token 一把梭出整个机场账号下的
     * 全部节点，价值高于任何单个节点的密码；而 §9 的表里恰恰没提到它。
     */
    val url: SecretText?,
    @ColumnInfo(name = "user_agent") val userAgent: String?,
    @ColumnInfo(name = "auto_update") val autoUpdate: Boolean,
    @ColumnInfo(name = "update_interval_minutes") val updateIntervalMinutes: Int,
    @ColumnInfo(name = "last_update_at") val lastUpdateAt: Long?,
    @ColumnInfo(name = "last_error") val lastError: String?,
    @ColumnInfo(name = "traffic_json") val trafficJson: String?,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    @ColumnInfo(name = "remarks_filter") val remarksFilter: String? = null,
    @ColumnInfo(name = "filter_exclude", defaultValue = "1") val filterExclude: Boolean = true,
    /** 部分机场用自定义头做鉴权，这里可能直接躺着一个 `Authorization`。 */
    @ColumnInfo(name = "extra_headers") val extraHeaders: SecretText? = null,
)

/**
 * 订阅地址解不开时退化为 null。
 *
 * `SubscriptionRepository` 对空地址已经有干净的处理（返回「缺少订阅地址」
 * 而不是 NPE），分组和组里的节点都还在，用户重新粘一次链接即可恢复 ——
 * 这是所有加密字段里恢复成本最低的一个。
 */
fun ServerGroupEntity.toDomain(): ServerGroup = ServerGroup(
    id = id,
    name = name,
    type = type,
    url = url?.readableOrNull,
    userAgent = userAgent,
    autoUpdate = autoUpdate,
    updateIntervalMinutes = updateIntervalMinutes,
    lastUpdateAt = lastUpdateAt,
    lastError = lastError,
    traffic = trafficJson?.let { entityJson.decodeFromString(SubscriptionTraffic.serializer(), it) },
    sortOrder = sortOrder,
    remarksFilter = remarksFilter,
    filterExclude = filterExclude,
    extraHeaders = extraHeaders?.readableOrNull,
)

fun ServerGroup.toEntity(): ServerGroupEntity = ServerGroupEntity(
    id = id,
    name = name,
    type = type,
    url = url?.asSecret(),
    userAgent = userAgent,
    autoUpdate = autoUpdate,
    updateIntervalMinutes = updateIntervalMinutes,
    lastUpdateAt = lastUpdateAt,
    lastError = lastError,
    trafficJson = traffic?.let { entityJson.encodeToString(SubscriptionTraffic.serializer(), it) },
    sortOrder = sortOrder,
    remarksFilter = remarksFilter,
    filterExclude = filterExclude,
    extraHeaders = extraHeaders?.asSecret(),
)
