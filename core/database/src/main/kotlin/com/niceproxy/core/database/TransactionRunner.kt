package com.niceproxy.core.database

import androidx.room.withTransaction

/**
 * 把跨多个 DAO 的一串写操作合并成一个原子单元。
 *
 * 抽成接口而不是让调用方直接注入 [NiceDatabase]：`core:data` 的仓库测试是
 * 普通 JUnit 加手写 fake DAO，起不起来一个真的 Room 实例。而「中途失败必须
 * 整体回滚」恰恰是那批仓库里最值得钉死的一条语义 —— 为了测它把整套测试拖进
 * Robolectric，代价远大于这一层间接。
 *
 * 只有真正需要**跨 DAO** 原子性的地方才用它。单个 DAO 内部的组合操作请继续用
 * `@Transaction`（见 `ServerDao.replaceGroupServers`），那是 Room 编译期就能
 * 校验的写法，不需要额外注入。
 */
interface TransactionRunner {

    /** 在一个数据库事务中执行 [block]；[block] 抛出任何异常都会让整个事务回滚。 */
    suspend fun <R> withTransaction(block: suspend () -> R): R
}

internal class RoomTransactionRunner(
    private val database: NiceDatabase,
) : TransactionRunner {

    override suspend fun <R> withTransaction(block: suspend () -> R): R =
        database.withTransaction { block() }
}
