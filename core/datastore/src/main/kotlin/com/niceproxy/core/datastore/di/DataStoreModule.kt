package com.niceproxy.core.datastore.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {

    /**
     * 文件损坏时重置成空配置，而不是让每一次读都抛 `CorruptionException`。
     *
     * 不给 `corruptionHandler` 的后果远不止「丢设置」：`ConfigRepository`
     * 生成配置时会 `serviceSettings.first()`，一抛异常配置就生成不出来，
     * **代理永远起不来**；热更新的 watcher merge 了好几条设置流，一并死掉。
     * 而且没有任何恢复路径 —— 用户只能清除应用数据，那会连带抹掉 Room 里
     * 的全部节点和订阅。
     *
     * 之所以敢直接重置：这里存的东西全都可重建（开关、DNS 地址、日志级别、
     * Clash API 的本机端口与密钥），不可替代的数据在数据库里。
     * 静默恢复成默认值严格优于永久砖机。
     */
    @Provides
    @Singleton
    fun providePreferencesDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = createSettingsDataStore { context.preferencesDataStoreFile("settings") }
}

/**
 * 建库的那几行单独拿出来，而不是留在 `@Provides` 里。
 *
 * 上面那段注释说的「损坏就重置成默认值」是一条断言，不是一句说明 —— 而它只有
 * 在 `corruptionHandler` 真的挂上去时才成立。留在 `@Provides` 里就只能靠肉眼
 * 复核，测试要么起一整个 Hilt 图，要么自己照抄一份配置去测 —— 照抄的那份
 * 恰恰不是线上跑的这份，哪天有人把 handler 摘了，测试照样绿。
 */
fun createSettingsDataStore(
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    produceFile: () -> File,
): DataStore<Preferences> = PreferenceDataStoreFactory.create(
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
    scope = scope,
    produceFile = produceFile,
)

private fun Context.preferencesDataStoreFile(name: String) =
    java.io.File(applicationContext.filesDir, "datastore/$name.preferences_pb")
