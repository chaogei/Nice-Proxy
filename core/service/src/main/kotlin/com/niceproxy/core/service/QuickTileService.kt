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

    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartListening() {
        super.onStartListening()
        // 磁贴只在下拉面板可见时监听，收起后立刻停止，避免无谓的后台订阅
        job = controller.state
            .onEach { render(it) }
            .launchIn(scope)
    }

    override fun onStopListening() {
        job?.cancel()
        job = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        // 磁贴点击跑在 SystemUI 托管的进程回调里，这里抛出去就是一次崩溃 ——
        // 而 toggle() 内部会调 startForegroundService，那在后台受限时是真的会抛。
        runCatching { controller.toggle() }
            .onFailure { Log.w(TAG, "磁贴切换失败", it) }
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
        tile.updateTile()
    }

    private companion object {
        const val TAG = "QuickTileService"
    }
}
