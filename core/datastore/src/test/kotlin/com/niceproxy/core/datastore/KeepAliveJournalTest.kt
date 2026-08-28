package com.niceproxy.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import com.niceproxy.core.model.StartReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

class KeepAliveJournalTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var harness: PreferencesHarness

    @BeforeEach
    fun setUp() {
        harness = PreferencesHarness(File(tempDir, "settings.preferences_pb"))
    }

    @Nested
    @DisplayName("Recording")
    inner class Recording {

        @Test
        fun `中断历史活过进程消失`() = runBlocking {
            // 这个类要记录的恰恰是「进程死掉」这件事，不落盘就等于没记
            harness.journal().recordStart(StartReason.STICKY_RESTART, now = 1_000)
            harness.restart()

            val stats = harness.journal().stats.first()
            assertThat(stats.interruptions).hasSize(1)
            assertThat(stats.interruptions.single().recovery).isEqualTo(StartReason.STICKY_RESTART)
            assertThat(stats.interruptions.single().atMillis).isEqualTo(1_000)
        }

        @Test
        fun `用户手动启动不算中断`() = runBlocking {
            val journal = harness.journal()
            journal.recordStart(StartReason.USER, now = 1_000)
            journal.recordStart(StartReason.BOOT, now = 2_000)

            assertThat(journal.stats.first().interruptions).isEmpty()
        }

        @Test
        fun `内核自愈不重置会话起点`() = runBlocking {
            val journal = harness.journal()
            journal.recordStart(StartReason.USER, now = 1_000)
            journal.recordStart(StartReason.CORE_REVIVE, now = 5_000)

            val stats = journal.stats.first()
            // 服务没断过，对局域网里那些设备来说网关一直是同一个
            assertThat(stats.sessionStartedAt).isEqualTo(1_000)
            assertThat(stats.interruptions).hasSize(1)
        }

        @Test
        fun `没有会话时内核自愈仍要立起会话起点`() = runBlocking {
            val journal = harness.journal()
            journal.recordStart(StartReason.CORE_REVIVE, now = 5_000)

            assertThat(journal.stats.first().sessionStartedAt).isEqualTo(5_000)
        }

        @Test
        fun `停止只结束会话，历史留着`() = runBlocking {
            val journal = harness.journal()
            journal.recordStart(StartReason.WATCHDOG, now = 1_000)
            journal.recordStop()

            val stats = journal.stats.first()
            assertThat(stats.sessionStartedAt).isNull()
            // 中断历史是跨会话的诊断依据，一停就清等于永远看不出规律
            assertThat(stats.interruptions).hasSize(1)
        }

        @Test
        fun `中断记录有上限，且最近的在最前`() = runBlocking {
            val journal = harness.journal()
            repeat(40) { journal.recordStart(StartReason.COLD_START, now = it.toLong() + 1) }

            val interruptions = journal.stats.first().interruptions
            // 无上限的话这一条记录会随时间无限膨胀，而它躺在设置文件里，
            // 每次读设置都要把它一起解出来
            assertThat(interruptions).hasSize(30)
            assertThat(interruptions.first().atMillis).isEqualTo(40)
            val timestamps = interruptions.map { it.atMillis }
            assertThat(timestamps).isEqualTo(timestamps.sortedDescending())
        }

        @Test
        fun `清空历史不影响正在进行的会话`() = runBlocking {
            val journal = harness.journal()
            journal.recordStart(StartReason.STICKY_RESTART, now = 1_000)
            journal.clearHistory()

            val stats = journal.stats.first()
            assertThat(stats.interruptions).isEmpty()
            assertThat(stats.sessionStartedAt).isEqualTo(1_000)
        }
    }

    @Nested
    @DisplayName("Corruption")
    inner class Corruption {

        @Test
        fun `历史 JSON 解不开时当成空，不能拖垮任何东西`() = runBlocking {
            harness.dataStore.edit {
                it[stringPreferencesKey("keepalive_interruptions")] = "{ 这不是 JSON"
            }
            harness.restart()

            assertThat(harness.journal().stats.first().interruptions).isEmpty()
        }

        @Test
        fun `历史里出现认不出的枚举值时不炸`() = runBlocking {
            // 降级安装后，新版本写下的 recovery 值这一版可能不认识
            harness.dataStore.edit {
                it[stringPreferencesKey("keepalive_interruptions")] =
                    """[{"atMillis":1,"recovery":"ANDROID_16_DOZE"}]"""
            }
            harness.restart()

            assertThat(harness.journal().stats.first().interruptions).isEmpty()
        }

        @Test
        fun `脏历史会在下一次记账时被覆盖掉`() = runBlocking {
            harness.dataStore.edit {
                it[stringPreferencesKey("keepalive_interruptions")] = "{ 这不是 JSON"
            }
            harness.restart()
            harness.journal().recordStart(StartReason.WATCHDOG, now = 7_000)
            harness.restart()

            assertThat(harness.journal().stats.first().interruptions).hasSize(1)
        }

        @Test
        fun `写不进去也不能让代理起不来`() = runBlocking {
            val journal = KeepAliveJournal(AlwaysFailingDataStore)

            // 记账失败是可以接受的，为它让代理启动失败是本末倒置
            journal.recordStart(StartReason.USER, now = 1_000)
            journal.recordStop()
            journal.clearHistory()

            assertThat(journal.stats.first().interruptions).isEmpty()
        }
    }
}

/** 存储彻底不可用：磁盘满、存储被卸载、权限被撤。 */
private object AlwaysFailingDataStore : DataStore<Preferences> {
    override val data: Flow<Preferences> = flow { throw IOException("存储不可用") }

    override suspend fun updateData(
        transform: suspend (t: Preferences) -> Preferences,
    ): Preferences = throw IOException("存储不可用")
}
