package com.niceproxy.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ArrowForwardIos
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.niceproxy.core.data.BackupRepository
import com.niceproxy.core.data.InboundRepository
import com.niceproxy.core.datastore.KeepAliveJournal
import com.niceproxy.core.datastore.SettingsDataStore
import com.niceproxy.core.designsystem.component.GlassPanel
import com.niceproxy.core.designsystem.component.LocalHazeState
import com.niceproxy.core.designsystem.theme.StatusColors
import com.niceproxy.core.model.DnsSettings
import com.niceproxy.core.model.InboundType
import com.niceproxy.core.model.KeepAliveStats
import com.niceproxy.core.model.NetworkPreference
import com.niceproxy.core.model.ServiceSettings
import com.niceproxy.core.service.core.NiceCore
import com.niceproxy.keepalive.KeepAlive
import com.niceproxy.keepalive.rememberIgnoringBatteryOptimizations
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class SettingsUiState(
    val service: ServiceSettings = ServiceSettings(),
    val dns: DnsSettings = DnsSettings(),
    val coreVersion: String = "",
    /** 没配 PAC 入站的人不该看到 PAC 相关的开关。 */
    val hasPacInbound: Boolean = false,
    val keepAlive: KeepAliveStats = KeepAliveStats(),
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsDataStore,
    private val backupRepository: BackupRepository,
    private val inboundRepository: InboundRepository,
    private val journal: KeepAliveJournal,
    @ApplicationContext private val context: Context,
    core: NiceCore,
) : ViewModel() {

    private val version = core.version

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun exportBackup(uri: Uri, password: String) {
        viewModelScope.launch {
            _busy.value = true
            try {
                backupRepository.export(password.toCharArray()).fold(
                    onSuccess = { bytes ->
                        runCatching {
                            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                                ?: error("无法写入所选位置")
                        }.fold(
                            onSuccess = { _message.value = "备份已导出（已加密）" },
                            onFailure = { _message.value = "写入失败：${it.message}" },
                        )
                    },
                    onFailure = { _message.value = "备份失败：${it.message}" },
                )
            } finally {
                _busy.value = false
            }
        }
    }

    fun restoreBackup(uri: Uri, password: String) {
        viewModelScope.launch {
            _busy.value = true
            try {
                val bytes = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("无法读取所选文件")
                }.getOrElse {
                    _message.value = "读取失败：${it.message}"
                    return@launch
                }

                backupRepository.restore(bytes, password.toCharArray()).fold(
                    onSuccess = {
                        _message.value = "已恢复 ${it.servers} 个节点、${it.inbounds} 个入站、" +
                            "${it.rules} 条规则"
                    },
                    onFailure = { _message.value = it.message ?: "恢复失败" },
                )
            } finally {
                _busy.value = false
            }
        }
    }

    fun consumeMessage() { _message.value = null }

    fun defaultBackupFileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        return "nice-proxy-$stamp.niceproxy"
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        settings.serviceSettings,
        settings.dnsSettings,
        inboundRepository.inbounds,
        journal.stats,
    ) { service, dns, inbounds, keepAlive ->
        SettingsUiState(
            service = service,
            dns = dns,
            coreVersion = version,
            hasPacInbound = inbounds.any { it.enabled && it.type == InboundType.PAC },
            keepAlive = keepAlive,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        SettingsUiState(coreVersion = version),
    )

    fun setAutoStart(value: Boolean) = update { it.copy(autoStartOnBoot = value) }
    fun setStartOnLaunch(value: Boolean) = update { it.copy(startOnAppLaunch = value) }
    fun setPowerSave(value: Boolean) = update { it.copy(powerSave = value) }
    fun setKeepWifiAwake(value: Boolean) = update { it.copy(keepWifiAwake = value) }
    fun setAutoRestart(value: Boolean) = update { it.copy(autoRestartOnFailure = value) }
    fun setPacDirectFallback(value: Boolean) = update { it.copy(pacDirectFallback = value) }

    fun clearKeepAliveHistory() {
        viewModelScope.launch {
            journal.clearHistory()
            _message.value = "已清空保活记录"
        }
    }
    fun setIpv6(value: Boolean) = update { it.copy(ipv6Enabled = value) }
    fun setNetworkPreference(value: NetworkPreference) = update { it.copy(networkPreference = value) }

    fun setRemoteDns(value: String) {
        viewModelScope.launch { settings.updateDnsSettings { it.copy(remoteServer = value) } }
    }

    fun setLocalDns(value: String) {
        viewModelScope.launch { settings.updateDnsSettings { it.copy(localServer = value) } }
    }

    private fun update(transform: (ServiceSettings) -> ServiceSettings) {
        viewModelScope.launch { settings.updateServiceSettings(transform) }
    }
}

private enum class PasswordPrompt { Export, Restore }

@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var passwordPrompt by remember { mutableStateOf<PasswordPrompt?>(null) }
    var pendingPassword by remember { mutableStateOf("") }

    val context = LocalContext.current
    val ignoringBatteryOptimizations = rememberIgnoringBatteryOptimizations()
    // 装了哪个手机管家不会在应用运行期间变，但这次查询要遍历候选组件，别放进重组路径
    val hasVendorAutoStart = remember(context) { KeepAlive.hasVendorAutoStartSettings(context) }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    // 备份文件用 SAF 落到用户自己选的位置，应用不持有任何外部存储权限
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        uri?.let { viewModel.exportBackup(it, pendingPassword) }
        pendingPassword = ""
    }
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let { viewModel.restoreBackup(it, pendingPassword) }
        pendingPassword = ""
    }

    passwordPrompt?.let { prompt ->
        BackupPasswordDialog(
            isExport = prompt == PasswordPrompt.Export,
            onDismiss = { passwordPrompt = null },
            onConfirm = { password ->
                pendingPassword = password
                passwordPrompt = null
                if (prompt == PasswordPrompt.Export) {
                    exportLauncher.launch(viewModel.defaultBackupFileName())
                } else {
                    restoreLauncher.launch(arrayOf("*/*"))
                }
            },
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp,
                    bottom = 24.dp,
                ),
            ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = "设置",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }

        SettingsGroup("服务") {
            SwitchRow(
                "开机自启",
                "设备重启后自动开启代理",
                state.service.autoStartOnBoot,
                viewModel::setAutoStart,
            )
            SwitchRow(
                "打开应用时自启",
                "启动应用后自动开启代理",
                state.service.startOnAppLaunch,
                viewModel::setStartOnLaunch,
            )
            SwitchRow(
                "省电模式",
                "绑定的 IP 从设备上消失时自动停止",
                state.service.powerSave,
                viewModel::setPowerSave,
            )
            SwitchRow(
                "保持 Wi-Fi 唤醒",
                // 不能承诺「息屏也保持」：API 34 起系统把 HIGH_PERF 换成了
                // 只在亮屏时生效的 LOW_LATENCY，Android 14+ 上这个开关息屏即失效
                "防止 Wi-Fi 进入省电模式导致断流。Android 14 及以上仅在亮屏时有效，" +
                    "息屏保活请依靠下方的电池优化设置",
                state.service.keepWifiAwake,
                viewModel::setKeepWifiAwake,
            )
        }

        SettingsGroup("后台保活") {
            SwitchRow(
                "内核异常时自动重启",
                "内核意外退出或启动失败时按退避策略重试，而不是悄无声息地停在那里",
                state.service.autoRestartOnFailure,
                viewModel::setAutoRestart,
            )
            // 只在真的用了 PAC 时才露出来，否则是一个对多数人毫无意义、
            // 却听起来很吓人的开关
            if (state.hasPacInbound) {
                SwitchRow(
                    "PAC：代理不可用时允许直连",
                    "关闭时代理一挂客户端就断网，这是有意的 —— 游戏机和电视盒子" +
                        "不会提示「没在走代理」，静默裸奔比断网危险得多。" +
                        "只有在断网影响更大时才打开。",
                    state.service.pacDirectFallback,
                    viewModel::setPacDirectFallback,
                )
            }
            ClickableRow(
                title = "忽略电池优化",
                subtitle = if (ignoringBatteryOptimizations) {
                    "已关闭，系统不会在息屏后冻结代理"
                } else {
                    "未关闭：息屏一段时间后代理可能被系统冻结，所有客户端一起断网"
                },
                // 未授权时用错误色而不是普通灰：这不是一条「建议」，
                // 不处理它整个网关随时会停
                subtitleColor = if (ignoringBatteryOptimizations) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
                onClick = { KeepAlive.requestIgnoreBatteryOptimizations(context) },
            )
            if (hasVendorAutoStart) {
                // 标题刻意不写死「自启动」：国产 ROM 上确实是自启动白名单，
                // 而三星跳过去的是「深度睡眠应用」，叫自启动会和用户看到的页面对不上
                ClickableRow(
                    title = "厂商后台限制",
                    subtitle = "厂商在系统电池优化之外另有一套管控，" +
                        "不把本应用加进白名单，前台服务照样会被手机管家清掉",
                    onClick = { KeepAlive.openAutoStartSettings(context) },
                )
            }
            KeepAliveHistory(
                stats = state.keepAlive,
                onClear = viewModel::clearKeepAliveHistory,
            )
        }

        SettingsGroup("网络") {
            Text(
                text = "出站网卡",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = "决定代理流量从哪张网卡发出。选定后即使系统默认网络变化也不跟随。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NetworkPreference.entries.forEach { preference ->
                    FilterChip(
                        selected = state.service.networkPreference == preference,
                        onClick = { viewModel.setNetworkPreference(preference) },
                        label = { Text(preference.displayName) },
                    )
                }
            }
            SwitchRow(
                "启用 IPv6",
                "关闭后 DNS 只解析 IPv4",
                state.service.ipv6Enabled,
                viewModel::setIpv6,
                topPadding = 12.dp,
            )
        }

        SettingsGroup("DNS") {
            OutlinedTextField(
                value = state.dns.remoteServer,
                onValueChange = viewModel::setRemoteDns,
                label = { Text("代理流量 DNS") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.dns.localServer,
                onValueChange = viewModel::setLocalDns,
                label = { Text("直连流量 DNS") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
            )
            Text(
                text = "支持 udp://、tls://、https://、quic:// 等写法，直接填 IP 等同于 udp。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        SettingsGroup("备份与恢复") {
            Text(
                text = "备份含节点密码与入站凭据，因此强制加密。密码只用于这个文件，" +
                    "丢失后无法找回。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 10.dp),
            )
            if (busy) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { passwordPrompt = PasswordPrompt.Export },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text("导出备份") }
                OutlinedButton(
                    onClick = { passwordPrompt = PasswordPrompt.Restore },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text("恢复备份") }
            }
        }

        SettingsGroup("关于") {
            Text("内核 sing-box ${state.coreVersion}", style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "Nice Proxy 以 GPL-3.0 开源。本应用不使用 VPN 权限，因此不会代理本机应用的流量。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun BackupPasswordDialog(
    isExport: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }

    // 导出时要二次确认：密码一旦记错，备份就永远打不开了
    val mismatch = isExport && confirm.isNotEmpty() && password != confirm
    val canConfirm = password.length >= MIN_BACKUP_PASSWORD &&
        (!isExport || password == confirm)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isExport) "设置备份密码" else "输入备份密码") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    supportingText = {
                        if (password.isNotEmpty() && password.length < MIN_BACKUP_PASSWORD) {
                            Text("至少 $MIN_BACKUP_PASSWORD 位")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (isExport) {
                    OutlinedTextField(
                        value = confirm,
                        onValueChange = { confirm = it },
                        label = { Text("确认密码") },
                        singleLine = true,
                        isError = mismatch,
                        visualTransformation = PasswordVisualTransformation(),
                        supportingText = { if (mismatch) Text("两次输入不一致") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "密码只用于这个备份文件，应用不会保存它。忘记密码将无法恢复。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = "恢复会覆盖当前的节点、入站与规则，此操作不可撤销。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(password) }, enabled = canConfirm) {
                Text(if (isExport) "选择保存位置" else "选择备份文件")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private const val MIN_BACKUP_PASSWORD = 6

@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        GlassPanel(
            hazeState = LocalHazeState.current,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = 16.dp,
        ) {
            content()
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    topPadding: androidx.compose.ui.unit.Dp = 0.dp,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topPadding, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * 保活的实际战绩。
 *
 * 这一段的价值全在于它能被**证伪**：说明书里写「四层防御」谁都会写，
 * 只有真实的中断次数能回答「在你这台手机上到底管不管用」。
 * 一次没中断过，说明现在的设置够了；每天几次，说明该去开上面那两个白名单。
 */
@Composable
private fun KeepAliveHistory(stats: KeepAliveStats, onClear: () -> Unit) {
    val recent = stats.interruptionsWithin(HISTORY_WINDOW_MS)
    val latest = stats.interruptions.firstOrNull()

    Column(modifier = Modifier.padding(top = 12.dp)) {
        Text(
            text = "最近 7 天",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = when {
                // 从没记录过和「记录过但都在 7 天前」是两回事，不能都说「没被中断」
                stats.interruptions.isEmpty() -> "还没有记录到中断。代理每次被系统或内核打断都会记在这里。"
                recent == 0 -> "最近 7 天没有被中断过。"
                else -> "被中断 $recent 次，均已自动恢复。频繁中断说明系统在杀后台，" +
                    "请确认上面两项白名单都已开启。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (recent > 0) StatusColors.connecting else MaterialTheme.colorScheme.onSurface,
        )
        latest?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "最近一次：${formatTimestamp(it.atMillis)} · ${it.recovery.label}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (stats.interruptions.isNotEmpty()) {
            TextButton(onClick = onClear, modifier = Modifier.align(Alignment.End)) {
                Text("清空记录")
            }
        }
    }
}

private fun formatTimestamp(millis: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))

private const val HISTORY_WINDOW_MS = 7L * 24 * 60 * 60 * 1000

/** [SwitchRow] 的「跳转」版本：右侧不是开关，而是一个把用户送去系统设置页的箭头。 */
@Composable
private fun ClickableRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // clip 必须在 clickable 之前：否则水波纹是个直角矩形，
            // 在圆角毛玻璃面板里会顶到边框上
            .clip(ClickableRowShape)
            .clickable(onClick = onClick)
            .padding(top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = subtitleColor,
            )
        }
        Spacer(Modifier.width(12.dp))
        Icon(
            Icons.Outlined.ArrowForwardIos,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val ClickableRowShape = RoundedCornerShape(12.dp)
