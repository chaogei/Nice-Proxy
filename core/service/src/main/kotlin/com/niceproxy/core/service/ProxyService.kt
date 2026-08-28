package com.niceproxy.core.service

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.niceproxy.core.common.ApplicationScope
import com.niceproxy.core.config.ConfigResult
import com.niceproxy.core.data.ConfigRepository
import com.niceproxy.core.data.InboundRepository
import com.niceproxy.core.datastore.KeepAliveJournal
import com.niceproxy.core.datastore.SettingsDataStore
import com.niceproxy.core.model.ClashApiSettings
import com.niceproxy.core.model.InboundService
import com.niceproxy.core.model.InboundType
import com.niceproxy.core.model.NetworkPreference
import com.niceproxy.core.model.StartReason
import com.niceproxy.core.network.clash.ClashApiClient
import com.niceproxy.core.service.config.ConfigChangeWatcher
import com.niceproxy.core.service.config.ConfigDigest
import com.niceproxy.core.service.core.NiceCore
import com.niceproxy.core.service.network.NetworkAddressDiscovery
import com.niceproxy.core.service.network.NetworkBinder
import com.niceproxy.core.service.pac.PacScript
import com.niceproxy.core.service.pac.PacServer
import com.niceproxy.core.service.work.ProxyWatchdogScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 承载 sing-box 内核的前台服务。
 *
 * **前台服务类型选用 `specialUse`**：代理服务器不属于任何预定义类型，
 * 而 `dataSync` 在 Android 15 上有 24 小时内累计 6 小时的运行上限 ——
 * 对一个需要长期运行的网关来说是致命的。见 docs/DESIGN.md §6.8 与 §10 P-2。
 *
 * **关于线程。** `lifecycleScope` 跑在 `Dispatchers.Main.immediate` 上，所以这个文件里
 * 每一个 `launch` 的协程体默认都在主线程。真正会阻塞的东西只有内核启停与 PAC 的
 * socket 操作，它们分别由 [NiceCore] 和 [PacServer] 在各自内部切到 IO —— 这里刻意
 * 不再包一层 `withContext`，让「哪些调用会挂起」这件事由被调用方的签名说清楚。
 */
@AndroidEntryPoint
class ProxyService : LifecycleService() {

    @Inject lateinit var core: NiceCore
    @Inject lateinit var configRepository: ConfigRepository
    @Inject lateinit var inboundRepository: InboundRepository
    @Inject lateinit var settings: SettingsDataStore
    @Inject lateinit var controller: ProxyServiceController
    @Inject lateinit var notifications: ProxyNotifications
    @Inject lateinit var networkBinder: NetworkBinder
    @Inject lateinit var addressDiscovery: NetworkAddressDiscovery
    @Inject lateinit var configChanges: ConfigChangeWatcher
    @Inject lateinit var clashApi: ClashApiClient
    @Inject lateinit var pacServer: PacServer
    @Inject lateinit var watchdog: ProxyWatchdogScheduler
    @Inject lateinit var journal: KeepAliveJournal

    /**
     * 用于那些**必须活过服务销毁**的工作。
     *
     * 两类：一是「用户按了停止」这件事一定要落盘，否则看门狗过 15 分钟就会把它又拉
     * 起来；二是内核与 PAC 的关停 —— 它们现在是挂起操作，而 `lifecycleScope` 在
     * `onDestroy` 时就被取消了，放在那里跑等于把内核撂在半路上。见 [shutdownDetached]。
     */
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var startJob: Job? = null
    private var trafficJob: Job? = null
    private var networkJob: Job? = null
    private var configWatchJob: Job? = null
    private var networkSettleJob: Job? = null
    private var applyJob: Job? = null
    private var healthJob: Job? = null
    private var retryJob: Job? = null

    /** 内核当前跑的是哪一份配置。为 null 表示内核没在跑。 */
    private var runningConfig: AppliedConfig? = null

    /** 连续失败次数，用于退避。一旦成功启动就清零。 */
    private var failureStreak = 0

    /**
     * 启动时快照下来的「失败自动重试」设置。
     *
     * 快照而不是每次现读，是因为失败路径 [fail] 必须是同步的：它会调 [teardown]，
     * 而 teardown 会取消掉调用它的那个协程。同步执行才能保证在被取消前跑完。
     */
    private var autoRestartOnFailure = true

    /**
     * 通知正文的临时覆盖，目前只用于退避重试的说明。
     *
     * 需要是字段而不是参数：重试等待期间网络变化仍会触发通知刷新，
     * 不记住它的话那一下就把「30 秒后重试」冲掉了。
     */
    private var statusOverride: String? = null

    /** 内核最近一次成功启动的时刻，用来识别「起来了又立刻死」的反复横跳。 */
    private var lastCoreStartAt = 0L

    /**
     * 这一次启动是谁发起的，等启动成功后记账。
     *
     * 要用字段传递是因为发起点（`onStartCommand` / `reviveCore`）和记账点
     * （`onStarted`）之间隔着好几层挂起调用，而中间那些函数没有一个需要知道来源，
     * 一路加参数传下去只会污染签名。
     */
    private var pendingStartReason: StartReason? = null

    /**
     * 上一次真正下发出去的通知内容。
     *
     * 通知每秒要刷好几次，而 `notify` 是一次跨进程 Binder 调用。速率读数从 1.0 KB/s
     * 变成 1.04 KB/s 时渲染出来的文本完全一样，那次 IPC 就是纯浪费。
     */
    private var lastNotified: ProxyNotifications.Content? = null

    /**
     * 两个 PendingIntent 在 [onCreate] 建一次就够了。
     *
     * `getActivity` / `getService` 都要走 ActivityManager 的 Binder 调用，而以前它们
     * 是在每次刷新通知时现建的 —— 一秒钟四次的主线程 IPC，全部花在构造两个内容
     * 完全相同的对象上。
     */
    private var contentIntent: PendingIntent? = null
    private var stopIntent: PendingIntent? = null

    override fun onCreate() {
        super.onCreate()
        notifications.ensureChannel()
        contentIntent = launchIntent()
        stopIntent = stopPendingIntent(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent == null) {
            // intent 为 null 只出现在 START_STICKY 的进程重建。停止与失败两条路径都会
            // stopSelf()，服务只在启动中/运行中保持 started 状态 —— 所以能走到这里就说明
            // 被杀时代理确实开着，直接恢复。见 docs/DESIGN.md 风险 R-3。
            Log.i(TAG, "进程被回收后重建，恢复代理")
            startProxy(StartReason.STICKY_RESTART)
        } else {
            when (intent.action) {
                ACTION_START -> startProxy(readStartReason(intent))
                ACTION_STOP -> stopProxy()
                ACTION_RELOAD -> reapplyConfig()
                else -> if (!controller.state.value.isActive) stopSelf()
            }
        }
        return START_STICKY
    }

    // ------------------------------------------------------------------ 启动

    /**
     * 认不出来的来源一律按「用户手动」处理，而不是按恢复。
     *
     * 记错方向的代价不对称：把手动启动误记成中断，会凭空制造出「你的手机在杀后台」
     * 的假象，用户跑去折腾一堆本来就没问题的系统设置。宁可漏记也不要虚报。
     */
    private fun readStartReason(intent: Intent): StartReason {
        val raw = intent.getStringExtra(EXTRA_START_REASON) ?: return StartReason.USER
        return runCatching { StartReason.valueOf(raw) }.getOrDefault(StartReason.USER)
    }

    private fun startProxy(reason: StartReason) {
        if (controller.state.value.isActive) return
        pendingStartReason = reason
        controller.updateState(ProxyState.Starting)
        controller.updateTraffic(TrafficSnapshot())
        if (!promoteToForeground()) {
            // 这是 Android 12+ 的后台启动限制，重试也一样会被拦；
            // 出路是让用户关掉电池优化（那是官方豁免项之一），或者回到应用内手动开。
            fail("无法在后台启动，请关闭本应用的电池优化", cause = FailureCause.ForegroundStartBlocked)
            return
        }

        startJob = lifecycleScope.launch {
            try {
                launchCore()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // 这条路径上可能已经拿到了 WakeLock / WifiLock，必须交给 fail() 收尾，
                // 否则协程一死掉它们就永远留在手上持续耗电。
                Log.w(TAG, "启动流程异常中断", t)
                fail("内核启动失败", t.message, FailureCause.CoreStartFailed)
            }
        }
    }

    private suspend fun launchCore() {
        autoRestartOnFailure = settings.serviceSettings.first().autoRestartOnFailure

        val workDir = filesDir.absolutePath
        val prepared = when (val result = configRepository.build(workDir)) {
            is ConfigResult.Failure -> {
                // 配置本身不合法是确定性错误，重试一万次也是同样的结果，
                // 只会让用户盯着一个永远在「即将重试」的通知。
                fail(
                    result.errors.joinToString("；") { it.message },
                    cause = FailureCause.InvalidConfig,
                )
                return
            }
            is ConfigResult.Success -> prepare(result)
        }

        networkBinder.apply(prepared.networkPreference)

        core.start(prepared.json, workDir).fold(
            onSuccess = {
                // 生成配置加起内核有几百毫秒（远程 rule-set 还可能是几十秒），
                // 这期间用户完全可能已经按了停止。不检查的话状态会被改回 Running，
                // 而服务本身正在销毁。
                if (controller.state.value !is ProxyState.Starting) {
                    Log.i(TAG, "内核起来时已被要求停止，立即收回")
                    // NonCancellable：走到这里说明多半有人刚取消过 startJob，
                    // 而这一步要是被取消掉，就留下一个没人持有引用的内核在占着端口
                    withContext(NonCancellable) { core.stop() }
                    return
                }
                onStarted(prepared)
            },
            onFailure = { throwable ->
                fail(
                    translateStartFailure(throwable),
                    throwable.message,
                    FailureCause.CoreStartFailed,
                )
            },
        )
    }

    /**
     * 把生成结果、生成它时的入站快照、以及出站网卡偏好绑成一个整体。
     *
     * 后两项不能等到用的时候再查库：PAC 服务、首页展示的监听端点、进程级网卡绑定都由
     * 它们决定，而自愈重启时数据库里可能已经躺着用户尚未确认的改动。
     */
    private suspend fun prepare(result: ConfigResult.Success): AppliedConfig {
        val inbounds = inboundRepository.getAll().filter { it.enabled }
        val service = settings.serviceSettings.first()
        return AppliedConfig(
            json = result.json,
            fingerprint = result.fingerprint,
            restartKey = ConfigDigest.restartKey(
                configJson = result.json,
                inbounds = inbounds,
                networkPreference = service.networkPreference,
                pacDirectFallback = service.pacDirectFallback,
            ),
            inbounds = inbounds,
            networkPreference = service.networkPreference,
            warnings = result.warnings.map { it.message },
        )
    }

    private suspend fun onStarted(
        config: AppliedConfig,
        startedAtMillis: Long = System.currentTimeMillis(),
    ) {
        runningConfig = config
        // 内核刚起来，控制面此刻一定是通的。不主动放行的话，上一个实例死掉时打开的
        // 熔断还会挡住接下来几秒的每一次 REST —— 用户点节点切换得到的是「正在冷却」，
        // 而那几秒恰恰是他最急着确认代理有没有恢复的时候。
        clashApi.noteCoreAlive()
        controller.updateConfigOutdated(false)
        acquireLocks(settings.serviceSettings.first().keepWifiAwake)
        startPacIfConfigured(config.inbounds)

        controller.updateState(
            ProxyState.Running(
                startedAtMillis = startedAtMillis,
                listeningOn = config.inbounds.map { inbound ->
                    ListeningEndpoint(
                        inboundId = inbound.id,
                        typeLabel = inbound.type.displayName,
                        port = inbound.listenPort,
                        requiresAuth = inbound.auth != null,
                    )
                },
                warnings = config.warnings,
            ),
        )
        refreshNotification()
        observeTraffic()
        observeNetworkChanges()
        observeConfigChanges()
        superviseCore()

        // 刻意**不**在这里清零 failureStreak。
        // 「起来了」不等于「好了」：内核可能起来几秒又退出，那种反复横跳的情况下，
        // 每起来一次就清零会让退避永远停留在第一档，变成 10 秒一轮的无限空转。
        // 清零交给健康检查在确认它真的稳住之后做。
        lastCoreStartAt = System.currentTimeMillis()
        statusOverride = null
        retryJob?.cancel()

        // 记一笔。consume 掉是为了不让下一次内核重启复用同一个来源标签 ——
        // 那会把一次中断记成两次。
        pendingStartReason?.let { reason ->
            pendingStartReason = null
            journal.recordStart(reason)
        }

        // 落盘运行意图。进程可能在用户毫不知情的情况下被回收，
        // 届时内存里的一切都没了，只有这一位能告诉看门狗「它本来开着」。
        settings.setShouldBeRunning(true)
        watchdog.ensureScheduled()
        // 起来了，之前那条「无法自动恢复」的提醒就不该再挂着
        notifications.cancelRecoveryBlocked()
    }

    /**
     * PAC 由应用自身的 HTTP 服务提供，不经过 sing-box。
     *
     * 脚本内容按请求方使用的主机名动态生成：同一台设备可能同时挂在 Wi-Fi
     * 和热点上，客户端从哪个网段进来，PAC 里就该给哪个地址。
     */
    private suspend fun startPacIfConfigured(inbounds: List<InboundService>) {
        val pac = inbounds.firstOrNull { it.type == InboundType.PAC } ?: return
        val httpPort = inbounds
            .firstOrNull { it.type == InboundType.MIXED || it.type == InboundType.HTTP }
            ?.listenPort
        val socksPort = inbounds
            .firstOrNull { it.type == InboundType.MIXED || it.type == InboundType.SOCKS }
            ?.listenPort

        if (httpPort == null && socksPort == null) {
            Log.w(TAG, "启用了 PAC 但没有任何可用的代理入站，跳过")
            return
        }

        // 现读一次而不是捕获 settings：这个闭包会被每个 PAC 请求调用，
        // 而闭包在服务重启前不会重建，直接引用 Flow 会让开关改了也不生效
        val allowFallback = settings.serviceSettings.first().pacDirectFallback

        pacServer.start(pac.listenPort) { host ->
            PacScript.build(
                PacScript.Options(
                    host = host,
                    httpPort = httpPort,
                    socksPort = socksPort,
                    allowDirectFallback = allowFallback,
                ),
            )
        }
    }

    // ------------------------------------------------------------ 配置的重新应用

    /**
     * 响应「重新应用配置」。真正决定要不要重启的是指纹，见 [applyLatestConfig]。
     */
    private fun reapplyConfig() {
        if (controller.state.value !is ProxyState.Running) {
            // 没在跑就没有「重新应用」可言。服务可能正是被这条 intent 拉起来的，
            // 留着它在后台空转没有意义。
            if (!controller.state.value.isActive) stopSelf()
            return
        }
        runApply("重新应用配置") { applyLatestConfig() }
    }

    private suspend fun applyLatestConfig() {
        val running = runningConfig ?: return
        when (val result = configRepository.build(filesDir.absolutePath)) {
            is ConfigResult.Failure -> {
                // 新配置不合法时让旧配置继续跑：为一份还没改对的配置断掉所有客户端太粗暴。
                // 过期标记也继续留着，用户改好后再点一次就是了。
                controller.postConfigMessage(result.errors.joinToString("；") { it.message })
            }

            is ConfigResult.Success -> {
                val prepared = prepare(result)
                if (prepared.restartKey == running.restartKey) {
                    // 差异全落在可热切换的字段上（用户切过节点），内核不用动，
                    // 只把记录对齐，免得下一次自愈重启拿着过时的 JSON。
                    runningConfig = prepared
                    controller.updateConfigOutdated(false)
                    return
                }
                Log.i(
                    TAG,
                    "应用新配置 ${running.fingerprint.take(FINGERPRINT_LOG_LENGTH)} " +
                        "→ ${prepared.fingerprint.take(FINGERPRINT_LOG_LENGTH)}",
                )
                restartCore(prepared)
            }
        }
    }

    /**
     * 盯着所有会进入配置的数据，指纹和运行中的对不上就打「已过期」标记。
     *
     * 只打标记不重启：用户改配置往往连着改好几处，每改一处静默断一次流，
     * 对一个正在给别的设备供网的网关是灾难。见 docs/DESIGN.md §8.2。
     */
    private fun observeConfigChanges() {
        // 重启后不重新订阅：这个订阅与内核实例无关，重来一遍只是白白多跑一次比对
        if (configWatchJob?.isActive == true) return
        configWatchJob = lifecycleScope.launch {
            configChanges.changes().collectLatest {
                // 手写防抖。导入订阅、批量测速会在极短时间内刷出几十次写入，
                // 每次都重新生成一份完整配置纯属浪费。
                delay(CONFIG_SETTLE_DELAY_MS)
                try {
                    refreshOutdatedFlag()
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w(TAG, "配置指纹比对失败", t)
                }
            }
        }
    }

    private suspend fun refreshOutdatedFlag() {
        val running = runningConfig ?: return
        val outdated = when (val result = configRepository.build(filesDir.absolutePath)) {
            // 生成不出来同样是「和运行中的不一致」。具体错在哪等用户点了应用再讲，
            // 边改边弹错误只会打断输入。
            is ConfigResult.Failure -> true
            is ConfigResult.Success -> prepare(result).restartKey != running.restartKey
        }
        controller.updateConfigOutdated(outdated)
    }

    // -------------------------------------------------------------- 内核健康监督

    /**
     * 盯着内核有没有自己死掉。
     *
     * sing-box 在原生层跑，它因为 panic、OOM 或内部错误退出时，宿主这边**不会**
     * 收到任何通知：状态仍是 Running、通知栏仍显示正常、只有客户端会莫名其妙连不上。
     * 这是「用着用着就没了」里最难察觉的一种，因为界面上一切正常。
     *
     * **这里以前查的是 [NiceCore.isRunning]，而那恰恰查不出上面这件事。** 那一位只在
     * `Start()` 成功时置 true、`Close()` 里置 false，内核自己退出时没有任何代码会把它
     * 改回去 —— 换句话说，健康检查唯一要防的场景正是它测不到的场景。现在改成去请求
     * Clash API 的 `/version`：那是内核自己起的 HTTP 服务，它能答上话，就说明内核的
     * 事件循环还活着、监听套接字还在。这是端到端的证据，不是我们自己记的一笔账。
     *
     * 为什么不改去 Go 侧监听 `box` 的生命周期：sing-box 没有暴露「实例已终止」的通知
     * 通道，要做只能在 libnice 里另起一条 goroutine 去轮询内部状态，等于把同样的探测
     * 挪到一个更难观测、崩了还会直接 SIGABRT 的地方。探测 Clash API 的代价只是每
     * [HEALTH_CHECK_INTERVAL_MS] 一次的回环请求。
     */
    private fun superviseCore() {
        if (healthJob?.isActive == true) return
        healthJob = lifecycleScope.launch {
            val api = runCatching { settings.clashApiSettings() }.getOrElse {
                // 读不到就没法探测。宁可不监督也不能让未捕获异常炸掉整个进程 ——
                // 那可比「监督失灵」严重得多。
                Log.w(TAG, "读取 Clash API 配置失败，内核存活探测未启用", it)
                return@launch
            }
            val liveness = CoreLiveness(
                missesBeforeDead = HEALTH_MISSES_BEFORE_REVIVE,
                probe = { probeCore(api) },
                // 探到了就说出去，别让控制面的熔断继续挡着用户的操作
                onAlive = clashApi::noteCoreAlive,
            )
            var checksSinceMetrics = 0
            while (true) {
                delay(HEALTH_CHECK_INTERVAL_MS)
                if (controller.state.value !is ProxyState.Running) {
                    liveness.reset()
                    continue
                }

                when (liveness.check()) {
                    CoreLiveness.Verdict.ALIVE ->
                        // 稳住足够久才算真的好了，这时才把退避计数清掉
                        if (failureStreak > 0 && coreUptime() >= MIN_HEALTHY_UPTIME_MS) {
                            Log.i(TAG, "内核已稳定运行，重置退避计数")
                            failureStreak = 0
                        }

                    CoreLiveness.Verdict.WATCHING ->
                        Log.i(TAG, "内核存活探测第 ${liveness.consecutiveMisses} 次失败，再观察一轮")

                    CoreLiveness.Verdict.DEAD -> {
                        Log.w(TAG, "内核已连续 $HEALTH_MISSES_BEFORE_REVIVE 次不响应，尝试拉起")
                        runApply("内核自愈") { reviveCore() }
                    }
                }

                if (++checksSinceMetrics >= METRICS_LOG_EVERY_N_CHECKS) {
                    checksSinceMetrics = 0
                    logRuntimeMetrics()
                }
            }
        }
    }

    /**
     * 搭着存活探测的车打一行观测量。
     *
     * 不另起一个定时器：这些数字只在「有人在查一个说不清的现象」时才有人看，
     * 为它单独排一个协程，代价全落在从来不看它的那 99% 的会话上。
     */
    private fun logRuntimeMetrics() {
        if (!Log.isLoggable(TAG, Log.DEBUG)) return
        val pac = if (pacServer.isRunning) pacServer.metrics() else null
        Log.d(TAG, RuntimeMetricsLog.format(clashApi.metrics(), pac))
    }

    /**
     * @return 内核是否还活着。
     */
    private suspend fun probeCore(api: ClashApiSettings): Boolean {
        // 宿主这边都已经不持有实例了，那连探都不用探。这一步同时挡掉了
        // 「内核确实被停了，但 Clash API 端口恰好被别的进程接管」这种误判。
        if (!core.isRunning()) return false
        return clashApi.version(api).isSuccess
    }

    private suspend fun reviveCore() {
        // 排到执行时状态可能已经变了：用户按了停止，或者别的路径已经重启过内核
        if (controller.state.value !is ProxyState.Running) return
        val config = runningConfig ?: return
        if (probeCore(settings.clashApiSettings())) return

        // 内核确实死了，这是一次真实中断，即便服务本身从没断过。
        // 在重启之前记账：重启失败的话这笔更该留下。
        pendingStartReason = StartReason.CORE_REVIVE

        if (coreUptime() < MIN_HEALTHY_UPTIME_MS) {
            // 起来没多久就又死了，说明不是偶发故障。交给退避，
            // 否则就是每 10 秒重启一次内核的无限循环，电量和日志一起遭殃。
            fail("内核反复退出", cause = FailureCause.CoreExitedRepeatedly)
            return
        }

        // 走 restartCore 而不是直接 start：内核异常退出后，Kotlin 侧仍持有那个
        // Service 实例，不先 stop 掉的话 NiceCore.start 会被「内核已在运行」挡下来。
        restartCore(config)
    }

    private fun coreUptime(): Long = System.currentTimeMillis() - lastCoreStartAt

    // -------------------------------------------------------------- 网络切换自愈

    private fun observeNetworkChanges() {
        // 重启内核后绝不能重新订阅：registerDefaultNetworkCallback 会立刻回调一次
        // 当前网络，那一下又会排一次自愈重启，形成自我触发的死循环。
        if (networkJob?.isActive == true) return

        var current: Network? = null
        var isFirstCallback = true
        networkJob = networkBinder.defaultNetworkChanges()
            .onEach { network ->
                // 监听地址会随网络变化，通知里的端口信息与首页都要刷新
                refreshNotification()

                // onLost 只刷通知，不重启。理由有两条：其一，没网的时候重启内核毫无
                // 意义，起来了照样连不上；其二，每失败一次 failureStreak 就加一，而清零
                // 要求连续稳定 60 秒 —— 地铁、电梯这种抖动环境下永远达不到，六次之后就
                // 进终态失败了。
                if (network == null) return@onEach

                if (isFirstCallback) {
                    // 注册瞬间必然收到一次当前网络，那不是「切网」
                    isFirstCallback = false
                    current = network
                    return@onEach
                }
                // 同一张网络的重复通告（能力变化、断了又以同一 netId 回来）不算切网。
                // 只有真的换到另一张网络，QUIC 连接才必须重建。
                if (network == current) return@onEach

                current = network
                scheduleNetworkRecovery()
            }
            .launchIn(lifecycleScope)
    }

    /**
     * 切网瞬间 onAvailable / onLost 会连着来好几个回调，逐个重启内核既没必要，
     * 也会让代理在几秒内反复中断，所以等网络安静下来再动手。
     */
    private fun scheduleNetworkRecovery() {
        networkSettleJob?.cancel()
        networkSettleJob = lifecycleScope.launch {
            delay(NETWORK_SETTLE_DELAY_MS)
            runApply("网络切换自愈") { selfHeal() }
        }
    }

    private suspend fun selfHeal() {
        if (controller.state.value !is ProxyState.Running) return
        val running = runningConfig ?: return
        if (stopIfBoundAddressGone(running)) return

        // Hysteria2 / TUIC 的 QUIC 连接建在绑定了旧网络的 UDP socket 上，切网后
        // 不会自愈，只能整个内核重来。见 docs/DESIGN.md 风险 R-6。
        Log.i(TAG, "网络已切换，重启内核以重建 QUIC 连接")
        restartCore(configForSelfHeal(running))
    }

    /**
     * 自愈重启该用哪一份配置。
     *
     * 默认沿用运行中的那份：切网不是「用户按下了应用」，顺手把此刻数据库里尚未确认的
     * 改动生效，会让客户端莫名其妙连不上（§8.2 要求显式应用）。唯一的例外是重新生成的
     * 配置只在可热切换字段上有差异 —— 那说明用户中途切过节点，继续用旧 JSON 会把
     * 节点选择悄悄退回去。
     */
    private suspend fun configForSelfHeal(running: AppliedConfig): AppliedConfig {
        val rebuilt = configRepository.build(filesDir.absolutePath) as? ConfigResult.Success
            ?: return running
        val prepared = prepare(rebuilt)
        return if (prepared.restartKey == running.restartKey) prepared else running
    }

    /**
     * 省电模式（FR-5.4）。
     *
     * 监听在具体 IP 上的入站，一旦那个 IP 从设备上消失，监听套接字就再也等不到连接，
     * 继续举着 WakeLock 和 WifiLock 纯属白耗电。监听 `0.0.0.0` 的入站与网卡增减无关，
     * 不参与判定。
     */
    private suspend fun stopIfBoundAddressGone(running: AppliedConfig): Boolean {
        val watched = running.boundAddresses
        if (watched.isEmpty()) return false
        if (!settings.serviceSettings.first().powerSave) return false

        val missing = watched.filterNot { addressDiscovery.isAddressPresent(it) }
        if (missing.isEmpty()) return false

        Log.i(TAG, "省电模式：监听地址 ${missing.joinToString("、")} 已不存在，停止服务")
        // 这是用户在设置里明确要求的停机，同样要清掉运行意图 ——
        // 否则看门狗 15 分钟后又把它拉起来，省电模式就成了摆设
        stopProxy(forgetRunIntent = true)
        return true
    }

    /**
     * 就地重启内核：不退出前台服务，也不清掉监听端点，UI 只会看到短暂的「正在启动」。
     *
     * 刻意复用 [ProxyState.Starting] 而不是新增一个「重启中」状态 —— ProxyState 是
     * app 层多处穷举 when 的分支来源，加一个分支会牵动 core:service 之外的代码。
     */
    private suspend fun restartCore(config: AppliedConfig) {
        val startedAt = (controller.state.value as? ProxyState.Running)?.startedAtMillis
            ?: System.currentTimeMillis()

        controller.updateState(ProxyState.Starting)
        refreshNotification()
        trafficJob?.cancel()
        pacServer.stop()

        // 停与起之间现在**有**挂起点（两者都要切到 IO 上跑）。协程若在这中间被取消，
        // 内核会停在「停了还没起」的状态 —— 但那没问题：唯一会取消 applyJob 的是
        // [teardown]，而它接着就会把内核关停，停着正是它要的结果。
        core.stop()
        // bindProcessToNetwork 只影响之后新建的 socket，改了偏好就得赶在内核起来前落定。
        // 没改就别动：重新 requestNetwork 是异步的，中间那段空窗期反而会让出站临时跑回默认网络。
        if (runningConfig?.networkPreference != config.networkPreference) {
            networkBinder.apply(config.networkPreference)
        }
        val result = core.start(config.json, filesDir.absolutePath)

        result.fold(
            onSuccess = { onStarted(config, startedAtMillis = startedAt) },
            onFailure = { throwable ->
                fail(
                    translateStartFailure(throwable),
                    throwable.message,
                    FailureCause.CoreStartFailed,
                )
            },
        )
    }

    /**
     * 串行执行所有「会动到内核」的操作。
     *
     * 前一次还没做完就直接放弃这一次，而不是抢占 —— 抢占的代价是把内核撂在
     * 停了还没起的半路上。被放弃的那次也不会丢：过期标记还在，用户可以再点一次。
     */
    private fun runApply(what: String, block: suspend () -> Unit) {
        if (applyJob?.isActive == true) {
            Log.i(TAG, "已有配置变更在处理中，跳过：$what")
            return
        }
        applyJob = lifecycleScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "$what 失败", t)
                controller.postConfigMessage("$what 失败：${t.message ?: "未知错误"}")
            }
        }
    }

    // ------------------------------------------------------------------ 监控

    private fun observeTraffic() {
        trafficJob?.cancel()
        trafficJob = lifecycleScope.launch {
            val api = settings.clashApiSettings()
            // 内核重启会把自己的计数器清零，但对用户来说这仍是同一次会话，
            // 累计流量不该跟着归零。
            var totalUp = controller.traffic.value.totalUploadBytes
            var totalDown = controller.traffic.value.totalDownloadBytes
            clashApi.traffic(api)
                .catch { cause ->
                    // 内核停止时流断开是正常路径，不打扰用户；但必须留下日志。
                    // 之前这里是个空 catch，把「明文 HTTP 被网络安全策略拦截」
                    // 这个真实故障静默吞掉了，界面上只表现为速率恒为 0。
                    Log.w(TAG, "流量订阅中断", cause)
                }
                .onEach { frame ->
                    totalUp += frame.up
                    totalDown += frame.down
                    controller.updateTraffic(
                        TrafficSnapshot(
                            uploadBytesPerSecond = frame.up,
                            downloadBytesPerSecond = frame.down,
                            totalUploadBytes = totalUp,
                            totalDownloadBytes = totalDown,
                        ),
                    )
                    refreshNotification()
                }
                .launchIn(this)
        }
    }

    // ------------------------------------------------------------------ 停止

    /**
     * @param forgetRunIntent 这是一次「有意的」停止 —— 用户按了停止按钮/磁贴，
     *        或者省电模式按用户配置停机。只有这类停止会清掉落盘的运行意图，
     *        否则看门狗过一会儿就会把它又拉起来，用户会觉得这应用关都关不掉。
     *        进程被杀、内核崩溃这些「非自愿」的停止绝不能走这条路。
     */
    private fun stopProxy(forgetRunIntent: Boolean = true) {
        if (forgetRunIntent) clearRunIntent()
        if (controller.state.value is ProxyState.Stopped) {
            stopSelf()
            return
        }
        controller.updateState(ProxyState.Stopping)
        teardown()
        controller.updateTraffic(TrafficSnapshot())
        controller.updateState(ProxyState.Stopped)
        stopForeground(STOP_FOREGROUND_REMOVE)
        notifications.cancel()
        stopSelf()
    }

    /**
     * 一次启动或重启失败。
     *
     * 默认会退避重试而不是直接躺平：这个应用的失败大多是**暂时性**的 ——
     * 切换 Wi-Fi 时地址还没就绪、上一个内核实例的端口还没释放、刚开机时网络栈没起来。
     * 遇上这些就永久停机，对一个给全屋设备供网的网关来说代价太大。
     *
     * @param cause 见 [FailureCause]。判定「不可重试」的门槛刻意收得很窄 ——
     *        误判的代价是用户彻底失去自动恢复。
     */
    private fun fail(
        message: String,
        detail: String? = null,
        cause: FailureCause = FailureCause.Unknown,
    ) {
        failureStreak++
        if (RetryPolicy.shouldRetry(failureStreak, cause, autoRestartOnFailure)) {
            scheduleRetry(message)
            return
        }

        // 确定性错误要连运行意图一起清掉。留着那一位的话，看门狗每 15 分钟醒来一次、
        // 失败一次、弹一次通知，而界面上因为状态不是 active，只有「启动」没有「停止」
        // —— 用户根本按不到那个能让它安静下来的按钮。
        //
        // 次数耗尽（cause 是暂时性的）则相反：那一位要留着，网络说不定过一会儿就好了，
        // 15 分钟后的看门狗还有一次机会。想彻底放弃的用户走
        // [ProxyServiceController.stopAndForget]。
        if (RetryPolicy.shouldForgetRunIntent(cause)) clearRunIntent()

        teardown()
        controller.updateTraffic(TrafficSnapshot())
        controller.updateState(ProxyState.Failed(message, detail))
        stopForeground(STOP_FOREGROUND_REMOVE)
        notifications.cancel()
        stopSelf()
    }

    /**
     * 退避重试。指数增长并封顶，避免「端口被别的应用长期占着」这类
     * 短期内注定失败的情况把 CPU 和电量耗在无谓的重试上。
     *
     * 全程**保持前台服务与 WakeLock**：退避最长半分钟，这期间掉出前台的话，
     * 再想启动就会撞上 Android 12+ 的后台启动限制，反而把可恢复的失败变成不可恢复的。
     */
    private fun scheduleRetry(reason: String) {
        val delayMs = RetryPolicy.delayFor(failureStreak)
        Log.i(TAG, "$reason —— ${delayMs}ms 后第 $failureStreak 次重试")

        // 只停内核与 PAC，不动锁和前台状态
        shutdownDetached()
        trafficJob?.cancel()
        controller.updateState(ProxyState.Starting)
        statusOverride = getString(
            R.string.service_retry_pending,
            reason,
            delayMs / MILLIS_PER_SECOND,
        )
        refreshNotification()

        // 这里取消的一定是「已经跑完、正在收尾」的那个自己：能走到 fail() 说明
        // 启动路径正在栈上执行，不可能同时有另一个重试在 delay 里睡着。
        retryJob?.cancel()
        retryJob = lifecycleScope.launch {
            delay(delayMs)
            // 等待期间用户可能已经按了停止
            if (controller.state.value !is ProxyState.Starting) return@launch
            statusOverride = null
            refreshNotification()
            try {
                launchCore()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "重试失败", t)
                fail("内核启动失败", t.message, FailureCause.CoreStartFailed)
            }
        }
    }

    /**
     * 落盘「用户不想让它跑了」。
     *
     * 用 [appScope] 而不是 `lifecycleScope`：紧接着就是 `stopSelf()`，
     * 服务销毁会取消 lifecycleScope，这条写入很可能来不及落盘 ——
     * 而它没落盘的后果是看门狗把用户刚关掉的服务又拉起来。
     */
    private fun clearRunIntent() {
        watchdog.cancel()
        appScope.launch {
            runCatching { settings.setShouldBeRunning(false) }
                .onFailure { Log.w(TAG, "运行意图写入失败", it) }
            // 会话到此为止。中断历史不清 —— 那是跨会话的诊断依据，
            // 用户这次关掉了，不代表他不想知道昨天被杀过几次。
            journal.recordStop()
        }
    }

    /**
     * 释放全部运行期资源。停止、失败、销毁三条路径共用一份实现，
     * 免得某条路径漏掉某个句柄 —— WakeLock 漏一个就是持续耗电。
     */
    private fun teardown() {
        // 启动、应用、自愈、重试这几个协程都会去动内核，
        // 不掐掉的话它们醒来会把刚停下的内核又拉起来
        startJob?.cancel()
        applyJob?.cancel()
        networkSettleJob?.cancel()
        trafficJob?.cancel()
        networkJob?.cancel()
        configWatchJob?.cancel()
        healthJob?.cancel()
        retryJob?.cancel()
        startJob = null
        applyJob = null
        networkSettleJob = null
        trafficJob = null
        networkJob = null
        configWatchJob = null
        healthJob = null
        retryJob = null
        failureStreak = 0

        shutdownDetached()

        networkBinder.release()
        releaseLocks()
        runningConfig = null
        lastNotified = null
        controller.updateConfigOutdated(false)
    }

    /**
     * 关停内核与 PAC，且**保证跑完**。
     *
     * 三件事必须同时成立，缺一个就会留下孤儿内核 —— 服务已经没了，内核还在跑，
     * 端口还占着，而没有任何对象持有它的引用，只能靠杀进程收场：
     *
     * 1. 用 [appScope] 而不是 `lifecycleScope`。[teardown] 的调用方全是同步路径
     *    （`onDestroy`、`onStartCommand` 里的停止分支），而关停内核现在是挂起操作；
     *    放进 lifecycleScope 里，紧随其后的 `stopSelf()` 会把它整个取消掉。
     * 2. 用 [NonCancellable]。appScope 本身虽然不会被取消，但这一步的语义是
     *    「无论如何都要做完」，把它写进上下文里比依赖外部约定可靠。
     * 3. 用 [CoroutineStart.UNDISPATCHED]。**这一条不是为了快，是为了定序。**
     *    [NiceCore.stop] 的第一件事就是抢那把互斥量，UNDISPATCHED 让这个动作发生在
     *    本函数返回**之前**（互斥量空闲时 `lock()` 走的是不挂起的快路径，被占时则
     *    立刻排进 FIFO 等待队列）。否则「用户飞快地按停止再按启动」时，后来的 start
     *    完全可能抢在这次 stop 之前拿到锁，然后对着一个还没关掉的内核报端口占用。
     *    正因如此，`core.stop()` 必须排在 `pacServer.stop()` 前面 —— 反过来的话，
     *    抢锁这一步就被 PAC 的挂起点推到了另一个线程上，定序保证也就没了。
     */
    private fun shutdownDetached() {
        appScope.launch(NonCancellable, start = CoroutineStart.UNDISPATCHED) {
            core.stop().onFailure { Log.w(TAG, "内核关停时报错", it) }
            runCatching { pacServer.stop() }
                .onFailure { Log.w(TAG, "PAC 服务关停时报错", it) }
        }
    }

    override fun onDestroy() {
        teardown()
        if (controller.state.value !is ProxyState.Failed) {
            controller.updateTraffic(TrafficSnapshot())
            controller.updateState(ProxyState.Stopped)
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 杂项

    /**
     * 把内核的原始错误翻译成用户能看懂的话。
     * sing-box 的报错对普通用户几乎不可读，而端口占用又是最常见的失败原因。
     */
    private fun translateStartFailure(throwable: Throwable): String {
        val raw = throwable.message.orEmpty()
        return when {
            raw.contains("address already in use", ignoreCase = true) ||
                raw.contains("EADDRINUSE", ignoreCase = true) ->
                "端口已被其他应用占用，请换一个端口"
            raw.contains("permission denied", ignoreCase = true) ->
                "无权绑定该端口，请使用 1025 以上的端口"
            raw.contains("cannot assign requested address", ignoreCase = true) ->
                "监听地址在当前网络下不存在，请改为 0.0.0.0"
            // NiceCore 超时时抛的就是这句话。首次启动要下远程 rule-set，
            // 弱网下卡满一分钟是最常见的成因。
            raw.contains("内核启动超时") -> "内核启动超时，请检查网络后重试"
            else -> "内核启动失败"
        }
    }

    /**
     * @return 是否成功进入前台。Android 12+ 会在后台启动前台服务时抛异常 ——
     *         START_STICKY 的进程重建正好落在这个口子上，不接住就是一次崩溃。
     */
    private fun promoteToForeground(): Boolean {
        val content = notifications.content(controller.state.value, controller.traffic.value)
        val notification = notifications.build(content, contentIntent, requireStopIntent())
        lastNotified = content
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    ProxyNotifications.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(ProxyNotifications.NOTIFICATION_ID, notification)
            }
        }.onFailure { Log.w(TAG, "提升为前台服务失败", it) }.isSuccess
    }

    private fun refreshNotification() {
        val content = notifications.content(
            state = controller.state.value,
            traffic = controller.traffic.value,
            statusText = statusOverride,
        )
        // 内容一个字都没变的话，这次 notify 就只是一次白花的主线程 Binder 调用
        if (content == lastNotified) return
        lastNotified = content
        notifications.notify(notifications.build(content, contentIntent, requireStopIntent()))
    }

    /** [onCreate] 一定先于任何通知刷新执行，这里只是把可空性收掉。 */
    private fun requireStopIntent(): PendingIntent =
        stopIntent ?: stopPendingIntent(this).also { stopIntent = it }

    private fun launchIntent() =
        packageManager.getLaunchIntentForPackage(packageName)?.let { intent ->
            PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE,
            )
        }

    /**
     * Doze 会挂起网络，而代理网关必须保持可达。
     * WakeLock 防 CPU 休眠，WifiLock 防 Wi-Fi 射频进入省电模式。
     *
     * **WifiLock 在 Android 14+ 上基本指望不上，这一点必须说清楚。**
     * `WIFI_MODE_FULL_HIGH_PERF` 自 API 34 起被系统自动替换成
     * `WIFI_MODE_FULL_LOW_LATENCY`，而后者「只在屏幕亮着且应用在前台时生效」——
     * 恰恰把息屏这个唯一需要它的场景排除在外。改用 LOW_LATENCY 也一样，
     * 它本来就是给游戏和 VR 用的。
     *
     * 所以这里刻意继续用已废弃的 HIGH_PERF：在 API ≤ 33 上它是真的有用
     * （息屏与后台均生效），在 34+ 上退化成屏幕亮时有效，不比任何替代方案更差。
     * Android 14+ 想在息屏时保住连接，真正的手段只有前台服务加上电池优化白名单，
     * 后者是设置页里那个入口存在的根本原因。
     *
     * @param keepWifiAwake 关掉时只放弃 WifiLock。CPU 睡下去代理就整个不通了，
     *        所以 WakeLock 没有开关。
     */
    @Suppress("DEPRECATION")
    private fun acquireLocks(keepWifiAwake: Boolean) {
        if (wakeLock == null) {
            val power = getSystemService(PowerManager::class.java)
            wakeLock = power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                ?.apply { setReferenceCounted(false); acquire() }
        }

        if (!keepWifiAwake) {
            runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
            wifiLock = null
            return
        }
        if (wifiLock == null) {
            val wifi = applicationContext.getSystemService(WifiManager::class.java)
            wifiLock = wifi?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WIFI_LOCK_TAG)
                ?.apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
    }

    /** 已经（或即将）交给内核的一份配置，连同生成它时的入站快照。 */
    private data class AppliedConfig(
        val json: String,
        /** [ConfigResult.Success.fingerprint] 原样保留，用于识别「跑的到底是哪一份」。 */
        val fingerprint: String,
        /** 剔除可热切换字段后的指纹，只有它变了才必须重启内核，见 [ConfigDigest]。 */
        val restartKey: String,
        val inbounds: List<InboundService>,
        /** 出站走哪张网卡。不在内核配置里，由宿主的 ConnectivityManager 负责（§6.7）。 */
        val networkPreference: NetworkPreference,
        val warnings: List<String>,
    ) {
        /** 绑在具体 IP 上的监听地址，供省电模式判定。通配地址不算。 */
        val boundAddresses: List<String>
            get() = inbounds
                .map { it.listen }
                .filter { it.isNotBlank() && it !in WILDCARD_LISTEN }
                .distinct()
    }

    companion object {
        const val ACTION_START = "com.niceproxy.action.START"
        const val ACTION_STOP = "com.niceproxy.action.STOP"
        const val ACTION_RELOAD = "com.niceproxy.action.RELOAD"

        /** [StartReason] 的名字。缺失或认不出来时按用户手动处理。 */
        const val EXTRA_START_REASON = "com.niceproxy.extra.START_REASON"

        private const val TAG = "ProxyService"
        private const val WAKE_LOCK_TAG = "NiceProxy::Core"
        private const val WIFI_LOCK_TAG = "NiceProxy::Wifi"

        /** 切网时回调会连着来好几个，等它安静下来再重启内核。 */
        private const val NETWORK_SETTLE_DELAY_MS = 2_000L

        /**
         * 内核存活探测的间隔。一次回环 HTTP 请求，代价可以忽略，
         * 所以取一个「客户端还没来得及抱怨」的量级。
         */
        private const val HEALTH_CHECK_INTERVAL_MS = 10_000L

        /**
         * 连续这么多次探测不到才判定内核已死。
         *
         * 重启内核会断掉全屋设备的连接，为一次可能只是「刚好赶上系统卡了一下」的
         * 超时付这个代价不划算。三次配合 10 秒间隔，最坏是半分钟才发现，
         * 仍远快于看门狗那张 15 分钟的粗网。
         */
        private const val HEALTH_MISSES_BEFORE_REVIVE = 3

        /**
         * 每这么多次存活探测打一行运行期指标，即约一分钟一行。
         *
         * 再密就会把 logcat 里真正要看的东西挤出缓冲区 —— 这些数字全是单调累加的
         * 计数器，采样密度对趋势判断没有任何帮助。
         */
        private const val METRICS_LOG_EVERY_N_CHECKS = 6

        /**
         * 内核活过这么久才算「稳住了」，之前累积的退避计数到此清零。
         *
         * 取值要明显大于退避的最长间隔（30 秒），否则「等 30 秒 → 起来 → 立刻死」
         * 这种循环会被误判成恢复正常，退避永远回到第一档。
         */
        private const val MIN_HEALTHY_UPTIME_MS = 60_000L

        // 重试的次数上限与退避曲线见 [RetryPolicy]

        /** 订阅导入、批量测速会连着刷很多次写入，攒一攒再比对指纹。 */
        private const val CONFIG_SETTLE_DELAY_MS = 800L

        /** 日志里只需要够区分两份配置的前缀，完整的 64 位十六进制没人读。 */
        private const val FINGERPRINT_LOG_LENGTH = 12

        private const val MILLIS_PER_SECOND = 1_000L
    }
}

/** 监听在这些地址上就与具体网卡无关，网卡来去不影响可达性。 */
private val WILDCARD_LISTEN = setOf(InboundService.LISTEN_ALL, "::", "0:0:0:0:0:0:0:0")
