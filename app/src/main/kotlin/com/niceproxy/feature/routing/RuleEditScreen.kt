package com.niceproxy.feature.routing

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.niceproxy.R
import com.niceproxy.core.data.RoutingRepository
import com.niceproxy.core.data.ServerRepository
import com.niceproxy.core.designsystem.component.GlassPanel
import com.niceproxy.core.designsystem.component.LocalHazeState
import com.niceproxy.core.model.RuleAction
import com.niceproxy.core.model.RuleMatcher
import com.niceproxy.core.model.WellKnownTag
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 出站选项。
 *
 * `proxy` / `direct` / `auto` 是配置生成器保留的固定 tag，界面上得显示成
 * 「代理」「直连」「自动选择」并跟着语言走；其余是用户给自己节点起的名字，
 * 原样显示。用 `Pair<String, String>` 的话这两种东西长得一模一样，
 * ViewModel 就只能自己拼中文，而 ViewModel 里没有 `Context`。
 */
sealed interface OutboundChoice {
    val tag: String

    data class WellKnown(override val tag: String, @StringRes val labelRes: Int) : OutboundChoice

    data class Node(override val tag: String, val name: String) : OutboundChoice
}

data class RuleEditUiState(
    val isNew: Boolean = true,
    val name: String = "",
    val domainSuffix: String = "",
    val domainKeyword: String = "",
    val ipCidr: String = "",
    val sourceIpCidr: String = "",
    val outboundTag: String = WellKnownTag.PROXY,
    val reject: Boolean = false,
    val locked: Boolean = false,
    val availableOutbounds: List<OutboundChoice> = emptyList(),
    val finished: Boolean = false,
) {
    val canSave: Boolean
        get() = name.isNotBlank() && listOf(domainSuffix, domainKeyword, ipCidr, sourceIpCidr)
            .any { it.isNotBlank() }
}

@HiltViewModel
class RuleEditViewModel @Inject constructor(
    private val repository: RoutingRepository,
    private val serverRepository: ServerRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val ruleId: String? = savedStateHandle.get<String>("ruleId")
        ?.takeIf { it.isNotBlank() && it != "new" }

    private val _uiState = MutableStateFlow(RuleEditUiState(isNew = ruleId == null))
    val uiState: StateFlow<RuleEditUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val outbounds = buildList<OutboundChoice> {
                add(
                    OutboundChoice.WellKnown(
                        WellKnownTag.PROXY,
                        R.string.routing_action_proxy,
                    ),
                )
                add(
                    OutboundChoice.WellKnown(
                        WellKnownTag.DIRECT,
                        R.string.routing_action_direct,
                    ),
                )
                add(
                    OutboundChoice.WellKnown(
                        WellKnownTag.AUTO,
                        R.string.routing_outbound_auto,
                    ),
                )
                serverRepository.getAll().forEach {
                    add(OutboundChoice.Node(it.outboundTag, it.name))
                }
            }
            _uiState.update { it.copy(availableOutbounds = outbounds) }

            ruleId?.let { id ->
                repository.getRules().firstOrNull { it.id == id }?.let { rule ->
                    _uiState.update {
                        it.copy(
                            isNew = false,
                            name = rule.name,
                            domainSuffix = rule.matcher.domainSuffix.joinToString("\n"),
                            domainKeyword = rule.matcher.domainKeyword.joinToString("\n"),
                            ipCidr = rule.matcher.ipCidr.joinToString("\n"),
                            sourceIpCidr = rule.matcher.sourceIpCidr.joinToString("\n"),
                            outboundTag = (rule.action as? RuleAction.Route)?.outboundTag
                                ?: WellKnownTag.PROXY,
                            reject = rule.action is RuleAction.Reject,
                            locked = rule.locked,
                        )
                    }
                }
            }
        }
    }

    /**
     * 新建规则时预填一个名字。
     *
     * 这个默认值原本写死在 state 的默认参数里，是个中文字面量 —— 而
     * ViewModel 拿不到 `Context`，翻不了。改由界面把已经本地化好的字符串
     * 送进来。只在「确实是新建」且用户还没输入时生效，否则重进页面（配置
     * 变更导致的重组也会触发）会把用户打了一半的名字盖掉。
     */
    fun seedDefaultName(value: String) = _uiState.update {
        if (it.isNew && it.name.isEmpty()) it.copy(name = value) else it
    }

    fun setName(value: String) = _uiState.update { it.copy(name = value) }
    fun setDomainSuffix(value: String) = _uiState.update { it.copy(domainSuffix = value) }
    fun setDomainKeyword(value: String) = _uiState.update { it.copy(domainKeyword = value) }
    fun setIpCidr(value: String) = _uiState.update { it.copy(ipCidr = value) }
    fun setSourceIpCidr(value: String) = _uiState.update { it.copy(sourceIpCidr = value) }
    fun setOutbound(tag: String) = _uiState.update { it.copy(outboundTag = tag, reject = false) }
    fun setReject() = _uiState.update { it.copy(reject = true) }
    fun setLocked(value: Boolean) = _uiState.update { it.copy(locked = value) }

    fun save() {
        val state = _uiState.value
        if (!state.canSave) return
        viewModelScope.launch {
            val existing = ruleId?.let { id -> repository.getRules().firstOrNull { it.id == id } }
            val base = existing ?: repository.newRule(repository.getRules().size)
            repository.saveRule(
                base.copy(
                    name = state.name.trim(),
                    matcher = RuleMatcher(
                        domainSuffix = state.domainSuffix.toLines(),
                        domainKeyword = state.domainKeyword.toLines(),
                        ipCidr = state.ipCidr.toLines(),
                        sourceIpCidr = state.sourceIpCidr.toLines(),
                    ),
                    action = if (state.reject) {
                        RuleAction.Reject()
                    } else {
                        RuleAction.Route(state.outboundTag)
                    },
                    locked = state.locked,
                ),
            )
            _uiState.update { it.copy(finished = true) }
        }
    }

    private fun String.toLines(): List<String> =
        split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }
}

@Composable
fun RuleEditScreen(
    onNavigateBack: () -> Unit,
    viewModel: RuleEditViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val defaultName = stringResource(R.string.rule_edit_default_name)

    LaunchedEffect(defaultName) { viewModel.seedDefaultName(defaultName) }

    LaunchedEffect(state.finished) {
        if (state.finished) onNavigateBack()
    }

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
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.common_back),
                )
            }
            Text(
                text = stringResource(
                    if (state.isNew) R.string.rule_edit_title_new else R.string.rule_edit_title_edit,
                ),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }

        GlassPanel(
            hazeState = LocalHazeState.current,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = 16.dp,
        ) {
            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::setName,
                label = { Text(stringResource(R.string.rule_edit_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        GlassPanel(
            hazeState = LocalHazeState.current,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = 16.dp,
        ) {
            Text(
                text = stringResource(R.string.rule_edit_match_section),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.rule_edit_match_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            MultilineField(
                label = stringResource(R.string.rule_edit_domain_suffix),
                placeholder = "google.com",
                value = state.domainSuffix,
                onChange = viewModel::setDomainSuffix,
            )
            MultilineField(
                label = stringResource(R.string.rule_edit_domain_keyword),
                placeholder = "youtube",
                value = state.domainKeyword,
                onChange = viewModel::setDomainKeyword,
            )
            MultilineField(
                label = stringResource(R.string.rule_edit_ip_cidr),
                placeholder = "8.8.8.0/24",
                value = state.ipCidr,
                onChange = viewModel::setIpCidr,
            )
            MultilineField(
                label = stringResource(R.string.rule_edit_source_ip),
                placeholder = "192.168.1.100/32",
                value = state.sourceIpCidr,
                onChange = viewModel::setSourceIpCidr,
                hint = stringResource(R.string.rule_edit_source_ip_hint),
            )
        }

        GlassPanel(
            hazeState = LocalHazeState.current,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = 16.dp,
        ) {
            Text(
                text = stringResource(R.string.rule_edit_action_section),
                style = MaterialTheme.typography.titleSmall,
            )
            FlowRow(
                modifier = Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.availableOutbounds.forEach { choice ->
                    FilterChip(
                        selected = !state.reject && state.outboundTag == choice.tag,
                        onClick = { viewModel.setOutbound(choice.tag) },
                        label = {
                            Text(
                                when (choice) {
                                    is OutboundChoice.WellKnown -> stringResource(choice.labelRes)
                                    is OutboundChoice.Node -> choice.name
                                },
                            )
                        },
                    )
                }
                FilterChip(
                    selected = state.reject,
                    onClick = viewModel::setReject,
                    label = { Text(stringResource(R.string.routing_action_reject)) },
                )
            }
        }

        GlassPanel(
            hazeState = LocalHazeState.current,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = 16.dp,
        ) {
            val lockedLabel = stringResource(R.string.rule_edit_locked_title)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(lockedLabel, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = stringResource(R.string.rule_edit_locked_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.locked,
                    onCheckedChange = viewModel::setLocked,
                    modifier = Modifier.semantics { contentDescription = lockedLabel },
                )
            }
        }

        Button(
            onClick = viewModel::save,
            enabled = state.canSave,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.common_save))
        }
    }
}

@Composable
private fun MultilineField(
    label: String,
    placeholder: String,
    value: String,
    onChange: (String) -> Unit,
    hint: String? = null,
) {
    Column(modifier = Modifier.padding(bottom = 10.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            placeholder = { Text(placeholder) },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        hint?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp),
            )
        }
    }
}
