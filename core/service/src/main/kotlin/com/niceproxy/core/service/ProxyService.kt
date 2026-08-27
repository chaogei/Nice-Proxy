package com.niceproxy.core.service

import android.content.Intent
import android.content.pm.ServiceInfo
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
import com.niceproxy.core.datastore.SettingsDataStore
import com.niceproxy.core.model.InboundService
import com.niceproxy.core.model.InboundType
import com.niceproxy.core.model.NetworkPreference
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 承载 sing-box 内核的前台服务。
 *
 * **前台服务类型选用 `specialUse`**：代理服务器不属于任何预定义类型，
 * 而 `dataSync` 在 Android 15 上有 24 小时内累计 6 小时的运行上限 ——
 * 对一个需要长期运行的网关来说是致命的。见 docs/DESIGN.md §6.8 与 §10 P-2。
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

    /**
     * 用于那些**必须活过服务销毁**的写操作。
     *
     * 「用户按了停止」这件事一定要落盘，否则看门狗过 15 分钟就会把它又拉起来。
     * 而 `lifecycleScope` 在 `onDestroy` 时就被取消了，`stopSelf()` 之后
     * 那点写入极可能来不及执行。
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

    override fun onCreate() {
        super.onCreate()
        notifications.ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent == null) {
            // intent 为 null 只出现在 START_STICKY 的进程重建。停止与失败两条路径都会
            // stopSelf()，服务只在启动中/运行中保持 started 状态 —— 所以能走到这里就说明
            // 被杀时代理确实开着，直接恢复。见 docs/DESIGN.md 风险 R-3。
            Log.i(TAG, "进程被回收后重建，恢复代理")
            startProxy()
        } else {
            when (intent.action) {
                ACTION_START -> startProxy()
                ACTION_STOP -> stopProxy()
                ACTION_RELOAD -> reapplyConfig()
                else -> if (!controller.state.value.isActive) stopSelf()
            }
        }
        return START_STICKY
    }

    // ------------------------------------------------------------------ 启动

    private fun startProxy() {
        if (controller.state.value.isActive) return
        controller.updateState(ProxyState.Starting)
        controller.updateTraffic(TrafficSnapshot())
        if (!promoteToForeground()) {
            // 这是 Android 12+ 的后台启动限制，重试也一样会被拦；
            // 出路是让用户关掉电池优化（那是官方豁免项之一），或者回到应用内手动开。
            fail("无法在后台启动，请关闭本应用的电池优化", retryable = false)
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
                fail("内核启动失败", t.message)
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
                fail(result.errors.joinToString("；") { it.message }, retryable = false)
                return
            }
            is ConfigResult.Success -> prepare(result)
        }

        networkBinder.apply(prepared.networkPreference)

        core.start(prepared.json, workDir).fold(
            onSuccess = {
                // 生成配置加起内核有几百毫秒，这期间用户完全可能已经按了停止。
                // 不检查的话状态会被改回 Running，而服务本身正在销毁。
                if (controller.state.value !is ProxyState.Starting) {
                    Log.i(TAG, "内核起来时已被要求停止，立即收回")
                    core.stop()
                    return
                }
                onStarted(prepared)
            },
            onFailure = { throwable ->
                fail(translateStartFailure(throwable), throwable.message)
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
        val preference = settings.serviceSettings.first().networkPreference
        return AppliedConfig(
            json = result.json,
            fingerprint = result.fingerprint,
            restartKey = ConfigDigest.restartKey(result.json, inbounds, preference),
            inbounds = inbounds,
            networkPreference = preference,
            warnings = result.warnings.map { it.message },
        )
    }

    private suspend fun onStarted(
        config: AppliedConfig,
        startedAtMillis: Long = System.currentTimeMillis(),
    ) {
        runningConfig = config
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
    private fun startPacIfConfigured(inbounds: List<InboundService>) {
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

        pacServer.start(pac.listenPort) { host ->
            PacScript.build(
                PacScript.Options(host = host, httpPort = httpPort, socksPort = socksPort),
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
     * [NiceCore.isRunning] 读的是 Go 侧的实例状态，是个纯内存判断，
     * 所以可以按秒级轮询而不心疼。
     */
    private fun superviseCore() {
        if (healthJob?.isActive == true) return
        healthJob = lifecycleScope.launch {
            while (true) {
                delay(HEALTH_CHECK_INTERVAL_MS)
                if (controller.state.value !is ProxyState.Running) continue

                if (core.isRunning) {
                    // 稳住足够久才算真的好了，这时才把退避计数清掉
                    if (failureStreak > 0 && coreUptime() >= MIN_HEALTHY_UPTIME_MS) {
                        Log.i(TAG, "内核已稳定运行，重置退避计数")
                        failureStreak = 0
                    }
                    continue
                }

                Log.w(TAG, "内核已不在运行，尝试拉起")
                runApply("内核自愈") { reviveCore() }
            }
        }
    }

    private suspend fun reviveCore() {
        // 排到执行时状态可能已经变了：用户按了停止，或者别的路径已经重启过内核
        if (controller.state.value !is ProxyState.Running) return
        if (core.isRunning) return
        val config = runningConfig ?: return

        if (coreUptime() < MIN_HEALTHY_UPTIME_MS) {
            // 起来没多久就又死了，说明不是偶发故障。交给退避，
            // 否则就是每 10 秒重启一次内核的无限循环，电量和日志一起遭殃。
            fail("内核反复退出")
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

        var isFirstCallback = true
        networkJob = networkBinder.defaultNetworkChanges()
            .onEach {
                // 监听地址会随网络变化，通知里的端口信息与首页都要刷新
                refreshNotification()
                if (isFirstCallback) {
                    // 注册瞬间必然收到一次当前网络，那不是「切网」
                    isFirstCallback = false
                    return@onEach
                }
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

        // 停与起之间没有挂起点，因此协程即使此刻被取消，也不会把内核卡在
        // 「停了还没起」的中间态上。
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
                fail(translateStartFailure(throwable), throwable.message)
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
     * @param retryable 确定性错误（配置不合法、后台启动被系统拦下）传 false，
     *        重试只会变成一个永远在倒计时却永远失败的通知。
     */
    private fun fail(message: String, detail: String? = null, retryable: Boolean = true) {
        failureStreak++
        if (RetryPolicy.shouldRetry(failureStreak, retryable, autoRestartOnFailure)) {
            scheduleRetry(message)
            return
        }

        // 彻底放弃。刻意**不**清运行意图：网络可能过一会儿就好了，
        // 留着这一位，15 分钟后的看门狗还有一次机会。用户看到的是失败通知，
        // 想彻底关掉按停止即可。
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

        // 只停内核，不动锁和前台状态
        core.stop()
        pacServer.stop()
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
                fail("内核启动失败", t.message)
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

        pacServer.stop()
        core.stop()
        networkBinder.release()
        releaseLocks()
        runningConfig = null
        controller.updateConfigOutdated(false)
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
            else -> "内核启动失败"
        }
    }

    /**
     * @return 是否成功进入前台。Android 12+ 会在后台启动前台服务时抛异常 ——
     *         START_STICKY 的进程重建正好落在这个口子上，不接住就是一次崩溃。
     */
    private fun promoteToForeground(): Boolean {
        val notification = notifications.build(
            state = controller.state.value,
            traffic = controller.traffic.value,
            contentIntent = launchIntent(),
            stopIntent = stopPendingIntent(this),
        )
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
        notifications.notify(
            notifications.build(
                state = controller.state.value,
                traffic = controller.traffic.value,
                contentIntent = launchIntent(),
                stopIntent = stopPendingIntent(this),
                statusText = statusOverride,
            ),
        )
    }

    private fun launchIntent() =
        packageManager.getLaunchIntentForPackage(packageName)?.let { intent ->
            android.app.PendingIntent.getActivity(
                this,
                0,
                intent,
                android.app.PendingIntent.FLAG_IMMUTABLE,
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

        private const val TAG = "ProxyService"
        private const val WAKE_LOCK_TAG = "NiceProxy::Core"
        private const val WIFI_LOCK_TAG = "NiceProxy::Wifi"

        /** 切网时回调会连着来好几个，等它安静下来再重启内核。 */
        private const val NETWORK_SETTLE_DELAY_MS = 2_000L

        /**
         * 内核存活检查的间隔。读的是内存里的一个布尔量，几乎不花代价，
         * 所以取一个「客户端还没来得及抱怨」的量级。
         */
        private const val HEALTH_CHECK_INTERVAL_MS = 10_000L

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
