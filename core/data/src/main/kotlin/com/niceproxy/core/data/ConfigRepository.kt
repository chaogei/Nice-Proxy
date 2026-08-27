package com.niceproxy.core.data

import com.niceproxy.core.common.Dispatcher
import com.niceproxy.core.common.NiceDispatcher
import com.niceproxy.core.config.ConfigInput
import com.niceproxy.core.config.ConfigResult
import com.niceproxy.core.config.SingBoxConfigBuilder
import com.niceproxy.core.datastore.SettingsDataStore
import com.niceproxy.core.model.RoutingRule
import com.niceproxy.core.model.RuleSetRef
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把分散在数据库与 DataStore 中的配置聚合起来，交给生成器产出 sing-box 配置。
 */
@Singleton
class ConfigRepository @Inject constructor(
    private val inboundRepository: InboundRepository,
    private val serverRepository: ServerRepository,
    private val routingRepository: RoutingRepository,
    private val settings: SettingsDataStore,
    private val builder: SingBoxConfigBuilder,
    @Dispatcher(NiceDispatcher.Default) private val defaultDispatcher: CoroutineDispatcher,
) {

    suspend fun build(workDir: String): ConfigResult = withContext(defaultDispatcher) {
        val serviceSettings = settings.serviceSettings.first()
        val rules = routingRepository.getRules()

        builder.build(
            ConfigInput(
                inbounds = inboundRepository.getAll(),
                nodes = serverRepository.getAll(),
                rules = rules,
                // 没有规则引用的规则集不必下载 —— 每个 .srs 都是几百 KB，
                // 且下载失败会拖慢内核启动。
                ruleSets = routingRepository.getRuleSets().filterReferencedBy(rules),
                outbound = settings.outboundSettings.first(),
                dns = settings.dnsSettings.first(),
                log = settings.logSettings.first(),
                clashApi = settings.clashApiSettings(),
                workDir = workDir,
                ipv6Enabled = serviceSettings.ipv6Enabled,
            ),
        )
    }
}

/**
 * 只保留被启用规则引用到的规则集。
 *
 * 引用集合先收成 Set 再过滤，整体是 O(规则数 + 规则集数)：规则集通常只有几个，
 * 但规则数会随用户自定义增长，逐个规则集去遍历规则会退化成乘积。
 *
 * 副作用需要知情：DNS 分流（`splitByRuleSet`）也要用 geosite-cn，
 * 如果没有任何路由规则引用它，这里会把它滤掉，DNS 分流随之失效。
 * 这是符合预期的 —— 生成器只会为真正声明出来的规则集写 DNS 规则，
 * 否则就是一条指向未声明 tag 的非法配置。
 */
internal fun List<RuleSetRef>.filterReferencedBy(rules: List<RoutingRule>): List<RuleSetRef> {
    val referenced = rules.filter { it.enabled }.flatMapTo(mutableSetOf()) { it.matcher.ruleSet }
    return filter { it.enabled && it.tag in referenced }
}
