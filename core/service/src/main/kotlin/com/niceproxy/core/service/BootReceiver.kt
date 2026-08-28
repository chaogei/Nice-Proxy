package com.niceproxy.core.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.niceproxy.core.datastore.SettingsDataStore
import com.niceproxy.core.model.StartReason
import com.niceproxy.core.service.work.ProxyWatchdogScheduler
import com.niceproxy.core.service.work.SubscriptionUpdateScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

/**
 * 开机自启，以及应用更新之后的恢复。
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
    @Inject lateinit var notifications: ProxyNotifications

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in BOOT_ACTIONS && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val pendingResult = goAsync()
        // 自己的 scope 而不是 GlobalScope：接收器返回之后这个 Job 必须能被收掉，
        // 否则读设置卡住时它会挂在进程上直到进程死亡
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            try {
                // 超时必须比系统给的窗口短。跑过头的话，pendingResult.finish() 永远
                // 不会被调用，系统会判定接收器 ANR —— 而那是一次可见的崩溃弹窗，
                // 比「开机没自启」严重得多。
                withTimeout(DEADLINE_MS) {
                    // 重启后 WorkManager 的周期任务需要重新排期
                    scheduler.ensureScheduled()
                    if (action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                        resumeAfterUpdate()
                    } else {
                        applyBootPolicy()
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "$action 处理失败", t)
            } finally {
                runCatching { pendingResult.finish() }
                scope.cancel()
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
        if (!settings.serviceSettings.first().autoStartOnBoot) {
            settings.setShouldBeRunning(false)
            watchdog.cancel()
            return
        }
        settings.setShouldBeRunning(true)
        watchdog.ensureScheduled()
        // 设备重启不是「被杀」，用户自己配的开机自启，不该记成中断
        start(StartReason.BOOT)
    }

    /**
     * 应用被更新覆盖装了一遍。
     *
     * **这条路径以前和开机走的是同一段代码，那是错的。** 安装器会先杀掉旧进程，
     * 代理随之停掉；而更新完之后如果按「开机自启」来判断，一个没开自启、却正开着
     * 代理的用户会被 `setShouldBeRunning(false)` 把运行意图一并清掉 —— 代理不但
     * 没恢复，连看门狗那张兜底的网也被撤了，用户下次注意到的时候只知道「更新完就
     * 不能用了」。
     *
     * 更新不是一次重来，它应该尽量装作没发生过：之前在跑就恢复，之前没在跑就什么
     * 都不做，`autoStartOnBoot` 在这里没有发言权。
     */
    private suspend fun resumeAfterUpdate() {
        if (!settings.shouldBeRunning.first()) return
        Log.i(TAG, "应用更新前代理正在运行，恢复")
        watchdog.ensureScheduled()
        start(StartReason.STICKY_RESTART)
    }

    /**
     * `BOOT_COMPLETED` 是后台启动前台服务的官方豁免项，`MY_PACKAGE_REPLACED`
     * **不是** —— 更新完成时应用仍在后台，这一下有很大概率被系统直接拦下。
     *
     * 拦下来只写一行 logcat 的话，用户看到的是「更新之后代理再也没起来」，而且
     * 完全无从判断原因。所以这里接住，并弹那条带「立即启动」按钮的通知：
     * 通知动作发出的 PendingIntent 恰好是另一个豁免项，一按就能真的起来。
     */
    private fun start(reason: StartReason) {
        runCatching { controller.start(reason) }.onFailure { throwable ->
            Log.w(TAG, "自启被系统拦下，提醒用户", throwable)
            runCatching {
                notifications.ensureChannel()
                notifications.notifyRecoveryBlocked(throwable.message)
            }
        }
    }

    private companion object {
        const val TAG = "BootReceiver"

        /** 系统给广播接收器约 10 秒，留出余量让 finish() 一定跑得到。 */
        const val DEADLINE_MS = 8_000L

        val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            // 国产 ROM 常用这个动作，且部分设备不发标准的 BOOT_COMPLETED
            "android.intent.action.QUICKBOOT_POWERON",
        )
    }
}
