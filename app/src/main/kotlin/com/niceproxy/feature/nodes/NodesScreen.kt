package com.niceproxy.feature.nodes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.niceproxy.core.designsystem.component.GlassPanel
import com.niceproxy.core.designsystem.component.LatencyIndicator
import com.niceproxy.core.designsystem.component.LocalHazeState
import com.niceproxy.core.designsystem.component.ProtocolBadge
import com.niceproxy.core.designsystem.component.QrCode
import com.niceproxy.core.designsystem.component.formatBytes
import com.niceproxy.core.model.CredentialState
import com.niceproxy.core.model.GroupType
import com.niceproxy.core.model.ServerGroup
import com.niceproxy.core.model.ServerProfile
import com.niceproxy.core.model.WellKnownTag

@Composable
fun NodesScreen(
    contentPadding: PaddingValues,
    onEditNode: (String) -> Unit,
    onAddNode: () -> Unit,
    onScan: () -> Unit,
    scanResult: String? = null,
    viewModel: NodesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val shareTarget by viewModel.shareTarget.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current

    var showSubscriptionDialog by remember { mutableStateOf(false) }
    var showOverflow by remember { mutableStateOf(false) }
    var pendingDeleteGroup by remember { mutableStateOf<ServerGroup?>(null) }
    var banner by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(message) {
        message?.let {
            banner = it
            viewModel.consumeMessage()
        }
    }

    LaunchedEffect(scanResult) {
        scanResult?.let { banner = it }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 12.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "节点",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onScan) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = "扫码导入")
                }
                IconButton(
                    onClick = { viewModel.importFromClipboard(clipboard.getText()?.text.orEmpty()) },
                ) {
                    Icon(Icons.Default.ContentPaste, contentDescription = "从剪贴板导入")
                }
                IconButton(onClick = { showSubscriptionDialog = true }) {
                    Icon(Icons.Default.RssFeed, contentDescription = "添加订阅")
                }
                IconButton(onClick = onAddNode) {
                    Icon(Icons.Default.Add, contentDescription = "手动添加")
                }
                Box {
                    IconButton(onClick = { showOverflow = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多操作")
                    }
                    DropdownMenu(
                        expanded = showOverflow,
                        onDismissRequest = { showOverflow = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("删除重复节点") },
                            onClick = {
                                viewModel.deleteDuplicates()
                                showOverflow = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("删除测速失败的节点") },
                            onClick = {
                                viewModel.deleteInvalid()
                                showOverflow = false
                            },
                        )
                    }
                }
            }
        }

        item {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                placeholder = { Text("搜索名称或地址") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = if (state.query.isNotEmpty()) {
                    {
                        IconButton(onClick = { viewModel.setQuery("") }) {
                            Icon(Icons.Default.Close, contentDescription = "清除")
                        }
                    }
                } else {
                    null
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (state.isRefreshing) {
            item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
        }

        banner?.let { text ->
            item {
                GlassPanel(
                    hazeState = LocalHazeState.current,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = 12.dp,
                    onClick = { banner = null },
                ) {
                    Text(text, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        item {
            GroupChips(
                groups = state.groups,
                totalCount = state.servers.size,
                selectedGroupId = state.selectedGroupId,
                onSelect = viewModel::selectGroup,
            )
        }

        state.groups.firstOrNull { it.id == state.selectedGroupId }?.let { group ->
            if (group.type == GroupType.SUBSCRIPTION) {
                item {
                    SubscriptionPanel(
                        group = group,
                        onRefresh = { viewModel.refreshSubscription(group.id) },
                        onDelete = { pendingDeleteGroup = group },
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "排序",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                NodeSort.entries.forEach { option ->
                    FilterChip(
                        selected = state.sort == option,
                        onClick = { viewModel.setSort(option) },
                        label = { Text(option.label) },
                    )
                }
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${state.visibleServers.size} 个节点",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                // TCPing 随时可测；真连接延迟更准但要求内核在跑
                TestMode.entries.forEach { mode ->
                    FilterChip(
                        selected = state.testMode == mode,
                        onClick = { viewModel.setTestMode(mode) },
                        label = { Text(mode.label) },
                        enabled = !state.isTesting,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                TextButton(onClick = viewModel::testAll, enabled = !state.isTesting) {
                    if (state.isTesting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("${state.testProgress}/${state.testTotal}")
                    } else {
                        Icon(
                            Icons.Default.Bolt,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("测速")
                    }
                }
            }
        }

        if (state.testMode == TestMode.REAL && !state.coreRunning) {
            item {
                Text(
                    text = "真连接测速需要先启动代理。只想快速筛掉连不上的节点可以用 TCPing。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }

        item {
            AutoSelectRow(
                selected = state.selectedTag == WellKnownTag.AUTO,
                onClick = { viewModel.selectNode(WellKnownTag.AUTO) },
            )
        }

        if (state.visibleServers.isEmpty()) {
            item {
                Text(
                    text = "还没有节点。可以从剪贴板粘贴分享链接，或添加机场订阅。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp, horizontal = 4.dp),
                )
            }
        } else {
            items(state.visibleServers, key = { it.id }) { node ->
                NodeRow(
                    node = node,
                    selected = node.outboundTag == state.selectedTag,
                    onSelect = { viewModel.selectNode(node.outboundTag) },
                    onEdit = { onEditNode(node.id) },
                    onShare = { viewModel.share(node) },
                    onDelete = { viewModel.deleteNode(node.id) },
                )
            }
        }
    }

    if (showSubscriptionDialog) {
        SubscriptionDialog(
            onDismiss = { showSubscriptionDialog = false },
            onConfirm = { url, name, filter ->
                viewModel.addSubscription(url, name, filter)
                showSubscriptionDialog = false
            },
        )
    }

    shareTarget?.let { target ->
        AlertDialog(
            onDismissRequest = viewModel::dismissShare,
            title = { Text(target.name, maxLines = 1) },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    QrCode(content = target.link, modifier = Modifier.size(240.dp))
                    Text(
                        text = "用另一台设备的代理客户端扫码即可导入。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    clipboard.setText(AnnotatedString(target.link))
                    viewModel.dismissShare()
                }) { Text("复制链接") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissShare) { Text("关闭") }
            },
        )
    }

    pendingDeleteGroup?.let { group ->
        AlertDialog(
            onDismissRequest = { pendingDeleteGroup = null },
            title = { Text("删除订阅") },
            text = { Text("将同时删除「${group.name}」下的所有节点，此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteGroup(group.id)
                    pendingDeleteGroup = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteGroup = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun GroupChips(
    groups: List<ServerGroup>,
    totalCount: Int,
    selectedGroupId: String?,
    onSelect: (String?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selectedGroupId == null,
            onClick = { onSelect(null) },
            label = { Text("全部 ($totalCount)") },
        )
        groups.forEach { group ->
            FilterChip(
                selected = selectedGroupId == group.id,
                onClick = { onSelect(group.id) },
                label = { Text(group.name) },
                leadingIcon = if (group.type == GroupType.SUBSCRIPTION) {
                    {
                        Icon(
                            Icons.Default.RssFeed,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
private fun SubscriptionPanel(
    group: ServerGroup,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
) {
    GlassPanel(
        hazeState = LocalHazeState.current,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = 14.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(group.name, style = MaterialTheme.typography.titleSmall)
                group.traffic?.let { traffic ->
                    Text(
                        text = "已用 ${formatBytes(traffic.usedBytes)} / ${formatBytes(traffic.totalBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (traffic.totalBytes > 0) {
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { traffic.usedRatio },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                group.lastError?.let {
                    Text(
                        text = "上次更新失败：$it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, contentDescription = "更新订阅")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "删除订阅")
            }
        }
    }
}

@Composable
private fun AutoSelectRow(selected: Boolean, onClick: () -> Unit) {
    GlassPanel(
        hazeState = LocalHazeState.current,
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        contentPadding = 14.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Bolt,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("自动选择", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "定期测速，始终使用延迟最低的节点",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "已选中",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun NodeRow(
    node: ServerProfile,
    selected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val unreadable = node.credentialState == CredentialState.UNREADABLE
    GlassPanel(
        hazeState = LocalHazeState.current,
        modifier = Modifier.fillMaxWidth(),
        // 凭据读不出来的节点点了也用不了，只能重新导入 —— 直接把点击引到编辑页
        onClick = if (unreadable) onEdit else onSelect,
        contentPadding = 14.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProtocolBadge(node.protocol.badge)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = node.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                )
                if (unreadable) {
                    Text(
                        text = "凭据无法解密，点击重新导入",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                    )
                }
                Text(
                    text = "${node.server}:${node.serverPort}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                if (!unreadable) LatencyIndicator(node.latencyMs)
                Spacer(Modifier.height(4.dp))
                Row {
                    if (selected && !unreadable) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "已选中",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        text = "分享",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(onClick = onShare),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "编辑",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(onClick = onEdit),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "删除",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.clickable(onClick = onDelete),
                    )
                }
            }
        }
    }
}

@Composable
private fun SubscriptionDialog(
    onDismiss: () -> Unit,
    onConfirm: (url: String, name: String, filter: String) -> Unit,
) {
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加订阅") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("订阅地址") },
                    placeholder = { Text("https://...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称（可留空）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    label = { Text("排除的节点名（正则，可留空）") },
                    placeholder = { Text("剩余流量|到期|官网|续费") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "机场常在订阅里塞「剩余流量」「官网地址」这类伪装成节点的公告，" +
                        "填个正则可以直接滤掉。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "支持 Base64 链接列表、Clash YAML、sing-box JSON 与 SIP008。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(url, name, filter) }) { Text("导入") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
