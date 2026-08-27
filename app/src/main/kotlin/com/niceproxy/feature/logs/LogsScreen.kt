package com.niceproxy.feature.logs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.niceproxy.core.designsystem.component.GlassPanel
import com.niceproxy.core.designsystem.component.LocalHazeState
import com.niceproxy.core.datastore.SettingsDataStore
import com.niceproxy.core.network.clash.ClashApiClient
import com.niceproxy.core.network.clash.LogFrame
import com.niceproxy.core.service.ProxyServiceController
import com.niceproxy.core.service.ProxyState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LogsViewModel @Inject constructor(
    private val clashApi: ClashApiClient,
    private val settings: SettingsDataStore,
    private val controller: ProxyServiceController,
) : ViewModel() {

    /**
     * 环形缓冲。日志是无界流，不设上限迟早 OOM。
     *
     * 用 [ArrayDeque] 而不是 `(current + frame).takeLast(n)`：后者每来一行要分配
     * 两个 500 元素的列表，内核繁忙时每秒几十行，纯粹是给 GC 送活干。
     */
    private val buffer = ArrayDeque<LogFrame>(MAX_LINES)
    private var lastEmitAt = 0L

    private val _lines = MutableStateFlow<List<LogFrame>>(emptyList())
    val lines: StateFlow<List<LogFrame>> = _lines.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    init {
        // 在 Default 上收集：缓冲区维护和快照构造都不该占主线程。
        // StateFlow 是线程安全的，UI 侧仍由 collectAsStateWithLifecycle 在主线程读。
        viewModelScope.launch(Dispatchers.Default) {
            // 内核每次重启，Clash API 的 WebSocket 都会断，需要重新订阅
            controller.state.collectLatest { state ->
                _running.value = state is ProxyState.Running
                if (state !is ProxyState.Running) return@collectLatest

                val api = settings.clashApiSettings()
                clashApi.logs(api)
                    .catch { /* 内核停止导致的断流是正常路径 */ }
                    .collect(::append)
            }
        }
    }

    /**
     * 日志到达速度远高于人眼能读的速度，也高于屏幕刷新率。
     * 按固定间隔发布快照，把「每行一次重组」压成「每 [EMIT_INTERVAL_MS] 毫秒一次」。
     */
    private fun append(frame: LogFrame) {
        buffer.addLast(frame)
        while (buffer.size > MAX_LINES) buffer.removeFirst()

        val now = System.currentTimeMillis()
        if (now - lastEmitAt >= EMIT_INTERVAL_MS) {
            lastEmitAt = now
            _lines.value = buffer.toList()
        }
    }

    fun clear() {
        buffer.clear()
        _lines.value = emptyList()
    }

    fun exportText(): String = _lines.value.joinToString("\n") { it.payload }

    private companion object {
        const val MAX_LINES = 500
        const val EMIT_INTERVAL_MS = 200L
    }
}

@Composable
fun LogsScreen(
    onNavigateBack: () -> Unit,
    viewModel: LogsViewModel = hiltViewModel(),
) {
    val lines by viewModel.lines.collectAsStateWithLifecycle()
    val running by viewModel.running.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current

    /**
     * 只有当用户本来就在底部时才自动跟随。
     * 否则他往上翻去看一条报错，下一行日志一来就会被拽回底部。
     */
    val pinnedToBottom by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            last == null || last.index >= listState.layoutInfo.totalItemsCount - 2
        }
    }

    LaunchedEffect(lines.size, pinnedToBottom) {
        // 用非动画版本：日志高频到达时，动画滚动会让列表永远处于滚动状态，
        // 每帧都要重绘整屏——这是这个页面原来最大的性能问题。
        if (pinnedToBottom && lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp,
                    start = 4.dp,
                    end = 12.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = "运行日志",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { clipboard.setText(AnnotatedString(viewModel.exportText())) },
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = "复制全部日志")
            }
            IconButton(onClick = viewModel::clear) {
                Icon(Icons.Default.DeleteSweep, contentDescription = "清空")
            }
        }

        if (!running) {
            Text(
                text = "代理未运行，日志将在启动后开始输出。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }

        // 整个列表共用一块毛玻璃。原来是每行一块 —— 每块 GlassPanel 都是一个独立的
        // Haze 模糊节点，同屏 30 行就意味着每帧做 30 次全屏模糊。
        GlassPanel(
            hazeState = LocalHazeState.current,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                itemsIndexed(lines, key = { index, _ -> index }) { _, frame ->
                    Text(
                        text = frame.payload,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = levelColor(frame.type),
                    )
                }
            }
        }
    }
}

@Composable
private fun levelColor(type: String): Color = when (type.lowercase()) {
    "error", "fatal", "panic" -> MaterialTheme.colorScheme.error
    "warning", "warn" -> Color(0xFFF59E0B)
    "debug", "trace" -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> MaterialTheme.colorScheme.onSurface
}
