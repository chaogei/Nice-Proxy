package com.niceproxy.feature.home

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.ArrowForwardIos
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.SouthWest
import androidx.compose.material.icons.outlined.NorthEast
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.niceproxy.R
import com.niceproxy.feature.inbound.labelRes
import com.niceproxy.core.designsystem.component.GlassPanel
import com.niceproxy.core.designsystem.component.GlowCircle
import com.niceproxy.core.designsystem.component.LocalHazeState
import com.niceproxy.core.designsystem.component.SectionTitle
import com.niceproxy.core.designsystem.component.Sparkline
import com.niceproxy.core.designsystem.component.SparklinePlaceholder
import com.niceproxy.core.designsystem.component.formatSpeed
import com.niceproxy.core.designsystem.theme.StatusColors
import kotlinx.coroutines.delay
import com.niceproxy.core.model.InboundAuth
import com.niceproxy.core.model.InboundService
import com.niceproxy.core.service.ProxyState
import com.niceproxy.core.service.network.LocalAddress
import com.niceproxy.keepalive.KeepAlive
import com.niceproxy.keepalive.rememberIgnoringBatteryOptimizations
import com.niceproxy.traffic.TrafficSamples

@Composable
fun HomeScreen(
    contentPadding: PaddingValues,
    onEditInbound: (String) -> Unit,
    onAddInbound: () -> Unit,
    onOpenNodes: () -> Unit,
    onOpenMonitor: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val ignoringBatteryOptimizations = rememberIgnoringBatteryOptimizations()

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    // 首页自己挂宿主，不复用壳层那个：壳层的「配置已变更」是 Indefinite 的，
    // 在用户点「应用」之前会一直占着队列，而复制地址恰恰常发生在改完配置之后。
    // 共用一个宿主的话，全应用最高频的那个动作会静默失败。
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 12.dp,
                bottom = contentPadding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.home_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = viewModel::refreshNetworkInfo) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.home_refresh_network),
                        )
                    }
                }
            }

            item { PowerPanel(state = state, onToggle = viewModel::toggleProxy) }

            // 「运行中」只说明本地端口在监听。用户在配置 Switch 的当口真正想知道的是
            // 「它连上来了没有」，所以这张卡排在出站之前。
            if (state.proxyState is ProxyState.Running) {
                item {
                    ConnectedDevicesPanel(devices = state.connectedDevices, onClick = onOpenMonitor)
                }
            }

            item { OutboundPanel(state = state, onClick = onOpenNodes) }

            // 排在其他警告之前：别的问题只是配置不理想，这一条是「服务随时会整个消失」
            if (state.proxyState.isActive && !ignoringBatteryOptimizations) {
                item {
                    WarningPanel(
                        message = stringResource(R.string.home_warning_battery),
                        onClick = { KeepAlive.requestIgnoreBatteryOptimizations(context) },
                    )
                }
            }
            // 紧跟在电池优化之后：库刚被清空时界面上一片空白，用户第一反应是
            // 「我的节点呢」，而这一刻备份还是有效的，晚一步他就自己重建完了。
            if (state.databaseWasReset) {
                item {
                    WarningPanel(
                        message = stringResource(R.string.home_warning_database_reset),
                        onClick = {
                            viewModel.acknowledgeDatabaseReset()
                            onOpenSettings()
                        },
                        severe = true,
                    )
                }
            }
            if (state.credentialsPlaintext) {
                item {
                    WarningPanel(
                        message = stringResource(R.string.home_warning_plaintext),
                        severe = true,
                    )
                }
            }
            if (state.hasExposedInboundWithoutAuth) {
                item {
                    WarningPanel(
                        message = stringResource(R.string.home_warning_no_auth),
                        // 装饰性警告变成一步可达的行动。刻意不顺手把监听地址改成
                        // 127.0.0.1 —— 开箱即用是这个产品的核心价值。
                        onClick = state.exposedInboundId?.let { id -> { onEditInbound(id) } },
                    )
                }
            }
            if (state.otherVpnActive) {
                item { WarningPanel(stringResource(R.string.home_warning_other_vpn)) }
            }
            // 配置生成器跳过了一些东西但内核照跑。以前这些警告生成了却没人渲染。
            state.configWarnings.forEach { warning ->
                item { WarningPanel(warning) }
            }
            (state.proxyState as? ProxyState.Failed)?.let { failed ->
                item {
                    // 「别再试了」的出口。失败之后界面上没有「停止」按钮
                    // （toggle 在非运行态只会走启动分支），而落盘的运行意图还留着，
                    // 看门狗每 15 分钟醒来失败一次、弹一次通知。不给这个入口的话，
                    // 用户唯一的出路是「先想办法让它成功启动一次，再按停止」。
                    FailurePanel(
                        message = failed.detail
                            ?.let {
                                stringResource(
                                    R.string.home_failure_detail,
                                    failed.message,
                                    it,
                                )
                            }
                            ?: failed.message,
                        onGiveUp = viewModel::stopAndForget,
                    )
                }
            }

            state.sessionStartedAt?.let { startedAt ->
                item {
                    UptimePanel(
                        startedAt = startedAt,
                        interruptions = state.interruptionsThisSession,
                    )
                }
            }

            item { SectionTitle(stringResource(R.string.home_addresses_title)) }
            item {
                Text(
                    // 「本机 App 不走代理」必须始终可见，而且要写成一句陈述
                    // 而不是警告：它是这个产品的形态，不是故障。
                    text = stringResource(R.string.home_addresses_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            if (state.addresses.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.home_addresses_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            } else {
                items(state.addresses, key = { it.address }) { address ->
                    AddressPanel(
                        address = address,
                        port = state.primaryPort,
                        onCopy = { text ->
                            clipboard.setText(AnnotatedString(text))
                            viewModel.showMessage(context.getString(R.string.home_copied, text))
                        },
                    )
                }
            }

            // 凭据紧跟在地址后面：用户是一次性把这几行抄到游戏机上的，
            // 分散到设置页里会让他抄完地址才发现还缺账号密码
            state.primaryAuth?.let { auth ->
                item {
                    CredentialsPanel(
                        auth = auth,
                        onCopy = { label, text ->
                            clipboard.setText(AnnotatedString(text))
                            viewModel.showMessage(
                                context.getString(R.string.home_copied_field, label),
                            )
                        },
                    )
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle(
                        stringResource(R.string.home_inbounds_title),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onAddInbound) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = stringResource(R.string.home_inbound_add),
                        )
                    }
                }
            }
            items(state.inbounds, key = { it.id }) { inbound ->
                InboundPanel(
                    inbound = inbound,
                    onClick = { onEditInbound(inbound.id) },
                    onToggle = { viewModel.setInboundEnabled(inbound.id, it) },
                )
            }

            item {
                Text(
                    text = stringResource(R.string.home_core_version, state.coreVersion),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                // NavHost 铺满整个 Scaffold，底部导航栏压在内容之上，
                // 不减掉这段高度提示会被它盖住
                .padding(
                    start = 12.dp,
                    end = 12.dp,
                    bottom = contentPadding.calculateBottomPadding() + 8.dp,
                ),
        )
    }
}

@Composable
private fun PowerPanel(state: HomeUiState, onToggle: () -> Unit) {
    val hazeState = LocalHazeState.current
    val proxyState = state.proxyState

    val statusColor by animateColorAsState(
        targetValue = when (proxyState) {
            is ProxyState.Running -> StatusColors.running
            ProxyState.Starting, ProxyState.Stopping -> StatusColors.connecting
            is ProxyState.Failed -> StatusColors.error
            ProxyState.Stopped -> StatusColors.stopped
        },
        label = "statusColor",
    )

    val label = stringResource(
        when (proxyState) {
            is ProxyState.Running -> R.string.home_state_running
            ProxyState.Starting -> R.string.home_state_starting
            ProxyState.Stopping -> R.string.home_state_stopping
            is ProxyState.Failed -> R.string.home_state_failed
            ProxyState.Stopped -> R.string.home_state_stopped
        },
    )

    GlassPanel(hazeState = hazeState, modifier = Modifier.fillMaxWidth(), contentPadding = 24.dp) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            GlowCircle(color = statusColor, modifier = Modifier.size(104.dp)) {
                if (proxyState == ProxyState.Starting || proxyState == ProxyState.Stopping) {
                    CircularProgressIndicator(color = statusColor, strokeWidth = 3.dp)
                } else {
                    Icon(
                        imageVector = Icons.Default.PowerSettingsNew,
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(46.dp),
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = statusColor,
            )

            Spacer(Modifier.height(16.dp))
            FilledTonalButton(
                onClick = onToggle,
                enabled = proxyState != ProxyState.Starting && proxyState != ProxyState.Stopping,
            ) {
                Text(
                    stringResource(
                        if (proxyState.isActive) R.string.home_stop else R.string.home_start,
                    ),
                )
            }

            if (proxyState is ProxyState.Running) {
                Spacer(Modifier.height(18.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    SpeedStat(
                        icon = Icons.Outlined.NorthEast,
                        label = stringResource(R.string.home_traffic_upload),
                        bytesPerSecond = state.traffic.uploadBytesPerSecond,
                    )
                    SpeedStat(
                        icon = Icons.Outlined.SouthWest,
                        label = stringResource(R.string.home_traffic_download),
                        bytesPerSecond = state.traffic.downloadBytesPerSecond,
                    )
                }
                Spacer(Modifier.height(12.dp))
                TrafficSparkline(samples = state.trafficSamples)
            }
        }
    }
}

/**
 * 最近一分钟的速率。
 *
 * 只给一分钟、不给坐标轴，是因为这条曲线要回答的问题就只有一个：
 * 「刚才那下卡顿，是我这边断了还是对面慢了」。要看月度用量该去别的地方。
 */
@Composable
private fun TrafficSparkline(samples: TrafficSamples) {
    val uploadColor = StatusColors.connecting
    val downloadColor = MaterialTheme.colorScheme.primary

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.home_traffic_chart_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (samples.peak > 0) {
                Text(
                    text = formatSpeed(samples.peak),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            if (samples.isPlottable) {
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
            } else {
                // 空白区域和「速率一直是 0」长得一样，得说清楚现在是哪一种
                SparklinePlaceholder(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.fillMaxSize(),
                )
                Text(
                    text = stringResource(R.string.home_traffic_chart_waiting),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

@Composable
private fun SpeedStat(icon: ImageVector, label: String, bytesPerSecond: Long) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatSpeed(bytesPerSecond),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/**
 * 已连接设备。
 *
 * 连接监控页早就有这些信息，但它按流量排序、埋在「更多 → 连接监控」两层之下。
 * 用户拿着手柄在电视机前调 Switch 的代理设置时，不会想到去那里翻。
 */
@Composable
private fun ConnectedDevicesPanel(devices: List<ConnectedDevice>, onClick: () -> Unit) {
    GlassPanel(
        hazeState = LocalHazeState.current,
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        contentPadding = 16.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.Devices,
                contentDescription = null,
                tint = if (devices.isEmpty()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    StatusColors.running
                },
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(14.dp))
            Text(
                text = stringResource(R.string.home_devices_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.home_devices_count, devices.size),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (devices.isEmpty()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    StatusColors.running
                },
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Outlined.ArrowForwardIos,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (devices.isEmpty()) {
            Text(
                // 「本机 App 不走代理」在这里再说一次是有意的：用户看到 0 台
                // 设备时的第一反应是「坏了」，而正确答案往往是「还没去配另一台」
                text = stringResource(R.string.home_devices_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        } else {
            devices.take(MAX_LISTED_DEVICES).forEach { device ->
                Text(
                    text = stringResource(
                        R.string.home_devices_line,
                        device.address,
                        device.connectionCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (devices.size > MAX_LISTED_DEVICES) {
                Text(
                    text = stringResource(
                        R.string.home_devices_more,
                        devices.size - MAX_LISTED_DEVICES,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

private const val MAX_LISTED_DEVICES = 3

@Composable
private fun OutboundPanel(state: HomeUiState, onClick: () -> Unit) {
    val hazeState = LocalHazeState.current
    GlassPanel(
        hazeState = hazeState,
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        contentPadding = 16.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.home_outbound_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = when (val outbound = state.outbound) {
                        OutboundLabel.RelayOnly ->
                            stringResource(R.string.home_outbound_relay_only)
                        OutboundLabel.Auto -> stringResource(R.string.home_outbound_auto)
                        OutboundLabel.Direct -> stringResource(R.string.home_outbound_direct)
                        OutboundLabel.None -> stringResource(R.string.home_outbound_none)
                        is OutboundLabel.Node -> outbound.name
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                if (state.isRelayOnly) {
                    Text(
                        text = stringResource(R.string.home_outbound_add_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = stringResource(R.string.home_outbound_nodes, state.nodeCount),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Outlined.ArrowForwardIos,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AddressPanel(
    address: LocalAddress,
    port: Int?,
    onCopy: (String) -> Unit,
) {
    val hazeState = LocalHazeState.current
    val text = if (port != null) "${address.hostForUrl}:$port" else address.address

    GlassPanel(
        hazeState = hazeState,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = 14.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${address.kind.label} · ${address.interfaceName}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = text,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                )
            }
            IconButton(onClick = { onCopy(text) }) {
                Icon(
                    Icons.Default.ContentCopy,
                    // 同屏可能有好几个地址卡片，只念「复制」读屏用户分不清是哪一个
                    contentDescription = stringResource(R.string.home_copy_field, text),
                )
            }
        }
    }
}

/**
 * 代理认证的账号密码。
 *
 * 默认入站带随机凭据而不是免认证：监听 0.0.0.0 意味着同网段任何人都能扫到，
 * 免认证的话不只是白嫖流量，配合「私有 IP 走直连」的路由还能拿这台手机
 * 当跳板打进整个局域网。所以这两行必须和地址一样醒目、一样好复制。
 */
@Composable
private fun CredentialsPanel(
    auth: InboundAuth,
    onCopy: (label: String, text: String) -> Unit,
) {
    GlassPanel(
        hazeState = LocalHazeState.current,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = 14.dp,
    ) {
        val usernameLabel = stringResource(R.string.home_credentials_username)
        val passwordLabel = stringResource(R.string.home_credentials_password)
        Column {
            Text(
                text = stringResource(R.string.home_credentials_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            CredentialRow(usernameLabel, auth.username) { onCopy(usernameLabel, auth.username) }
            CredentialRow(passwordLabel, auth.password) { onCopy(passwordLabel, auth.password) }
        }
    }
}

@Composable
private fun CredentialRow(label: String, value: String, onCopy: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(52.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onCopy) {
            Icon(
                Icons.Default.ContentCopy,
                contentDescription = stringResource(R.string.home_copy_field, label),
            )
        }
    }
}

@Composable
private fun InboundPanel(
    inbound: InboundService,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val hazeState = LocalHazeState.current
    val typeLabel = stringResource(inbound.type.labelRes())
    val toggleLabel = stringResource(R.string.home_inbound_toggle, typeLabel)
    GlassPanel(
        hazeState = hazeState,
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        contentPadding = 14.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (inbound.auth != null) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
                contentDescription = null,
                tint = if (inbound.isExposedWithoutAuth) {
                    StatusColors.connecting
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(typeLabel, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "${inbound.listen}:${inbound.listenPort}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = inbound.enabled,
                onCheckedChange = onToggle,
                // 首页可能列着 SOCKS5、HTTP、PAC 三个入站，三个开关不带标签
                // 时读屏软件只会念「开关，已开启」，念三遍
                modifier = Modifier.semantics {
                    contentDescription = toggleLabel
                },
            )
        }
    }
}

/**
 * 已连续运行多久，以及期间被打断过几次。
 *
 * 这两个数字在此之前一个都没有，导致「保活到底行不行」是一个无法证伪的问题 ——
 * 用户只能凭感觉说「好像不太稳」，而那种说法既没法排查也没法反驳。
 *
 * 有了它们，「感觉不稳」会变成「已运行 6 小时、期间自动恢复 3 次」，
 * 后者直接指向该去开电池优化还是厂商自启动白名单。
 */
@Composable
private fun UptimePanel(startedAt: Long, interruptions: Int) {
    // 时长得自己走，否则这个数字会一直停在进入页面的那一刻。
    // 显示精度到分钟，所以半分钟刷一次绰绰有余，不必每秒唤醒。
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt) {
        while (true) {
            delay(UPTIME_TICK_MS)
            now = System.currentTimeMillis()
        }
    }

    GlassPanel(
        hazeState = LocalHazeState.current,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = 14.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.home_uptime_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formatDuration(now - startedAt),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (interruptions > 0) {
                Text(
                    text = stringResource(R.string.home_uptime_interruptions, interruptions),
                    style = MaterialTheme.typography.labelMedium,
                    color = StatusColors.connecting,
                )
            }
        }
    }
}

/** 分钟精度就够了：这个数字是用来看「稳不稳」的，不是秒表。 */
@Composable
private fun formatDuration(millis: Long): String {
    val totalMinutes = (millis / MILLIS_PER_MINUTE).coerceAtLeast(0)
    val days = totalMinutes / MINUTES_PER_DAY
    val hours = (totalMinutes % MINUTES_PER_DAY) / MINUTES_PER_HOUR
    val minutes = totalMinutes % MINUTES_PER_HOUR
    return when {
        days > 0 -> stringResource(R.string.home_duration_days, days, hours)
        hours > 0 -> stringResource(R.string.home_duration_hours, hours, minutes)
        else -> stringResource(R.string.home_duration_minutes, minutes)
    }
}

private const val MILLIS_PER_MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60L
private const val MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR

private const val UPTIME_TICK_MS = 30_000L

/**
 * 启动失败，附一个「别再试了」。
 *
 * 与 [WarningPanel] 分开是因为这条需要一个动作而不是一次跳转：自动重试与看门狗
 * 会持续在后台尝试，用户得有办法叫停，而不是只能眼看着通知每 15 分钟回来一次。
 */
@Composable
private fun FailurePanel(message: String, onGiveUp: () -> Unit) {
    GlassPanel(
        hazeState = LocalHazeState.current,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = 14.dp,
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = StatusColors.error,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusColors.error,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = onGiveUp, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.home_failure_give_up))
            }
        }
    }
}

@Composable
private fun WarningPanel(
    message: String,
    onClick: (() -> Unit)? = null,
    severe: Boolean = false,
) {
    val hazeState = LocalHazeState.current
    // 「配置不理想」和「你的密码正在明文躺着」不该是同一个颜色
    val tint: Color = if (severe) StatusColors.error else StatusColors.connecting
    GlassPanel(
        hazeState = hazeState,
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        contentPadding = 14.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = if (severe) tint else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            // 只有可点的那条才给箭头，否则用户会去戳那些纯提示的面板
            if (onClick != null) {
                Spacer(Modifier.width(10.dp))
                Icon(
                    Icons.Outlined.ArrowForwardIos,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
