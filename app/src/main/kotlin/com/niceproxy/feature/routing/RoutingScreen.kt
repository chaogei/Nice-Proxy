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
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.niceproxy.R
import com.niceproxy.core.designsystem.component.GlassPanel
import com.niceproxy.core.designsystem.component.LocalHazeState
import com.niceproxy.core.designsystem.component.SectionTitle
import com.niceproxy.core.model.RoutingMode
import com.niceproxy.core.model.RoutingRule

@Composable
fun RoutingScreen(
    contentPadding: PaddingValues,
    onEditRule: (String) -> Unit,
    onAddRule: () -> Unit,
    viewModel: RoutingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var pendingMode by remember { mutableStateOf<RoutingMode?>(null) }
    var pendingDeleteRule by remember { mutableStateOf<RoutingRule?>(null) }
    var pendingUnlockRule by remember { mutableStateOf<RoutingRule?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    val context = LocalContext.current
    // 套用模板的结果以前是插在列表顶部的一张卡片。而触发它的 chip 也在顶部，
    // 唯一看不到那张卡片的人，恰好是滚到下面去检查规则有没有被换掉的人。
    LaunchedEffect(message) {
        message?.let { mode ->
            viewModel.consumeMessage()
            snackbarHostState.showSnackbar(
                context.getString(
                    R.string.routing_applied,
                    context.getString(mode.labelRes()),
                ),
            )
        }
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
                        text = stringResource(R.string.routing_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onAddRule) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = stringResource(R.string.routing_add_rule),
                        )
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
                            label = { Text(stringResource(mode.labelRes())) },
                        )
                    }
                }
            }

            item {
                Text(
                    text = stringResource(state.mode.descriptionRes()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }

            item { SectionTitle(stringResource(R.string.routing_rules_section)) }

            if (state.rules.isEmpty()) {
                item {
                    Text(
                        text = if (state.mode == RoutingMode.GLOBAL_PROXY) {
                            stringResource(R.string.routing_empty_global)
                        } else {
                            stringResource(R.string.routing_empty)
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
                        onMoveToTop = { viewModel.moveRuleToTop(index) },
                        onMoveToBottom = { viewModel.moveRuleToBottom(index) },
                        onLock = { viewModel.setRuleLocked(rule.id, true) },
                        onUnlock = { pendingUnlockRule = rule },
                        onDelete = { pendingDeleteRule = rule },
                    )
                }
            }

            if (state.ruleSets.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.routing_rulesets_section)) }
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

    pendingMode?.let { mode ->
        val doomed = state.rules.count { !it.locked }
        AlertDialog(
            onDismissRequest = { pendingMode = null },
            title = {
                Text(
                    stringResource(
                        R.string.routing_apply_title,
                        stringResource(mode.labelRes()),
                    ),
                )
            },
            text = { Text(stringResource(R.string.routing_apply_message, doomed)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setMode(mode)
                    pendingMode = null
                }) { Text(stringResource(R.string.routing_apply_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingMode = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    pendingDeleteRule?.let { rule ->
        AlertDialog(
            onDismissRequest = { pendingDeleteRule = null },
            title = { Text(stringResource(R.string.routing_delete_title)) },
            text = { Text(stringResource(R.string.routing_delete_message, rule.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteRule(rule.id)
                    pendingDeleteRule = null
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteRule = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    // 解锁本身不改分流，但它的后果要到「下一次套用预设」才显现，
    // 而那时规则已经没了。只有在这里说清楚，用户才有机会反悔。
    pendingUnlockRule?.let { rule ->
        AlertDialog(
            onDismissRequest = { pendingUnlockRule = null },
            title = { Text(stringResource(R.string.routing_unlock_title, rule.name)) },
            text = { Text(stringResource(R.string.routing_unlock_message)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setRuleLocked(rule.id, false)
                    pendingUnlockRule = null
                }) { Text(stringResource(R.string.routing_unlock)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingUnlockRule = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
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
    onMoveToTop: () -> Unit,
    onMoveToBottom: () -> Unit,
    onLock: () -> Unit,
    onUnlock: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    // 读屏软件在这一列上会连着念好几遍「上移」「开关」，不带规则名就分不清
    // 自己在操作哪一条 —— 而这一列每个动作都会改变流量的走向。
    val moveUpLabel = stringResource(R.string.routing_move_up) + " " + rule.name
    val moveDownLabel = stringResource(R.string.routing_move_down) + " " + rule.name
    val toggleLabel = stringResource(R.string.routing_rule_enabled, rule.name)
    val menuLabel = stringResource(R.string.routing_rule_menu, rule.name)

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
                            contentDescription = stringResource(R.string.routing_locked_badge),
                            modifier = Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    text = stringResource(
                        R.string.routing_rule_summary,
                        rule.matcher.summary(),
                        rule.action.label().resolve(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            // 四个控件原来挤在一起，其中三个小于 48dp，而最靠边那个还是不可逆的删除。
            // 上移/下移是默认 48dp 的 IconButton，删除与「移到两端」在溢出菜单里，
            // 删除还要再确认一次。
            Column {
                IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                    Icon(
                        Icons.Default.ArrowUpward,
                        contentDescription = moveUpLabel,
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                    Icon(
                        Icons.Default.ArrowDownward,
                        contentDescription = moveDownLabel,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            Switch(
                checked = rule.enabled,
                onCheckedChange = onToggle,
                modifier = Modifier.semantics { contentDescription = toggleLabel },
            )
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = menuLabel,
                        modifier = Modifier.size(18.dp),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    // 「命中即停」意味着位置就是优先级。二三十条规则里靠点上移
                    // 把一条送到最前面要点二三十次，中途还会看丢是哪一条。
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.routing_move_top)) },
                        leadingIcon = {
                            Icon(Icons.Default.VerticalAlignTop, contentDescription = null)
                        },
                        enabled = canMoveUp,
                        onClick = {
                            menuOpen = false
                            onMoveToTop()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.routing_move_bottom)) },
                        leadingIcon = {
                            Icon(Icons.Default.VerticalAlignBottom, contentDescription = null)
                        },
                        enabled = canMoveDown,
                        onClick = {
                            menuOpen = false
                            onMoveToBottom()
                        },
                    )
                    // 「套用模板时保留」以前只能在规则编辑页里翻到，而用户想到
                    // 要用它的时刻，恰恰是站在这份即将被模板替换掉的列表前面。
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    if (rule.locked) {
                                        R.string.routing_unlock
                                    } else {
                                        R.string.routing_lock
                                    },
                                ),
                            )
                        },
                        leadingIcon = {
                            Icon(
                                if (rule.locked) Icons.Default.LockOpen else Icons.Default.Lock,
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            menuOpen = false
                            if (rule.locked) onUnlock() else onLock()
                        },
                    )
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
