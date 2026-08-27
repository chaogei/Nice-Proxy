package com.niceproxy

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.niceproxy.core.common.ApplicationScope
import com.niceproxy.core.data.InboundRepository
import com.niceproxy.core.datastore.SettingsDataStore
import com.niceproxy.core.service.ProxyServiceController
import com.niceproxy.core.service.work.ProxyWatchdogScheduler
import com.niceproxy.core.service.work.SubscriptionUpdateScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class NiceProxyApplication : Application(), Configuration.Provider {

    @Inject lateinit var inboundRepository: InboundRepository
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var subscriptionScheduler: SubscriptionUpdateScheduler
    @Inject lateinit var watchdogScheduler: ProxyWatchdogScheduler
    @Inject lateinit var settings: SettingsDataStore
    @Inject lateinit var controller: ProxyServiceController

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    /** Worker 需要注入 Repository，必须换成 Hilt 的工厂。 */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            // 首次启动写入一个默认的混合入站，用户装完就能直接开，
            // 不需要先弄懂「入站」是什么
            inboundRepository.ensureDefaults()
        }
        subscriptionScheduler.ensureScheduled()
        recoverIfKilled()
    }

    /**
     * 应用进程起来时，顺手把「本该在跑却没在跑」的代理补起来。
     *
     * 这条路径存在的意义是它**没有后台启动限制** —— 进程刚被创建（用户点了图标、
     * 或系统因别的原因唤起了应用），此时启动前台服务是被允许的。而看门狗 Worker
     * 在 Android 12+ 且未获电池优化豁免时会被系统拦下。
     *
     * 换句话说：真被 ROM 杀干净、看门狗也被冻住的最坏情况下，
     * 用户只要打开一次应用，代理就自己回来了，不用再点一次启动。
     */
    private fun recoverIfKilled() {
        applicationScope.launch {
            if (!settings.shouldBeRunning.first()) return@launch
            if (controller.state.value.isActive) return@launch
            watchdogScheduler.ensureScheduled()
            runCatching { controller.start() }
                .onFailure { Log.w(TAG, "冷启动补拉代理失败", it) }
        }
    }

    private companion object {
        const val TAG = "NiceProxyApp"
    }
}
