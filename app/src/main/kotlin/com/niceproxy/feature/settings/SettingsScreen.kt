package com.niceproxy.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.material.icons.automirrored.outlined.ArrowForwardIos
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.niceproxy.R
import com.niceproxy.appearance.AppLanguage
import com.niceproxy.appearance.AppearancePreferences
import com.niceproxy.appearance.ThemeMode
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

/**
 * 待展示的一次性提示。
 *
 * 存事件而不是拼好的字符串：ViewModel 活得比 Activity 长，切完语言之后它还在，
 * 在这里取资源会吐出上一门语言的句子。
 */
sealed interface SettingsMessage {
    data object BackupExported : SettingsMessage
    data object KeepAliveCleared : SettingsMessage
    data class WriteFailed(val reason: String) : SettingsMessage
    data class ReadFailed(val reason: String) : SettingsMessage
    data class BackupFailed(val reason: String) : SettingsMessage
    data class RestoreFailed(val reason: String) : SettingsMessage
    data class Restored(val servers: Int, val inbounds: Int, val rules: Int) : SettingsMessage

    fun resolve(context: Context): String = when (this) {
        BackupExported -> context.getString(R.string.settings_backup_exported)
        KeepAliveCleared -> context.getString(R.string.settings_keepalive_cleared)
        is WriteFailed -> context.getString(R.string.settings_backup_write_failed, reason)
        is ReadFailed -> context.getString(R.string.settings_backup_read_failed, reason)
        is BackupFailed -> context.getString(R.string.settings_backup_failed, reason)
        is RestoreFailed -> reason
        is Restored ->
            context.getString(R.string.settings_backup_restored, servers, inbounds, rules)
    }
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsDataStore,
    private val backupRepository: BackupRepository,
    private val inboundRepository: InboundRepository,
    private val journal: KeepAliveJournal,
    private val appearance: AppearancePreferences,
    @ApplicationContext private val context: Context,
    core: NiceCore,
) : ViewModel() {

    private val version = core.version

    private val _message = MutableStateFlow<SettingsMessage?>(null)
    val message: StateFlow<SettingsMessage?> = _message.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    val themeMode: StateFlow<ThemeMode> = appearance.themeMode
    val language: StateFlow<AppLanguage> = appearance.language

    fun setThemeMode(mode: ThemeMode) = appearance.setThemeMode(mode)

    /** @return true 表示调用方要重建 Activity，见 [AppearancePreferences.setLanguage]。 */
    fun setLanguage(value: AppLanguage): Boolean = appearance.setLanguage(value)

    fun exportBackup(uri: Uri, password: String) {
        viewModelScope.launch {
            _busy.value = true
            try {
                backupRepository.export(password.toCharArray()).fold(
                    onSuccess = { bytes ->
                        runCatching {
                            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                                ?: error(context.getString(R.string.settings_backup_no_target))
                        }.fold(
                            onSuccess = { _message.value = SettingsMessage.BackupExported },
                            onFailure = {
                                _message.value = SettingsMessage.WriteFailed(it.reason())
                            },
                        )
                    },
                    onFailure = { _message.value = SettingsMessage.BackupFailed(it.reason()) },
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
                        ?: error(context.getString(R.string.settings_backup_no_source))
                }.getOrElse {
                    _message.value = SettingsMessage.ReadFailed(it.reason())
                    return@launch
                }

                backupRepository.restore(bytes, password.toCharArray()).fold(
                    onSuccess = {
                        _message.value = SettingsMessage.Restored(
                            servers = it.servers,
                            inbounds = it.inbounds,
                            rules = it.rules,
                        )
                    },
                    onFailure = {
                        _message.value = SettingsMessage.RestoreFailed(
                            it.message ?: context.getString(R.string.settings_backup_restore_failed),
                        )
                    },
                )
            } finally {
                _busy.value = false
            }
        }
    }

    private fun Throwable.reason(): String =
        message ?: this::class.simpleName.orEmpty()

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
            _message.value = SettingsMessage.KeepAliveCleared
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
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val language by viewModel.language.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var passwordPrompt by remember { mutableStateOf<PasswordPrompt?>(null) }
    var pendingPassword by remember { mutableStateOf("") }
    var localMessage by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val ignoringBatteryOptimizations = rememberIgnoringBatteryOptimizations()
    // 装了哪个手机管家不会在应用运行期间变，但这次查询要遍历候选组件，别放进重组路径
    val hasVendorAutoStart = remember(context) { KeepAlive.hasVendorAutoStartSettings(context) }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it.resolve(context))
            viewModel.consumeMessage()
        }
    }
    LaunchedEffect(localMessage) {
        localMessage?.let {
            snackbarHostState.showSnackbar(it)
            localMessage = null
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
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.common_back),
                )
            }
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }

        AppearanceGroup(
            themeMode = themeMode,
            language = language,
            onThemeChange = viewModel::setThemeMode,
            onLanguageChange = { selected ->
                // API 33 以下换语言只能靠重建：资源在 attachBaseContext 时就已
                // 解析完，此刻整棵 Compose 树拿到的都是旧语言的字符串。
                if (viewModel.setLanguage(selected)) {
                    (context as? Activity)?.recreate()
                }
            },
        )

        SettingsGroup(stringResource(R.string.settings_group_service)) {
            SwitchRow(
                stringResource(R.string.settings_auto_start),
                stringResource(R.string.settings_auto_start_desc),
                state.service.autoStartOnBoot,
                viewModel::setAutoStart,
            )
            SwitchRow(
                stringResource(R.string.settings_start_on_launch),
                stringResource(R.string.settings_start_on_launch_desc),
                state.service.startOnAppLaunch,
                viewModel::setStartOnLaunch,
            )
            SwitchRow(
                stringResource(R.string.settings_power_save),
                stringResource(R.string.settings_power_save_desc),
                state.service.powerSave,
                viewModel::setPowerSave,
            )
            SwitchRow(
                stringResource(R.string.settings_keep_wifi),
                // 不能承诺「息屏也保持」：API 34 起系统把 HIGH_PERF 换成了
                // 只在亮屏时生效的 LOW_LATENCY，Android 14+ 上这个开关息屏即失效
                stringResource(R.string.settings_keep_wifi_desc),
                state.service.keepWifiAwake,
                viewModel::setKeepWifiAwake,
            )
        }

        SettingsGroup(stringResource(R.string.settings_group_keepalive)) {
            SwitchRow(
                stringResource(R.string.settings_auto_restart),
                stringResource(R.string.settings_auto_restart_desc),
                state.service.autoRestartOnFailure,
                viewModel::setAutoRestart,
            )
            // 只在真的用了 PAC 时才露出来，否则是一个对多数人毫无意义、
            // 却听起来很吓人的开关
            if (state.hasPacInbound) {
                SwitchRow(
                    stringResource(R.string.settings_pac_fallback),
                    stringResource(R.string.settings_pac_fallback_desc),
                    state.service.pacDirectFallback,
                    viewModel::setPacDirectFallback,
                )
            }
            ClickableRow(
                title = stringResource(R.string.settings_battery),
                subtitle = if (ignoringBatteryOptimizations) {
                    stringResource(R.string.settings_battery_granted)
                } else {
                    stringResource(R.string.settings_battery_denied)
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
                    title = stringResource(R.string.settings_vendor),
                    subtitle = stringResource(R.string.settings_vendor_desc),
                    onClick = { KeepAlive.openAutoStartSettings(context) },
                )
            }
            KeepAliveHistory(
                stats = state.keepAlive,
                onClear = viewModel::clearKeepAliveHistory,
            )
        }

        SettingsGroup(stringResource(R.string.settings_group_network)) {
            Text(
                text = stringResource(R.string.settings_outbound_interface),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(R.string.settings_outbound_interface_desc),
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
                stringResource(R.string.settings_ipv6),
                stringResource(R.string.settings_ipv6_desc),
                state.service.ipv6Enabled,
                viewModel::setIpv6,
                topPadding = 12.dp,
            )
        }

        SettingsGroup(stringResource(R.string.settings_group_dns)) {
            OutlinedTextField(
                value = state.dns.remoteServer,
                onValueChange = viewModel::setRemoteDns,
                label = { Text(stringResource(R.string.settings_dns_remote)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.dns.localServer,
                onValueChange = viewModel::setLocalDns,
                label = { Text(stringResource(R.string.settings_dns_local)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
            )
            Text(
                text = stringResource(R.string.settings_dns_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        SettingsGroup(stringResource(R.string.settings_group_storage)) {
            // 「清理缓存」这个按钮**没有**做成应用内动作，因为应用内没有任何
            // 一条安全的实现路径：那些 .srs 和 cache.db 归 sing-box 所有，
            // 运行中删掉它们，内核会在下一次规则匹配时找不到规则集。
            // 与其放一个点了不知道发生什么的按钮，不如把用户送到系统那一页。
            Text(
                text = stringResource(R.string.settings_storage_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            val noStoragePage = stringResource(R.string.settings_storage_unavailable)
            ClickableRow(
                title = stringResource(R.string.settings_storage_open),
                subtitle = stringResource(R.string.settings_storage_open_desc),
                onClick = { localMessage = context.openAppStorageSettings(noStoragePage) },
            )
        }

        SettingsGroup(stringResource(R.string.settings_group_backup)) {
            Text(
                text = stringResource(R.string.settings_backup_desc),
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
                ) { Text(stringResource(R.string.settings_backup_export)) }
                OutlinedButton(
                    onClick = { passwordPrompt = PasswordPrompt.Restore },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.settings_backup_restore)) }
            }
        }

        SettingsGroup(stringResource(R.string.settings_group_about)) {
            Text(
                text = stringResource(R.string.settings_about_core, state.coreVersion),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.settings_about_license),
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

/**
 * 主题与语言（FR-7.1、FR-7.2）。
 *
 * 放在设置页最上面：这两项是用户进设置最常找的东西，而下面那些开关
 * （电池优化、PAC 回退）都要求先理解一段解释才敢动。
 */
@Composable
private fun AppearanceGroup(
    themeMode: ThemeMode,
    language: AppLanguage,
    onThemeChange: (ThemeMode) -> Unit,
    onLanguageChange: (AppLanguage) -> Unit,
) {
    SettingsGroup(stringResource(R.string.settings_group_appearance)) {
        Text(
            text = stringResource(R.string.settings_theme),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ThemeMode.entries.forEach { mode ->
                FilterChip(
                    selected = themeMode == mode,
                    onClick = { onThemeChange(mode) },
                    label = { Text(stringResource(mode.labelRes)) },
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Text(
            text = stringResource(R.string.settings_language),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppLanguage.entries.forEach { option ->
                FilterChip(
                    selected = language == option,
                    onClick = { onLanguageChange(option) },
                    label = { Text(stringResource(option.labelRes)) },
                )
            }
        }
        Text(
            text = stringResource(R.string.settings_language_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/**
 * 把用户送到系统的「应用信息 → 存储」。
 *
 * @return 需要提示的失败原因，成功时为 null。定制 ROM 上这一页可能不存在，
 *         不接住 [ActivityNotFoundException] 就是一次崩溃。
 */
private fun Context.openAppStorageSettings(fallbackMessage: String): String? = try {
    startActivity(
        Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    null
} catch (e: ActivityNotFoundException) {
    android.util.Log.w("SettingsScreen", "系统没有应用详情页", e)
    fallbackMessage
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
        title = {
            Text(
                stringResource(
                    if (isExport) {
                        R.string.settings_backup_password_export
                    } else {
                        R.string.settings_backup_password_restore
                    },
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.settings_backup_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    supportingText = {
                        if (password.isNotEmpty() && password.length < MIN_BACKUP_PASSWORD) {
                            Text(
                                stringResource(
                                    R.string.settings_backup_password_min,
                                    MIN_BACKUP_PASSWORD,
                                ),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (isExport) {
                    OutlinedTextField(
                        value = confirm,
                        onValueChange = { confirm = it },
                        label = { Text(stringResource(R.string.settings_backup_password_confirm)) },
                        singleLine = true,
                        isError = mismatch,
                        visualTransformation = PasswordVisualTransformation(),
                        supportingText = {
                            if (mismatch) {
                                Text(stringResource(R.string.settings_backup_password_mismatch))
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = stringResource(R.string.settings_backup_password_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.settings_backup_restore_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(password) }, enabled = canConfirm) {
                Text(
                    stringResource(
                        if (isExport) {
                            R.string.settings_backup_pick_target
                        } else {
                            R.string.settings_backup_pick_file
                        },
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
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
            // 一屏十来个开关，标题只有一行时整行不到 48dp。撑到 48 之后
            // 开关自身的触控目标也跟着落在推荐尺寸上。
            .defaultMinSize(minHeight = 48.dp)
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
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            // 标题和开关是两个独立的语义节点，不关联的话读屏软件在这一页上
            // 会连着念十几遍「开关，已开启」，一遍都说不出是哪个开关
            modifier = Modifier.semantics { contentDescription = title },
        )
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
            text = stringResource(R.string.settings_keepalive_window),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = when {
                // 从没记录过和「记录过但都在 7 天前」是两回事，不能都说「没被中断」
                stats.interruptions.isEmpty() ->
                    stringResource(R.string.settings_keepalive_none)
                recent == 0 -> stringResource(R.string.settings_keepalive_clean)
                else -> stringResource(R.string.settings_keepalive_count, recent)
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (recent > 0) StatusColors.connecting else MaterialTheme.colorScheme.onSurface,
        )
        latest?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(
                    R.string.settings_keepalive_latest,
                    formatTimestamp(it.atMillis),
                    it.recovery.label,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (stats.interruptions.isNotEmpty()) {
            TextButton(onClick = onClear, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.settings_keepalive_clear))
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
            .defaultMinSize(minHeight = 48.dp)
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
            Icons.AutoMirrored.Outlined.ArrowForwardIos,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val ClickableRowShape = RoundedCornerShape(12.dp)
