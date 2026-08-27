package com.niceproxy.navigation

import androidx.lifecycle.ViewModel
import com.niceproxy.core.service.ProxyServiceController
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
) : ViewModel() {

    val configOutdated: StateFlow<Boolean> = controller.configOutdated
    val configMessage: StateFlow<String?> = controller.configMessage

    fun reapplyConfig() = controller.reapplyConfig()

    fun consumeConfigMessage() = controller.consumeConfigMessage()
}
