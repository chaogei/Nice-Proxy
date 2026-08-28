package com.niceproxy.core.data.di

import android.content.Context
import com.niceproxy.core.data.CacheLayout
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    /**
     * 缓存清理要用到的几个目录。
     *
     * `kernelWorkDir` 必须和 `ProxyService` 传给内核的那个是同一个
     * （两处都是 `filesDir`），否则「清理规则集缓存」会去删一个空目录，
     * 用户点了没反应而应用一句话都不说。
     */
    @Provides
    @Singleton
    fun provideCacheLayout(@ApplicationContext context: Context): CacheLayout = CacheLayout(
        kernelWorkDir = context.filesDir,
        httpCacheDir = File(context.cacheDir, CacheLayout.HTTP_CACHE_DIR_NAME),
        transientDir = context.cacheDir,
    )
}
