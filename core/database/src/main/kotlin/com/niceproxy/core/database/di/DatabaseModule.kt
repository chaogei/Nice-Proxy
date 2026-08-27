package com.niceproxy.core.database.di

import android.content.Context
import android.database.sqlite.SQLiteDatabaseCorruptException
import androidx.room.Room
import com.niceproxy.core.database.NiceDatabase
import com.niceproxy.core.database.RoomTransactionRunner
import com.niceproxy.core.database.TransactionRunner
import com.niceproxy.core.database.crypto.KeystoreSecretKeyProvider
import com.niceproxy.core.database.crypto.SecretCodec
import com.niceproxy.core.database.crypto.SecretTextConverter
import com.niceproxy.core.database.dao.InboundDao
import com.niceproxy.core.database.dao.RoutingDao
import com.niceproxy.core.database.dao.ServerDao
import com.niceproxy.core.database.dao.ServerGroupDao
import com.niceproxy.core.database.health.DatabaseHealth
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

    /**
     * 应用已经公开发布，破坏性迁移的兜底必须收窄到只对**降级**生效。
     *
     * `fallbackToDestructiveMigration` 会在任何一次找不到迁移路径时清库；
     * 换成 `...OnDowngrade` 之后，只有磁盘上的 version 高于代码里的 version
     * 才会清 —— 也就是用户装过新版又退回旧版，那种情况下旧代码确实读不懂
     * 新结构，没有别的出路。正常升级路径不再清库，代之以「必须存在迁移路径」，
     * 约束见 [NiceDatabase] 的类注释。
     */
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        secretTextConverter: SecretTextConverter,
        health: DatabaseHealth,
    ): NiceDatabase {
        fun build(): NiceDatabase =
            Room.databaseBuilder(context, NiceDatabase::class.java, NiceDatabase.NAME)
                .addTypeConverter(secretTextConverter)
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                .build()

        /**
         * 删库重建，但**留下一个可查询的标记**。
         *
         * 静默清空正是这次改动要消灭的行为。用户重新打开应用时看到的是一个
         * 空列表，若没有任何解释，他既不知道发生了什么，也不会想到去恢复备份 ——
         * 而这一刻备份恰恰还是有效的。
         */
        fun recreate(failed: NiceDatabase): NiceDatabase {
            runCatching { failed.close() }
            health.markReset()
            // deleteDatabase 会连 -wal / -shm / -journal 一起删掉；只删主文件的话
            // 残留的 WAL 会被下一次打开重新套用，等于什么都没删。
            context.deleteDatabase(NiceDatabase.NAME)
            return build()
        }

        val database = build()

        // 全新安装（以及刚被重建过）没有任何东西需要校验，建表留给第一次查询 ——
        // DAO 全是 suspend / Flow，那一定发生在后台线程上。这条捷径很值得：
        // 下面的预打开是这个模块唯一一处可能落在主线程上的磁盘 I/O。
        if (!context.getDatabasePath(NiceDatabase.NAME).exists()) return database

        return try {
            // Room 是懒打开的，schema 校验要到第一次查询才发生。不在这里主动
            // 摸一下的话，失败会出现在随便哪个调用点上 —— 很可能是开机自启的
            // 后台服务，用户只看到「应用已停止运行」，连一次提示都没有。
            database.openHelper.writableDatabase
            database
        } catch (_: IllegalStateException) {
            // 预览期间有人改过实体却忘了升 version：磁盘上是一个同样标着
            // version 2、结构却对不上的库，Room 的 identityHash 校验硬失败。
            // 「找不到迁移路径」也走这一条。这两种都只能删库重来。
            recreate(database)
        } catch (_: SQLiteDatabaseCorruptException) {
            recreate(database)
        }
        // 刻意不接住其它 SQLiteException：磁盘写满、临时 I/O 错误都属于那一类，
        // 删库会把一个重启就能恢复的问题变成永久的数据丢失。
    }

    @Provides
    @Singleton
    fun provideTransactionRunner(database: NiceDatabase): TransactionRunner =
        RoomTransactionRunner(database)

    @Provides
    fun provideInboundDao(database: NiceDatabase): InboundDao = database.inboundDao()

    @Provides
    fun provideServerDao(database: NiceDatabase): ServerDao = database.serverDao()

    @Provides
    fun provideServerGroupDao(database: NiceDatabase): ServerGroupDao = database.serverGroupDao()

    @Provides
    fun provideRoutingDao(database: NiceDatabase): RoutingDao = database.routingDao()
}
