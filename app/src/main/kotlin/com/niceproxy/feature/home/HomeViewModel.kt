package com.niceproxy.feature.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.niceproxy.core.data.InboundRepository
import com.niceproxy.core.data.ServerRepository
import com.niceproxy.core.database.health.CredentialHealth
import com.niceproxy.core.database.health.DatabaseHealth
import com.niceproxy.core.datastore.KeepAliveJournal
import com.niceproxy.core.datastore.SettingsDataStore
import com.niceproxy.core.model.InboundAuth
import com.niceproxy.core.model.InboundService
import com.niceproxy.core.model.ServerProfile
import com.niceproxy.core.model.WellKnownTag
import com.niceproxy.core.network.clash.ClashApiClient
import com.niceproxy.core.network.clash.ConnectionInfo
import com.niceproxy.core.service.ProxyServiceController
import com.niceproxy.core.service.ProxyState
import com.niceproxy.core.service.TrafficSnapshot
import com.niceproxy.core.service.core.NiceCore
import com.niceproxy.core.service.network.LocalAddress
import com.niceproxy.core.service.network.NetworkAddressDiscovery
import com.niceproxy.core.service.network.NetworkBinder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 一台正连着的客户端设备。
 *
 * 用来源 IP 而不是连接数做主键：用户装设备时脑子里想的是「Switch 连上没有」，
 * 不是「现在有多少条 TCP 连接」。
 */
data class ConnectedDevice(
    val address: String,
    val connectionCount: Int,
)

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
    val connectedDevices: List<ConnectedDevice> = emptyList(),
    /** 本轮连续服务的起点，null 表示没在跑。 */
    val sessionStartedAt: Long? = null,
    /** 本轮期间被系统或内核打断过几次，全都自动恢复了。 */
    val interruptionsThisSession: Int = 0,
    /** Keystore 密钥失效，节点密码与入站凭据正以明文落盘。 */
    val credentialsPlaintext: Boolean = false,
    /** 数据库打不开被删库重建过，用户的节点、订阅、规则都没了。 */
    val databaseWasReset: Boolean = false,
) {
    /**
     * 监听在 0.0.0.0 却没开认证 —— 同网段任何人都能白嫖。
     * 这是默认配置，必须在首页醒目提示。见 docs/DESIGN.md §8.2。
     */
    val hasExposedInboundWithoutAuth: Boolean
        get() = exposedInboundId != null

    /** 让首页那条警告能直接把用户送到出问题的那个入站，而不是只念一遍风险。 */
    val exposedInboundId: String?
        get() = inbounds.firstOrNull { it.enabled && it.isExposedWithoutAuth }?.id

    /** 配置生成器跳过无效节点之类的非致命问题，内核照跑，但用户有权知道。 */
    val configWarnings: List<String>
        get() = (proxyState as? ProxyState.Running)?.warnings.orEmpty()

    val primaryPort: Int?
        get() = inbounds.firstOrNull { it.enabled }?.listenPort

    /**
     * 首个启用入站的认证凭据，null 表示免认证。
     *
     * 和 [primaryPort] 取同一个入站：用户是把地址、端口、账号密码作为一组
     * 抄到游戏机上的，取自不同入站会拼出一组连不上的配置。
     */
    val primaryAuth: InboundAuth?
        get() = inbounds.firstOrNull { it.enabled }?.auth

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

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val controller: ProxyServiceController,
    private val inboundRepository: InboundRepository,
    private val serverRepository: ServerRepository,
    private val settings: SettingsDataStore,
    private val addressDiscovery: NetworkAddressDiscovery,
    private val networkBinder: NetworkBinder,
    private val clashApi: ClashApiClient,
    private val databaseHealth: DatabaseHealth,
    private val journal: KeepAliveJournal,
    credentialHealth: CredentialHealth,
    core: NiceCore,
) : ViewModel() {

    private val addresses = MutableStateFlow<List<LocalAddress>>(emptyList())
    private val otherVpnActive = MutableStateFlow(false)
    private val coreVersion = core.version

    /**
     * 局域网里有几台设备正连着。
     *
     * 用户在配置 Switch 的当口，这是唯一重要的反馈 —— 「运行中」只说明本地端口在
     * 监听，说明不了对面那台机器有没有把流量送过来。数据本来就在连接监控页里，
     * 但那埋在「更多 → 连接监控」两层之下。
     *
     * 做成 combine 的一路而不是 init 里的常驻订阅：这样它跟着 [uiState] 的
     * WhileSubscribed 一起起停，用户切到别的 Tab 时不会留一条 WebSocket 空跑。
     */
    private val connectedDevices: Flow<List<ConnectedDevice>> = controller.state
        .map { it is ProxyState.Running }
        .distinctUntilChanged()
        .flatMapLatest { running ->
            if (!running) {
                flowOf(emptyList<ConnectedDevice>())
            } else {
                clashApi.connections(settings.clashApiSettings())
                    .map { snapshot -> snapshot.connections.groupByDevice() }
                    // 首帧先给空列表，否则内核刚起来、第一份快照还没到的那几百毫秒里
                    // 卡片会沿用上一次的设备数
                    .onStart { emit(emptyList()) }
                    .catch { error ->
                        // README §「明文 HTTP 与 Clash API」的教训：空 catch 会让
                        // 「真的没设备连」和「订阅断了」在界面上长得一模一样
                        Log.w(TAG, "首页连接快照订阅中断", error)
                        emit(emptyList())
                    }
            }
        }
        // 连接快照每秒一份、可能上千条，分组统计不该占主线程
        .flowOn(Dispatchers.Default)

    val uiState: StateFlow<HomeUiState> = combine(
        controller.state,
        controller.traffic,
        inboundRepository.inbounds,
        // combine 的两层都已经是 5 路上限了，这里塞成 Triple 是为了不再多套一层
        combine(serverRepository.servers, settings.outboundSettings, journal.stats, ::Triple),
        // 这两条 health 信号在此之前没有任何生产代码读过：它们各自的注释都写着
        // 「必须能被上层看见」，而唯一能看见的地方就是首页。
        combine(
            addresses,
            otherVpnActive,
            connectedDevices,
            credentialHealth.degraded,
            databaseHealth.wasReset,
        ) { addressList, vpnActive, devices, plaintext, dbReset ->
            LocalSignals(addressList, vpnActive, devices, plaintext, dbReset)
        },
    ) { state, traffic, inbounds, (servers, outbound, keepAlive), signals ->
        HomeUiState(
            proxyState = state,
            traffic = traffic,
            inbounds = inbounds,
            addresses = signals.addresses,
            nodeCount = servers.size,
            selectedNode = servers.firstOrNull { it.outboundTag == outbound.selectedTag },
            selectedTag = outbound.selectedTag,
            otherVpnActive = signals.otherVpnActive,
            coreVersion = coreVersion,
            connectedDevices = signals.connectedDevices,
            credentialsPlaintext = signals.credentialsPlaintext,
            databaseWasReset = signals.databaseWasReset,
            // 只在真的在跑的时候报运行时长。停止之后 sessionStartedAt 会被清掉，
            // 但状态流可能先到一步，那一瞬间显示「已运行 3 天」会很怪
            sessionStartedAt = keepAlive.sessionStartedAt?.takeIf { state is ProxyState.Running },
            interruptionsThisSession = keepAlive.interruptionsSince(keepAlive.sessionStartedAt),
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

    /**
     * 终态失败后的唯一出口。
     *
     * 失败状态下 [ProxyServiceController.toggle] 只会走启动分支，界面上没有「停止」，
     * 而落盘的运行意图还留着，看门狗会一直把它拉起来。
     */
    fun stopAndForget() = controller.stopAndForget()

    fun setInboundEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch { inboundRepository.setEnabled(id, enabled) }
    }

    fun showMessage(text: String) {
        _message.value = text
    }

    fun consumeMessage() {
        _message.value = null
    }

    /** 用户已经看到「库被重建过」的提示，不用再挂着了。 */
    fun acknowledgeDatabaseReset() = databaseHealth.acknowledge()

    /** combine 最多接五路，把这几个本机侧信号打包成一路。 */
    private data class LocalSignals(
        val addresses: List<LocalAddress>,
        val otherVpnActive: Boolean,
        val connectedDevices: List<ConnectedDevice>,
        val credentialsPlaintext: Boolean,
        val databaseWasReset: Boolean,
    )

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val TAG = "HomeViewModel"
    }
}

/**
 * 没有来源 IP 的连接不计入设备数：那多半是内核自己发起的（DNS、订阅更新），
 * 把它算成一台「设备」会让用户对着空气找机器。
 */
private fun List<ConnectionInfo>.groupByDevice(): List<ConnectedDevice> =
    filter { it.clientAddress != NO_CLIENT_ADDRESS }
        .groupingBy { it.clientAddress }
        .eachCount()
        .map { (address, count) -> ConnectedDevice(address, count) }
        .sortedByDescending { it.connectionCount }

private const val NO_CLIENT_ADDRESS = "-"
