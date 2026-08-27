package com.niceproxy.core.service.config

import com.niceproxy.core.data.InboundRepository
import com.niceproxy.core.data.RoutingRepository
import com.niceproxy.core.data.ServerRepository
import com.niceproxy.core.datastore.SettingsDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 汇总所有会进入 sing-box 配置的数据源，任何一处写入都发一个信号。
 *
 * 刻意只发信号、不带内容：「到底变了什么、要不要重启内核」由配置指纹回答
 * （见 [ConfigDigest]）。这样节点批量测速这类只写延迟、不影响配置的高频写入
 * 不会被误判成配置变更 —— 它们会触发信号，但重新生成的配置指纹是一样的。
 */
@Singleton
class ConfigChangeWatcher @Inject constructor(
    private val inboundRepository: InboundRepository,
    private val serverRepository: ServerRepository,
    private val routingRepository: RoutingRepository,
    private val settings: SettingsDataStore,
) {

    fun changes(): Flow<Unit> = merge(
        inboundRepository.inbounds.map { },
        serverRepository.servers.map { },
        routingRepository.rules.map { },
        routingRepository.ruleSets.map { },
        settings.serviceSettings.map { },
        settings.dnsSettings.map { },
        settings.logSettings.map { },
        settings.outboundSettings.map { },
    )
}
