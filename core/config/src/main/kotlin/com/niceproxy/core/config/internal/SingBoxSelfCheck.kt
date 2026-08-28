package com.niceproxy.core.config.internal

import com.niceproxy.core.model.ClashApiSettings
import com.niceproxy.core.model.WellKnownTag
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 对**生成结果本身**做一遍只读校验。
 *
 * 生成器里每一条约束都有对应的收敛逻辑，但收敛逻辑分散在十几个方法里，
 * 任何一次重构都可能让其中一条悄悄失效 —— 而失效的表现是内核吐一句
 * `outbound not found: node-xxx` 就退出，用户只看到「启动失败」，
 * 我们连是哪个环节漏了都不知道。
 *
 * 这里换一个角度：不看生成过程，只看产物，把「内核会当场拒绝」的那几类结构性
 * 问题重新验一遍。它复刻的是 sing-box `option.checkOutbounds` 与装配阶段的判据，
 * 与生成逻辑没有共享代码，所以生成侧漂移时它抓得住。
 */
internal object SingBoxSelfCheck {

    /** 返回违反的不变量，空表示通过。 */
    fun verify(root: JsonObject): List<String> {
        val problems = mutableListOf<String>()

        val inbounds = root.array("inbounds")
        val outbounds = root.array("outbounds")
        val endpoints = root.array("endpoints")

        problems += checkTags(inbounds, "入站")
        // outbound 与 endpoint 在内核里共用一个命名空间
        problems += checkTags(outbounds + endpoints, "出站")

        val declared = (outbounds + endpoints).mapNotNullTo(mutableSetOf()) { it.str("tag") }
        problems += checkOutboundReferences(root, outbounds, endpoints, declared)
        problems += checkPolicyGroups(outbounds)
        problems += checkRuleSets(root)
        problems += checkDns(root)
        problems += checkClashApi(root)
        problems += checkRemovedTypes(outbounds)

        return problems
    }

    private fun checkTags(items: List<JsonObject>, what: String): List<String> {
        val problems = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        items.forEach { item ->
            val tag = item.str("tag")
            if (tag.isNullOrBlank()) {
                problems += "有一个${what}缺少 tag"
            } else if (!seen.add(tag)) {
                problems += "$what tag「$tag」重复"
            }
        }
        return problems
    }

    private fun checkOutboundReferences(
        root: JsonObject,
        outbounds: List<JsonObject>,
        endpoints: List<JsonObject>,
        declared: Set<String>,
    ): List<String> {
        val problems = mutableListOf<String>()
        fun requireDeclared(tag: String?, where: String) {
            if (tag.isNullOrBlank()) return
            if (tag !in declared) problems += "$where 引用了未声明的出站「$tag」"
        }

        val route = root.obj("route")
        requireDeclared(route?.str("final"), "route.final")
        route?.let { r ->
            r.array("rules").forEach { rule ->
                requireDeclared(rule.str("outbound"), "路由规则")
            }
            r.array("rule_set").forEach { ref ->
                requireDeclared(ref.str("download_detour"), "规则集「${ref.str("tag")}」")
            }
        }
        root.obj("dns")?.array("servers")?.forEach { server ->
            requireDeclared(server.str("detour"), "DNS 服务器「${server.str("tag")}」")
        }
        (outbounds + endpoints).forEach { out ->
            val tag = out.str("tag")
            val detour = out.str("detour")
            requireDeclared(detour, "出站「$tag」的链式代理")
            if (detour != null && detour == tag) {
                problems += "出站「$tag」的链式代理指向了自己"
            }
            (out["outbounds"] as? JsonArray).orEmpty().forEach { member ->
                requireDeclared((member as? JsonPrimitive)?.contentOrNull, "策略组「$tag」")
            }
            requireDeclared(out.str("default"), "策略组「$tag」的默认选择")
        }
        return problems
    }

    /**
     * 空的 selector / urltest 是最阴的一种：内核照常加载，界面上策略组也在，
     * 只是一个候选都没有 —— 流量到了这里无处可去，全部连接直接失败，
     * 而日志里没有任何一行说明原因。
     */
    private fun checkPolicyGroups(outbounds: List<JsonObject>): List<String> =
        outbounds
            .filter { it.str("type") in POLICY_GROUP_TYPES }
            .filter { (it["outbounds"] as? JsonArray).isNullOrEmpty() }
            .map { "策略组「${it.str("tag")}」没有任何候选出站" }

    private fun checkRuleSets(root: JsonObject): List<String> {
        val route = root.obj("route") ?: return emptyList()
        val declared = route.array("rule_set").mapNotNullTo(mutableSetOf()) { it.str("tag") }
        val referenced = (route.array("rules") + root.obj("dns")?.array("rules").orEmpty())
            .flatMap { rule ->
                (rule["rule_set"] as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            }
        return (referenced.toSet() - declared).map { "规则引用了未声明的规则集「$it」" }
    }

    /** C-10：显式 `detour: direct` 会让内核在启动阶段直接罢工。 */
    private fun checkDns(root: JsonObject): List<String> {
        val servers = root.obj("dns")?.array("servers").orEmpty()
        return servers
            .filter { it.str("detour") == WellKnownTag.DIRECT }
            .map { "DNS 服务器「${it.str("tag")}」detour 到了 direct" }
    }

    /** NFR-9：Clash API 一旦漏到局域网，任何设备都能改节点、读全部连接明细。 */
    private fun checkClashApi(root: JsonObject): List<String> {
        val controller = root.obj("experimental")?.obj("clash_api")?.str("external_controller")
            ?: return emptyList()
        return if (controller.startsWith("${ClashApiSettings.LOOPBACK}:")) {
            emptyList()
        } else {
            listOf("Clash API 监听在 $controller，必须绑定回环地址")
        }
    }

    /** C-2：1.13 已经把 block / dns 这两种出站类型删掉了。 */
    private fun checkRemovedTypes(outbounds: List<JsonObject>): List<String> =
        outbounds
            .filter { it.str("type") in REMOVED_OUTBOUND_TYPES }
            .map { "出站「${it.str("tag")}」使用了 1.13 已移除的类型 ${it.str("type")}" }

    private val POLICY_GROUP_TYPES = setOf("selector", "urltest")
    private val REMOVED_OUTBOUND_TYPES = setOf("block", "dns")
}

private fun JsonObject.array(key: String): List<JsonObject> =
    (this[key] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
