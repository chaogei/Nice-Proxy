package com.niceproxy.feature.monitor

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.niceproxy.core.designsystem.component.GlassPanel
import com.niceproxy.core.designsystem.component.LocalHazeState
import com.niceproxy.core.designsystem.component.formatBytes
import com.niceproxy.core.datastore.SettingsDataStore
import com.niceproxy.core.network.clash.ClashApiClient
import com.niceproxy.core.network.clash.ConnectionInfo
import com.niceproxy.core.service.ProxyServiceController
import com.niceproxy.core.service.ProxyState
import com.niceproxy.util.describe
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
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
)

@HiltViewModel
class MonitorViewModel @Inject constructor(
    private val clashApi: ClashApiClient,
    private val settings: SettingsDataStore,
    private val controller: ProxyServiceController,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MonitorUiState())
    val uiState: StateFlow<MonitorUiState> = _uiState.asStateFlow()

    init {
        // 在 Default 上收集：连接快照可能上千条，排序不能占主线程。
        // Clash API 每秒推一次，主线程上排序会稳定地吃掉帧预算。
        viewModelScope.launch(Dispatchers.Default) {
            controller.state.collectLatest { state ->
                val running = state is ProxyState.Running
                // 重新订阅时清掉上一轮的断流提示，否则内核重启之后它会一直挂着
                _uiState.value = _uiState.value.copy(running = running, streamError = null)
                if (!running) return@collectLatest

                val api = settings.clashApiSettings()
                clashApi.connections(api)
                    // 空 catch 是 README 记过的坑：代理正常跑着，页面永远「暂无活动
                    // 连接」，而日志里什么都没有。内核停止导致的断流确实是正常路径，
                    // 但那要和真正的异常分开处理，不能一起吞掉。
                    .catch { error ->
                        if (controller.state.value is ProxyState.Running) {
                            Log.w(TAG, "连接订阅中断，内核仍在运行", error)
                            _uiState.value = _uiState.value.copy(
                                streamError = "监控数据已断开（${error.describe()}），" +
                                    "代理本身可能仍在正常工作。重开本页可重试。",
                            )
                        } else {
                            Log.d(TAG, "内核已停止，连接订阅正常结束")
                        }
                    }
                    .collect { snapshot ->
                        _uiState.value = MonitorUiState(
                            // 流量大的排前面，用户关心的是「谁在占带宽」
                            connections = snapshot.connections.sortedByDescending {
                                it.download + it.upload
                            },
                            totalUpload = snapshot.uploadTotal,
                            totalDownload = snapshot.downloadTotal,
                            running = true,
                        )
                    }
            }
        }
    }

    fun close(id: String) {
        viewModelScope.launch {
            clashApi.closeConnection(settings.clashApiSettings(), id)
        }
    }

    private companion object {
        const val TAG = "MonitorViewModel"
    }
}

@Composable
fun MonitorScreen(
    onNavigateBack: () -> Unit,
    viewModel: MonitorViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp,
                    start = 4.dp,
                    end = 16.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "连接监控",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                if (state.running) {
                    Text(
                        text = "${state.connections.size} 个活动连接 · " +
                            "累计 ↑${formatBytes(state.totalUpload)} ↓${formatBytes(state.totalDownload)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (!state.running) {
            Text(
                text = "代理未运行。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
            return@Column
        }

        state.streamError?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

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
                            text = "暂无活动连接。让局域网里的设备访问一下网络就会出现在这里。",
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
                text = "${connection.clientAddress} → ${connection.outbound}" +
                    if (connection.rule.isNotBlank()) " · ${connection.rule}" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(
                text = "↑${formatBytes(connection.upload)} ↓${formatBytes(connection.download)}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onClose) {
            Icon(
                Icons.Default.Close,
                contentDescription = "断开",
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
