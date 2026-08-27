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
        val result = pull(group)
        result.onSuccess { serverRepository.saveGroup(it.second) }
        result.map { it.first }
    }

    /** 刷新一个已有订阅。失败会写入 lastError 供 UI 展示，但保留原有节点。 */
    suspend fun refresh(groupId: String): Result<UpdateOutcome> = withContext(ioDispatcher) {
        val group = serverRepository.getGroup(groupId)
            ?: return@withContext Result.failure(IllegalArgumentException("分组不存在"))
        if (group.type != GroupType.SUBSCRIPTION || group.url.isNullOrBlank()) {
            return@withContext Result.failure(IllegalArgumentException("该分组不是订阅"))
        }

        pull(group).fold(
            onSuccess = { (outcome, updated) ->
                serverRepository.saveGroup(updated)
                Result.success(outcome)
            },
            onFailure = { error ->
                // 保留旧节点：订阅服务器临时不可用时，用户至少还能连上
                serverRepository.saveGroup(
                    group.copy(lastError = SubscriptionPipeline.describe(error)),
                )
                Result.failure(error)
            },
        )
    }

    suspend fun refreshAll(): List<Result<UpdateOutcome>> = withContext(ioDispatcher) {
        serverRepository.getGroups()
            .filter { it.type == GroupType.SUBSCRIPTION && !it.url.isNullOrBlank() }
            .map { refresh(it.id) }
    }

    /** 拉取 + 解析 + 过滤 + 落库，返回更新后的分组元数据。 */
    private suspend fun pull(group: ServerGroup): Result<Pair<UpdateOutcome, ServerGroup>> {
        val url = group.url ?: return Result.failure(IllegalArgumentException("缺少订阅地址"))
        val headers = SubscriptionPipeline.parseExtraHeaders(group.extraHeaders)
            .getOrElse { return Result.failure(it) }
        val response = fetcher.fetch(url, group.userAgent, headers)
            .getOrElse { return Result.failure(it) }

        val outcome = SubscriptionPipeline.process(
            group = group,
            body = response.body,
            userInfoHeader = response.userInfoHeader,
            suggestedName = response.suggestedName,
        ).getOrElse { return Result.failure(it) }

        serverRepository.replaceGroupServers(group.id, outcome.nodes)

        return Result.success(
            UpdateOutcome(
                groupName = outcome.group.name,
                nodeCount = outcome.nodes.size,
                failedCount = outcome.failedCount,
                filteredCount = outcome.filteredCount,
            ) to outcome.group,
        )
    }
}
