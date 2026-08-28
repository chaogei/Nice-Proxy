package com.niceproxy.feature.nodes

import android.content.Context
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.niceproxy.R
import com.niceproxy.core.config.share.ShareLinkExporter
import com.niceproxy.core.data.ServerRepository
import com.niceproxy.core.data.SubscriptionRepository
import com.niceproxy.core.datastore.SettingsDataStore
import com.niceproxy.core.model.GroupType
import com.niceproxy.core.model.ServerGroup
import com.niceproxy.core.model.ServerProfile
import com.niceproxy.core.model.WellKnownTag
import com.niceproxy.core.network.LatencyTester
import com.niceproxy.core.network.clash.ClashApiClient
import com.niceproxy.core.service.ProxyServiceController
import com.niceproxy.core.service.ProxyState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** 节点列表排序方式。 */
enum class NodeSort(@StringRes val labelRes: Int) {
    DEFAULT(R.string.nodes_sort_default),
    LATENCY(R.string.nodes_sort_latency),
    NAME(R.string.nodes_sort_name),
}

/**
 * 两种测速的取舍见 [LatencyTester] 的注释：
 * TCPing 随时可测但只反映入口可达性，真连接延迟准确但要求内核在运行。
 *
 * 「TCPing」是协议层面的既有名词，各语言下都保持原样，所以它也走资源
 * —— 让两个选项从同一处取文案，而不是一个走资源一个写字面量。
 */
enum class TestMode(@StringRes val labelRes: Int) {
    TCPING(R.string.nodes_test_tcping),
    REAL(R.string.nodes_test_real),
}

/**
 * 节点页要弹的提示。
 *
 * 存成结构而不是拼好的字符串：ViewModel 活得比 Activity 长，用户在设置里
 * 换了语言之后，早先塞进 StateFlow 的那句话仍是旧语言的。让界面在真正要
 * 显示的那一刻，用它当时的 Context 取文案。
 */
sealed interface NodesMessage {
    data object Switched : NodesMessage
    data class SwitchFailed(val reason: String?) : NodesMessage
    data object RealTestNeedsCore : NodesMessage
    data object TestFinished : NodesMessage
    data class DuplicatesRemoved(val count: Int) : NodesMessage
    data object NoDuplicates : NodesMessage
    data class InvalidRemoved(val count: Int) : NodesMessage
    data object NoInvalid : NodesMessage
    data object ClipboardEmpty : NodesMessage
    data object NoLinksFound : NodesMessage
    data class Imported(val imported: Int) : NodesMessage
    data class PartiallyImported(val imported: Int, val failed: Int) : NodesMessage
    data object SubscriptionUrlRequired : NodesMessage
    data class Subscribed(val group: String, val nodes: Int, val filtered: Int) : NodesMessage
    data class SubscribeFailed(val reason: String?) : NodesMessage
    data class Refreshed(val nodes: Int) : NodesMessage
    data class RefreshFailed(val reason: String?) : NodesMessage
    data class NotShareable(val protocol: String) : NodesMessage
    data object GroupDeleted : NodesMessage

    fun resolve(context: Context): String = when (this) {
        Switched -> context.getString(R.string.nodes_switched)
        is SwitchFailed -> context.getString(R.string.nodes_switch_failed, reason.orUnknown(context))
        RealTestNeedsCore -> context.getString(R.string.nodes_real_test_needs_core)
        TestFinished -> context.getString(R.string.nodes_test_finished)
        is DuplicatesRemoved -> context.getString(R.string.nodes_duplicates_removed, count)
        NoDuplicates -> context.getString(R.string.nodes_no_duplicates)
        is InvalidRemoved -> context.getString(R.string.nodes_invalid_removed, count)
        NoInvalid -> context.getString(R.string.nodes_no_invalid)
        ClipboardEmpty -> context.getString(R.string.nodes_clipboard_empty)
        NoLinksFound -> context.getString(R.string.nodes_no_links_found)
        is Imported -> context.getString(R.string.nodes_imported, imported)
        is PartiallyImported ->
            context.getString(R.string.nodes_imported_partial, imported, failed)
        SubscriptionUrlRequired -> context.getString(R.string.nodes_subscription_url_required)
        is Subscribed -> if (filtered > 0) {
            context.getString(R.string.nodes_subscribed_filtered, group, nodes, filtered)
        } else {
            context.getString(R.string.nodes_subscribed, group, nodes)
        }
        is SubscribeFailed ->
            context.getString(R.string.nodes_subscribe_failed, reason.orUnknown(context))
        is Refreshed -> context.getString(R.string.nodes_refreshed, nodes)
        is RefreshFailed ->
            context.getString(R.string.nodes_refresh_failed, reason.orUnknown(context))
        is NotShareable -> context.getString(R.string.nodes_not_shareable, protocol)
        GroupDeleted -> context.getString(R.string.nodes_group_deleted)
    }
}

/** 异常的 message 经常是 null，直接插进去会在句子中间留一个字面的 "null"。 */
private fun String?.orUnknown(context: Context): String =
    this?.takeIf { it.isNotBlank() } ?: context.getString(R.string.common_unknown_error)

data class NodesUiState(
    val groups: List<ServerGroup> = emptyList(),
    val servers: List<ServerProfile> = emptyList(),
    /**
     * 已完成分组过滤、搜索与排序的结果。
     *
     * 刻意做成字段而不是 `get()` 计算属性：界面上有两处读它（列表本身和数量统计），
     * 计算属性会让每次重组都跑两趟完整的过滤加排序。批量测速时进度每完成一个节点
     * 就推一次新 state，几百个节点下这个开销会直接堆在主线程上。
     */
    val visibleServers: List<ServerProfile> = emptyList(),
    val selectedGroupId: String? = null,
    val selectedTag: String = WellKnownTag.AUTO,
    val query: String = "",
    val sort: NodeSort = NodeSort.DEFAULT,
    val testMode: TestMode = TestMode.TCPING,
    val testProgress: Int? = null,
    val testTotal: Int = 0,
    val isRefreshing: Boolean = false,
    val coreRunning: Boolean = false,
) {
    val isTesting: Boolean get() = testProgress != null
}

internal fun computeVisible(
    servers: List<ServerProfile>,
    groupId: String?,
    query: String,
    sort: NodeSort,
): List<ServerProfile> {
    val scoped = if (groupId == null) servers else servers.filter { it.groupId == groupId }
    val filtered = if (query.isBlank()) {
        scoped
    } else {
        scoped.filter {
            it.name.contains(query, ignoreCase = true) ||
                it.server.contains(query, ignoreCase = true)
        }
    }
    return when (sort) {
        NodeSort.DEFAULT -> filtered
        NodeSort.NAME -> filtered.sortedBy { it.name }
        // 未测速的排在最后，超时的排在已测出延迟的之后
        NodeSort.LATENCY -> filtered.sortedBy { node ->
            val latency = node.latencyMs
            when {
                latency == null -> Int.MAX_VALUE
                latency < 0 -> Int.MAX_VALUE - 1
                else -> latency
            }
        }
    }
}

@HiltViewModel
class NodesViewModel @Inject constructor(
    private val serverRepository: ServerRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val settings: SettingsDataStore,
    private val clashApi: ClashApiClient,
    private val latencyTester: LatencyTester,
    private val controller: ProxyServiceController,
) : ViewModel() {

    private val selectedGroupId = MutableStateFlow<String?>(null)
    private val query = MutableStateFlow("")
    private val sort = MutableStateFlow(NodeSort.DEFAULT)
    private val testMode = MutableStateFlow(TestMode.TCPING)
    private val testProgress = MutableStateFlow<Int?>(null)
    private val testTotal = MutableStateFlow(0)
    private val refreshing = MutableStateFlow(false)

    private val listOptions = combine(query, sort, testMode) { q, s, m -> Triple(q, s, m) }
    private val testState = combine(testProgress, testTotal, refreshing) { p, t, r -> Triple(p, t, r) }

    val uiState: StateFlow<NodesUiState> = combine(
        serverRepository.groups,
        serverRepository.servers,
        combine(selectedGroupId, settings.outboundSettings, controller.state, ::Triple),
        listOptions,
        testState,
    ) { groups, servers, (groupId, outbound, proxyState), (q, s, mode), (progress, total, isRefreshing) ->
        NodesUiState(
            groups = groups,
            servers = servers,
            visibleServers = computeVisible(servers, groupId, q, s),
            selectedGroupId = groupId,
            selectedTag = outbound.selectedTag,
            query = q,
            sort = s,
            testMode = mode,
            testProgress = progress,
            testTotal = total,
            isRefreshing = isRefreshing,
            coreRunning = proxyState is ProxyState.Running,
        )
    }
        // combine 的变换默认跑在收集方的上下文里，也就是 viewModelScope 的主线程。
        // computeVisible 要把整份节点列表过滤再排序一遍，而批量测速期间每测完
        // 一个节点就推两次新值（进度计数一次、延迟落库后仓库再发一次）。一份
        // 三百节点的订阅测下来，就是六百趟全量排序全落在主线程上 —— 界面正好
        // 在这段时间里卡住，而这恰恰是用户盯着进度条看的那段时间。
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NodesUiState())

    private val _message = MutableStateFlow<NodesMessage?>(null)
    val message: StateFlow<NodesMessage?> = _message.asStateFlow()

    fun selectGroup(groupId: String?) { selectedGroupId.value = groupId }
    fun setQuery(value: String) { query.value = value }
    fun setSort(value: NodeSort) { sort.value = value }
    fun setTestMode(value: TestMode) { testMode.value = value }

    /**
     * 选择出站节点。
     *
     * 内核在跑就走 Clash API 热切换 —— 不重启、不断开已有连接（§6.9）；
     * 没在跑就只记录选择，下次启动时由配置生成器写进 selector 的 default。
     */
    fun selectNode(tag: String) {
        viewModelScope.launch {
            settings.setSelectedOutbound(tag)
            if (controller.state.value is ProxyState.Running) {
                val api = settings.clashApiSettings()
                clashApi.selectProxy(api, WellKnownTag.PROXY, tag).fold(
                    onSuccess = { _message.value = NodesMessage.Switched },
                    onFailure = { _message.value = NodesMessage.SwitchFailed(it.message) },
                )
            }
        }
    }

    fun testAll() {
        val targets = uiState.value.visibleServers
        if (targets.isEmpty()) return

        if (testMode.value == TestMode.REAL && controller.state.value !is ProxyState.Running) {
            _message.value = NodesMessage.RealTestNeedsCore
            return
        }

        viewModelScope.launch {
            testTotal.value = targets.size
            testProgress.value = 0
            try {
                when (testMode.value) {
                    TestMode.TCPING -> runTcping(targets)
                    TestMode.REAL -> runRealDelay(targets)
                }
                _message.value = NodesMessage.TestFinished
            } finally {
                testProgress.value = null
            }
        }
    }

    private suspend fun runTcping(targets: List<ServerProfile>) {
        latencyTester.tcpingAll(
            targets = targets.map { LatencyTester.Target(it.id, it.server, it.serverPort) },
        ) { id, latency ->
            serverRepository.updateLatency(id, latency ?: ServerProfile.LATENCY_TIMEOUT)
            testProgress.value = (testProgress.value ?: 0) + 1
        }
    }

    private suspend fun runRealDelay(targets: List<ServerProfile>) {
        val api = settings.clashApiSettings()
        val testUrl = settings.outboundSettings.first().urlTestUrl
        // 并发度设上限：真连接测速每一路都要经内核建立完整代理链路，
        // 不限流会让内核瞬时压力过大，测出来的延迟反而失真。
        val gate = Semaphore(REAL_TEST_CONCURRENCY)
        withContext(Dispatchers.IO) {
            targets.map { node ->
                async {
                    gate.withPermit {
                        val delay = clashApi.testDelay(api, node.outboundTag, testUrl).getOrNull()
                        serverRepository.updateLatency(
                            node.id,
                            delay ?: ServerProfile.LATENCY_TIMEOUT,
                        )
                        testProgress.value = (testProgress.value ?: 0) + 1
                    }
                }
            }.awaitAll()
        }
    }

    fun deleteDuplicates() {
        viewModelScope.launch {
            val removed = serverRepository.deleteDuplicates(selectedGroupId.value)
            _message.value = if (removed > 0) {
                NodesMessage.DuplicatesRemoved(removed)
            } else {
                NodesMessage.NoDuplicates
            }
        }
    }

    fun deleteInvalid() {
        viewModelScope.launch {
            val removed = serverRepository.deleteInvalid(selectedGroupId.value)
            _message.value = if (removed > 0) {
                NodesMessage.InvalidRemoved(removed)
            } else {
                NodesMessage.NoInvalid
            }
        }
    }

    fun importFromClipboard(text: String) {
        if (text.isBlank()) {
            _message.value = NodesMessage.ClipboardEmpty
            return
        }
        viewModelScope.launch {
            val outcome = serverRepository.importFromText(text, selectedGroupId.value)
            _message.value = when {
                outcome.imported == 0 -> NodesMessage.NoLinksFound
                outcome.failed == 0 -> NodesMessage.Imported(outcome.imported)
                else -> NodesMessage.PartiallyImported(outcome.imported, outcome.failed)
            }
        }
    }

    fun addSubscription(url: String, name: String, filter: String) {
        if (url.isBlank()) {
            _message.value = NodesMessage.SubscriptionUrlRequired
            return
        }
        viewModelScope.launch {
            refreshing.value = true
            try {
                subscriptionRepository.addSubscription(
                    url = url.trim(),
                    name = name.trim(),
                    remarksFilter = filter.trim().takeIf { it.isNotBlank() },
                ).fold(
                    onSuccess = { outcome ->
                        _message.value = NodesMessage.Subscribed(
                            group = outcome.groupName,
                            nodes = outcome.nodeCount,
                            filtered = outcome.filteredCount,
                        )
                    },
                    onFailure = { _message.value = NodesMessage.SubscribeFailed(it.message) },
                )
            } finally {
                refreshing.value = false
            }
        }
    }

    fun refreshSubscription(groupId: String) {
        viewModelScope.launch {
            refreshing.value = true
            try {
                subscriptionRepository.refresh(groupId).fold(
                    onSuccess = { _message.value = NodesMessage.Refreshed(it.nodeCount) },
                    onFailure = { _message.value = NodesMessage.RefreshFailed(it.message) },
                )
            } finally {
                refreshing.value = false
            }
        }
    }

    fun deleteNode(id: String) {
        viewModelScope.launch { serverRepository.delete(id) }
    }

    private val _shareTarget = MutableStateFlow<ShareTarget?>(null)
    val shareTarget: StateFlow<ShareTarget?> = _shareTarget.asStateFlow()

    data class ShareTarget(val name: String, val link: String)

    fun share(node: ServerProfile) {
        val link = ShareLinkExporter.export(node)
        if (link == null) {
            _message.value = NodesMessage.NotShareable(node.protocol.displayName)
            return
        }
        _shareTarget.value = ShareTarget(node.name, link)
    }

    fun dismissShare() { _shareTarget.value = null }

    fun deleteGroup(id: String) {
        viewModelScope.launch {
            serverRepository.deleteGroup(id)
            if (selectedGroupId.value == id) selectedGroupId.value = null
            _message.value = NodesMessage.GroupDeleted
        }
    }

    fun isSubscription(groupId: String?): Boolean =
        uiState.value.groups.firstOrNull { it.id == groupId }?.type == GroupType.SUBSCRIPTION

    fun consumeMessage() { _message.value = null }

    private companion object {
        const val REAL_TEST_CONCURRENCY = 8
    }
}
