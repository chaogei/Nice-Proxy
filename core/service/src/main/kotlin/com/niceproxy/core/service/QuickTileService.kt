package com.niceproxy.core.service

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.annotation.RequiresApi
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

/**
 * 通知栏快捷设置磁贴。
 *
 * 对一个需要频繁开关的工具来说，这是比桌面图标更顺手的入口 ——
 * 下拉通知栏一下就能切换，不必回到桌面找图标、等应用冷启动。
 */
@RequiresApi(Build.VERSION_CODES.N)
@AndroidEntryPoint
class QuickTileService : TileService() {

    @Inject lateinit var controller: ProxyServiceController
    @Inject lateinit var notifications: ProxyNotifications

    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartListening() {
        super.onStartListening()
        // 先掐掉上一轮。文档上 onStartListening / onStopListening 是配对的，但
        // SystemUI 崩溃重启之后并不保证 —— 漏掉一次配对就多一条永远收不掉的订阅，
        // 而它们全都活在这个 Service 实例上，SystemUI 不重启就不会消失。
        job?.cancel()
        job = controller.state
            .onEach { render(it) }
            .launchIn(scope)
    }

    override fun onStopListening() {
        job?.cancel()
        job = null
        super.onStopListening()
    }

    override fun onDestroy() {
        // scope 不跟着实例一起走的话，SupervisorJob 会一直挂在这个对象上，
        // 连带它引用的 controller 与 Service context
        scope.cancel()
        job = null
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        // 锁屏状态下直接切换，动作会被系统吞掉而磁贴看起来没反应。
        // unlockAndRun 会先要求解锁，解完再执行。
        if (isSecure && isLocked) {
            runCatching { unlockAndRun(::toggleSafely) }
                .onFailure { Log.w(TAG, "请求解锁失败", it) }
            return
        }
        toggleSafely()
    }

    /**
     * 磁贴点击跑在 SystemUI 托管的回调里，这里抛出去就是一次崩溃 —— 而
     * `toggle()` 内部会调 `startForegroundService`，那在后台受限时是真的会抛。
     *
     * 抛了不能只是咽下去：用户按了按钮，什么都没发生，他会以为磁贴坏了。
     * 磁贴上没有地方写原因（subtitle 会被下一次状态刷新覆盖），所以把那条
     * 「无法自动恢复」的通知拿来用 —— 它带着「立即启动」和「关闭电池优化」两个
     * 按钮，正好是这时候用户需要的两条出路。
     */
    private fun toggleSafely() {
        runCatching { controller.toggle() }.onFailure { throwable ->
            Log.w(TAG, "磁贴切换失败", throwable)
            runCatching {
                notifications.ensureChannel()
                notifications.notifyRecoveryBlocked(throwable.message)
            }
        }
    }

    private fun render(state: ProxyState) {
        val tile = qsTile ?: return
        tile.state = when {
            state.isActive -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = getString(R.string.app_tile_label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when (state) {
                is ProxyState.Running -> state.listeningOn
                    .joinToString("、") { it.port.toString() }
                    .ifBlank { getString(R.string.service_state_running) }
                ProxyState.Starting -> getString(R.string.service_state_starting)
                ProxyState.Stopping -> getString(R.string.service_state_stopping)
                is ProxyState.Failed -> getString(R.string.service_state_failed)
                ProxyState.Stopped -> null
            }
        }
        // updateTile 也会跨进程，SystemUI 正在重启时会抛
        runCatching { tile.updateTile() }
    }

    private companion object {
        const val TAG = "QuickTileService"
    }
}
