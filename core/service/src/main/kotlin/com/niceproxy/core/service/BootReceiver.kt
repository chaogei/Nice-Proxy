package com.niceproxy.core.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.niceproxy.core.datastore.SettingsDataStore
import com.niceproxy.core.service.work.ProxyWatchdogScheduler
import com.niceproxy.core.service.work.SubscriptionUpdateScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 开机自启。
 *
 * 广播接收器的执行窗口只有约 10 秒，所以这里只做「读设置 + 发一个启动 Intent」，
 * 真正的启动逻辑仍在 [ProxyService] 里异步完成。
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var settings: SettingsDataStore
    @Inject lateinit var controller: ProxyServiceController
    @Inject lateinit var scheduler: SubscriptionUpdateScheduler
    @Inject lateinit var watchdog: ProxyWatchdogScheduler

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in BOOT_ACTIONS) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                // 重启后 WorkManager 的周期任务需要重新排期
                scheduler.ensureScheduled()
                applyBootPolicy()
            } catch (t: Throwable) {
                Log.w(TAG, "开机自启失败", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * 重启是一次干净的重来，所以在这里把「开机自启」和落盘的运行意图对齐。
     *
     * 不对齐的话会出现一种很难解释的行为：用户没开自启，但关机前代理是开着的，
     * 于是运行意图仍是 true，看门狗会在开机约 15 分钟后无声无息地把代理拉起来 ——
     * 从用户视角看就是「关了自启还是会自己开」。
     */
    private suspend fun applyBootPolicy() {
        if (settings.serviceSettings.first().autoStartOnBoot) {
            settings.setShouldBeRunning(true)
            watchdog.ensureScheduled()
            controller.start()
        } else {
            settings.setShouldBeRunning(false)
            watchdog.cancel()
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
        val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            // 国产 ROM 常用这个动作，且部分设备不发标准的 BOOT_COMPLETED
            "android.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}
