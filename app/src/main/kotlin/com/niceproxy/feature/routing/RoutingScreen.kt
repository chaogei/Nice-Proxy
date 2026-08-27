package com.niceproxy.feature.routing

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.niceproxy.core.designsystem.component.GlassPanel
import com.niceproxy.core.designsystem.component.LocalHazeState
import com.niceproxy.core.designsystem.component.SectionTitle
import com.niceproxy.core.model.RoutingMode
import com.niceproxy.core.model.RoutingRule
import com.niceproxy.core.model.RuleAction
import com.niceproxy.core.model.WellKnownTag

@Composable
fun RoutingScreen(
    contentPadding: PaddingValues,
    onEditRule: (String) -> Unit,
    onAddRule: () -> Unit,
    viewModel: RoutingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var banner by remember { mutableStateOf<String?>(null) }
    var pendingMode by remember { mutableStateOf<RoutingMode?>(null) }
    var pendingDeleteRule by remember { mutableStateOf<RoutingRule?>(null) }

    LaunchedEffect(message) {
        message?.let {
            banner = it
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
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "分流",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onAddRule) {
                    Icon(Icons.Default.Add, contentDescription = "添加规则")
                }
            }
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RoutingMode.entries.forEach { mode ->
                    FilterChip(
                        selected = state.mode == mode,
                        onClick = {
                            // 点一下模式 chip 会整体替换规则列表，而唯一说明这件事的
                            // 文案躺在规则编辑页里 —— 只有已经知道的人才看得到。
                            // 有东西会被删掉时必须先问一句。
                            if (mode.replacesRules(state)) {
                                pendingMode = mode
                            } else {
                                viewModel.setMode(mode)
                            }
                        },
                        label = { Text(mode.displayName) },
                    )
                }
            }
        }

        item {
            Text(
                text = state.mode.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        item { SectionTitle("规则（自上而下匹配，命中即停）") }

        if (state.rules.isEmpty()) {
            item {
                Text(
                    text = if (state.mode == RoutingMode.GLOBAL_PROXY) {
                        "全局代理模式无需规则，所有流量都走上游节点。"
                    } else {
                        "还没有规则。选一个预设模式，或手动添加。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp, horizontal = 4.dp),
                )
            }
        } else {
            itemsIndexed(state.rules, key = { _, rule -> rule.id }) { index, rule ->
                RuleRow(
                    rule = rule,
                    canMoveUp = index > 0,
                    canMoveDown = index < state.rules.lastIndex,
                    onClick = { onEditRule(rule.id) },
                    onToggle = { viewModel.setRuleEnabled(rule.id, it) },
                    onMoveUp = { viewModel.moveRule(index, index - 1) },
                    onMoveDown = { viewModel.moveRule(index, index + 1) },
                    onDelete = { pendingDeleteRule = rule },
                )
            }
        }

        if (state.ruleSets.isNotEmpty()) {
            item { SectionTitle("规则集") }
            items(state.ruleSets, key = { it.id }) { ruleSet ->
                GlassPanel(
                    hazeState = LocalHazeState.current,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = 12.dp,
                ) {
                    Text(ruleSet.tag, style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = ruleSet.url ?: ruleSet.path.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }

    pendingMode?.let { mode ->
        val doomed = state.rules.count { !it.locked }
        AlertDialog(
            onDismissRequest = { pendingMode = null },
            title = { Text("套用「${mode.displayName}」") },
            text = {
                Text(
                    "预设模式会整体替换规则列表：当前 $doomed 条未锁定的规则将被删除，" +
                        "已锁定的会保留。此操作不可撤销。\n\n" +
                        "想留下某条手写规则，可以先取消，打开那条规则的" +
                        "「套用模板时保留」再回来。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setMode(mode)
                    pendingMode = null
                }) { Text("套用") }
            },
            dismissButton = {
                TextButton(onClick = { pendingMode = null }) { Text("取消") }
            },
        )
    }

    pendingDeleteRule?.let { rule ->
        AlertDialog(
            onDismissRequest = { pendingDeleteRule = null },
            title = { Text("删除规则") },
            text = {
                Text(
                    "「${rule.name}」将被删除，此操作不可撤销。" +
                        "命中它的流量会改由后面的规则或默认出站处理。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteRule(rule.id)
                    pendingDeleteRule = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteRule = null }) { Text("取消") }
            },
        )
    }
}

/**
 * 切到这个模式会不会真的删掉东西。
 *
 * 自定义模式只改记号不动规则；已经在这个模式上、或者根本没有未锁定的规则时，
 * 弹确认框只是徒增一次点击。
 */
private fun RoutingMode.replacesRules(state: RoutingUiState): Boolean =
    this != state.mode && this != RoutingMode.CUSTOM && state.rules.any { !it.locked }

@Composable
private fun RuleRow(
    rule: RoutingRule,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    GlassPanel(
        hazeState = LocalHazeState.current,
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        contentPadding = 12.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(rule.name, style = MaterialTheme.typography.titleSmall)
                    if (rule.locked) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = "套用模板时保留",
                            modifier = Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    text = "${rule.matcher.summary()} → ${rule.action.summary()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            // 四个控件原来挤在一起，其中三个小于 48dp，而最靠边那个还是不可逆的删除。
            // 上移/下移改回默认 48dp，删除挪进溢出菜单并加确认。
            Column {
                IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                    Icon(
                        Icons.Default.ArrowUpward,
                        contentDescription = "上移",
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                    Icon(
                        Icons.Default.ArrowDownward,
                        contentDescription = "下移",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            Switch(checked = rule.enabled, onCheckedChange = onToggle)
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "更多操作",
                        modifier = Modifier.size(18.dp),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("删除", color = MaterialTheme.colorScheme.error) },
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

/** 把匹配条件压缩成一行可读描述，列表里不需要展示完整细节。 */
private fun com.niceproxy.core.model.RuleMatcher.summary(): String {
    val parts = buildList {
        if (ruleSet.isNotEmpty()) add("规则集 ${ruleSet.joinToString()}")
        if (domainSuffix.isNotEmpty()) add("域名后缀 ${domainSuffix.take(2).joinToString()}")
        if (domain.isNotEmpty()) add("域名 ${domain.take(2).joinToString()}")
        if (domainKeyword.isNotEmpty()) add("关键词 ${domainKeyword.take(2).joinToString()}")
        if (ipCidr.isNotEmpty()) add("IP ${ipCidr.take(2).joinToString()}")
        if (sourceIpCidr.isNotEmpty()) add("来源 ${sourceIpCidr.take(2).joinToString()}")
        if (port.isNotEmpty()) add("端口 ${port.joinToString()}")
        if (ipIsPrivate == true) add("局域网地址")
        if (protocol.isNotEmpty()) add("协议 ${protocol.joinToString()}")
    }
    return if (parts.isEmpty()) "全部流量" else parts.joinToString("，")
}

private fun RuleAction.summary(): String = when (this) {
    is RuleAction.Route -> when (outboundTag) {
        WellKnownTag.DIRECT -> "直连"
        WellKnownTag.PROXY -> "代理"
        else -> outboundTag
    }
    is RuleAction.Reject -> "拒绝"
    RuleAction.HijackDns -> "接管 DNS"
    is RuleAction.Sniff -> "嗅探"
    is RuleAction.Resolve -> "解析域名"
}
