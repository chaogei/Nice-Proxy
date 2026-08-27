package com.niceproxy.util

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle

/**
 * 复制文本到剪贴板，可标记为敏感内容。
 *
 * 不走 Compose 的 `LocalClipboardManager`：它拿不到 [ClipDescription]，而
 * Android 13 起系统会在屏幕左下角弹一个浮层预览刚复制的内容。分享链接里带着
 * 节点密码、日志里带着服务器地址，这个浮层会把它们明文渲染出来 —— 用户复制
 * 恰恰常常是为了发给别人，此时屏幕多半正被别人看着或正在录屏。
 *
 * 只有打上 [ClipDescription.EXTRA_IS_SENSITIVE]，系统才会把预览换成星号。
 */
fun Context.copyToClipboard(label: String, text: String, sensitive: Boolean = false) {
    val manager = getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText(label, text)
    // 这个标记 API 33 才有，更低版本本来也没有预览浮层，不需要降级方案
    if (sensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    manager.setPrimaryClip(clip)
}
