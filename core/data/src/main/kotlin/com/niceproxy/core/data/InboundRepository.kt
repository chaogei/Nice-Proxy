package com.niceproxy.core.data

import com.niceproxy.core.common.Dispatcher
import com.niceproxy.core.common.NiceDispatcher
import com.niceproxy.core.database.dao.InboundDao
import com.niceproxy.core.database.entity.toDomain
import com.niceproxy.core.database.entity.toEntity
import com.niceproxy.core.model.InboundAuth
import com.niceproxy.core.model.InboundService
import com.niceproxy.core.model.InboundType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InboundRepository @Inject constructor(
    private val dao: InboundDao,
    @Dispatcher(NiceDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) {

    val inbounds: Flow<List<InboundService>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }.flowOn(ioDispatcher)

    suspend fun getAll(): List<InboundService> = withContext(ioDispatcher) {
        dao.getAll().map { it.toDomain() }
    }

    suspend fun get(id: String): InboundService? = withContext(ioDispatcher) {
        dao.getById(id)?.toDomain()
    }

    suspend fun save(inbound: InboundService) = withContext(ioDispatcher) {
        dao.upsert(inbound.toEntity())
    }

    suspend fun delete(id: String) = withContext(ioDispatcher) {
        dao.deleteById(id)
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = withContext(ioDispatcher) {
        dao.setEnabled(id, enabled)
    }

    /** 保存前的端口冲突检测，返回 true 表示该端口已被其他启用中的入站占用。 */
    suspend fun isPortTaken(port: Int, excludeId: String): Boolean = withContext(ioDispatcher) {
        dao.countByPort(port, excludeId) > 0
    }

    /**
     * 首次启动时写入一个开箱即用的默认入站。
     *
     * 默认监听 0.0.0.0 —— 这正是本应用存在的意义（给局域网其他设备用）。
     *
     * **但默认带随机凭据，不是免认证。** 免认证 + 通配监听的组合意味着：咖啡厅、
     * 宿舍、公司网络里任何人扫到这个端口就能白嫖用户付费的机场流量，行为还算在
     * 用户头上；更糟的是路由表里有一条「私有 IP 走直连」，于是未认证的人能拿这台
     * 手机当跳板访问整个局域网（NAS、路由器管理页）乃至手机自己的回环地址。
     *
     * 随机凭据是唯一不牺牲开箱即用的选项：用户本来就要把 IP 和端口抄到 Switch 上，
     * 顺带多抄两行的成本很小，而首页会把它们和地址一起显示出来。
     */
    suspend fun ensureDefaults() = withContext(ioDispatcher) {
        if (dao.count() > 0) return@withContext
        dao.upsert(
            InboundService(
                id = UUID.randomUUID().toString(),
                type = InboundType.MIXED,
                listen = InboundService.LISTEN_ALL,
                listenPort = InboundService.DEFAULT_MIXED_PORT,
                auth = randomAuth(),
                enabled = true,
            ).toEntity(),
        )
    }

    /**
     * 给默认入站生成的凭据。
     *
     * 用 [SecureRandom] 而不是 `Random`：这是一份对局域网开放的访问口令，
     * 可预测的伪随机等于没有认证。
     *
     * 字母表刻意剔掉了 `0O1lI` 这类易混字符 —— 用户是拿着手机对着电视机
     * 或游戏机的软键盘一个个敲进去的，认错一个字符的排查成本远高于多两位熵。
     * 剩余 32 个字符、12 位长度约 60 bit，对局域网爆破足够。
     */
    private fun randomAuth(): InboundAuth {
        val random = SecureRandom()
        fun token(length: Int) = buildString(length) {
            repeat(length) { append(CREDENTIAL_ALPHABET[random.nextInt(CREDENTIAL_ALPHABET.length)]) }
        }
        return InboundAuth(username = "nice-${token(4)}", password = token(12))
    }

    fun newInbound(type: InboundType): InboundService = InboundService(
        id = UUID.randomUUID().toString(),
        type = type,
        listenPort = type.defaultPort,
    )

    private companion object {
        /** 去掉了 0/O/1/l/I —— 这串要靠用户在游戏机软键盘上手抄。 */
        const val CREDENTIAL_ALPHABET = "abcdefghijkmnopqrstuvwxyz23456789"
    }
}
