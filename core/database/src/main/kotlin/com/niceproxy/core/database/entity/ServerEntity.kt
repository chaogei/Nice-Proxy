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
import kotlinx.serialization.DeserializationStrategy
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

/**
 * 反序列化失败一律退化成 null，不让异常冒到调用方。
 *
 * `ServerRepository.servers` 是 `list.map { it.toDomain() }`，一条记录抛异常
 * 会让整条 Flow 失败、整个节点页变空白，`ConfigRepository` 也会跟着抛，
 * 代理直接起不来。单条记录的 JSON 读不懂不该有这么大的爆炸半径。
 *
 * 注意 `ignoreUnknownKeys` 顶不住这些情况：它只忽略未知的**键**，
 * 对未知的**类型判别符**无效 —— 删掉一个 `RuleAction` 分支之后，
 * 旧数据依然会抛 `SerializationException`。
 */
internal fun <T> decodeOrNull(deserializer: DeserializationStrategy<T>, json: String): T? =
    runCatching { entityJson.decodeFromString(deserializer, json) }.getOrNull()

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

    /**
     * 这一行能不能被**无损**地还原成领域对象。
     *
     * 和 [hasUnreadableCredentials] 问的不是一件事：那个问「密文解不解得开」，
     * 这个问「解开之后的 JSON 还认不认得」。后者的成因通常是代码改动
     * ——给某个配置类加了没有默认值的字段、改了字段名、删了一个密封类分支——
     * 数据本身是好的。
     *
     * 备份导出靠它过滤：降级后的领域对象里塞的是占位值，照原样写进备份等于
     * 把一次序列化失误固化成永久的数据损坏，而用户要等到换机恢复才发现。
     */
    val isFullyDecodable: Boolean
        get() = !decode().degraded
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

/**
 * TLS 配置读不出来时顶替上去的值。
 *
 * 关键在于**不能退化成 null**。`tls_json` 非空就说明这个节点当初是配了 TLS 的，
 * 而 VMess / VLESS / Trojan 在 `tls` 缺席时照样能生成一个语法合法的出站 ——
 * 连接直接变成明文，界面上没有任何区别，用户不会察觉。相比之下，顶一个
 * 最保守的「TLS 开着、其余走默认」上去，最坏结果是握手失败连不上：
 * 那是看得见的失败，远好过看不见的裸奔。
 */
private val UNREADABLE_TLS = TlsConfig(enabled = true)

/**
 * 一行 [ServerEntity] 的四个 JSON 列的解码结果。
 *
 * 单独抽出来是因为 `toDomain()` 和 [ServerEntity.isFullyDecodable] 要问的是
 * 同一件事的两面，共用一份解码逻辑才不会有一天走偏。
 */
private class DecodedServer(
    val params: ProtocolParams?,
    val transport: TransportConfig?,
    val tls: TlsConfig?,
    val multiplex: MultiplexConfig?,
    /** 四列里只要有一列「本来有值、却读不出来」就为 true。 */
    val degraded: Boolean,
)

private fun ServerEntity.decode(): DecodedServer {
    val params = paramsJson.readableOrNull?.let { decodeOrNull(ProtocolParams.serializer(), it) }
    val transport = transportJson?.let { decodeOrNull(TransportConfig.serializer(), it) }
    val tls = tlsJson?.let { decodeOrNull(TlsConfig.serializer(), it) }
    val multiplex = multiplexJson?.let { decodeOrNull(MultiplexConfig.serializer(), it) }
    return DecodedServer(
        params = params,
        transport = transport,
        tls = tls,
        multiplex = multiplex,
        degraded = params == null ||
            (transportJson != null && transport == null) ||
            (tlsJson != null && tls == null) ||
            (multiplexJson != null && multiplex == null),
    )
}

/**
 * 注意这个映射是**有损**的：降级过的节点再 `toEntity()` 写回去，占位值就会
 * 覆盖掉原本还完好的 JSON。目前只有用户手动编辑并保存节点会走到那条路
 * （测速回写、启停都是直接打 SQL，碰不到这几列），而那时用户本来就是在
 * 重填这个节点。加新的写入路径时要留意这一点。
 */
fun ServerEntity.toDomain(): ServerProfile {
    val decoded = decode()
    return ServerProfile(
        id = id,
        groupId = groupId,
        name = name,
        // 复用 UNREADABLE 这一个状态位是因为 core:model 里只有它，而对用户来说
        // 两种情况的处置方式完全一样：这个节点用不了了，重新导入一次。
        credentialState = if (decoded.degraded) CredentialState.UNREADABLE else CredentialState.OK,
        protocol = protocol,
        server = server,
        serverPort = serverPort,
        params = decoded.params ?: UNREADABLE_PARAMS,
        // transport / multiplex 退化成 null 是安全的：前者会让连接握不上手，
        // 后者只是不复用连接，两者都不会把加密悄悄关掉。tls 不行，见 UNREADABLE_TLS。
        transport = decoded.transport,
        tls = if (tlsJson != null && decoded.tls == null) UNREADABLE_TLS else decoded.tls,
        multiplex = decoded.multiplex,
        sortOrder = sortOrder,
        latencyMs = latencyMs,
        lastTestedAt = lastTestedAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

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
) {
    /**
     * 分组里有解不开的密文。
     *
     * 两个字段都算：订阅 URL 里的 token 和 `extra_headers` 里可能存在的
     * `Authorization`，缺任何一个这个订阅都刷新不了，而它们恰恰是用户最
     * 不可能靠记忆重建的东西。备份导出要据此把这个分组整个排除掉 ——
     * 见 `BackupRepository`。
     */
    val hasUnreadableSecrets: Boolean
        get() = url is SecretText.Unreadable || extraHeaders is SecretText.Unreadable
}

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
    // 流量统计读不懂就丢掉，不影响这个分组是否可导出：它是纯展示数据，
    // 下一次刷新订阅时会从响应头里重新拿到，不存在「丢了就没了」的问题。
    traffic = trafficJson?.let { decodeOrNull(SubscriptionTraffic.serializer(), it) },
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
