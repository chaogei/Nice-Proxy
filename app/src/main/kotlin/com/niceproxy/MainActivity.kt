package com.niceproxy

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.niceproxy.core.datastore.SettingsDataStore
import com.niceproxy.core.designsystem.theme.NiceProxyTheme
import com.niceproxy.core.service.ProxyServiceController
import com.niceproxy.navigation.NiceApp
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import android.content.Intent
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var controller: ProxyServiceController
    @Inject lateinit var shortcuts: ProxyShortcuts
    @Inject lateinit var settings: SettingsDataStore

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 毛玻璃背景需要铺到状态栏和导航栏下面才成立
        enableEdgeToEdge()
        ensureNotificationPermission()
        val handledShortcut = handleShortcutIntent(intent)

        // 只在真正的冷启动时自启：savedInstanceState 非空说明是配置变更
        // （旋屏、深色模式切换）导致的重建，那不是「打开应用」。
        // 用户刚用快捷方式手动切过状态的话也让开，否则会把他的操作顶回去。
        if (savedInstanceState == null && !handledShortcut) {
            startOnLaunchIfConfigured()
        }

        // 快捷方式的文案随运行状态变化
        controller.state
            .onEach(shortcuts::publish)
            .launchIn(lifecycleScope)

        setContent {
            NiceProxyTheme {
                NiceApp()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShortcutIntent(intent)
    }

    private fun handleShortcutIntent(intent: Intent?): Boolean {
        if (intent?.action != ProxyShortcuts.ACTION_TOGGLE) return false
        controller.toggle()
        // 消费掉，避免 Activity 重建（如旋转屏幕）时重复触发
        intent.action = null
        return true
    }

    private fun startOnLaunchIfConfigured() {
        lifecycleScope.launch {
            if (!settings.serviceSettings.first().startOnAppLaunch) return@launch
            // 服务可能已经在跑（用户上次没停、或开机自启已经拉起来了）
            if (controller.state.value.isActive) return@launch
            controller.start()
        }
    }

    /**
     * Android 13+ 通知需要运行时权限。没有它前台服务的常驻通知不显示，
     * 用户会以为服务没跑，系统也更容易在后台回收进程。
     * 见 docs/DESIGN.md §10 P-8。
     */
    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
