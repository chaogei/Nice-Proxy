package com.niceproxy.core.data

import com.niceproxy.core.config.share.SubscriptionParser
import com.niceproxy.core.model.ServerGroup
import com.niceproxy.core.model.ServerProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 订阅更新中「拿到响应之后」的那一段：解析、过滤、算出新的分组元数据。
 *
 * 从 [SubscriptionRepository] 里拆出来是为了可测 —— 这一段的判断
 * （过滤规则把节点滤空了算不算失败、机场给的名字要不要采纳、流量头怎么合并）
 * 全都不需要网络，但恰恰是最容易出错的部分。
 */
internal object SubscriptionPipeline {

    /** 用户没起名字时的占位名，只有它才允许被机场返回的名字覆盖。 */
    const val DEFAULT_GROUP_NAME = "订阅"

    private val json = Json { ignoreUnknownKeys = true }

    data class Outcome(
        val nodes: List<ServerProfile>,
        val group: ServerGroup,
        val failedCount: Int,
        val filteredCount: Int,
    )

    fun process(
        group: ServerGroup,
        body: String,
        userInfoHeader: String? = null,
        suggestedName: String? = null,
        now: Long = System.currentTimeMillis(),
    ): Result<Outcome> {
        val parsed = SubscriptionParser.parse(body, group.id)
            .getOrElse { return Result.failure(it) }

        // 过滤掉机场塞进来的公告条目（「剩余流量」「官网地址」等）
        val nodes = parsed.nodes.filter { group.accepts(it.name) }
        if (nodes.isEmpty()) {
            // 两种空的原因完全不同，处置方式也不同：一种去找机场，
            // 一种是自己的正则写宽了，错误文案必须分开
            return Result.failure(
                if (parsed.nodes.isEmpty()) {
                    IllegalStateException("订阅中没有可用节点")
                } else {
                    IllegalStateException("所有节点都被过滤规则排除了")
                },
            )
        }

        val updated = group.copy(
            // 机场通过响应头给出的名字通常比用户随手填的更准确
            name = if (group.name == DEFAULT_GROUP_NAME) suggestedName ?: group.name else group.name,
            lastUpdateAt = now,
            lastError = null,
            traffic = SubscriptionParser.parseUserInfo(userInfoHeader) ?: group.traffic,
        )
        return Result.success(
            Outcome(
                nodes = nodes,
                group = updated,
                failedCount = parsed.failedEntries.size,
                filteredCount = parsed.nodes.size - nodes.size,
            ),
        )
    }

    /**
     * 自定义请求头以 JSON 对象存储。
     *
     * 解析失败**不再静默降级成空表**：这串 JSON 是用户一个字一个字敲的，
     * 悄悄丢掉之后订阅八成还是会失败（机场就是靠这个头决定返回什么格式），
     * 但用户看到的错误会是「无法识别的订阅格式」，跟真正的原因隔了十万八千里。
     */
    fun parseExtraHeaders(raw: String?): Result<Map<String, String>> {
        val text = raw?.takeIf { it.isNotBlank() } ?: return Result.success(emptyMap())
        val obj = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: return Result.failure(IllegalArgumentException("自定义请求头不是合法的 JSON 对象"))

        val headers = mutableMapOf<String, String>()
        for ((key, value) in obj) {
            val content = (value as? JsonPrimitive)?.contentOrNull
                ?: return Result.failure(
                    IllegalArgumentException("自定义请求头「$key」的值必须是字符串"),
                )
            headers[key] = content
        }
        return Result.success(headers)
    }

    /**
     * 失败原因一定要落成非空文案。
     *
     * 直接用 `throwable.message` 的话，不带消息的异常会写进一个 null，
     * UI 上就成了「更新失败但没有任何错误信息」—— 和 docs/DESIGN.md §10.2
     * 里那个把明文 HTTP 拦截吞掉的空 catch 是同一类问题。
     */
    fun describe(throwable: Throwable): String =
        throwable.message?.takeIf { it.isNotBlank() }
            ?: throwable::class.java.simpleName.ifBlank { "未知错误" }
}
