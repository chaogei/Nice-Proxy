package com.niceproxy.core.data

import com.niceproxy.core.common.Dispatcher
import com.niceproxy.core.common.NiceDispatcher
import com.niceproxy.core.model.GroupType
import com.niceproxy.core.model.ServerGroup
import com.niceproxy.core.network.SubscriptionFetcher
import com.niceproxy.core.network.SubscriptionRequest
import com.niceproxy.core.network.SubscriptionResponse
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
        val group = ServerGroup(
            id = UUID.randomUUID().toString(),
            name = name?.takeIf { it.isNotBlank() } ?: SubscriptionPipeline.DEFAULT_GROUP_NAME,
            type = GroupType.SUBSCRIPTION,
            url = url,
            userAgent = userAgent,
            autoUpdate = true,
            remarksFilter = remarksFilter,
            extraHeaders = extraHeaders,
        )
        when (val plan = plan(group)) {
            // 新增时失败一律不落库，所以这里不走 commit 的记账分支
            is Plan.Rejected -> Result.failure(plan.error)
            is Plan.Fetchable -> commit(group, fetcher.fetch(plan.request), recordFailure = false)
        }
    }

    /** 刷新一个已有订阅。失败会写入 lastError 供 UI 展示，但保留原有节点。 */
    suspend fun refresh(groupId: String): Result<UpdateOutcome> = refresh(listOf(groupId)).single()

    /**
     * 批量刷新，结果与 [groupIds] 一一对应。
     *
     * 一条条串着拉是很自然的写法，也是这里原本的写法，但它把一次「全部更新」的耗时
     * 变成了所有订阅往返时间之和 —— 二十条订阅每条两秒就是四十秒，而这四十秒里
     * 本机几乎全闲着。后台任务尚可忍受，用户在设置页点「全部更新」时正盯着进度条。
     *
     * 并发度由 [SubscriptionFetcher.fetchAll] 压住，而不是这里放开跑：机场普遍按 IP
     * 限流，打太猛换来的是一串 429，比串行还慢，还可能触发临时封禁。
     *
     * **只有网络那一段是并发的。** 解析与落库随后按入参顺序串行执行：它们写的是同
     * 一批表，而相对一次 HTTP 往返，这点耗时可以忽略。
     */
    suspend fun refresh(groupIds: List<String>): List<Result<UpdateOutcome>> =
        withContext(ioDispatcher) {
            if (groupIds.isEmpty()) return@withContext emptyList()

            val plans = groupIds.map { id ->
                val group = serverRepository.getGroup(id)
                    ?: return@map Plan.Rejected(null, IllegalArgumentException("分组不存在"))
                plan(group)
            }

            val responses = fetcher.fetchAll(
                plans.filterIsInstance<Plan.Fetchable>().map { it.request },
            )

            // 响应只覆盖那些真的发出去了的分组，所以要自己数着往前走，
            // 不能拿 plans 的下标去索引
            var nextResponse = 0
            plans.map { planned ->
                when (planned) {
                    is Plan.Rejected -> {
                        planned.record?.let { recordFailure(it, planned.error) }
                        Result.failure(planned.error)
                    }

                    is Plan.Fetchable ->
                        commit(planned.group, responses[nextResponse++], recordFailure = true)
                }
            }
        }

    suspend fun refreshAll(): List<Result<UpdateOutcome>> = refresh(
        serverRepository.getGroups()
            .filter { it.type == GroupType.SUBSCRIPTION && !it.url.isNullOrBlank() }
            .map { it.id },
    )

    /** 一条订阅在真正发请求之前的两种结局。 */
    private sealed interface Plan {

        /**
         * 请求根本拼不出来。
         *
         * [record] 非空时把原因写回该分组的 `lastError`。只有「确实是一条订阅、
         * 但这次拼不出请求」才该写 —— 给一个被误传进来的手动分组挂上订阅错误，
         * UI 上就多了一个解释不清、也没法清掉的红点。
         */
        data class Rejected(val record: ServerGroup?, val error: Throwable) : Plan

        data class Fetchable(val group: ServerGroup, val request: SubscriptionRequest) : Plan
    }

    private fun plan(group: ServerGroup): Plan {
        if (group.type != GroupType.SUBSCRIPTION) {
            return Plan.Rejected(null, IllegalArgumentException("该分组不是订阅"))
        }
        // 地址可能是解密失败后降级成 null 的（见 ServerGroupEntity.toDomain）。
        // 分组本身仍是订阅，所以错误要落到它头上：用户看到「缺少订阅地址」，
        // 重新粘一次链接就能恢复。
        val url = group.url?.takeIf { it.isNotBlank() }
            ?: return Plan.Rejected(group, IllegalArgumentException("缺少订阅地址"))
        val headers = SubscriptionPipeline.parseExtraHeaders(group.extraHeaders)
            .getOrElse { return Plan.Rejected(group, it) }
        return Plan.Fetchable(group, SubscriptionRequest(url, group.userAgent, headers))
    }

    /**
     * 解析响应、替换组内节点、更新分组元数据。
     *
     * @param recordFailure 失败时是否把错误写回分组。刷新时要写（UI 靠它显示为什么
     *        没更新成），新增时绝不能写 —— 那会留下一个永远空的分组。
     */
    private suspend fun commit(
        group: ServerGroup,
        response: Result<SubscriptionResponse>,
        recordFailure: Boolean,
    ): Result<UpdateOutcome> {
        val body = response.getOrElse { return fail(group, it, recordFailure) }
        val outcome = SubscriptionPipeline.process(
            group = group,
            body = body.body,
            userInfoHeader = body.userInfoHeader,
            suggestedName = body.suggestedName,
        ).getOrElse { return fail(group, it, recordFailure) }

        serverRepository.replaceGroupServers(group.id, outcome.nodes)
        serverRepository.saveGroup(outcome.group)

        return Result.success(
            UpdateOutcome(
                groupName = outcome.group.name,
                nodeCount = outcome.nodes.size,
                failedCount = outcome.failedCount,
                filteredCount = outcome.filteredCount,
            ),
        )
    }

    private suspend fun fail(
        group: ServerGroup,
        error: Throwable,
        record: Boolean,
    ): Result<UpdateOutcome> {
        if (record) recordFailure(group, error)
        return Result.failure(error)
    }

    /** 只动分组元数据，不碰节点：订阅服务器临时不可用时，用户至少还能连上旧节点。 */
    private suspend fun recordFailure(group: ServerGroup, error: Throwable) {
        serverRepository.saveGroup(group.copy(lastError = SubscriptionPipeline.describe(error)))
    }
}
