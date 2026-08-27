package com.niceproxy.onboarding

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「引导页看过没有」这一个布尔值。
 *
 * 刻意不放进 `core:datastore`：那里存的是内核配置，会随备份导出、也会被恢复覆盖。
 * 而「这台设备前面的人读没读过说明」是纯本地的一次性状态，跟着别人的备份恢复出一个
 * 「已看过」，新用户就再也见不到引导了。
 *
 * 用 SharedPreferences 而不是再起一个 DataStore 实例：这个值必须在第一帧之前就拿到，
 * 否则会先闪一下首页再盖上引导页。同步读一个布尔值是这里唯一需要的能力，
 * 代价是首次访问的那次磁盘读发生在主线程 —— 文件里只有一个 boolean，可以接受。
 */
@Singleton
class OnboardingPreferences @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val _completed = MutableStateFlow(prefs.getBoolean(KEY_COMPLETED, false))
    val completed: StateFlow<Boolean> = _completed.asStateFlow()

    fun markCompleted() {
        prefs.edit().putBoolean(KEY_COMPLETED, true).apply()
        _completed.value = true
    }

    private companion object {
        const val FILE_NAME = "onboarding"
        const val KEY_COMPLETED = "completed"
    }
}
