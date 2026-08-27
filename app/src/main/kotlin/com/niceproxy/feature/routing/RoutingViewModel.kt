package com.niceproxy.feature.routing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.niceproxy.core.data.RoutingRepository
import com.niceproxy.core.datastore.SettingsDataStore
import com.niceproxy.core.model.RoutingMode
import com.niceproxy.core.model.RoutingRule
import com.niceproxy.core.model.RuleSetRef
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RoutingUiState(
    val mode: RoutingMode = RoutingMode.BYPASS_MAINLAND,
    val rules: List<RoutingRule> = emptyList(),
    val ruleSets: List<RuleSetRef> = emptyList(),
)

@HiltViewModel
class RoutingViewModel @Inject constructor(
    private val repository: RoutingRepository,
    private val settings: SettingsDataStore,
) : ViewModel() {

    val uiState: StateFlow<RoutingUiState> = combine(
        settings.routingMode,
        repository.rules,
        repository.ruleSets,
    ) { mode, rules, ruleSets ->
        RoutingUiState(mode = mode, rules = rules, ruleSets = ruleSets)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RoutingUiState())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /**
     * 切换分流模式。
     *
     * 自定义模式只改记号、不动规则，用户手写的规则不会被模板覆盖。
     * 切到预设模式则整体替换，因为模板的价值就是「一键回到已知状态」。
     */
    fun setMode(mode: RoutingMode) {
        viewModelScope.launch {
            settings.setRoutingMode(mode)
            if (mode != RoutingMode.CUSTOM) {
                repository.applyTemplate(mode)
                _message.value = "已套用「${mode.displayName}」"
            }
        }
    }

    fun setRuleEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            repository.setRuleEnabled(id, enabled)
            markCustom()
        }
    }

    fun deleteRule(id: String) {
        viewModelScope.launch {
            repository.deleteRule(id)
            markCustom()
        }
    }

    fun moveRule(from: Int, to: Int) {
        val current = uiState.value.rules.toMutableList()
        if (from !in current.indices || to !in current.indices) return
        current.add(to, current.removeAt(from))
        viewModelScope.launch {
            repository.reorder(current)
            markCustom()
        }
    }

    /**
     * 用户一旦手工改动规则，就不该再声称自己处于某个预设模式 ——
     * 否则下次进页面看到「绕过大陆」高亮，实际规则却已被改过，会造成误解。
     */
    private suspend fun markCustom() {
        settings.setRoutingMode(RoutingMode.CUSTOM)
    }

    fun consumeMessage() {
        _message.value = null
    }
}
