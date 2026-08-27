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
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
    ) {
        context.preferencesDataStoreFile("settings")
    }
}

private fun Context.preferencesDataStoreFile(name: String) =
    java.io.File(applicationContext.filesDir, "datastore/$name.preferences_pb")
