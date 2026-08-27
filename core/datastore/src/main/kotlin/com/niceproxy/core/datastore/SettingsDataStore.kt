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
    val shouldBeRunning: Flow<Boolean> = preferences.map { it[Keys.SHOULD_BE_RUNNING] ?: false }

    suspend fun setShouldBeRunning(value: Boolean) {
        dataStore.edit { it[Keys.SHOULD_BE_RUNNING] = value }
    }

    val dnsSettings: Flow<DnsSettings> = preferences.map { readDnsSettings(it) }

    val logSettings: Flow<LogSettings> = preferences.map { prefs ->
        LogSettings(
            level = prefs[Keys.LOG_LEVEL]
                ?.let { runCatching { LogLevel.valueOf(it) }.getOrNull() }
                ?: LogLevel.INFO,
        )
    }

    val outboundSettings: Flow<OutboundSettings> = preferences.map { prefs ->
        val default = OutboundSettings()
        OutboundSettings(
            selectedTag = prefs[Keys.SELECTED_OUTBOUND] ?: default.selectedTag,
            urlTestUrl = prefs[Keys.URLTEST_URL] ?: default.urlTestUrl,
            urlTestInterval = prefs[Keys.URLTEST_INTERVAL] ?: default.urlTestInterval,
            urlTestTolerance = prefs[Keys.URLTEST_TOLERANCE] ?: default.urlTestTolerance,
        )
    }

    val routingMode: Flow<RoutingMode> = preferences.map { prefs ->
        prefs[Keys.ROUTING_MODE]
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
        }
    }

    private fun readServiceSettings(prefs: Preferences) = ServiceSettings(
        autoStartOnBoot = prefs[Keys.AUTO_START_ON_BOOT] ?: false,
        startOnAppLaunch = prefs[Keys.START_ON_LAUNCH] ?: false,
        powerSave = prefs[Keys.POWER_SAVE] ?: false,
        keepWifiAwake = prefs[Keys.KEEP_WIFI_AWAKE] ?: true,
        networkPreference = prefs[Keys.NETWORK_PREFERENCE]
            ?.let { runCatching { NetworkPreference.valueOf(it) }.getOrNull() }
            ?: NetworkPreference.AUTO,
        ipv6Enabled = prefs[Keys.IPV6_ENABLED] ?: true,
        autoRestartOnFailure = prefs[Keys.AUTO_RESTART] ?: true,
    )

    private fun readDnsSettings(prefs: Preferences) = DnsSettings(
        remoteServer = prefs[Keys.DNS_REMOTE] ?: DnsSettings().remoteServer,
        localServer = prefs[Keys.DNS_LOCAL] ?: DnsSettings().localServer,
        strategy = prefs[Keys.DNS_STRATEGY] ?: DnsSettings().strategy,
    )

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
            prefs[Keys.CLASH_API_PORT] = prefs[Keys.CLASH_API_PORT]
                ?: (RANDOM_PORT_BASE + random.nextInt(RANDOM_PORT_RANGE))
            prefs[Keys.CLASH_API_SECRET] = validSecret(prefs[Keys.CLASH_API_SECRET])
                ?: ByteArray(SECRET_BYTES)
                    .also(random::nextBytes)
                    .joinToString("") { "%02x".format(it) }
        }
        return checkNotNull(readClashApiSettings(updated)) { "Clash API 配置刚写入就读不回来" }
    }

    private fun readClashApiSettings(prefs: Preferences): ClashApiSettings? {
        val port = prefs[Keys.CLASH_API_PORT] ?: return null
        val secret = validSecret(prefs[Keys.CLASH_API_SECRET]) ?: return null
        return ClashApiSettings(port, secret)
    }

    /**
     * 空 secret 在 Clash API 里等于「不需要认证」，一旦出现就是本机任意应用
     * 都能操控内核。宁可当成没生成过重新生成，也不能把它当有效值用。
     */
    private fun validSecret(value: String?): String? = value?.takeIf { it.isNotBlank() }

    private object Keys {
        val AUTO_START_ON_BOOT = booleanPreferencesKey("auto_start_on_boot")
        val START_ON_LAUNCH = booleanPreferencesKey("start_on_launch")
        val POWER_SAVE = booleanPreferencesKey("power_save")
        val KEEP_WIFI_AWAKE = booleanPreferencesKey("keep_wifi_awake")
        val NETWORK_PREFERENCE = stringPreferencesKey("network_preference")
        val IPV6_ENABLED = booleanPreferencesKey("ipv6_enabled")
        val AUTO_RESTART = booleanPreferencesKey("auto_restart_on_failure")

        /** 运行意图，非用户设置。见 [shouldBeRunning]。 */
        val SHOULD_BE_RUNNING = booleanPreferencesKey("should_be_running")

        val DNS_REMOTE = stringPreferencesKey("dns_remote")
        val DNS_LOCAL = stringPreferencesKey("dns_local")
        val DNS_STRATEGY = stringPreferencesKey("dns_strategy")

        val LOG_LEVEL = stringPreferencesKey("log_level")

        val SELECTED_OUTBOUND = stringPreferencesKey("selected_outbound")
        val URLTEST_URL = stringPreferencesKey("urltest_url")
        val URLTEST_INTERVAL = stringPreferencesKey("urltest_interval")
        val URLTEST_TOLERANCE = intPreferencesKey("urltest_tolerance")
        val ROUTING_MODE = stringPreferencesKey("routing_mode")

        val CLASH_API_PORT = intPreferencesKey("clash_api_port")
        val CLASH_API_SECRET = stringPreferencesKey("clash_api_secret")
    }

    private companion object {
        const val RANDOM_PORT_BASE = 19000
        const val RANDOM_PORT_RANGE = 900

        /** 192 位。远超暴力猜测所需，而这个值只在本机传递，长一点不花什么代价。 */
        const val SECRET_BYTES = 24
    }
}
