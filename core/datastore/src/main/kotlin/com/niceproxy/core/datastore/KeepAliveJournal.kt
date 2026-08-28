package com.niceproxy.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.niceproxy.core.model.InterruptionRecord
import com.niceproxy.core.model.KeepAliveStats
import com.niceproxy.core.model.StartReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 保活的运行流水。
 *
 * 必须落盘：它要记录的恰恰是「进程死掉」这件事，而进程一死内存里什么都不剩。
 * 用户重新打开应用时看到的中断次数，全靠这里存着。
 *
 * 单独一个类而不是塞进 [SettingsDataStore]：那边装的是用户设置，这里是运行时
 * 观测数据，两者的读写时机、生命周期和「能不能随便清掉」的答案都不一样
 * （这些丢了无所谓，设置丢了用户会骂人）。共用同一个 DataStore 文件是为了
 * 少一次文件打开，键前缀区分开即可。
 */
@Singleton
class KeepAliveJournal @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {

    private val json = Json { ignoreUnknownKeys = true }

    private val preferences: Flow<Preferences> = dataStore.data.catch { cause ->
        // 观测数据读不出来绝不能拖垮任何东西。它的全部价值是「让人看见」，
        // 为它让代理起不来是本末倒置。
        if (cause is IOException) emit(emptyPreferences()) else throw cause
    }

    val stats: Flow<KeepAliveStats> = preferences.map { prefs ->
        KeepAliveStats(
            sessionStartedAt = prefs[Keys.SESSION_STARTED_AT]?.takeIf { it > 0 },
            interruptions = decode(prefs[Keys.INTERRUPTIONS]),
        )
    }

    /**
     * 代理起来了。
     *
     * @param reason 谁拉起来的。[StartReason.involuntary] 为真的会同时记一笔中断 ——
     *        判据取自来源而不是「之前是不是在跑」，因为开机自启和被杀后恢复在状态上
     *        长得完全一样。
     */
    suspend fun recordStart(reason: StartReason, now: Long = System.currentTimeMillis()) {
        edit { prefs ->
            // 会话起点只在「从没在跑变成在跑」时设。内核自愈时服务没断过，
            // 对局域网那些设备来说网关一直是同一个，不该把已运行时长清零。
            if (prefs[Keys.SESSION_STARTED_AT] == null || reason != StartReason.CORE_REVIVE) {
                prefs[Keys.SESSION_STARTED_AT] = now
            }
            if (reason.involuntary) {
                val updated = (listOf(InterruptionRecord(now, reason)) + decode(prefs[Keys.INTERRUPTIONS]))
                    .take(MAX_RECORDS)
                prefs[Keys.INTERRUPTIONS] = encode(updated)
            }
        }
    }

    /** 用户主动停止。会话结束，但中断历史留着 —— 那是跨会话的诊断依据。 */
    suspend fun recordStop() {
        edit { it.remove(Keys.SESSION_STARTED_AT) }
    }

    suspend fun clearHistory() {
        edit { it.remove(Keys.INTERRUPTIONS) }
    }

    /**
     * 写失败同样只能吞掉：记账失败不该让代理启动失败。
     *
     * `block` 必须声明成 suspend：`DataStore.edit` 要的是 `suspend (MutablePreferences) -> Unit`，
     * 而普通函数类型不是它的子类型。lambda **字面量**会被隐式转换，所以调用方看不出区别，
     * 但把一个已有的函数**值**转手传下去就是类型不匹配。
     */
    private suspend fun edit(block: suspend (MutablePreferences) -> Unit) {
        runCatching { dataStore.edit(block) }
    }

    private fun decode(raw: String?): List<InterruptionRecord> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(InterruptionRecord.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    private fun encode(records: List<InterruptionRecord>): String =
        json.encodeToString(ListSerializer(InterruptionRecord.serializer()), records)

    private object Keys {
        val SESSION_STARTED_AT = longPreferencesKey("keepalive_session_started_at")
        val INTERRUPTIONS = stringPreferencesKey("keepalive_interruptions")
    }

    private companion object {
        /** 够看出规律就行。无上限的话这条记录会随时间无限膨胀。 */
        const val MAX_RECORDS = 30
    }
}
