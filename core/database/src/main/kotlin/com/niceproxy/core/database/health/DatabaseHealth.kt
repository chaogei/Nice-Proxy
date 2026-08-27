package com.niceproxy.core.database.health

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 数据库是否曾经因为打不开而被删库重建。
 *
 * `DatabaseModule` 去掉破坏性迁移兜底之后，schema 对不上不再静默清库，而是
 * 开库失败。预览期间如果有人改过实体却忘了升 version，磁盘上会存在同样标着
 * version 2、结构却不同的库，Room 的 identityHash 校验会硬失败 ——
 * 那类用户以前是被静默清空，现在会变成启动即崩溃。重建是最后的退路，
 * 但必须留下痕迹，否则用户打开应用只看到一个空空如也的节点列表。
 *
 * **落盘而不是只放内存**：本应用支持开机自启，第一次开库很可能发生在后台
 * 服务里，那时没有任何界面能接住这个通知。标记要一直留到用户真的看见为止，
 * 由界面调用 [acknowledge] 清掉。
 */
@Singleton
class DatabaseHealth @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val _wasReset = MutableStateFlow(prefs.getBoolean(KEY_WAS_RESET, false))

    /** true 表示库被重建过，用户的节点、订阅、路由规则都没了，应提示从备份恢复。 */
    val wasReset: StateFlow<Boolean> = _wasReset.asStateFlow()

    internal fun markReset() {
        // commit 而不是 apply：调用点紧接着就要删库重建，进程若在此期间被杀，
        // 异步写会连同标记一起丢掉，用户就得不到任何解释了。
        prefs.edit().putBoolean(KEY_WAS_RESET, true).commit()
        _wasReset.value = true
    }

    /** 用户已经看到提示。 */
    fun acknowledge() {
        prefs.edit().putBoolean(KEY_WAS_RESET, false).apply()
        _wasReset.value = false
    }

    private companion object {
        const val FILE_NAME = "nice-database-health"
        const val KEY_WAS_RESET = "was_reset"
    }
}
