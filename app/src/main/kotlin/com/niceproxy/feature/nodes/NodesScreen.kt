package com.niceproxy.feature.nodes

import androidx.annotation.StringRes
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.niceproxy.R
import com.niceproxy.core.designsystem.component.FlatPanel
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
import com.niceproxy.util.copyToClipboard
import kotlinx.coroutines.launch

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
    val context = LocalContext.current

    var showSubscriptionDialog by remember { mutableStateOf(false) }
    var showOverflow by remember { mutableStateOf(false) }
    var pendingDeleteGroup by remember { mutableStateOf<ServerGroup?>(null) }
    var pendingDeleteNode by remember { mutableStateOf<ServerProfile?>(null) }
    var pendingBulkDelete by remember { mutableStateOf<BulkDelete?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 导入结果以前是插在搜索框下面的一张卡片。可这些提示恰恰是在列表底部
    // 忙活时冒出来的 —— 批量测延迟、删重复节点 —— 那时它在屏幕外面。
    LaunchedEffect(message) {
        message?.let {
            viewModel.consumeMessage()
            snackbarHostState.showSnackbar(it.resolve(context))
        }
    }

    LaunchedEffect(scanResult) {
        scanResult?.let { snackbarHostState.showSnackbar(it) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
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
                        text = stringResource(R.string.nodes_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onScan) {
                        Icon(
                            Icons.Default.QrCodeScanner,
                            contentDescription = stringResource(R.string.nodes_scan),
                        )
                    }
                    IconButton(
                        onClick = { viewModel.importFromClipboard(clipboard.getText()?.text.orEmpty()) },
                    ) {
                        Icon(
                            Icons.Default.ContentPaste,
                            contentDescription = stringResource(R.string.nodes_import_clipboard),
                        )
                    }
                    IconButton(onClick = { showSubscriptionDialog = true }) {
                        Icon(
                            Icons.Default.RssFeed,
                            contentDescription = stringResource(R.string.nodes_add_subscription),
                        )
                    }
                    IconButton(onClick = onAddNode) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = stringResource(R.string.nodes_add_manual),
                        )
                    }
                    Box {
                        IconButton(onClick = { showOverflow = true }) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.common_more_actions),
                            )
                        }
                        DropdownMenu(
                            expanded = showOverflow,
                            onDismissRequest = { showOverflow = false },
                        ) {
                            BulkDelete.entries.forEach { action ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(action.labelRes)) },
                                    onClick = {
                                        pendingBulkDelete = action
                                        showOverflow = false
                                    },
                                )
                            }
                        }
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    placeholder = { Text(stringResource(R.string.nodes_search_placeholder)) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = if (state.query.isNotEmpty()) {
                        {
                            IconButton(onClick = { viewModel.setQuery("") }) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(R.string.common_clear),
                                )
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
                        text = stringResource(R.string.nodes_sort),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    NodeSort.entries.forEach { option ->
                        FilterChip(
                            selected = state.sort == option,
                            onClick = { viewModel.setSort(option) },
                            label = { Text(stringResource(option.labelRes)) },
                        )
                    }
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.nodes_count, state.visibleServers.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    // TCPing 随时可测；真连接延迟更准但要求内核在跑
                    TestMode.entries.forEach { mode ->
                        FilterChip(
                            selected = state.testMode == mode,
                            onClick = { viewModel.setTestMode(mode) },
                            label = { Text(stringResource(mode.labelRes)) },
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
                            Text(
                                stringResource(
                                    R.string.nodes_test_progress,
                                    state.testProgress ?: 0,
                                    state.testTotal,
                                ),
                            )
                        } else {
                            Icon(
                                Icons.Default.Bolt,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.nodes_test))
                        }
                    }
                }
            }

            if (state.testMode == TestMode.REAL && !state.coreRunning) {
                item {
                    Text(
                        text = stringResource(R.string.nodes_real_test_hint),
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
                        text = stringResource(R.string.nodes_empty),
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
                        onDelete = { pendingDeleteNode = node },
                    )
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
                    bottom = contentPadding.calculateBottomPadding() + 8.dp,
                ),
        )
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
                        text = stringResource(R.string.nodes_share_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                val copied = stringResource(R.string.nodes_share_copied)
                Button(onClick = {
                    // 分享链接里带着节点密码，Android 13+ 的剪贴板预览浮层
                    // 会把它明文渲染在屏幕上
                    context.copyToClipboard(
                        label = target.name,
                        text = target.link,
                        sensitive = true,
                    )
                    scope.launch { snackbarHostState.showSnackbar(copied) }
                    viewModel.dismissShare()
                }) { Text(stringResource(R.string.nodes_share_copy)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissShare) {
                    Text(stringResource(R.string.common_close))
                }
            },
        )
    }

    pendingDeleteGroup?.let { group ->
        AlertDialog(
            onDismissRequest = { pendingDeleteGroup = null },
            title = { Text(stringResource(R.string.nodes_delete_group_title)) },
            text = { Text(stringResource(R.string.nodes_delete_group_message, group.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteGroup(group.id)
                    pendingDeleteGroup = null
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteGroup = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    // 删除分组一直有确认，删除单个节点却没有 —— 说明这是漏了，不是设计决定。
    // 手动导入的节点删掉之后没有任何找回途径。
    pendingDeleteNode?.let { node ->
        AlertDialog(
            onDismissRequest = { pendingDeleteNode = null },
            title = { Text(stringResource(R.string.nodes_delete_node_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.nodes_delete_node_message,
                        node.name,
                        node.server,
                        node.serverPort,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteNode(node.id)
                    pendingDeleteNode = null
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteNode = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    pendingBulkDelete?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingBulkDelete = null },
            title = { Text(stringResource(action.labelRes)) },
            text = { Text(stringResource(action.confirmMessageRes)) },
            confirmButton = {
                TextButton(onClick = {
                    when (action) {
                        BulkDelete.DUPLICATES -> viewModel.deleteDuplicates()
                        BulkDelete.INVALID -> viewModel.deleteInvalid()
                    }
                    pendingBulkDelete = null
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingBulkDelete = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

/** 一次删掉一批节点的两个入口，都不可撤销，都必须先问一句。 */
private enum class BulkDelete(
    @StringRes val labelRes: Int,
    @StringRes val confirmMessageRes: Int,
) {
    DUPLICATES(R.string.nodes_bulk_duplicates, R.string.nodes_bulk_duplicates_message),
    INVALID(R.string.nodes_bulk_invalid, R.string.nodes_bulk_invalid_message),
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
            label = { Text(stringResource(R.string.nodes_group_all, totalCount)) },
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
                        text = stringResource(
                            R.string.nodes_subscription_traffic,
                            formatBytes(traffic.usedBytes),
                            formatBytes(traffic.totalBytes),
                        ),
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
                        text = stringResource(R.string.nodes_subscription_last_error, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            IconButton(onClick = onRefresh) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.nodes_subscription_refresh),
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.nodes_subscription_delete),
                )
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
                Text(
                    text = stringResource(R.string.nodes_auto_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.nodes_auto_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = stringResource(R.string.common_selected),
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
    var menuOpen by remember { mutableStateOf(false) }
    // 这一行会被订阅列表复制成几百份，所以用不带模糊的 FlatPanel：
    // 批量测延迟时整张列表每秒重画，省下的是几百次 RenderEffect。
    FlatPanel(
        modifier = Modifier.fillMaxWidth(),
        // 凭据读不出来的节点点了也用不了，只能重新导入 —— 直接把点击引到编辑页
        onClick = if (unreadable) onEdit else onSelect,
        contentPadding = 14.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProtocolBadge(node.protocol.badge)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                // 右侧换成 48dp 的按钮之后名字能占的宽度变窄了，
                // 截断要有省略号，不能直接切在半个字上
                Text(
                    text = node.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (unreadable) {
                    Text(
                        text = stringResource(R.string.nodes_unreadable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                    )
                }
                Text(
                    text = stringResource(
                        R.string.nodes_endpoint,
                        node.server,
                        node.serverPort,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                if (!unreadable) LatencyIndicator(node.latencyMs)
                // 原来这三个是 11sp 的文字，彼此只隔 10dp，手指偏两毫米就把节点删了。
                // 换成 IconButton 拿到默认的 48dp 命中区，删除再往溢出菜单里挪一层。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (selected && !unreadable) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = stringResource(R.string.common_selected),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    // 读屏软件在这一列上会连着念好几遍「分享」「编辑」，不带
                    // 节点名就分不清自己在操作哪一条
                    IconButton(onClick = onShare) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = "${stringResource(R.string.nodes_share)} ${node.name}",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    IconButton(onClick = onEdit) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "${stringResource(R.string.common_edit)} ${node.name}",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription =
                                    "${stringResource(R.string.common_more_actions)} ${node.name}",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.common_delete),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    onDelete()
                                },
                            )
                        }
                    }
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
        title = { Text(stringResource(R.string.nodes_subscription_dialog_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.nodes_subscription_url)) },
                    placeholder = { Text("https://...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.nodes_subscription_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    label = { Text(stringResource(R.string.nodes_subscription_filter)) },
                    placeholder = {
                        Text(stringResource(R.string.nodes_subscription_filter_placeholder))
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.nodes_subscription_filter_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.nodes_subscription_formats),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(url, name, filter) }) {
                Text(stringResource(R.string.nodes_subscription_import))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
