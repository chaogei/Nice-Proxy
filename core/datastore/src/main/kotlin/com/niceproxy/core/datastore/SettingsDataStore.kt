package com.niceproxy.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.niceproxy.core.model.ClashApiSettings
import com.niceproxy.core.model.DnsSettings
import com.niceproxy.core.model.LogLevel
import com.niceproxy.core.model.LogSettings
import com.niceproxy.core.model.NetworkPreference
import com.niceproxy.core.model.OutboundSettings
import com.niceproxy.core.model.RoutingMode
import com.niceproxy.core.model.ServiceSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsDataStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {

    /**
     * 所有读取都走这里，不直接用 `dataStore.data`。
     *
     * `corruptionHandler` 只覆盖「文件解析不出来」，读取时的 I/O 错误
     * （磁盘满、权限、存储被卸载）依然会让流失败，而下游没有一个地方接得住：
     * `ConfigRepository` 会 `first()` 一下，抛出来就意味着代理起不来；
     * 设置页会直接崩。这些设置全都可重建，退回默认值比让整个应用不可用好。
     *
     * `catch` 里 emit 一次之后流就结束了，也就是说出错以后这条流不再推送
     * 更新 —— 这是 `catch` 的语义，也是可以接受的：拿到一份默认值继续跑，
     * 好过整条链路断掉。
     */
    private val preferences: Flow<Preferences> = dataStore.data.catch { cause ->
        if (cause is IOException) emit(emptyPreferences()) else throw cause
    }

    val serviceSettings: Flow<ServiceSettings> = preferences.map { readServiceSettings(it) }

    /**
     * 代理**应当**处于运行状态。这不是用户设置，是服务自己记的运行意图。
     *
     * 存在的理由是进程可能在用户毫不知情的情况下消失：系统内存回收、
     * 国产 ROM 的后台清理、内核崩溃。这些情况下内存里的一切状态都没了，
     * 只有落盘的这一位能回答「它本来是不是开着的」——
     * 看门狗据此决定要不要把它拉回来。
     *
     * 只有用户显式停止、省电模式停机、以及开机时未启用自启这三种情况会置 false。
     */
    val shouldBeRunning: Flow<Boolean> = preferences.map { it.stored(Keys.SHOULD_BE_RUNNING) ?: false }

    suspend fun setShouldBeRunning(value: Boolean) {
        dataStore.edit { it[Keys.SHOULD_BE_RUNNING] = value }
    }

    val dnsSettings: Flow<DnsSettings> = preferences.map { readDnsSettings(it) }

    val logSettings: Flow<LogSettings> = preferences.map { readLogSettings(it) }

    val outboundSettings: Flow<OutboundSettings> = preferences.map { readOutboundSettings(it) }

    val routingMode: Flow<RoutingMode> = preferences.map { prefs ->
        prefs.stored(Keys.ROUTING_MODE)
            ?.let { runCatching { RoutingMode.valueOf(it) }.getOrNull() }
            ?: RoutingMode.BYPASS_MAINLAND
    }

    suspend fun setSelectedOutbound(tag: String) {
        dataStore.edit { it[Keys.SELECTED_OUTBOUND] = tag }
    }

    suspend fun setRoutingMode(mode: RoutingMode) {
        dataStore.edit { it[Keys.ROUTING_MODE] = mode.name }
    }

    suspend fun setUrlTestUrl(url: String) {
        dataStore.edit { it[Keys.URLTEST_URL] = url }
    }

    /**
     * 变换必须发生在 `edit` **内部**，从 `prefs` 现读现改。
     *
     * 先 `first()` 读、在外面算好、再整体写回，是同一个反模式在
     * [clashApiSettings] 里已经批判过的那个：DataStore 只串行化 `edit` 里的
     * transform，两次调用在外面各自基于同一份旧值计算，后写的会把先写的
     * 整片覆盖掉。用户快速连着切两个开关，其中一个就静默弹回去了。
     */
    suspend fun updateDnsSettings(transform: (DnsSettings) -> DnsSettings) {
        dataStore.edit { prefs ->
            val updated = transform(readDnsSettings(prefs))
            prefs[Keys.DNS_REMOTE] = updated.remoteServer
            prefs[Keys.DNS_LOCAL] = updated.localServer
            prefs[Keys.DNS_STRATEGY] = updated.strategy
            prefs[Keys.DNS_SPLIT_BY_RULE_SET] = updated.splitByRuleSet
            prefs[Keys.DNS_DISABLE_CACHE] = updated.disableCache
        }
    }

    /** 同 [updateDnsSettings]，变换在事务内进行。 */
    suspend fun updateLogSettings(transform: (LogSettings) -> LogSettings) {
        dataStore.edit { prefs ->
            val updated = transform(readLogSettings(prefs))
            prefs[Keys.LOG_LEVEL] = updated.level.name
            prefs[Keys.LOG_TIMESTAMP] = updated.timestamp
            prefs[Keys.LOG_PERSIST] = updated.persist
            prefs[Keys.LOG_MAX_BUFFERED_LINES] = updated.maxBufferedLines
        }
    }

    /** 同 [updateDnsSettings]，变换在事务内进行。 */
    suspend fun updateOutboundSettings(transform: (OutboundSettings) -> OutboundSettings) {
        dataStore.edit { prefs ->
            val updated = transform(readOutboundSettings(prefs))
            prefs[Keys.SELECTED_OUTBOUND] = updated.selectedTag
            prefs[Keys.URLTEST_URL] = updated.urlTestUrl
            prefs[Keys.URLTEST_INTERVAL] = updated.urlTestInterval
            prefs[Keys.URLTEST_TOLERANCE] = updated.urlTestTolerance
            prefs[Keys.OUTBOUND_INTERRUPT_EXIST] = updated.interruptExistConnections
        }
    }

    /** 同 [updateDnsSettings]，变换在事务内进行。 */
    suspend fun updateServiceSettings(transform: (ServiceSettings) -> ServiceSettings) {
        dataStore.edit { prefs ->
            val updated = transform(readServiceSettings(prefs))
            prefs[Keys.AUTO_START_ON_BOOT] = updated.autoStartOnBoot
            prefs[Keys.START_ON_LAUNCH] = updated.startOnAppLaunch
            prefs[Keys.POWER_SAVE] = updated.powerSave
            prefs[Keys.KEEP_WIFI_AWAKE] = updated.keepWifiAwake
            prefs[Keys.NETWORK_PREFERENCE] = updated.networkPreference.name
            prefs[Keys.IPV6_ENABLED] = updated.ipv6Enabled
            prefs[Keys.AUTO_RESTART] = updated.autoRestartOnFailure
            prefs[Keys.PAC_DIRECT_FALLBACK] = updated.pacDirectFallback
        }
    }

    private fun readServiceSettings(prefs: Preferences) = ServiceSettings(
        autoStartOnBoot = prefs.stored(Keys.AUTO_START_ON_BOOT) ?: false,
        startOnAppLaunch = prefs.stored(Keys.START_ON_LAUNCH) ?: false,
        powerSave = prefs.stored(Keys.POWER_SAVE) ?: false,
        keepWifiAwake = prefs.stored(Keys.KEEP_WIFI_AWAKE) ?: true,
        networkPreference = prefs.stored(Keys.NETWORK_PREFERENCE)
            ?.let { runCatching { NetworkPreference.valueOf(it) }.getOrNull() }
            ?: NetworkPreference.AUTO,
        ipv6Enabled = prefs.stored(Keys.IPV6_ENABLED) ?: true,
        autoRestartOnFailure = prefs.stored(Keys.AUTO_RESTART) ?: true,
        pacDirectFallback = prefs.stored(Keys.PAC_DIRECT_FALLBACK) ?: false,
    )

    private fun readDnsSettings(prefs: Preferences): DnsSettings {
        val default = DnsSettings()
        return DnsSettings(
            remoteServer = prefs.stored(Keys.DNS_REMOTE) ?: default.remoteServer,
            localServer = prefs.stored(Keys.DNS_LOCAL) ?: default.localServer,
            // strategy 是自由字符串而不是枚举，读回来一个 sing-box 不认识的值
            // 会让**整份配置**被内核拒绝，代理直接起不来。它落盘的路径不止用户
            // 一条（备份恢复、旧版本残留、文件被外部改写），所以在读这一侧兜住。
            strategy = prefs.stored(Keys.DNS_STRATEGY)?.takeIf { it in DnsSettings.STRATEGIES }
                ?: default.strategy,
            splitByRuleSet = prefs.stored(Keys.DNS_SPLIT_BY_RULE_SET) ?: default.splitByRuleSet,
            disableCache = prefs.stored(Keys.DNS_DISABLE_CACHE) ?: default.disableCache,
        )
    }

    private fun readLogSettings(prefs: Preferences): LogSettings {
        val default = LogSettings()
        return LogSettings(
            level = prefs.stored(Keys.LOG_LEVEL)
                ?.let { runCatching { LogLevel.valueOf(it) }.getOrNull() }
                ?: default.level,
            timestamp = prefs.stored(Keys.LOG_TIMESTAMP) ?: default.timestamp,
            persist = prefs.stored(Keys.LOG_PERSIST) ?: default.persist,
            // 0 或负数会让日志缓冲区一行都留不住，界面上就是「日志页永远空白」，
            // 而用户根本不会把它和某个数字联系起来
            maxBufferedLines = prefs.stored(Keys.LOG_MAX_BUFFERED_LINES)?.takeIf { it > 0 }
                ?: default.maxBufferedLines,
        )
    }

    private fun readOutboundSettings(prefs: Preferences): OutboundSettings {
        val default = OutboundSettings()
        return OutboundSettings(
            selectedTag = prefs.stored(Keys.SELECTED_OUTBOUND) ?: default.selectedTag,
            urlTestUrl = prefs.stored(Keys.URLTEST_URL) ?: default.urlTestUrl,
            urlTestInterval = prefs.stored(Keys.URLTEST_INTERVAL) ?: default.urlTestInterval,
            urlTestTolerance = prefs.stored(Keys.URLTEST_TOLERANCE)?.takeIf { it >= 0 }
                ?: default.urlTestTolerance,
            interruptExistConnections = prefs.stored(Keys.OUTBOUND_INTERRUPT_EXIST)
                ?: default.interruptExistConnections,
        )
    }

    suspend fun setLogLevel(level: LogLevel) {
        dataStore.edit { it[Keys.LOG_LEVEL] = level.name }
    }

    /**
     * Clash API 的端口与密钥在首次使用时随机生成并固化。
     *
     * 端口随机是为了避开常见的 9090 占用，密钥随机是为了防止本机其他应用
     * 猜到并调用管理接口 —— 它能切换节点、断开连接、读取全部流量元数据。
     * 见 docs/DESIGN.md §6.9。
     *
     * **密钥不做落盘加密**，这是有意的：它只对本机 `127.0.0.1` 上正在运行的
     * 那个内核实例有效，离线拿到一份数据库文件的人拿它什么也做不了。而能读到
     * DataStore 文件的攻击者必然也能读到同一目录下的数据库，用 Keystore 加密它
     * 只是把成本转嫁给每次启动，换不到任何实际防护。
     */
    suspend fun clashApiSettings(): ClashApiSettings {
        readClashApiSettings(preferences.first())?.let { return it }

        // 生成路径必须整个塞进 edit：DataStore 会串行化 transform，
        // 而「先 read 再 edit」在并发下会让两个调用各生成一份 secret，
        // 后写的覆盖先写的，先返回的那个调用方拿着一个已经失效的 secret
        // 去调 Clash API，只会得到 401。服务启动与监控页同时初始化就会撞上。
        val updated = dataStore.edit { prefs ->
            val random = SecureRandom()
            prefs[Keys.CLASH_API_PORT] = validPort(prefs.stored(Keys.CLASH_API_PORT))
                ?: (RANDOM_PORT_BASE + random.nextInt(RANDOM_PORT_RANGE))
            prefs[Keys.CLASH_API_SECRET] = validSecret(prefs.stored(Keys.CLASH_API_SECRET))
                ?: ByteArray(SECRET_BYTES)
                    .also(random::nextBytes)
                    .joinToString("") { "%02x".format(it) }
        }
        return checkNotNull(readClashApiSettings(updated)) { "Clash API 配置刚写入就读不回来" }
    }

    private fun readClashApiSettings(prefs: Preferences): ClashApiSettings? {
        val port = validPort(prefs.stored(Keys.CLASH_API_PORT)) ?: return null
        val secret = validSecret(prefs.stored(Keys.CLASH_API_SECRET)) ?: return null
        return ClashApiSettings(port, secret)
    }

    /**
     * 空 secret 在 Clash API 里等于「不需要认证」，一旦出现就是本机任意应用
     * 都能操控内核。宁可当成没生成过重新生成，也不能把它当有效值用。
     */
    private fun validSecret(value: String?): String? = value?.takeIf { it.isNotBlank() }

    /**
     * 端口同样要当成可能被污染的值来读。
     *
     * 它会原样拼进 `external_controller`，而 sing-box 拒绝一个非法的
     * `external_controller` 的方式是**拒绝整份配置** —— 表现出来就是代理
     * 起不来，而报错指向的是一个用户从来没设置过、界面上也看不到的端口。
     * 判定为非法就当作没生成过，重新随机一个。
     */
    private fun validPort(value: Int?): Int? = value?.takeIf { it in 1..MAX_PORT }

    /**
     * 读取时按名字**和类型**取值，类型不符当作没有这个键。
     *
     * `Preferences` 的键相等只看名字，取值那一步是一个未经检查的强制转换。
     * 于是一个「名字对、类型不对」的条目会在读取处抛 `ClassCastException`——
     * 而上面那个 `catch` 只接 `IOException`，接不住它。后果是 `dnsSettings`
     * 这类流整条炸掉：设置页崩，`ConfigRepository` 生成不出配置，代理起不来。
     *
     * 这种条目不是假想的：某个键换过类型（`urltest_interval` 从秒数改成
     * `"3m"` 这种字符串就是一次），旧版本写的值就还躺在文件里；恢复一份来自
     * 不同版本的备份也一样。而 `corruptionHandler` 帮不上忙 —— 文件本身是
     * 完好的 protobuf，解析得出来，只是里面某个值的类型不是这一版期待的。
     *
     * 当作缺省处理，让那一个字段退回默认值，而不是让整组设置陪葬。
     */
    private inline fun <reified T : Any> Preferences.stored(key: Preferences.Key<T>): T? =
        asMap()[key] as? T

    private object Keys {
        val AUTO_START_ON_BOOT = booleanPreferencesKey("auto_start_on_boot")
        val START_ON_LAUNCH = booleanPreferencesKey("start_on_launch")
        val POWER_SAVE = booleanPreferencesKey("power_save")
        val KEEP_WIFI_AWAKE = booleanPreferencesKey("keep_wifi_awake")
        val NETWORK_PREFERENCE = stringPreferencesKey("network_preference")
        val IPV6_ENABLED = booleanPreferencesKey("ipv6_enabled")
        val AUTO_RESTART = booleanPreferencesKey("auto_restart_on_failure")
        val PAC_DIRECT_FALLBACK = booleanPreferencesKey("pac_direct_fallback")

        /** 运行意图，非用户设置。见 [shouldBeRunning]。 */
        val SHOULD_BE_RUNNING = booleanPreferencesKey("should_be_running")

        val DNS_REMOTE = stringPreferencesKey("dns_remote")
        val DNS_LOCAL = stringPreferencesKey("dns_local")
        val DNS_STRATEGY = stringPreferencesKey("dns_strategy")
        val DNS_SPLIT_BY_RULE_SET = booleanPreferencesKey("dns_split_by_rule_set")
        val DNS_DISABLE_CACHE = booleanPreferencesKey("dns_disable_cache")

        val LOG_LEVEL = stringPreferencesKey("log_level")
        val LOG_TIMESTAMP = booleanPreferencesKey("log_timestamp")
        val LOG_PERSIST = booleanPreferencesKey("log_persist")
        val LOG_MAX_BUFFERED_LINES = intPreferencesKey("log_max_buffered_lines")

        val SELECTED_OUTBOUND = stringPreferencesKey("selected_outbound")
        val URLTEST_URL = stringPreferencesKey("urltest_url")
        val URLTEST_INTERVAL = stringPreferencesKey("urltest_interval")
        val URLTEST_TOLERANCE = intPreferencesKey("urltest_tolerance")
        val OUTBOUND_INTERRUPT_EXIST = booleanPreferencesKey("outbound_interrupt_exist_connections")
        val ROUTING_MODE = stringPreferencesKey("routing_mode")

        val CLASH_API_PORT = intPreferencesKey("clash_api_port")
        val CLASH_API_SECRET = stringPreferencesKey("clash_api_secret")
    }

    private companion object {
        const val RANDOM_PORT_BASE = 19000
        const val RANDOM_PORT_RANGE = 900
        const val MAX_PORT = 65535

        /** 192 位。远超暴力猜测所需，而这个值只在本机传递，长一点不花什么代价。 */
        const val SECRET_BYTES = 24
    }
}
