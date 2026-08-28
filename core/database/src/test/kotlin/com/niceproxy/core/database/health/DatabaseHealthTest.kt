package com.niceproxy.core.database.health

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 「库被重建过」这件事必须活过进程重启。
 *
 * 触发重建的场景（schema 校验失败、库文件损坏）几乎总是发生在**开库那一刻**，
 * 而本应用支持开机自启，那一刻很可能在一个没有界面的后台服务里。标记若只在
 * 内存里，用户下次打开应用看到的就是一个空空如也的节点列表加上零解释 ——
 * 而那一刻他手里的备份还是有效的，只是没人告诉他该去用。
 */
class DatabaseHealthTest {

    /** 复刻 SharedPreferences 的语义：值留在「磁盘」上，跨实例可见。 */
    private class FakeStore(private var value: Boolean = false) : ResetFlagStore {
        var durableWrites = 0
            private set

        override fun read(): Boolean = value

        override fun write(value: Boolean, durable: Boolean) {
            this.value = value
            if (durable) durableWrites++
        }
    }

    @Test
    @DisplayName("全新安装不报重建")
    fun freshInstallIsClean() {
        assertThat(DatabaseHealth(FakeStore()).wasReset.value).isFalse()
    }

    @Test
    @DisplayName("重建过之后立刻能查到")
    fun resetIsVisibleImmediately() {
        val health = DatabaseHealth(FakeStore())

        health.markReset()

        assertThat(health.wasReset.value).isTrue()
    }

    @Test
    @DisplayName("标记落盘，进程重启后还在")
    fun resetSurvivesProcessDeath() {
        // 这才是它存在的理由：开机自启时第一次开库在后台服务里，
        // 那时没有任何界面能接住通知
        val store = FakeStore()
        DatabaseHealth(store).markReset()

        assertThat(DatabaseHealth(store).wasReset.value).isTrue()
    }

    @Test
    @DisplayName("标记是同步落盘的，不能等系统慢慢刷")
    fun markIsDurable() {
        // 调用点紧接着就要 deleteDatabase() 再重建，进程若在此期间被杀，
        // 异步写会连同标记一起丢，用户就得不到任何解释
        val store = FakeStore()

        DatabaseHealth(store).markReset()

        assertThat(store.durableWrites).isEqualTo(1)
    }

    @Test
    @DisplayName("用户点过「知道了」之后不再复现")
    fun acknowledgeClearsIt() {
        val store = FakeStore()
        val health = DatabaseHealth(store)
        health.markReset()

        health.acknowledge()

        assertThat(health.wasReset.value).isFalse()
        assertThat(DatabaseHealth(store).wasReset.value).isFalse()
    }
}
