package com.niceproxy.core.datastore

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.niceproxy.core.model.DnsSettings
import com.niceproxy.core.model.LogLevel
import com.niceproxy.core.model.LogSettings
import com.niceproxy.core.model.NetworkPreference
import com.niceproxy.core.model.OutboundSettings
import com.niceproxy.core.model.RoutingMode
import com.niceproxy.core.model.ServiceSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.lang.reflect.Modifier

class SettingsDataStoreTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var harness: PreferencesHarness

    @BeforeEach
    fun setUp() {
        harness = PreferencesHarness(File(tempDir, "settings.preferences_pb"))
    }

    // ---------------------------------------------------------------- 往返

    @Nested
    @DisplayName("Round-trip")
    inner class RoundTrip {

        @Test
        fun `服务设置的每个字段都活过一次进程重启`() = runBlocking {
            val mutated = ServiceSettings(
                autoStartOnBoot = true,
                startOnAppLaunch = true,
                powerSave = true,
                keepWifiAwake = false,
                networkPreference = NetworkPreference.CELLULAR,
                ipv6Enabled = false,
                autoRestartOnFailure = false,
                pacDirectFallback = true,
            )
            assertEveryFieldDiffersFromDefault(ServiceSettings(), mutated)

            harness.settings().updateServiceSettings { mutated }
            harness.restart()

            assertThat(harness.settings().serviceSettings.first()).isEqualTo(mutated)
        }

        @Test
        fun `DNS 设置的每个字段都活过一次进程重启`() = runBlocking {
            val mutated = DnsSettings(
                remoteServer = "https://dns.google/dns-query",
                localServer = "119.29.29.29",
                strategy = "ipv4_only",
                splitByRuleSet = false,
                disableCache = true,
            )
            assertEveryFieldDiffersFromDefault(DnsSettings(), mutated)

            harness.settings().updateDnsSettings { mutated }
            harness.restart()

            assertThat(harness.settings().dnsSettings.first()).isEqualTo(mutated)
        }

        @Test
        fun `日志设置的每个字段都活过一次进程重启`() = runBlocking {
            val mutated = LogSettings(
                level = LogLevel.TRACE,
                timestamp = false,
                persist = true,
                maxBufferedLines = 500,
            )
            assertEveryFieldDiffersFromDefault(LogSettings(), mutated)

            harness.settings().updateLogSettings { mutated }
            harness.restart()

            assertThat(harness.settings().logSettings.first()).isEqualTo(mutated)
        }

        @Test
        fun `出站设置的每个字段都活过一次进程重启`() = runBlocking {
            val mutated = OutboundSettings(
                selectedTag = "香港 01",
                urlTestUrl = "https://cp.cloudflare.com/generate_204",
                urlTestInterval = "10m",
                urlTestTolerance = 200,
                interruptExistConnections = true,
            )
            assertEveryFieldDiffersFromDefault(OutboundSettings(), mutated)

            harness.settings().updateOutboundSettings { mutated }
            harness.restart()

            assertThat(harness.settings().outboundSettings.first()).isEqualTo(mutated)
        }

        @Test
        fun `路由模式活过一次进程重启`() = runBlocking {
            harness.settings().setRoutingMode(RoutingMode.GLOBAL_PROXY)
            harness.restart()

            assertThat(harness.settings().routingMode.first()).isEqualTo(RoutingMode.GLOBAL_PROXY)
        }

        @Test
        fun `运行意图活过一次进程重启`() = runBlocking {
            // 这一位存在的全部理由就是活过进程消失：看门狗醒来时靠它判断
            // 「它本来是不是开着的」，没活下来就等于保活整个失效
            harness.settings().setShouldBeRunning(true)
            harness.restart()

            assertThat(harness.settings().shouldBeRunning.first()).isTrue()
        }

        @Test
        fun `单字段 setter 不会顺手把同组的其他字段抹回默认`() = runBlocking {
            val settings = harness.settings()
            settings.updateOutboundSettings {
                it.copy(urlTestInterval = "10m", interruptExistConnections = true)
            }

            // 这三个 setter 是设置页在用的，走的是 edit 单键写入而不是整组覆盖
            settings.setSelectedOutbound("日本 02")
            settings.setUrlTestUrl("https://example.com/204")
            settings.setLogLevel(LogLevel.DEBUG)
            harness.restart()

            val outbound = harness.settings().outboundSettings.first()
            assertThat(outbound.selectedTag).isEqualTo("日本 02")
            assertThat(outbound.urlTestUrl).isEqualTo("https://example.com/204")
            assertThat(outbound.urlTestInterval).isEqualTo("10m")
            assertThat(outbound.interruptExistConnections).isTrue()
        }
    }

    // -------------------------------------------------------------- 键名固化

    @Nested
    @DisplayName("Keys")
    inner class Keys {

        /**
         * 键名是**落盘格式的一部分**。
         *
         * 改一个键名，编译器不会说话，测试不会红，但每个已经装着这个应用的用户
         * 在升级后都会发现对应的设置悄悄回到了默认值 —— 而这个应用里
         * 「自启」「省电停机」这类开关关掉的后果，用户往往几天后才发现。
         * 这条测试的作用就是让这种改动必须是显式的。
         */
        @Test
        fun `落盘的键名不能被悄悄改掉`() = runBlocking {
            harness.settings().updateServiceSettings { it.copy(powerSave = true) }
            harness.settings().updateDnsSettings { it.copy(disableCache = true) }
            harness.settings().updateLogSettings { it.copy(persist = true) }
            harness.settings().updateOutboundSettings {
                it.copy(interruptExistConnections = true)
            }
            harness.settings().setRoutingMode(RoutingMode.CUSTOM)
            harness.settings().setShouldBeRunning(true)
            harness.restart()

            val stored = harness.dataStore.data.first()
            val names = stored.asMap().keys.map { it.name }
            assertThat(names).containsAtLeast(
                "auto_start_on_boot",
                "start_on_launch",
                "power_save",
                "keep_wifi_awake",
                "network_preference",
                "ipv6_enabled",
                "auto_restart_on_failure",
                "pac_direct_fallback",
                "should_be_running",
                "dns_remote",
                "dns_local",
                "dns_strategy",
                "dns_split_by_rule_set",
                "dns_disable_cache",
                "log_level",
                "log_timestamp",
                "log_persist",
                "log_max_buffered_lines",
                "selected_outbound",
                "urltest_url",
                "urltest_interval",
                "urltest_tolerance",
                "outbound_interrupt_exist_connections",
                "routing_mode",
            )
        }
    }

    // ---------------------------------------------------------- 脏数据不得崩

    @Nested
    @DisplayName("Corruption")
    inner class Corruption {

        @Test
        fun `枚举值认不出来时退回默认值而不是抛异常`() = runBlocking {
            harness.dataStore.edit { prefs ->
                // 降级安装、手改文件、恢复了一份更新版本的备份，都会留下这种值
                prefs[stringPreferencesKey("log_level")] = "VERBOSE"
                prefs[stringPreferencesKey("routing_mode")] = "TUN"
                prefs[stringPreferencesKey("network_preference")] = "SATELLITE"
            }
            harness.restart()

            val settings = harness.settings()
            assertThat(settings.logSettings.first().level).isEqualTo(LogLevel.INFO)
            assertThat(settings.routingMode.first()).isEqualTo(RoutingMode.BYPASS_MAINLAND)
            assertThat(settings.serviceSettings.first().networkPreference)
                .isEqualTo(NetworkPreference.AUTO)
        }

        @Test
        fun `枚举被污染后写回的是一个认得出来的值`() = runBlocking {
            harness.dataStore.edit {
                it[stringPreferencesKey("network_preference")] = "SATELLITE"
            }
            // 用户在设置页随便动了一个别的开关，读-改-写这一轮应该顺手把
            // 脏值冲掉；否则它会一直躺在文件里，每次读都要靠兜底救一次
            harness.settings().updateServiceSettings { it.copy(powerSave = true) }
            harness.restart()

            val raw = harness.dataStore.data.first()[stringPreferencesKey("network_preference")]
            assertThat(NetworkPreference.entries.map { it.name }).contains(raw)
        }

        @Test
        fun `DNS 策略是自由字符串，认不出来的值不能原样进配置`() = runBlocking {
            // sing-box 拒绝非法 strategy 的方式是拒绝整份配置，代理直接起不来
            harness.dataStore.edit {
                it[stringPreferencesKey("dns_strategy")] = "prefer_ipv7"
            }
            harness.restart()

            val strategy = harness.settings().dnsSettings.first().strategy
            assertThat(DnsSettings.STRATEGIES).contains(strategy)
            assertThat(strategy).isEqualTo(DnsSettings().strategy)
        }

        @Test
        fun `日志缓冲行数为零时退回默认值`() = runBlocking {
            // 0 的表现是「日志页永远空白」，而用户不会把它和某个数字联系起来
            harness.dataStore.edit { it[intPreferencesKey("log_max_buffered_lines")] = 0 }
            harness.restart()

            assertThat(harness.settings().logSettings.first().maxBufferedLines)
                .isEqualTo(LogSettings().maxBufferedLines)
        }

        @Test
        fun `文件不是 protobuf 时重置成默认值，而不是让每次读都抛异常`() = runBlocking {
            harness.settings().updateServiceSettings { it.copy(autoStartOnBoot = true) }
            harness.corruptFile()

            // 关键不在于「设置丢了」，而在于 ConfigRepository 还能 first() 出
            // 一份配置来。抛出去的话代理永远起不来，且没有任何恢复路径
            assertThat(harness.settings().serviceSettings.first()).isEqualTo(ServiceSettings())
        }

        @Test
        fun `损坏重置之后还能继续写`() = runBlocking {
            harness.corruptFile()

            harness.settings().updateServiceSettings { it.copy(powerSave = true) }
            harness.restart()

            assertThat(harness.settings().serviceSettings.first().powerSave).isTrue()
        }

        @Test
        fun `类型对不上的键不会连累同一组里的其他字段`() = runBlocking {
            harness.settings().updateServiceSettings { it.copy(powerSave = true) }
            // Preferences 是按 (名字, 类型) 取值的，类型不符等同于「没有这个键」。
            // 断言的是这一点不会升级成异常，把整组设置一起带走
            harness.dataStore.edit { it[stringPreferencesKey("ipv6_enabled")] = "yes" }
            harness.restart()

            val service = harness.settings().serviceSettings.first()
            assertThat(service.powerSave).isTrue()
            assertThat(service.ipv6Enabled).isEqualTo(ServiceSettings().ipv6Enabled)
        }
    }

    // ------------------------------------------------------------ 凭据降级

    @Nested
    @DisplayName("ClashApi")
    inner class ClashApi {

        @Test
        fun `端口和密钥只生成一次，之后固定下来`() = runBlocking {
            val first = harness.settings().clashApiSettings()
            harness.restart()
            val second = harness.settings().clashApiSettings()

            assertThat(second).isEqualTo(first)
            assertThat(first.secret).hasLength(48)
            assertThat(first.port).isIn(19000..19899)
        }

        @Test
        fun `空密钥要重新生成，不能当成不需要认证`() = runBlocking {
            val original = harness.settings().clashApiSettings()
            // 空 secret 在 Clash API 里等于关掉认证：本机任意应用都能切节点、
            // 断连接、读取全部流量元数据
            harness.dataStore.edit { it[stringPreferencesKey("clash_api_secret")] = "   " }
            harness.restart()

            val repaired = harness.settings().clashApiSettings()
            assertThat(repaired.secret).isNotEmpty()
            assertThat(repaired.secret.isBlank()).isFalse()
            assertThat(repaired.secret).isNotEqualTo(original.secret)
            // 端口是好的，不该被一起重置：换端口意味着已经连着的监控页要重连
            assertThat(repaired.port).isEqualTo(original.port)
        }

        @Test
        fun `端口越界要重新生成，而不是拼出一份内核不认的配置`() = runBlocking {
            harness.settings().clashApiSettings()
            harness.dataStore.edit { it[intPreferencesKey("clash_api_port")] = 0 }
            harness.restart()

            val repaired = harness.settings().clashApiSettings()
            assertThat(repaired.port).isIn(1..65535)
            assertThat(repaired.externalController).isEqualTo("127.0.0.1:${repaired.port}")
        }

        @Test
        fun `并发首次初始化只会得到同一份凭据`() = runBlocking {
            // 服务启动和监控页初始化会同时撞上来。各自生成一份的话，后写的
            // 覆盖先写的，先返回的那个调用方拿着失效 secret 去调 API 只会拿到 401
            val settings = harness.settings()
            val results = withContext(Dispatchers.IO) {
                List(8) { async { settings.clashApiSettings() } }.awaitAll()
            }

            assertThat(results.toSet()).hasSize(1)
        }
    }

    // -------------------------------------------------------------- 并发写

    @Nested
    @DisplayName("Concurrency")
    inner class Concurrency {

        @Test
        fun `并发改不同开关时不会互相覆盖`() = runBlocking {
            // 变换发生在 edit 内部才有这个性质。先 first() 读、在外面算好、
            // 再整体写回的话，两个调用基于同一份旧值计算，后写的会把先写的
            // 整片盖掉，用户快速连点两个开关就有一个静默弹回去
            val settings = harness.settings()
            withContext(Dispatchers.IO) {
                listOf(
                    async { settings.updateServiceSettings { it.copy(autoStartOnBoot = true) } },
                    async { settings.updateServiceSettings { it.copy(powerSave = true) } },
                    async { settings.updateServiceSettings { it.copy(ipv6Enabled = false) } },
                    async { settings.updateServiceSettings { it.copy(pacDirectFallback = true) } },
                ).awaitAll()
            }
            harness.restart()

            val service = harness.settings().serviceSettings.first()
            assertThat(service.autoStartOnBoot).isTrue()
            assertThat(service.powerSave).isTrue()
            assertThat(service.ipv6Enabled).isFalse()
            assertThat(service.pacDirectFallback).isTrue()
        }
    }
}

/**
 * 断言这份「改过的」实例真的每个字段都和默认值不同。
 *
 * 没有它，round-trip 断言是自证的：漏掉一个字段不去改，它在两边都是默认值，
 * `isEqualTo` 照样过。用反射而不是逐个列出来，是为了让**以后新增的字段**
 * 也自动落进这张网 —— 加了字段却忘了落盘，正是这次要修的那类缺陷。
 */
private fun assertEveryFieldDiffersFromDefault(default: Any, mutated: Any) {
    val untouched = default.javaClass.declaredFields
        .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
        .filter { field ->
            field.isAccessible = true
            field.get(default) == field.get(mutated)
        }
        .map { it.name }

    assertWithMessage(
        "这些字段没有被改成非默认值，round-trip 断言覆盖不到它们：$untouched",
    ).that(untouched).isEmpty()
}
