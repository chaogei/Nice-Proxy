package com.niceproxy.feature.nodes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** 节点列表排序方式。 */
enum class NodeSort(val label: String) {
    DEFAULT("默认"),
    LATENCY("延迟"),
    NAME("名称"),
}

/**
 * 两种测速的取舍见 [LatencyTester] 的注释：
 * TCPing 随时可测但只反映入口可达性，真连接延迟准确但要求内核在运行。
 */
enum class TestMode(val label: String) {
    TCPING("TCPing"),
    REAL("真连接"),
}

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

private fun computeVisible(
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
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NodesUiState())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

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
                    onSuccess = { _message.value = "已切换，现有连接不受影响" },
                    onFailure = { _message.value = "切换失败：${it.message}" },
                )
            }
        }
    }

    fun testAll() {
        val targets = uiState.value.visibleServers
        if (targets.isEmpty()) return

        if (testMode.value == TestMode.REAL && controller.state.value !is ProxyState.Running) {
            _message.value = "真连接测速需要先启动代理，或改用 TCPing"
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
                _message.value = "测速完成"
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
            _message.value = if (removed > 0) "已删除 $removed 个重复节点" else "没有发现重复节点"
        }
    }

    fun deleteInvalid() {
        viewModelScope.launch {
            val removed = serverRepository.deleteInvalid(selectedGroupId.value)
            _message.value = when {
                removed > 0 -> "已删除 $removed 个测速失败的节点"
                else -> "没有测速失败的节点。请先测速。"
            }
        }
    }

    fun importFromClipboard(text: String) {
        if (text.isBlank()) {
            _message.value = "剪贴板为空"
            return
        }
        viewModelScope.launch {
            val outcome = serverRepository.importFromText(text, selectedGroupId.value)
            _message.value = when {
                outcome.imported == 0 -> "没有识别到可用的节点链接"
                outcome.failed == 0 -> "已导入 ${outcome.imported} 个节点"
                else -> "已导入 ${outcome.imported} 个，${outcome.failed} 个无法识别"
            }
        }
    }

    fun addSubscription(url: String, name: String, filter: String) {
        if (url.isBlank()) {
            _message.value = "请填写订阅地址"
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
                        _message.value = buildString {
                            append("「${outcome.groupName}」已导入 ${outcome.nodeCount} 个节点")
                            if (outcome.filteredCount > 0) {
                                append("，过滤掉 ${outcome.filteredCount} 条")
                            }
                        }
                    },
                    onFailure = { _message.value = "订阅失败：${it.message}" },
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
                    onSuccess = { _message.value = "已更新 ${it.nodeCount} 个节点" },
                    onFailure = { _message.value = "更新失败：${it.message}" },
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
            _message.value = "${node.protocol.displayName} 没有通用的分享链接格式"
            return
        }
        _shareTarget.value = ShareTarget(node.name, link)
    }

    fun dismissShare() { _shareTarget.value = null }

    fun deleteGroup(id: String) {
        viewModelScope.launch {
            serverRepository.deleteGroup(id)
            if (selectedGroupId.value == id) selectedGroupId.value = null
            _message.value = "已删除分组及其节点"
        }
    }

    fun isSubscription(groupId: String?): Boolean =
        uiState.value.groups.firstOrNull { it.id == groupId }?.type == GroupType.SUBSCRIPTION

    fun consumeMessage() { _message.value = null }

    private companion object {
        const val REAL_TEST_CONCURRENCY = 8
    }
}
