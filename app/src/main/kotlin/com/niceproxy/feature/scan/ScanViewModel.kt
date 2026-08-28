package com.niceproxy.feature.scan

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.niceproxy.R
import com.niceproxy.core.data.ServerRepository
import com.niceproxy.core.data.SubscriptionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 扫码之后要告诉用户的结果。
 *
 * 结果最终会跨过一次导航送回节点页显示，所以界面拿到之后立刻取字符串；
 * 但 ViewModel 这边只给结构，它没有 `Context`，也不该有。
 */
sealed interface ScanOutcome {
    data object Empty : ScanOutcome
    data class Subscribed(val group: String, val nodes: Int) : ScanOutcome
    data class SubscribeFailed(val reason: String?) : ScanOutcome
    data object Unrecognised : ScanOutcome
    data class Imported(val imported: Int) : ScanOutcome
    data class PartiallyImported(val imported: Int, val failed: Int) : ScanOutcome

    fun resolve(context: Context): String = when (this) {
        Empty -> context.getString(R.string.scan_empty)
        is Subscribed -> context.getString(R.string.nodes_subscribed, group, nodes)
        is SubscribeFailed -> context.getString(
            R.string.scan_subscribe_failed,
            reason?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.common_unknown_error),
        )
        Unrecognised -> context.getString(R.string.scan_unrecognised)
        is Imported -> context.getString(R.string.nodes_imported, imported)
        is PartiallyImported ->
            context.getString(R.string.nodes_imported_partial, imported, failed)
    }
}

@HiltViewModel
class ScanViewModel @Inject constructor(
    private val serverRepository: ServerRepository,
    private val subscriptionRepository: SubscriptionRepository,
) : ViewModel() {

    private val _result = MutableStateFlow<ScanOutcome?>(null)
    val result: StateFlow<ScanOutcome?> = _result.asStateFlow()

    private var handled = false

    /**
     * 处理扫到的内容。
     *
     * 二维码里既可能是节点分享链接，也可能是订阅地址 —— 机场两种码都在发。
     * 按内容形态自动分流，比让用户先选「这是节点还是订阅」体验好得多。
     */
    fun onScanned(content: String) {
        // 相机每秒出几十帧，识别成功后要立刻上锁，否则会重复导入
        if (handled) return
        handled = true

        val text = content.trim()
        viewModelScope.launch {
            _result.value = when {
                text.isBlank() -> ScanOutcome.Empty
                looksLikeSubscription(text) -> importSubscription(text)
                else -> importNodes(text)
            }
        }
    }

    /** 普通 http(s) 链接没有节点协议前缀，视作订阅地址。 */
    private fun looksLikeSubscription(text: String): Boolean {
        if (!text.startsWith("http://", true) && !text.startsWith("https://", true)) return false
        // http:// 也可能是 HTTP 代理节点，那种带 # 备注或用户信息
        return !text.contains('#') && !text.substringAfter("://").contains('@')
    }

    private suspend fun importSubscription(url: String): ScanOutcome =
        subscriptionRepository.addSubscription(url).fold(
            onSuccess = { ScanOutcome.Subscribed(it.groupName, it.nodeCount) },
            onFailure = { ScanOutcome.SubscribeFailed(it.message) },
        )

    private suspend fun importNodes(text: String): ScanOutcome {
        val outcome = serverRepository.importFromText(text)
        return when {
            outcome.imported == 0 -> ScanOutcome.Unrecognised
            outcome.failed == 0 -> ScanOutcome.Imported(outcome.imported)
            else -> ScanOutcome.PartiallyImported(outcome.imported, outcome.failed)
        }
    }
}
