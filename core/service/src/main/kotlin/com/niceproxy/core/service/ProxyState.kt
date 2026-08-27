package com.niceproxy.core.service

/**
 * 代理服务的状态机：
 *
 * ```
 * Stopped ──start──> Starting ──ok──> Running ──stop──> Stopping ──> Stopped
 *                       │                                              ▲
 *                       └────────────── fail ──> Failed ───────────────┘
 * ```
 */
sealed interface ProxyState {
    data object Stopped : ProxyState
    data object Starting : ProxyState

    data class Running(
        val startedAtMillis: Long,
        val listeningOn: List<ListeningEndpoint>,
        /** 生成配置时被跳过的无效节点等非致命问题，UI 可提示但不阻断。 */
        val warnings: List<String> = emptyList(),
    ) : ProxyState

    data object Stopping : ProxyState

    data class Failed(
        val message: String,
        /** 内核返回的原始错误，便于用户反馈问题时附上。 */
        val detail: String? = null,
    ) : ProxyState

    val isActive: Boolean
        get() = this is Running || this is Starting
}

/** 一个正在监听的端点，用于在首页告诉用户「电脑上该填什么」。 */
data class ListeningEndpoint(
    val inboundId: String,
    val typeLabel: String,
    val port: Int,
    val requiresAuth: Boolean,
)

/** 实时速率，单位字节/秒。 */
data class TrafficSnapshot(
    val uploadBytesPerSecond: Long = 0,
    val downloadBytesPerSecond: Long = 0,
    val totalUploadBytes: Long = 0,
    val totalDownloadBytes: Long = 0,
)
