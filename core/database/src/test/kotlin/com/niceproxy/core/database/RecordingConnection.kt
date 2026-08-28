package com.niceproxy.core.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement

/**
 * 只把执行过的 SQL 记下来的假连接。
 *
 * 迁移测试的正经做法是 Room 的 `MigrationTestHelper`，但那要 instrumentation
 * 或 Robolectric，跑一次的代价是这一整个模块单测的几十倍。而 v2 → v3 的迁移
 * 里没有一条语句依赖 SQLite 的执行结果（全是 DDL，`migrate()` 也不读游标），
 * 所以「它到底发出了哪几条语句」就是这条迁移的全部行为 —— 记下来逐条比对，
 * 和真跑一遍能得到的结论是同一个。
 *
 * 换句话说：这个替身能证伪的是「迁移写错了」，证伪不了「SQLite 不接受这条
 * 语句」。后者由 `MigrationTest` 拿导出的 schema JSON 逐字对齐来兜 ——
 * 那几条 DDL 正是 Room 自己生成、自己会拿去校验的原文。
 */
internal class RecordingConnection : SQLiteConnection {

    val executed = mutableListOf<String>()

    override fun prepare(sql: String): SQLiteStatement {
        executed += sql
        return NoOpStatement
    }

    override fun close() = Unit

    private object NoOpStatement : SQLiteStatement {
        /** DDL 没有结果行，`execSQL` 只会 step 一次并期待 false。 */
        override fun step(): Boolean = false

        override fun close() = Unit
        override fun reset() = Unit
        override fun clearBindings() = Unit

        override fun bindBlob(index: Int, value: ByteArray) = unsupported()
        override fun bindDouble(index: Int, value: Double) = unsupported()
        override fun bindLong(index: Int, value: Long) = unsupported()
        override fun bindText(index: Int, value: String) = unsupported()
        override fun bindNull(index: Int) = unsupported()
        override fun getBlob(index: Int): ByteArray = unsupported()
        override fun getDouble(index: Int): Double = unsupported()
        override fun getLong(index: Int): Long = unsupported()
        override fun getText(index: Int): String = unsupported()
        override fun isNull(index: Int): Boolean = unsupported()
        override fun getColumnCount(): Int = 0
        override fun getColumnName(index: Int): String = unsupported()
        override fun getColumnType(index: Int): Int = unsupported()

        private fun unsupported(): Nothing =
            throw UnsupportedOperationException("迁移里出现了读写数据的语句，这条测试的前提不再成立")
    }
}
