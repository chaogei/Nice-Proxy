package com.niceproxy.core.service.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.niceproxy.core.data.ServerRepository
import com.niceproxy.core.data.SubscriptionRepository
import com.niceproxy.core.model.GroupType
import com.niceproxy.core.model.ServerGroup
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 后台定期更新订阅。
 *
 * 只更新「到点了」的分组：WorkManager 的最小周期是 15 分钟，而用户设置的
 * 更新间隔通常是几小时到一天。让 Worker 每 15 分钟醒一次、自己判断哪些该更新，
 * 比给每个订阅各排一个周期任务要省电得多，也避免了任务数随订阅数线性增长。
 */
@HiltWorker
class SubscriptionUpdateWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val serverRepository: ServerRepository,
    private val subscriptionRepository: SubscriptionRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val due = serverRepository.getGroups().filter { it.isDue() }
        if (due.isEmpty()) return Result.success()

        var failed = 0
        due.forEach { group ->
            subscriptionRepository.refresh(group.id).onFailure {
                failed++
                Log.w(TAG, "订阅「${group.name}」更新失败", it)
            }
        }

        // 全部失败多半是没网或订阅服务不可用，交给 WorkManager 退避重试；
        // 部分失败则不重试，避免已成功的订阅被重复拉取。
        return if (failed == due.size) Result.retry() else Result.success()
    }

    private fun ServerGroup.isDue(): Boolean {
        if (type != GroupType.SUBSCRIPTION || !autoUpdate || url.isNullOrBlank()) return false
        val last = lastUpdateAt ?: return true
        val elapsed = System.currentTimeMillis() - last
        return elapsed >= effectiveIntervalMinutes * 60_000L
    }

    companion object {
        private const val TAG = "SubUpdateWorker"
        const val WORK_NAME = "subscription-auto-update"
    }
}

@Singleton
class SubscriptionUpdateScheduler @Inject constructor(
    private val workManager: WorkManager,
) {

    fun ensureScheduled() {
        val request = PeriodicWorkRequestBuilder<SubscriptionUpdateWorker>(
            CHECK_INTERVAL_MINUTES,
            TimeUnit.MINUTES,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()

        workManager.enqueueUniquePeriodicWork(
            SubscriptionUpdateWorker.WORK_NAME,
            // KEEP 而不是 REPLACE：每次冷启动都 REPLACE 会不断重置周期，
            // 导致任务永远等不到第一次执行。
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun cancel() {
        workManager.cancelUniqueWork(SubscriptionUpdateWorker.WORK_NAME)
    }

    private companion object {
        /** WorkManager 周期任务的下限就是 15 分钟，再小也不会生效。 */
        const val CHECK_INTERVAL_MINUTES = 15L
    }
}
