package com.niceproxy.feature.expert

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.niceproxy.R
import com.niceproxy.core.config.ConfigResult
import com.niceproxy.core.data.ConfigRepository
import com.niceproxy.core.designsystem.component.GlassPanel
import com.niceproxy.core.designsystem.component.LocalHazeState
import com.niceproxy.core.designsystem.theme.StatusColors
import com.niceproxy.util.copyToClipboard
import com.niceproxy.util.describe
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 专家模式的状态（FR-7.5）。
 *
 * 刻意**没有**「编辑」这一态。需求写的是「只读预览 + 覆盖注入」，这里只做前半段：
 * 手工注入的 JSON 会绕过配置生成器的全部校验（出站引用、规则集声明、DNS 绕道），
 * 而那些校验正是内核起不来时唯一能给出人话解释的东西。放开注入之后，
 * 一份看起来没问题的配置能让代理静默地不工作，界面上一个字都没有。
 * 真要做，得先有一条「注入后校验 + 一键回退」的路径，那不是 UI 层能决定的。
 */
sealed interface ConfigPreviewState {
    data object Loading : ConfigPreviewState

    data class Ready(
        val json: String,
        val fingerprint: String,
        /** 生成器跳过了这些东西，但内核照常启动。 */
        val warnings: List<String>,
    ) : ConfigPreviewState

    /** 配置根本生成不出来 —— 此刻按启动键也是这个结果，提前让用户看到。 */
    data class Failed(val errors: List<String>) : ConfigPreviewState
}

@HiltViewModel
class ConfigPreviewViewModel @Inject constructor(
    private val configRepository: ConfigRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow<ConfigPreviewState>(ConfigPreviewState.Loading)
    val state: StateFlow<ConfigPreviewState> = _state.asStateFlow()

    init {
        refresh()
    }

    /**
     * workDir 必须和 `ProxyService` 用的是同一个（`filesDir`）：配置里的
     * cache.db、规则集路径都是由它拼出来的绝对路径。取别的目录会预览出一份
     * 和真正下发的那份不一样的 JSON，而这个页面的全部价值就在于两者一致。
     */
    fun refresh() {
        viewModelScope.launch {
            _state.value = ConfigPreviewState.Loading
            _state.value = runCatching { configRepository.build(context.filesDir.absolutePath) }
                .fold(
                    onSuccess = { result ->
                        when (result) {
                            is ConfigResult.Success -> ConfigPreviewState.Ready(
                                json = result.json,
                                fingerprint = result.fingerprint.take(FINGERPRINT_CHARS),
                                warnings = result.warnings.map { it.message },
                            )
                            is ConfigResult.Failure ->
                                ConfigPreviewState.Failed(result.errors.map { it.message })
                        }
                    },
                    // 生成器本身不抛异常，但它读的是数据库和 DataStore，那两个会
                    onFailure = { ConfigPreviewState.Failed(listOf(it.describe())) },
                )
        }
    }

    private companion object {
        /** 指纹只用来肉眼比对「这份和刚才那份是不是同一个」，前 12 位足够。 */
        const val FINGERPRINT_CHARS = 12
    }
}

@Composable
fun ConfigPreviewScreen(
    onNavigateBack: () -> Unit,
    viewModel: ConfigPreviewViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var confirmShare by remember { mutableStateOf(false) }
    var pendingMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(pendingMessage) {
        pendingMessage?.let {
            snackbarHostState.showSnackbar(it)
            pendingMessage = null
        }
    }

    val ready = state as? ConfigPreviewState.Ready

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp,
                bottom = WindowInsets.navigationBars.asPaddingValues()
                    .calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.expert_title),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = stringResource(R.string.expert_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = viewModel::refresh) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.expert_refresh),
                        )
                    }
                    if (ready != null) {
                        IconButton(
                            onClick = {
                                context.copyToClipboard(
                                    label = context.getString(R.string.expert_clipboard_label),
                                    text = ready.json,
                                    // 里面有节点密码和 Clash API 密钥，
                                    // 绝不能让系统弹出明文预览浮层
                                    sensitive = true,
                                )
                                pendingMessage = context.getString(
                                    R.string.expert_copied,
                                    ready.json.length,
                                )
                            },
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = stringResource(R.string.expert_copy),
                            )
                        }
                        IconButton(onClick = { confirmShare = true }) {
                            Icon(
                                Icons.Default.Share,
                                contentDescription = stringResource(R.string.expert_share),
                            )
                        }
                    }
                }
            }

            item {
                GlassPanel(
                    hazeState = LocalHazeState.current,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = 14.dp,
                ) {
                    Text(
                        text = stringResource(R.string.expert_readonly_notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.expert_secret_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusColors.connecting,
                    )
                }
            }

            when (val current = state) {
                ConfigPreviewState.Loading -> item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.height(20.dp))
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.expert_generating),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }

                is ConfigPreviewState.Failed -> item {
                    MessageListPanel(
                        title = stringResource(R.string.expert_failed_title),
                        messages = current.errors,
                        tint = StatusColors.error,
                    )
                }

                is ConfigPreviewState.Ready -> {
                    if (current.warnings.isNotEmpty()) {
                        item {
                            MessageListPanel(
                                title = stringResource(R.string.expert_warnings_title),
                                messages = current.warnings,
                                tint = StatusColors.connecting,
                            )
                        }
                    }
                    item {
                        Row(
                            modifier = Modifier.padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.expert_fingerprint,
                                    current.fingerprint,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = stringResource(R.string.expert_size, current.json.length),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    item {
                        // 整份 JSON 一块面板、一个 Text。按行拆成 LazyColumn 的
                        // item 反而更糟：这里没有无界增长的风险（配置是有限的），
                        // 而每行一个可组合项会让「复制」和文本选择都对不上。
                        GlassPanel(
                            hazeState = LocalHazeState.current,
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = 12.dp,
                        ) {
                            Text(
                                text = current.json,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                // JSON 不该被折行：缩进是它唯一的结构提示，
                                // 一折行整棵树就看不出层级了
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                softWrap = false,
                            )
                        }
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

    if (confirmShare && ready != null) {
        // 分享是这个页面唯一会把密码送出设备的动作，必须问一次。
        AlertDialog(
            onDismissRequest = { confirmShare = false },
            title = { Text(stringResource(R.string.expert_share_confirm_title)) },
            text = { Text(stringResource(R.string.expert_share_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmShare = false
                    pendingMessage = context.shareConfig(ready.json)
                }) { Text(stringResource(R.string.common_share)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmShare = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

/**
 * @return 需要提示给用户的失败原因，成功时为 null。
 *
 * 没有可接收文本的应用在电视盒子上是常态，而 `startActivity` 那时抛的是
 * [ActivityNotFoundException] —— 不接住就是一次崩溃。
 */
private fun Context.shareConfig(json: String): String? {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, json)
        // 不设 EXTRA_SUBJECT：部分邮件客户端会把它当标题发出去，
        // 而这份内容不该在标题里留下任何痕迹
    }
    return try {
        startActivity(
            Intent.createChooser(intent, getString(R.string.expert_share_title))
                // 从非 Activity 的 Context 发起时必须带这个标记
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        null
    } catch (e: ActivityNotFoundException) {
        android.util.Log.w("ConfigPreview", "没有可接收文本的应用", e)
        getString(R.string.expert_share_no_target)
    }
}

@Composable
private fun MessageListPanel(
    title: String,
    messages: List<String>,
    tint: androidx.compose.ui.graphics.Color,
) {
    GlassPanel(
        hazeState = LocalHazeState.current,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = 14.dp,
    ) {
        Text(text = title, style = MaterialTheme.typography.titleSmall, color = tint)
        messages.forEach { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
