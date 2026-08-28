package com.niceproxy.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.niceproxy.core.datastore.di.createSettingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import java.io.File

/**
 * 一个真的落在磁盘上的 DataStore，可以「重启进程」。
 *
 * 不用内存替身，是因为这一层要验的恰恰是**落盘**这件事本身：round-trip
 * 测试问的是「用户改的设置能不能活过一次进程重启」，而进程重启正是这个应用
 * 的常态（省电模式停机、国产 ROM 后台清理、系统回收内存）。拿一个
 * `MutableStateFlow` 当替身，写进去读出来当然一致，但那证明不了任何东西 ——
 * 真正会丢数据的地方在序列化和键名，而替身把这两样都跳过了。
 *
 * 用的是 [createSettingsDataStore]，也就是线上那份配置，`corruptionHandler`
 * 一起带进来。
 */
internal class PreferencesHarness(private val file: File) {

    private var job: Job = SupervisorJob()
    private var store: DataStore<Preferences> = open()

    val dataStore: DataStore<Preferences> get() = store

    fun settings(): SettingsDataStore = SettingsDataStore(store)

    fun journal(): KeepAliveJournal = KeepAliveJournal(store)

    /**
     * 关掉当前实例，重新打开同一个文件。
     *
     * 必须等旧 scope 的 Job 真的结束：DataStore 全局记着「同一个文件同时只能
     * 有一个实例」，而那条记录是在 Job 完成时才移除的。不 join 就重开会撞上
     * `IllegalStateException`，而且是概率性的 —— 那种测试比没有更糟。
     */
    suspend fun restart() {
        job.cancelAndJoin()
        job = SupervisorJob()
        store = open()
    }

    /** 把文件内容换成不是 protobuf 的东西，模拟写到一半断电／存储出错。 */
    suspend fun corruptFile() {
        job.cancelAndJoin()
        file.parentFile?.mkdirs()
        file.writeText("这不是 protobuf，是一段被别的东西覆盖进来的垃圾")
        job = SupervisorJob()
        store = open()
    }

    private fun open(): DataStore<Preferences> =
        createSettingsDataStore(scope = CoroutineScope(job + Dispatchers.IO)) { file }
}
