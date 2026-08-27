package com.niceproxy.navigation

import androidx.lifecycle.ViewModel
import com.niceproxy.core.service.ProxyServiceController
import com.niceproxy.onboarding.OnboardingPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * 全局壳层的状态。
 *
 * 「配置已变更」这类提示挂在壳层而不是首页：用户通常是在入站配置页、分流页
 * 改完东西的，提示要跟着他走，而不是等他回到首页才出现。
 */
@HiltViewModel
class AppShellViewModel @Inject constructor(
    private val controller: ProxyServiceController,
    private val onboarding: OnboardingPreferences,
) : ViewModel() {

    val configOutdated: StateFlow<Boolean> = controller.configOutdated
    val configMessage: StateFlow<String?> = controller.configMessage

    /**
     * 引导页也归壳层管：它要盖住整个 NavHost，而不是作为某个 Tab 里的一屏。
     * 值来自同步读的 SharedPreferences，所以首帧就是终态，不会先闪一下首页。
     */
    val onboardingCompleted: StateFlow<Boolean> = onboarding.completed

    fun completeOnboarding() = onboarding.markCompleted()

    fun reapplyConfig() = controller.reapplyConfig()

    fun consumeConfigMessage() = controller.consumeConfigMessage()
}
