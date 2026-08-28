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

    private val _message = MutableStateFlow<RoutingMode?>(null)

    /**
     * 刚刚套用了哪个模板，界面据此显示一条提示。
     *
     * 存枚举而不是拼好的句子：ViewModel 里没有 `Context`，拼出来的只能是写死
     * 的中文，而 `RoutingMode.displayName` 本身也是 `core:model` 里的中文常量。
     */
    val message: StateFlow<RoutingMode?> = _message.asStateFlow()

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
                _message.value = mode
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
        if (from !in current.indices || to !in current.indices || from == to) return
        current.add(to, current.removeAt(from))
        viewModelScope.launch {
            repository.reorder(current)
            markCustom()
        }
    }

    /**
     * 一步挪到列表两端。
     *
     * 分流规则「命中即停」，所以位置就是优先级。二三十条规则的列表里，
     * 靠反复点上移把一条规则送到最前面要点二三十次，中途还会因为列表
     * 重排而看丢自己在操作哪一条。
     */
    fun moveRuleToTop(index: Int) = moveRule(index, 0)

    fun moveRuleToBottom(index: Int) = moveRule(index, uiState.value.rules.lastIndex)

    /**
     * 「套用模板时保留」。
     *
     * 刻意**不**调用 [markCustom]：这一位只影响下次套用预设时留不留它，
     * 不改变任何一条流量的走向。把它算作「用户改过规则」会让预设模式的
     * 高亮无端消失，而用户并没有动过分流本身。
     */
    fun setRuleLocked(id: String, locked: Boolean) {
        viewModelScope.launch { repository.setRuleLocked(id, locked) }
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
