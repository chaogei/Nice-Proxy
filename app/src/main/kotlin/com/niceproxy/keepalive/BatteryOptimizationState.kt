package com.niceproxy.keepalive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/**
 * 电池优化白名单的当前状态，每次回到前台重新读一遍。
 *
 * 这个开关是在系统设置里改的，改完按返回键回来时应用收不到任何回调。只在首次组合
 * 时读一次的话，用户明明已经关掉了优化，界面还在红着字说没关 —— 反过来也一样。
 *
 * 设置页和首页都要用，所以放在这里而不是各自写一份：两处显示不一致比不显示更糟。
 */
@Composable
fun rememberIgnoringBatteryOptimizations(): Boolean {
    val context = LocalContext.current
    var ignoring by remember(context) {
        mutableStateOf(KeepAlive.isIgnoringBatteryOptimizations(context))
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        ignoring = KeepAlive.isIgnoringBatteryOptimizations(context)
    }
    return ignoring
}
