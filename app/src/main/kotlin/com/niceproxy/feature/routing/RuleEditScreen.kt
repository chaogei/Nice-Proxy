package com.niceproxy.feature.routing

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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

data class RuleEditUiState(
    val isNew: Boolean = true,
    val name: String = "新规则",
    val domainSuffix: String = "",
    val domainKeyword: String = "",
    val ipCidr: String = "",
    val sourceIpCidr: String = "",
    val outboundTag: String = WellKnownTag.PROXY,
    val reject: Boolean = false,
    val locked: Boolean = false,
    val availableOutbounds: List<Pair<String, String>> = emptyList(),
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
            val outbounds = buildList {
                add(WellKnownTag.PROXY to "代理")
                add(WellKnownTag.DIRECT to "直连")
                add(WellKnownTag.AUTO to "自动选择")
                serverRepository.getAll().forEach { add(it.outboundTag to it.name) }
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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = if (state.isNew) "新建规则" else "编辑规则",
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
                label = { Text("规则名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        GlassPanel(
            hazeState = LocalHazeState.current,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = 16.dp,
        ) {
            Text("匹配条件", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "每行一项，也可用逗号分隔。多个条件之间是「且」的关系。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            MultilineField("域名后缀", "google.com", state.domainSuffix, viewModel::setDomainSuffix)
            MultilineField("域名关键词", "youtube", state.domainKeyword, viewModel::setDomainKeyword)
            MultilineField("目标 IP 段", "8.8.8.0/24", state.ipCidr, viewModel::setIpCidr)
            MultilineField(
                label = "客户端来源 IP",
                placeholder = "192.168.1.100/32",
                value = state.sourceIpCidr,
                onChange = viewModel::setSourceIpCidr,
                hint = "网关形态独有：让不同设备走不同出站",
            )
        }

        GlassPanel(
            hazeState = LocalHazeState.current,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = 16.dp,
        ) {
            Text("命中后", style = MaterialTheme.typography.titleSmall)
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.availableOutbounds.forEach { (tag, label) ->
                    FilterChip(
                        selected = !state.reject && state.outboundTag == tag,
                        onClick = { viewModel.setOutbound(tag) },
                        label = { Text(label) },
                    )
                }
                FilterChip(
                    selected = state.reject,
                    onClick = viewModel::setReject,
                    label = { Text("拒绝") },
                )
            }
        }

        GlassPanel(
            hazeState = LocalHazeState.current,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = 16.dp,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("套用模板时保留", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "切换分流模式会清空未锁定的规则。手写的规则建议锁定，" +
                            "否则一键切模板就没了。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.locked, onCheckedChange = viewModel::setLocked)
            }
        }

        Button(
            onClick = viewModel::save,
            enabled = state.canSave,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("保存")
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
