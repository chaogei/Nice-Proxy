package com.niceproxy.core.service.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.niceproxy.core.datastore.SettingsDataStore
import com.niceproxy.core.model.StartReason
import com.niceproxy.core.service.ProxyNotifications
import com.niceproxy.core.service.ProxyServiceController
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 进程级看门狗：代理本该在跑却没在跑时，把它拉回来。
 *
 * **为什么 START_STICKY 不够。** 它只覆盖「系统因内存压力回收了服务、之后决定重建」
 * 这一种情况。真机上更常见的是另外几种，它一概管不了：
 *
 * - 国产 ROM 的后台清理直接干掉整个进程，且不少 ROM 会连带忽略 sticky 标志；
 * - 用户在最近任务里划掉了应用（部分 ROM 视同 force-stop）；
 * - 进程因为原生层崩溃而消失。
 *
 * WorkManager 的任务信息落在它自己的数据库里，进程重建后由 `androidx.startup`
 * 恢复调度，所以这条链路能跨越进程死亡。
 *
 * **它的局限也要说清楚。** 周期任务的最小间隔是 15 分钟且不保证准时，所以这是一张
 * 粗网，用来兜住「掉了很久都没人管」，不是用来做秒级恢复的 —— 秒级那部分由
 * `ProxyService` 内部的内核健康检查负责。另外真正的 force-stop 会把应用打入
 * stopped 状态、连同 WorkManager 任务一起冻结，那种情况下任何方案都无能为力，
 * 只能等用户再次打开应用。
 */
@HiltWorker
class ProxyWatchdogWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val settings: SettingsDataStore,
    private val controller: ProxyServiceController,
    private val notifications: ProxyNotifications,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // 用户主动停过就别自作主张。这一位是落盘的，进程重建后依然可信。
        if (!settings.shouldBeRunning.first()) return Result.success()
        if (controller.state.value.isActive) return Result.success()

        Log.i(TAG, "代理应当运行但当前未运行，尝试拉起")
        return try {
            controller.start(StartReason.WATCHDOG)
            Result.success()
        } catch (t: Throwable) {
            // Android 12+ 后台启动前台服务会抛 ForegroundServiceStartNotAllowedException，
            // 除非用户关掉了本应用的电池优化（这正是设置页里要引导的那一项）。
            //
            // 走到这里就是保活链条上最坏的一格：代理停了，自动恢复也被挡住了。
            // 只记一条 logcat 的话，用户看到的是「所有设备莫名断网且再也不会好」，
            // 而且完全无从判断原因，所以必须让他知道。
            Log.w(TAG, "拉起失败，提醒用户", t)
            notifications.ensureChannel()
            notifications.notifyRecoveryBlocked()
            // 不返回 retry：周期任务下一轮还会来，而 retry 的退避会打乱固定节奏。
            Result.success()
        }
    }

    companion object {
        private const val TAG = "ProxyWatchdog"
        const val WORK_NAME = "proxy-watchdog"
    }
}

@Singleton
class ProxyWatchdogScheduler @Inject constructor(
    private val workManager: WorkManager,
) {

    fun ensureScheduled() {
        val request = PeriodicWorkRequestBuilder<ProxyWatchdogWorker>(
            CHECK_INTERVAL_MINUTES,
            TimeUnit.MINUTES,
        ).build()

        workManager.enqueueUniquePeriodicWork(
            ProxyWatchdogWorker.WORK_NAME,
            // KEEP 而不是 UPDATE：每次服务启动都重排会不断把下次执行时间往后推，
            // 而频繁开关代理的用户恰恰最需要这张网。
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun cancel() {
        workManager.cancelUniqueWork(ProxyWatchdogWorker.WORK_NAME)
    }

    private companion object {
        /**
         * 刻意不加 NetworkType 约束。没网时代理确实没用，但服务停掉的话，
         * 局域网客户端收到的是「连接被拒绝」而不是「网页打不开」——
         * 前者会让用户以为代理配置坏了，跑去改设置。
         */
        const val CHECK_INTERVAL_MINUTES = 15L
    }
}
