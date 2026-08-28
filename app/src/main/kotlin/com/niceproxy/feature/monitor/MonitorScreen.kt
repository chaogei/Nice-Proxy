package com.niceproxy.feature.monitor

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.niceproxy.R
import com.niceproxy.core.designsystem.component.GlassPanel
import com.niceproxy.core.designsystem.component.LocalHazeState
import com.niceproxy.core.designsystem.component.Sparkline
import com.niceproxy.core.designsystem.component.SparklinePlaceholder
import com.niceproxy.core.designsystem.component.formatBytes
import com.niceproxy.core.designsystem.component.formatSpeed
import com.niceproxy.core.designsystem.theme.StatusColors
import com.niceproxy.core.datastore.SettingsDataStore
import com.niceproxy.core.model.ClashApiSettings
import com.niceproxy.core.network.clash.ClashApiClient
import com.niceproxy.core.network.clash.ConnectionInfo
import com.niceproxy.core.service.ProxyServiceController
import com.niceproxy.core.service.ProxyState
import com.niceproxy.traffic.TrafficHistory
import com.niceproxy.traffic.TrafficSamples
import com.niceproxy.util.describe
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MonitorUiState(
    val connections: List<ConnectionInfo> = emptyList(),
    val totalUpload: Long = 0,
    val totalDownload: Long = 0,
    val running: Boolean = false,
    /**
     * 订阅断了而内核还在跑。
     *
     * 必须和「真的没有连接」区分开：两者在界面上都是一张空列表，
     * 而用户会据此判断代理到底通没通。
     */
    val streamError: String? = null,
    /**
     * 内核自报的常驻内存（FR-6.5）。null 表示第一帧还没到 ——
     * 和「占用为 0」是两回事，后者不可能发生，显示成 0 B 只会让人以为读错了。
     */
    val memoryInUse: Long? = null,
    /** 最近一分钟的速率曲线，只活在内存里。见 [TrafficHistory]。 */
    val samples: TrafficSamples = TrafficSamples(),
    /** 「断开全部」正在逐条发请求，期间不让再点第二次。 */
    val closingAll: Boolean = false,
)

@HiltViewModel
class MonitorViewModel @Inject constructor(
    private val clashApi: ClashApiClient,
    private val settings: SettingsDataStore,
    private val controller: ProxyServiceController,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MonitorUiState())
    val uiState: StateFlow<MonitorUiState> = _uiState.asStateFlow()

    private val _message = MutableStateFlow<MonitorMessage?>(null)
    val message: StateFlow<MonitorMessage?> = _message.asStateFlow()

    /**
     * 只在这条协程里写，因此不需要额外同步 —— 见 [TrafficHistory] 的线程安全说明。
     */
    private val history = TrafficHistory()

    /**
     * 手动重连的计数器。
     *
     * 流一旦 `catch` 掉就结束了，不会自己回来 —— 于是内核明明还在跑，页面却
     * 停在一句错误上，只有退出去再进来才恢复。让它参与到订阅的重建条件里，
     * 「重试」就是加一。
     */
    private val retries = MutableStateFlow(0)

    init {
        // 在 Default 上收集：连接快照可能上千条，排序不能占主线程。
        // Clash API 每秒推一次，主线程上排序会稳定地吃掉帧预算。
        viewModelScope.launch(Dispatchers.Default) {
            combine(
                controller.state
                    .map { it is ProxyState.Running }
                    // 不去重的话，Running 里任何一个字段变化（比如 warnings）都会
                    // 重建三条 WebSocket，曲线跟着断一次
                    .distinctUntilChanged(),
                retries,
            ) { running, _ -> running }
                .collectLatest { running ->
                    if (!running) {
                        history.clear()
                        _uiState.value = MonitorUiState(running = false)
                        return@collectLatest
                    }
                    _uiState.value = _uiState.value.copy(running = true, streamError = null)

                    val api = settings.clashApiSettings()
                    // 三条流并行。串行 collect 的话，第一条永远不会结束，
                    // 后面两条根本起不来 —— 内存和曲线会一直是空的。
                    coroutineScope {
                        launch { collectConnections(api) }
                        launch { collectMemory(api) }
                        launch { collectTraffic(api) }
                    }
                }
        }
    }

    private suspend fun collectConnections(api: ClashApiSettings) {
        clashApi.connections(api)
            .catch { reportStreamError(it, "连接") }
            .collect { snapshot ->
                _uiState.value = _uiState.value.copy(
                    // 流量大的排前面，用户关心的是「谁在占带宽」
                    connections = snapshot.connections.sortedByDescending {
                        it.download + it.upload
                    },
                    totalUpload = snapshot.uploadTotal,
                    totalDownload = snapshot.downloadTotal,
                )
            }
    }

    private suspend fun collectMemory(api: ClashApiSettings) {
        clashApi.memory(api)
            // 内存这一路断了不该盖掉连接列表的错误提示：那条才是用户真正在看的
            .catch { Log.w(TAG, "内存订阅中断", it) }
            .collect { frame ->
                _uiState.value = _uiState.value.copy(memoryInUse = frame.inuse)
            }
    }

    private suspend fun collectTraffic(api: ClashApiSettings) {
        clashApi.traffic(api)
            .catch { Log.w(TAG, "速率订阅中断", it) }
            .collect { frame ->
                history.record(frame.up, frame.down)
                _uiState.value = _uiState.value.copy(samples = history.snapshot())
            }
    }

    /**
     * 空 catch 是 README 记过的坑：代理正常跑着，页面永远「暂无活动连接」，
     * 而日志里什么都没有。内核停止导致的断流确实是正常路径，
     * 但那要和真正的异常分开处理，不能一起吞掉。
     */
    private fun reportStreamError(error: Throwable, what: String) {
        if (controller.state.value is ProxyState.Running) {
            Log.w(TAG, "${what}订阅中断，内核仍在运行", error)
            _uiState.value = _uiState.value.copy(streamError = error.describe())
        } else {
            Log.d(TAG, "内核已停止，${what}订阅正常结束")
        }
    }

    /** 重新建立三条订阅。内核没在跑的时候没什么可重连的。 */
    fun retry() {
        if (!_uiState.value.running) return
        _uiState.value = _uiState.value.copy(streamError = null)
        retries.value++
    }

    fun close(id: String) {
        viewModelScope.launch {
            clashApi.closeConnection(settings.clashApiSettings(), id)
        }
    }

    /**
     * 断开全部。
     *
     * 逐条调 DELETE 而不是找一个「清空」接口：`ClashApiClient` 只暴露了单条关闭，
     * 而为了这个按钮去改 `core:network` 会把一个纯 UI 需求推进网络层。连接数
     * 通常是几十条，一轮请求都发往 127.0.0.1，代价可以接受。
     *
     * 用快照而不是边遍历边读 [uiState]：列表每秒都在被 WebSocket 换掉，
     * 遍历一个正在变的集合会漏掉一半、或者对着已经消失的 id 白发请求。
     */
    fun closeAll() {
        val targets = _uiState.value.connections.map { it.id }
        if (targets.isEmpty() || _uiState.value.closingAll) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(closingAll = true)
            try {
                val api = settings.clashApiSettings()
                val closed = targets.count { clashApi.closeConnection(api, it).isSuccess }
                _message.value = MonitorMessage.ConnectionsClosed(closed)
            } finally {
                _uiState.value = _uiState.value.copy(closingAll = false)
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    private companion object {
        const val TAG = "MonitorViewModel"
    }
}

/**
 * 待展示的一次性提示。
 *
 * 刻意传「事件 + 参数」而不是拼好的字符串：ViewModel 活得比 Activity 长，
 * 用户切完语言之后它还在，在那里取资源会吐出上一门语言的句子。
 */
sealed interface MonitorMessage {
    data class ConnectionsClosed(val count: Int) : MonitorMessage

    fun resolve(context: Context): String = when (this) {
        is ConnectionsClosed ->
            context.getString(R.string.monitor_disconnected_count, count)
    }
}

@Composable
fun MonitorScreen(
    onNavigateBack: () -> Unit,
    viewModel: MonitorViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var confirmCloseAll by remember { mutableStateOf(false) }

    val context = LocalContext.current
    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it.resolve(context))
            viewModel.consumeMessage()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp,
                        start = 4.dp,
                        end = 8.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onNavigateBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.common_back),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.monitor_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    if (state.running) {
                        Text(
                            text = stringResource(
                                R.string.monitor_summary,
                                state.connections.size,
                                formatBytes(state.totalUpload),
                                formatBytes(state.totalDownload),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (state.running && state.connections.isNotEmpty()) {
                    IconButton(
                        onClick = { confirmCloseAll = true },
                        enabled = !state.closingAll,
                    ) {
                        Icon(
                            Icons.Outlined.LinkOff,
                            contentDescription = stringResource(R.string.monitor_disconnect_all),
                        )
                    }
                }
            }

            if (!state.running) {
                Text(
                    text = stringResource(R.string.monitor_not_running),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
                return@Column
            }

            // 断流之后列表就永远停在原地了。以前唯一的出路是退出本页再进来，
            // 而界面上没有任何地方提到这一点。
            state.streamError?.let { error ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.monitor_stream_error, error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = viewModel::retry) {
                        Text(stringResource(R.string.common_retry))
                    }
                }
            }

            ThroughputPanel(samples = state.samples, memoryInUse = state.memoryInUse)

            // 整个列表共用一块毛玻璃。每行一块的话，同屏十几个连接就是十几个
            // 独立的 Haze 模糊节点，而这个页面每秒都会收到新快照。
            GlassPanel(
                hazeState = LocalHazeState.current,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (state.connections.isEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.monitor_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 24.dp),
                            )
                        }
                    }
                    items(state.connections, key = { it.id }) { connection ->
                        ConnectionRow(
                            connection = connection,
                            onClose = { viewModel.close(connection.id) },
                        )
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(
                    start = 12.dp,
                    end = 12.dp,
                    bottom = WindowInsets.navigationBars.asPaddingValues()
                        .calculateBottomPadding() + 12.dp,
                ),
        )
    }

    if (confirmCloseAll) {
        // 断开全部是一次性影响所有客户端的动作，且没有撤销。
        AlertDialog(
            onDismissRequest = { confirmCloseAll = false },
            title = { Text(stringResource(R.string.monitor_disconnect_all_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.monitor_disconnect_all_message,
                        state.connections.size,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmCloseAll = false
                    viewModel.closeAll()
                }) { Text(stringResource(R.string.monitor_disconnect_all)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmCloseAll = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

/**
 * 实时速率曲线 + 内核内存（FR-6.4 的 UI 侧、FR-6.5）。
 *
 * 两个数字放在一张卡里是有理由的：内存涨得不正常的时候，第一个要问的问题就是
 * 「是不是流量也在涨」。分成两张卡，用户得来回扫视才能把它们对上。
 */
@Composable
private fun ThroughputPanel(samples: TrafficSamples, memoryInUse: Long?) {
    GlassPanel(
        hazeState = LocalHazeState.current,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        contentPadding = 14.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.monitor_speed_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (samples.peak > 0) {
                Text(
                    text = stringResource(
                        R.string.monitor_speed_peak,
                        formatSpeed(samples.peak),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        TrafficChart(samples = samples, modifier = Modifier.fillMaxWidth().height(64.dp))

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.monitor_memory_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                // 「还没收到第一帧」和「占用 0 字节」必须分开：后者不可能发生，
                // 显示成 0 B 会让人以为读数坏了
                text = memoryInUse
                    ?.let { stringResource(R.string.monitor_memory_value, formatBytes(it)) }
                    ?: stringResource(R.string.monitor_memory_unavailable),
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
            )
        }
        Text(
            text = stringResource(R.string.monitor_memory_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** 上下行两条曲线叠在同一个区域里，共用 [TrafficSamples.peak] 这把标尺。 */
@Composable
private fun TrafficChart(samples: TrafficSamples, modifier: Modifier = Modifier) {
    val downloadColor = MaterialTheme.colorScheme.primary
    val uploadColor = StatusColors.connecting
    Box(modifier = modifier) {
        if (!samples.isPlottable) {
            SparklinePlaceholder(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.fillMaxSize(),
            )
            return@Box
        }
        Sparkline(
            values = samples.download,
            peak = samples.peak,
            color = downloadColor,
            modifier = Modifier.fillMaxSize(),
        )
        Sparkline(
            values = samples.upload,
            peak = samples.peak,
            color = uploadColor,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun ConnectionRow(connection: ConnectionInfo, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = connection.destination,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
            Text(
                // 来源 IP 是网关形态下最有价值的信息：能直接看出是哪台设备
                text = if (connection.rule.isBlank()) {
                    stringResource(
                        R.string.monitor_connection_route,
                        connection.clientAddress,
                        connection.outbound,
                    )
                } else {
                    stringResource(
                        R.string.monitor_connection_route_with_rule,
                        connection.clientAddress,
                        connection.outbound,
                        connection.rule,
                    )
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(
                text = stringResource(
                    R.string.monitor_connection_traffic,
                    formatBytes(connection.upload),
                    formatBytes(connection.download),
                ),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onClose) {
            Icon(
                Icons.Default.Close,
                // 同屏十几个「断开」按钮，读屏软件念出来一模一样，
                // 用户无从知道自己焦点在哪一条上
                contentDescription = stringResource(
                    R.string.monitor_disconnect_named,
                    connection.destination,
                ),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
