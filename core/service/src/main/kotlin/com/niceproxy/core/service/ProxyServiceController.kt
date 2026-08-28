package com.niceproxy.core.service

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.niceproxy.core.common.ApplicationScope
import com.niceproxy.core.datastore.SettingsDataStore
import com.niceproxy.core.model.StartReason
import com.niceproxy.core.service.work.ProxyWatchdogScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UI 与代理服务之间的唯一接口。
 *
 * 界面只观察这里的 StateFlow、只调用这里的 start/stop，从不直接 bind 到
 * [ProxyService]。这样 Service 的生命周期（可能被系统回收重建）不会渗透到
 * ViewModel 层，状态也不会因为 Service 重建而丢失。
 */
@Singleton
class ProxyServiceController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsDataStore,
    private val watchdog: ProxyWatchdogScheduler,
    @ApplicationScope private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow<ProxyState>(ProxyState.Stopped)
    val state: StateFlow<ProxyState> = _state.asStateFlow()

    private val _traffic = MutableStateFlow(TrafficSnapshot())
    val traffic: StateFlow<TrafficSnapshot> = _traffic.asStateFlow()

    private val _configOutdated = MutableStateFlow(false)

    /**
     * 内核正在跑的那份配置已经和当前设置对不上了，需要重新应用才会生效。
     *
     * 不自动重启是刻意的：内核重启意味着所有客户端连接一起断开，而用户改配置往往是
     * 连着改好几处。UI 应当在这里为 true 时给出 Snackbar「配置已变更，点击应用」，
     * 点击时调用 [reapplyConfig]。见 docs/DESIGN.md §8.2。
     *
     * 仅切换节点不会让它变 true —— 那条路径走 Clash API 热切换，无需重启（§6.3）。
     */
    val configOutdated: StateFlow<Boolean> = _configOutdated.asStateFlow()

    private val _configMessage = MutableStateFlow<String?>(null)

    /**
     * 应用配置没能成功的原因（例如新配置本身不合法）。
     * 这种情况下旧配置会继续运行，UI 展示后调用 [consumeConfigMessage] 清除。
     */
    val configMessage: StateFlow<String?> = _configMessage.asStateFlow()

    /**
     * @param reason 谁发起的这次启动。它不影响启动逻辑，只用于记账 ——
     *        「被杀之后自动恢复」和「用户自己点的」在服务眼里长得一模一样，
     *        不带上这个标签就永远分不出保活到底有没有在起作用。
     */
    fun start(reason: StartReason = StartReason.USER) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, ProxyService::class.java)
                .setAction(ProxyService.ACTION_START)
                .putExtra(ProxyService.EXTRA_START_REASON, reason.name),
        )
    }

    fun stop() {
        context.startService(
            Intent(context, ProxyService::class.java).setAction(ProxyService.ACTION_STOP),
        )
    }

    fun toggle() {
        if (_state.value.isActive) stop() else start()
    }

    /**
     * 「别再试了」。
     *
     * 存在的理由是 [toggle] 在非 active 状态下只会走 [start] 分支 —— 代理进入终态失败
     * 之后，界面上根本不存在「停止」按钮，而落盘的运行意图还留着，看门狗会每 15 分钟
     * 醒来、失败一次、弹一次通知。用户唯一的出路竟然是「先想办法让它成功启动一次，
     * 再按停止」，这显然不能算一条出路。
     *
     * UI 应当在 [ProxyState.Failed] 上给出一个走这里的入口。
     */
    fun stopAndForget() {
        forgetRunIntent()
        // 服务没在跑的时候不必为了停止而先把它拉起来；状态本来就是 Failed / Stopped，
        // 直接就地清掉即可
        if (_state.value.isActive) {
            stop()
        } else {
            _state.value = ProxyState.Stopped
        }
    }

    /**
     * 清掉落盘的「代理本该在跑」，并撤销看门狗。
     *
     * 用 [ApplicationScope] 而不是调用方的作用域：这条写入没落盘的后果是看门狗把
     * 用户刚放弃的服务又拉起来，它必须活过任何一个页面或服务的销毁。
     */
    private fun forgetRunIntent() {
        watchdog.cancel()
        scope.launch {
            runCatching { settings.setShouldBeRunning(false) }
                .onFailure { Log.w(TAG, "运行意图写入失败", it) }
        }
    }

    /**
     * 把最新配置应用到运行中的内核。
     *
     * 服务端会先比对指纹：没变就直接返回，不会为一次「其实没改到内核」的保存
     * 白白重启一遍、断掉所有客户端连接。见 docs/DESIGN.md §6.3。
     */
    fun reapplyConfig() {
        // 没在跑就没有「重新应用」可言，此时也不该从后台把服务拉起来
        if (!_state.value.isActive) return
        context.startService(
            Intent(context, ProxyService::class.java).setAction(ProxyService.ACTION_RELOAD),
        )
    }

    fun consumeConfigMessage() {
        _configMessage.value = null
    }

    /** 仅供 [ProxyService] 回写状态。 */
    internal fun updateState(state: ProxyState) {
        _state.value = state
    }

    internal fun updateTraffic(snapshot: TrafficSnapshot) {
        _traffic.value = snapshot
    }

    internal fun updateConfigOutdated(outdated: Boolean) {
        _configOutdated.value = outdated
    }

    internal fun postConfigMessage(text: String) {
        _configMessage.value = text
    }

    private companion object {
        const val TAG = "ProxyServiceController"
    }
}
