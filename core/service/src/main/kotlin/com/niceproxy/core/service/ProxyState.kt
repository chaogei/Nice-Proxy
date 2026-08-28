package com.niceproxy.core.service

/**
 * 代理服务的状态机：
 *
 * ```
 * Stopped ──start──> Starting ──ok──> Running ──stop──> Stopping ──> Stopped
 *                       │                                              ▲
 *                       └────────────── fail ──> Failed ───────────────┘
 * ```
 *
 * 合法迁移由 [ProxyStateMachine] 钉死，非法的那些会被 `ProxyServiceController` 丢弃。
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

/**
 * 哪些状态迁移是合法的。
 *
 * **为什么需要它。** 这个服务里有好几条互相看不见的路径会去改状态：启动流程、退避
 * 重试、切网自愈、内核自愈、用户按停止、服务销毁。它们全都跑在各自的协程里，而
 * 「改状态」这个动作本身没有任何门槛 —— 谁最后写谁说了算。于是有两类事故：
 *
 * - **状态复活**。用户按了停止，服务已经销毁、状态是 Stopped，而那条还在飞的启动
 *   协程终于等到内核起来，把状态改回 Running。界面上代理开着，实际上没有任何东西在
 *   跑，用户点停止也没反应（服务早就没了）。以前靠 `launchCore` 里手写一次
 *   `if (state !is Starting)` 挡着，但那只挡住了它自己那一条路径。
 * - **状态卡死**。Stopping 之后如果谁再写一次 Stopping，或者 Failed 之后又来一次
 *   Running，界面会永远停在一个用户操作不了的中间态。
 *
 * 把规则收成一张纯函数的表，既能让每条路径都受同一套约束，也终于能用 JVM 单测钉住。
 */
object ProxyStateMachine {

    /**
     * @return [to] 能不能从 [from] 迁移过去。false 表示这次更新应当被**丢弃**，
     *         而不是排队等待 —— 会被拒绝的更新全都来自已经过时的那条路径。
     */
    fun canTransition(from: ProxyState, to: ProxyState): Boolean =
        // 同类原地更新一律放行：Running 的端口列表、Failed 的文案都会就地刷新
        from.sameKindAs(to) || allowedFrom(from).any { it(to) }

    /**
     * 从 [from] 出发允许迁到哪些类型。
     *
     * 用谓词列表而不是类型集合，是因为 Running / Failed 带数据，没法用 `data object`
     * 那样直接比。
     */
    private fun allowedFrom(from: ProxyState): List<(ProxyState) -> Boolean> = when (from) {
        // 停着的时候只能被启动，或者直接落到一个失败（前台启动被系统拦下就是这样）
        ProxyState.Stopped -> listOf(isStarting, isFailed)

        // 启动中什么都可能：起来了、失败了、用户中途按了停止
        ProxyState.Starting -> listOf(isRunning, isFailed, isStopping, isStopped)

        // 跑着的时候可以就地重启（回到 Starting）、被停、或者内核死了
        is ProxyState.Running -> listOf(isStarting, isStopping, isStopped, isFailed)

        // **Stopping 不能回到 Running / Starting**。用户已经表达了停止的意愿，
        // 迟到的启动结果不该把它推翻 —— 那正是「关不掉」的来源。
        ProxyState.Stopping -> listOf(isStopped, isFailed)

        // **Failed 不能直接跳到 Running**。终态失败时服务已经 stopSelf 了，
        // 一个迟到的 Running 只会让界面显示一个根本不存在的运行中代理。
        is ProxyState.Failed -> listOf(isStarting, isStopped)
    }

    private fun ProxyState.sameKindAs(other: ProxyState): Boolean = when (this) {
        is ProxyState.Running -> other is ProxyState.Running
        is ProxyState.Failed -> other is ProxyState.Failed
        // 其余三个都是 data object，同一份实例即同一种状态
        else -> this === other
    }

    private val isStarting: (ProxyState) -> Boolean = { it is ProxyState.Starting }
    private val isRunning: (ProxyState) -> Boolean = { it is ProxyState.Running }
    private val isStopping: (ProxyState) -> Boolean = { it is ProxyState.Stopping }
    private val isStopped: (ProxyState) -> Boolean = { it is ProxyState.Stopped }
    private val isFailed: (ProxyState) -> Boolean = { it is ProxyState.Failed }
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
