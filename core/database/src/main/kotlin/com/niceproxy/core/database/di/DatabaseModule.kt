package com.niceproxy.core.database.di

import android.content.Context
import androidx.room.Room
import com.niceproxy.core.database.NiceDatabase
import com.niceproxy.core.database.crypto.KeystoreSecretKeyProvider
import com.niceproxy.core.database.crypto.SecretCodec
import com.niceproxy.core.database.crypto.SecretTextConverter
import com.niceproxy.core.database.dao.InboundDao
import com.niceproxy.core.database.dao.RoutingDao
import com.niceproxy.core.database.dao.ServerDao
import com.niceproxy.core.database.dao.ServerGroupDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideSecretCodec(keyProvider: KeystoreSecretKeyProvider): SecretCodec =
        SecretCodec(keyProvider)

    @Provides
    @Singleton
    fun provideSecretTextConverter(codec: SecretCodec): SecretTextConverter =
        SecretTextConverter(codec)

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        secretTextConverter: SecretTextConverter,
    ): NiceDatabase =
        Room.databaseBuilder(context, NiceDatabase::class.java, NiceDatabase.NAME)
            .addTypeConverter(secretTextConverter)
            // 1.0 之前 schema 仍在演进，破坏性迁移可以避免为每次结构调整写迁移脚本。
            // 正式发布前必须换成真实的 Migration，否则用户升级会丢配置。
            //
            // 这条兜底也是敏感字段加密选择「信封前缀 + 不动 schema」的直接原因：
            // 只要改了列定义，version 就得加一，而加一在这里等于把用户的
            // 节点、订阅、路由规则全部清空一次。见 crypto/SecretEnvelope。
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun provideInboundDao(database: NiceDatabase): InboundDao = database.inboundDao()

    @Provides
    fun provideServerDao(database: NiceDatabase): ServerDao = database.serverDao()

    @Provides
    fun provideServerGroupDao(database: NiceDatabase): ServerGroupDao = database.serverGroupDao()

    @Provides
    fun provideRoutingDao(database: NiceDatabase): RoutingDao = database.routingDao()
}
