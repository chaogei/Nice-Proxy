package com.niceproxy.di

import android.content.Context
import androidx.work.WorkManager
import com.niceproxy.core.common.ApplicationScope
import com.niceproxy.core.common.Dispatcher
import com.niceproxy.core.common.NiceDispatcher
import com.niceproxy.core.config.SingBoxConfigBuilder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Dispatcher(NiceDispatcher.IO)
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Dispatcher(NiceDispatcher.Default)
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    /**
     * SupervisorJob：某一个后台任务失败不应该连带取消其他任务
     * （比如订阅更新失败不该让服务状态订阅也停掉）。
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(
        @Dispatcher(NiceDispatcher.Default) dispatcher: CoroutineDispatcher,
    ): CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)

    @Provides
    @Singleton
    fun provideConfigBuilder(): SingBoxConfigBuilder = SingBoxConfigBuilder()

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)
}
