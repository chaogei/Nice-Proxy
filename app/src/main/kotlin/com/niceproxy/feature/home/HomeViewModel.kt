package com.niceproxy.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.niceproxy.core.data.InboundRepository
import com.niceproxy.core.data.ServerRepository
import com.niceproxy.core.datastore.SettingsDataStore
import com.niceproxy.core.model.InboundService
import com.niceproxy.core.model.ServerProfile
import com.niceproxy.core.model.WellKnownTag
import com.niceproxy.core.service.ProxyServiceController
import com.niceproxy.core.service.ProxyState
import com.niceproxy.core.service.TrafficSnapshot
import com.niceproxy.core.service.core.NiceCore
import com.niceproxy.core.service.network.LocalAddress
import com.niceproxy.core.service.network.NetworkAddressDiscovery
import com.niceproxy.core.service.network.NetworkBinder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val proxyState: ProxyState = ProxyState.Stopped,
    val traffic: TrafficSnapshot = TrafficSnapshot(),
    val inbounds: List<InboundService> = emptyList(),
    val addresses: List<LocalAddress> = emptyList(),
    val nodeCount: Int = 0,
    val selectedNode: ServerProfile? = null,
    val selectedTag: String = WellKnownTag.AUTO,
    val otherVpnActive: Boolean = false,
    val coreVersion: String = "",
) {
    /**
     * 监听在 0.0.0.0 却没开认证 —— 同网段任何人都能白嫖。
     * 这是默认配置，必须在首页醒目提示。见 docs/DESIGN.md §8.2。
     */
    val hasExposedInboundWithoutAuth: Boolean
        get() = inbounds.any { it.enabled && it.isExposedWithoutAuth }

    val primaryPort: Int?
        get() = inbounds.firstOrNull { it.enabled }?.listenPort

    /** 没有节点时应用退化为纯中继，首页要如实告知，而不是假装在代理。 */
    val isRelayOnly: Boolean get() = nodeCount == 0

    val outboundLabel: String
        get() = when {
            isRelayOnly -> "直连（未配置节点）"
            selectedTag == WellKnownTag.AUTO -> "自动选择最快节点"
            selectedTag == WellKnownTag.DIRECT -> "直连"
            else -> selectedNode?.name ?: "未选择"
        }
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val controller: ProxyServiceController,
    private val inboundRepository: InboundRepository,
    private val serverRepository: ServerRepository,
    private val settings: SettingsDataStore,
    private val addressDiscovery: NetworkAddressDiscovery,
    private val networkBinder: NetworkBinder,
    core: NiceCore,
) : ViewModel() {

    private val addresses = MutableStateFlow<List<LocalAddress>>(emptyList())
    private val otherVpnActive = MutableStateFlow(false)
    private val coreVersion = core.version

    val uiState: StateFlow<HomeUiState> = combine(
        controller.state,
        controller.traffic,
        inboundRepository.inbounds,
        combine(serverRepository.servers, settings.outboundSettings, ::Pair),
        combine(addresses, otherVpnActive, ::Pair),
    ) { state, traffic, inbounds, (servers, outbound), (addressList, vpnActive) ->
        HomeUiState(
            proxyState = state,
            traffic = traffic,
            inbounds = inbounds,
            addresses = addressList,
            nodeCount = servers.size,
            selectedNode = servers.firstOrNull { it.outboundTag == outbound.selectedTag },
            selectedTag = outbound.selectedTag,
            otherVpnActive = vpnActive,
            coreVersion = coreVersion,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = HomeUiState(coreVersion = coreVersion),
    )

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        refreshNetworkInfo()
        // 网络切换会改变可用监听地址；用户照着过期的 IP 配置客户端会连不上
        viewModelScope.launch {
            networkBinder.defaultNetworkChanges().collect { refreshNetworkInfo() }
        }
    }

    fun refreshNetworkInfo() {
        viewModelScope.launch {
            addresses.value = addressDiscovery.discover()
            otherVpnActive.value = networkBinder.isOtherVpnActive()
        }
    }

    fun toggleProxy() = controller.toggle()

    fun setInboundEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch { inboundRepository.setEnabled(id, enabled) }
    }

    fun showMessage(text: String) {
        _message.value = text
    }

    fun consumeMessage() {
        _message.value = null
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
