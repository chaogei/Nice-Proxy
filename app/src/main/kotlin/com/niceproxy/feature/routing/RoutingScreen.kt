package com.niceproxy.feature.routing

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
                        onClick = { viewModel.setMode(mode) },
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
                    onDelete = { viewModel.deleteRule(rule.id) },
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
}

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
            Column {
                IconButton(
                    onClick = onMoveUp,
                    enabled = canMoveUp,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        Icons.Default.ArrowUpward,
                        contentDescription = "上移",
                        modifier = Modifier.size(16.dp),
                    )
                }
                IconButton(
                    onClick = onMoveDown,
                    enabled = canMoveDown,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        Icons.Default.ArrowDownward,
                        contentDescription = "下移",
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            Switch(checked = rule.enabled, onCheckedChange = onToggle)
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "删除",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
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
