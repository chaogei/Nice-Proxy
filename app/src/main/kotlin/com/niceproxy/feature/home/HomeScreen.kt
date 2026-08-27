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
import androidx.compose.foundation.layout.calculateEndPadding
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
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.niceproxy.core.designsystem.component.GlassPanel
import com.niceproxy.core.designsystem.component.GlowCircle
import com.niceproxy.core.designsystem.component.LocalHazeState
import com.niceproxy.core.designsystem.component.SectionTitle
import com.niceproxy.core.designsystem.component.formatSpeed
import com.niceproxy.core.designsystem.theme.StatusColors
import com.niceproxy.core.model.InboundService
import com.niceproxy.core.service.ProxyState
import com.niceproxy.core.service.network.LocalAddress
import com.niceproxy.keepalive.KeepAlive
import com.niceproxy.keepalive.rememberIgnoringBatteryOptimizations

@Composable
fun HomeScreen(
    contentPadding: PaddingValues,
    onEditInbound: (String) -> Unit,
    onAddInbound: () -> Unit,
    onOpenNodes: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    val layoutDirection = LocalLayoutDirection.current
    val context = LocalContext.current
    val ignoringBatteryOptimizations = rememberIgnoringBatteryOptimizations()

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

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
                    text = "Nice Proxy",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = viewModel::refreshNetworkInfo) {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新网络信息")
                }
            }
        }

        item { PowerPanel(state = state, onToggle = viewModel::toggleProxy) }

        item { OutboundPanel(state = state, onClick = onOpenNodes) }

        // 排在其他警告之前：别的问题只是配置不理想，这一条是「服务随时会整个消失」
        if (state.proxyState.isActive && !ignoringBatteryOptimizations) {
            item {
                WarningPanel(
                    message = "未关闭电池优化：息屏一段时间后代理可能被系统冻结，" +
                        "指向本机的设备会全部断网。点此前往设置。",
                    onClick = { KeepAlive.requestIgnoreBatteryOptimizations(context) },
                )
            }
        }
        if (state.hasExposedInboundWithoutAuth) {
            item { WarningPanel("未启用认证：同一网络下的任何设备都能使用这个代理") }
        }
        if (state.otherVpnActive) {
            item { WarningPanel("检测到其他 VPN 正在运行，出站流量会被它接管") }
        }
        (state.proxyState as? ProxyState.Failed)?.let { failed ->
            item {
                WarningPanel(failed.detail?.let { "${failed.message}：$it" } ?: failed.message)
            }
        }

        item { SectionTitle("在其他设备上填写") }
        if (state.addresses.isEmpty()) {
            item {
                Text(
                    text = "未找到可用的网络接口，请连接 Wi-Fi 或开启热点",
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
                        viewModel.showMessage("已复制 $text")
                    },
                )
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("入站服务", modifier = Modifier.weight(1f))
                IconButton(onClick = onAddInbound) {
                    Icon(Icons.Default.Add, contentDescription = "添加入站")
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
                text = "sing-box ${state.coreVersion}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp),
            )
        }
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

    val label = when (proxyState) {
        is ProxyState.Running -> "运行中"
        ProxyState.Starting -> "正在启动…"
        ProxyState.Stopping -> "正在停止…"
        is ProxyState.Failed -> "启动失败"
        ProxyState.Stopped -> "已停止"
    }

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
                Text(if (proxyState.isActive) "停止代理" else "启动代理")
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
                        label = "上传",
                        bytesPerSecond = state.traffic.uploadBytesPerSecond,
                    )
                    SpeedStat(
                        icon = Icons.Outlined.SouthWest,
                        label = "下载",
                        bytesPerSecond = state.traffic.downloadBytesPerSecond,
                    )
                }
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
                    text = "出站",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = state.outboundLabel,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                if (state.isRelayOnly) {
                    Text(
                        text = "点击添加节点后即可代理到上游",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = "${state.nodeCount} 个节点",
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
                Icon(Icons.Default.ContentCopy, contentDescription = "复制")
            }
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
                Text(inbound.type.displayName, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "${inbound.listen}:${inbound.listenPort}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = inbound.enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun WarningPanel(message: String, onClick: (() -> Unit)? = null) {
    val hazeState = LocalHazeState.current
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
                    tint = StatusColors.connecting,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
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
