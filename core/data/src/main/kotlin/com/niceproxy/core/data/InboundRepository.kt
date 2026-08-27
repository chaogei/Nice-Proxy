package com.niceproxy.core.data

import com.niceproxy.core.common.Dispatcher
import com.niceproxy.core.common.NiceDispatcher
import com.niceproxy.core.database.dao.InboundDao
import com.niceproxy.core.database.entity.toDomain
import com.niceproxy.core.database.entity.toEntity
import com.niceproxy.core.model.InboundService
import com.niceproxy.core.model.InboundType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
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
     * 默认监听 0.0.0.0 —— 这正是本应用存在的意义（给局域网其他设备用），
     * 但也意味着同网段任何人都能用，UI 必须为此显示警告徽章。
     * 见 docs/DESIGN.md §8.2。
     */
    suspend fun ensureDefaults() = withContext(ioDispatcher) {
        if (dao.count() > 0) return@withContext
        dao.upsert(
            InboundService(
                id = UUID.randomUUID().toString(),
                type = InboundType.MIXED,
                listen = InboundService.LISTEN_ALL,
                listenPort = InboundService.DEFAULT_MIXED_PORT,
                enabled = true,
            ).toEntity(),
        )
    }

    fun newInbound(type: InboundType): InboundService = InboundService(
        id = UUID.randomUUID().toString(),
        type = type,
        listenPort = type.defaultPort,
    )
}
