package com.niceproxy.feature.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.niceproxy.core.data.ServerRepository
import com.niceproxy.core.data.SubscriptionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ScanViewModel @Inject constructor(
    private val serverRepository: ServerRepository,
    private val subscriptionRepository: SubscriptionRepository,
) : ViewModel() {

    private val _result = MutableStateFlow<String?>(null)
    val result: StateFlow<String?> = _result.asStateFlow()

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
                text.isBlank() -> "扫到的内容为空"
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

    private suspend fun importSubscription(url: String): String =
        subscriptionRepository.addSubscription(url).fold(
            onSuccess = { "「${it.groupName}」已导入 ${it.nodeCount} 个节点" },
            onFailure = { "订阅导入失败：${it.message}" },
        )

    private suspend fun importNodes(text: String): String {
        val outcome = serverRepository.importFromText(text)
        return when {
            outcome.imported == 0 -> "无法识别这个二维码的内容"
            outcome.failed == 0 -> "已导入 ${outcome.imported} 个节点"
            else -> "已导入 ${outcome.imported} 个，${outcome.failed} 个无法识别"
        }
    }
}
