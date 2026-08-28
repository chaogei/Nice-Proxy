package com.niceproxy.core.service.config

import android.util.Log
import com.niceproxy.core.model.InboundService
import com.niceproxy.core.model.InboundType
import com.niceproxy.core.model.NetworkPreference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest

/**
 * 「这次改动必须重启内核才能生效吗」的判定依据。
 *
 * 不直接用 [com.niceproxy.core.config.ConfigResult.Success.fingerprint]：它是整份配置
 * 文本的哈希，用来回答这个问题会在两头失真 ——
 *
 * 1. **多算了 selector 的选中项**。切换节点走 Clash API 热切换，现有连接不中断
 *    （§6.3、§6.9）。把它算进去的话，用户每切一次节点都会被提示「配置已变更」，
 *    点下去反而白白断一次流，正好是 §8.2 想避免的事。
 * 2. **少算了两件宿主侧的事**。PAC 由应用自己的 ServerSocket 提供（§6.5），出站网卡
 *    绑定由 `ConnectivityManager` 完成（§6.7），两者都不出现在 sing-box 配置里 ——
 *    只盯着内核配置的话，改了 PAC 端口或出站网卡这边毫无感知，用户会以为设置没生效。
 */
internal object ConfigDigest {

    /**
     * 宿主侧的几项刻意用独立参数而不是整个 `ServiceSettings`：那样的话，将来往
     * 设置里加一个与内核无关的字段（比如「打开应用时自启」）就会改变指纹，
     * 用户每动一次那种开关都被提示「配置已变更」，点下去白断一次流。
     * 逐个列出会强制每次新增设置时做一次「它到底要不要重启」的判断。
     */
    fun restartKey(
        configJson: String,
        inbounds: List<InboundService>,
        networkPreference: NetworkPreference,
        pacDirectFallback: Boolean,
    ): String = listOf(
        normalize(configJson),
        pacSignature(inbounds),
        // PAC 脚本的内容在服务启动时就定死在闭包里了，改了这一位必须重建 PAC 服务，
        // 否则开关看起来生效了、实际发给客户端的还是旧脚本
        pacDirectFallback.toString(),
        networkPreference.name,
    ).joinToString(SEPARATOR).sha256()

    private fun normalize(configJson: String): String = runCatching {
        val root = Json.parseToJsonElement(configJson).jsonObject
        val outbounds = root["outbounds"]?.jsonArray ?: return@runCatching configJson
        val stripped = buildJsonArray {
            outbounds.forEach { element ->
                val outbound = element.jsonObject
                val isSelector = outbound["type"]?.jsonPrimitive?.contentOrNull == SELECTOR_TYPE
                add(if (isSelector) JsonObject(outbound - SELECTED_KEY) else outbound)
            }
        }
        JsonObject(root + ("outbounds" to stripped)).toString()
    }.getOrElse {
        // 退化成整份文本比对。最坏结果只是多提示一次「配置已变更」，
        // 不会把真正需要重启的变更漏掉，这个方向的错误是可接受的。
        Log.w(TAG, "配置指纹归一化失败，退回全文比对", it)
        configJson
    }

    private fun pacSignature(inbounds: List<InboundService>): String =
        inbounds
            .filter { it.enabled && it.type == InboundType.PAC }
            .map { "${it.listen}:${it.listenPort}" }
            .sorted()
            .joinToString(",")

    private fun String.sha256(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(toByteArray())
            .joinToString("") { "%02x".format(it) }

    private const val TAG = "ConfigDigest"
    private const val SELECTOR_TYPE = "selector"
    private const val SELECTED_KEY = "default"

    /** 分隔各段输入，避免两段内容首尾相接拼出同一个字符串。 */
    private const val SEPARATOR = "\u0000"
}
