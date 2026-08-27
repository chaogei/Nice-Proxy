package com.niceproxy

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.niceproxy.core.service.ProxyState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 长按桌面图标出现的快捷方式。
 *
 * 用动态快捷方式而不是 `shortcuts.xml`：静态快捷方式的 `targetPackage` 只能写死，
 * 而 debug 构建带 `.debug` 后缀，写死就会让调试包的快捷方式失效。
 * 动态方式还能顺带按运行状态在「启动」和「停止」之间切换文案。
 */
@Singleton
class ProxyShortcuts @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun publish(state: ProxyState) {
        val running = state.isActive
        val shortcut = ShortcutInfoCompat.Builder(context, SHORTCUT_TOGGLE)
            .setShortLabel(context.getString(if (running) R.string.shortcut_stop else R.string.shortcut_start))
            .setLongLabel(context.getString(if (running) R.string.shortcut_stop else R.string.shortcut_start))
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_start))
            .setIntent(
                Intent(context, MainActivity::class.java)
                    .setAction(ACTION_TOGGLE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            .build()

        runCatching { ShortcutManagerCompat.pushDynamicShortcut(context, shortcut) }
    }

    companion object {
        const val ACTION_TOGGLE = "com.niceproxy.action.TOGGLE"
        private const val SHORTCUT_TOGGLE = "toggle_proxy"
    }
}
