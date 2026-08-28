package com.niceproxy.core.data

import com.niceproxy.core.common.Dispatcher
import com.niceproxy.core.common.NiceDispatcher
import com.niceproxy.core.model.GroupType
import com.niceproxy.core.model.ServerGroup
import com.niceproxy.core.network.SubscriptionFetcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SubscriptionRepository @Inject constructor(
    private val fetcher: SubscriptionFetcher,
    private val serverRepository: ServerRepository,
    @Dispatcher(NiceDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) {

    data class UpdateOutcome(
        val groupName: String,
        val nodeCount: Int,
        val failedCount: Int,
        /** 被备注过滤规则排除的条目数。 */
        val filteredCount: Int = 0,
    )

    /** 新增一个订阅并立即拉取。失败时不落库，避免留下一个永远空的分组。 */
    suspend fun addSubscription(
        url: String,
        name: String? = null,
        userAgent: String? = null,
        remarksFilter: String? = null,
        extraHeaders: String? = null,
    ): Result<UpdateOutcome> = withContext(ioDispatcher) {
        val groupId = UUID.randomUUID().toString()
        val group = ServerGroup(
            id = groupId,
            name = name?.takeIf { it.isNotBlank() } ?: SubscriptionPipeline.DEFAULT_GROUP_NAME,
            type = GroupType.SUBSCRIPTION,
            url = url,
            userAgent = userAgent,
            autoUpdate = true,
            remarksFilter = remarksFilter,
            extraHeaders = extraHeaders,
        )
        pull(group).mapCatching { commit(it) }
    }

    /** 刷新一个已有订阅。失败会写入 lastError 供 UI 展示，但保留原有节点。 */
    suspend fun refresh(groupId: String): Result<UpdateOutcome> = withContext(ioDispatcher) {
        val group = serverRepository.getGroup(groupId)
            ?: return@withContext Result.failure(IllegalArgumentException("分组不存在"))
        if (group.type != GroupType.SUBSCRIPTION || group.url.isNullOrBlank()) {
            return@withContext Result.failure(IllegalArgumentException("该分组不是订阅"))
        }

        pull(group).fold(
            onSuccess = { fetched -> runCatching { commit(fetched) } },
            onFailure = { error ->
                // 保留旧节点：订阅服务器临时不可用时，用户至少还能连上。
                // 只写元数据，一个字段都不碰节点表。
                runCatching {
                    serverRepository.saveGroup(
                        group.copy(lastError = SubscriptionPipeline.describe(error)),
                    )
                }
                Result.failure(error)
            },
        )
    }

    suspend fun refreshAll(): List<Result<UpdateOutcome>> = withContext(ioDispatcher) {
        serverRepository.getGroups()
            .filter { it.type == GroupType.SUBSCRIPTION && !it.url.isNullOrBlank() }
            .map { refresh(it.id) }
    }

    /**
     * 拉取 + 解析 + 过滤，**不落库**。
     *
     * 落库拆到 [commit] 是因为它必须是原子的，见那边的注释。这里只做
     * 「有可能失败但不改变任何状态」的那一段 —— 网络、解析、过滤规则。
     */
    private suspend fun pull(group: ServerGroup): Result<SubscriptionPipeline.Outcome> {
        val url = group.url ?: return Result.failure(IllegalArgumentException("缺少订阅地址"))
        val headers = SubscriptionPipeline.parseExtraHeaders(group.extraHeaders)
            .getOrElse { return Result.failure(it) }
        val response = fetcher.fetch(url, group.userAgent, headers)
            .getOrElse { return Result.failure(it) }

        return SubscriptionPipeline.process(
            group = group,
            body = response.body,
            userInfoHeader = response.userInfoHeader,
            suggestedName = response.suggestedName,
        )
    }

    /**
     * 分组元数据与整组节点一次写完。
     *
     * 拆分成「先写节点、再写分组」曾经有两个后果，而且都不是理论上的：
     *
     * - **新增订阅根本走不通**：分组是在拉取成功之后才写的，而节点在此之前
     *   就已经在写了 —— `servers.group_id` 上挂着指向 `server_groups` 的外键，
     *   插第一条节点就是 `FOREIGN KEY constraint failed`。那个异常还不在
     *   `runCatching` 里，会直接从 `addSubscription` 抛给调用方。
     * - **刷新会留下半截状态**：节点换成了新的一批，而分组的 `last_update_at`
     *   还停在上一次，`last_error` 也没清。下一次自动更新看到的是一个「很久
     *   没更新过」的订阅，于是再拉一遍。
     */
    private suspend fun commit(outcome: SubscriptionPipeline.Outcome): UpdateOutcome {
        serverRepository.saveGroupWithServers(outcome.group, outcome.nodes)
        return UpdateOutcome(
            groupName = outcome.group.name,
            nodeCount = outcome.nodes.size,
            failedCount = outcome.failedCount,
            filteredCount = outcome.filteredCount,
        )
    }
}
