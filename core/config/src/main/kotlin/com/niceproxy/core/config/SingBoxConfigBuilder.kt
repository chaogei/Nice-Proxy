package com.niceproxy.core.config

import com.niceproxy.core.config.internal.DetourResolver
import com.niceproxy.core.config.internal.DnsServerParser
import com.niceproxy.core.config.internal.OutboundFactory
import com.niceproxy.core.config.internal.OutboundResult
import com.niceproxy.core.config.internal.SingBoxFormats
import com.niceproxy.core.config.internal.SingBoxSelfCheck
import com.niceproxy.core.config.internal.putIfNotBlank
import com.niceproxy.core.config.internal.putIfNotEmpty
import com.niceproxy.core.config.internal.putIfNotNull
import com.niceproxy.core.config.internal.putIfTrue
import com.niceproxy.core.model.CredentialState
import com.niceproxy.core.model.InboundService
import com.niceproxy.core.model.RoutingRule
import com.niceproxy.core.model.RuleAction
import com.niceproxy.core.model.RuleMatcher
import com.niceproxy.core.model.RuleSetRef
import com.niceproxy.core.model.RuleSetType
import com.niceproxy.core.model.ServerProfile
import com.niceproxy.core.model.WellKnownTag
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.security.MessageDigest

/**
 * 把应用的领域模型翻译成 sing-box v1.13 可加载的配置。
 *
 * 生成器必须遵守 docs/DESIGN.md §6.3 列出的 C-1 ~ C-8 约束，
 * 每一条都有对应的单元测试守护。
 */
class SingBoxConfigBuilder(
    private val json: Json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    },
) {

    fun build(input: ConfigInput): ConfigResult {
        val warnings = mutableListOf<ConfigError>()
        validate(input)
            .takeIf { it.isNotEmpty() }
            ?.let { return ConfigResult.Failure(it) }

        val nodes = buildNodeOutbounds(input, warnings)
        if (nodes.isEmpty() && input.nodes.isNotEmpty()) {
            return ConfigResult.Failure(noUsableNodeErrors(input, warnings), warnings)
        }
        // 走到这里只剩两种局面：有可用节点，或者用户一个节点都没配（纯中继）。
        val hasProxy = nodes.isNotEmpty()

        // 规则集与规则要先一起收敛完再往下走：规则能引用哪些 rule_set、
        // 能指向哪些 outbound，都取决于最终真正声明出来的那一批。
        val ruleSets = resolveRuleSets(input, warnings)
        val rules = resolveRules(
            input = input,
            declaredRuleSets = ruleSets.mapTo(mutableSetOf()) { it.tag },
            declaredOutbounds = declaredOutbounds(nodes, hasProxy),
            hasProxy = hasProxy,
            warnings = warnings,
        )

        val endpoints = nodes.filter { it.isEndpoint }
        val root = buildJsonObject {
            put("log", buildLog(input))
            put("dns", buildDns(input, ruleSets, hasProxy))
            // 1.13 的 WireGuard 是 endpoint 而不是 outbound；数组为空时不能写出来，
            // 空 `endpoints` 本身合法但没有意义，留着只会让 diff 变吵
            if (endpoints.isNotEmpty()) {
                put("endpoints", buildJsonArray { endpoints.forEach { add(it.json) } })
            }
            put("inbounds", buildInbounds(input))
            put("outbounds", buildOutbounds(input, nodes))
            put("route", buildRoute(input, rules, ruleSets, hasProxy))
            put("experimental", buildExperimental(input))
        }

        // 只读自检：抓的是生成器自己漂移出来的结构性问题，见 SingBoxSelfCheck
        SingBoxSelfCheck.verify(root)
            .takeIf { it.isNotEmpty() }
            ?.let { problems ->
                return ConfigResult.Failure(
                    problems.map { ConfigError.SelfCheckFailed(it) },
                    warnings,
                )
            }

        val text = json.encodeToString(JsonObject.serializer(), root)
        return ConfigResult.Success(
            json = text,
            fingerprint = text.sha256(),
            warnings = warnings,
        )
    }

    // ---------------------------------------------------------------- 收敛

    private fun declaredOutbounds(nodes: List<NodeOutbound>, hasProxy: Boolean): Set<String> =
        buildSet {
            add(WellKnownTag.DIRECT)
            if (hasProxy) {
                add(WellKnownTag.PROXY)
                add(WellKnownTag.AUTO)
                nodes.forEach { add(it.tag) }
            }
        }

    /**
     * 挑出真正能写进 `route.rule_set` 的规则集。
     *
     * 缺 URL 的远程规则集会让内核拒绝加载整份配置，而 tag 重复同样如此 ——
     * 用户在规则集管理页把同一个 geosite-cn 添加两遍是很容易发生的事。
     * 这两种都降级成警告跳过，而不是让整个代理起不来。
     */
    private fun resolveRuleSets(
        input: ConfigInput,
        warnings: MutableList<ConfigError>,
    ): List<RuleSetRef> {
        val seen = mutableSetOf<String>()
        return input.ruleSets.filter { it.enabled }.mapNotNull { ref ->
            val missing = when (ref.type) {
                RuleSetType.REMOTE -> ref.url.isNullOrBlank()
                RuleSetType.LOCAL -> ref.path.isNullOrBlank()
            }
            when {
                missing -> {
                    val what = if (ref.type == RuleSetType.REMOTE) "缺少下载地址" else "缺少本地路径"
                    warnings += ConfigError.InvalidRuleSet(ref.tag, what)
                    null
                }
                !seen.add(ref.tag) -> {
                    warnings += ConfigError.InvalidRuleSet(ref.tag, "标签重复")
                    null
                }
                // update_interval 是 badoption.Duration，写错的话内核在读配置的
                // 第一步就失败 —— 跟规则集本身能不能下载完全无关
                ref.type == RuleSetType.REMOTE && !SingBoxFormats.isDuration(ref.updateInterval) -> {
                    warnings += ConfigError.InvalidRuleSet(
                        ref.tag,
                        "更新间隔「${ref.updateInterval}」不是合法时长",
                    )
                    null
                }
                else -> ref
            }
        }
    }

    /**
     * 把用户规则收敛成一定能被内核接受的形态。
     *
     * 三种会让内核拒绝加载、但从 UI 上完全看不出问题的输入：
     * 引用了已被删除或停用的规则集、指向了已被删除的节点、以及一条空规则
     * （新建规则后没填任何条件就保存）。空规则尤其危险 —— 它不报错，
     * 而是匹配全部流量，把后面所有规则截胡。
     */
    private fun resolveRules(
        input: ConfigInput,
        declaredRuleSets: Set<String>,
        declaredOutbounds: Set<String>,
        hasProxy: Boolean,
        warnings: MutableList<ConfigError>,
    ): List<RoutingRule> {
        val fallback = if (hasProxy) WellKnownTag.PROXY else WellKnownTag.DIRECT
        return input.rules
            .filter { it.enabled }
            .sortedBy { it.sortOrder }
            .mapNotNull { rule ->
                if (rule.matcher.isEmpty) {
                    warnings += ConfigError.EmptyRule(rule.name)
                    return@mapNotNull null
                }
                val keptRuleSets = rule.matcher.ruleSet.filter { it in declaredRuleSets }
                if (keptRuleSets.size != rule.matcher.ruleSet.size) {
                    (rule.matcher.ruleSet - keptRuleSets.toSet()).forEach {
                        warnings += ConfigError.InvalidRuleSet(it, "被规则「${rule.name}」引用但未声明")
                    }
                }
                val matcher = rule.matcher.copy(ruleSet = keptRuleSets)
                if (matcher.isEmpty) {
                    warnings += ConfigError.EmptyRule(rule.name)
                    return@mapNotNull null
                }

                val action = when (val original = rule.action) {
                    is RuleAction.Route -> {
                        // C-7：没有可用节点时，指向 proxy 的规则要降级到 direct，
                        // 否则会引用一个不存在的 outbound 导致内核拒绝加载。
                        val tag = when {
                            !hasProxy && original.outboundTag != WellKnownTag.DIRECT -> WellKnownTag.DIRECT
                            original.outboundTag in declaredOutbounds -> original.outboundTag
                            else -> {
                                warnings += ConfigError.DanglingOutbound(rule.name, original.outboundTag)
                                fallback
                            }
                        }
                        RuleAction.Route(tag)
                    }
                    else -> original
                }
                rule.copy(matcher = matcher, action = action)
            }
    }

    // ---------------------------------------------------------------- 校验

    /**
     * 「配了节点但一个都用不了」必须 fail-closed，理由见 [ConfigError.NoUsableNode]。
     *
     * 与 core/database 的 `InboundEntity.toDomain`「一个用不了的入站远好过一个
     * 敞开的入站」是同一条原则：宁可给出一个有明确指向的失败，也不要一份
     * 悄悄放行的配置。
     */
    private fun noUsableNodeErrors(
        input: ConfigInput,
        nodeWarnings: List<ConfigError>,
    ): List<ConfigError> {
        val summary = ConfigError.NoUsableNode(
            configuredCount = input.nodes.size,
            unreadableCount = input.nodes.count { it.credentialState == CredentialState.UNREADABLE },
        )
        // 明细只带前几条：密钥失效是全局的，整张表会同时解不开，几千条同因的
        // InvalidNode 拼进错误文案（ProxyService 把 errors 直接 join 进通知）
        // 只会把提示撑爆，而它们说的是同一件事。
        return listOf(summary) + nodeWarnings.take(MAX_REPORTED_INVALID_NODES)
    }

    private fun validate(input: ConfigInput): List<ConfigError> {
        val errors = mutableListOf<ConfigError>()
        val enabled = input.inbounds.filter { it.enabled }

        if (enabled.isEmpty()) {
            errors += ConfigError.NoEnabledInbound()
        }

        enabled.forEach { inbound ->
            if (inbound.listenPort !in InboundService.PORT_RANGE) {
                errors += ConfigError.InvalidPort(inbound.listenPort)
            }
        }

        enabled.groupBy { it.listenPort }
            .filterValues { it.size > 1 }
            .keys
            .forEach { errors += ConfigError.DuplicatePort(it) }

        // 端口查重挡不住这个：两个入站可以 tag 相同而端口不同，
        // 而 `checkInbounds` 见到重复 tag 会拒绝整份配置
        enabled.groupBy { it.tag }
            .filterValues { it.size > 1 }
            .keys
            .forEach { errors += ConfigError.DuplicateInboundTag(it) }

        // C-5：节点与入站的 tag 都带前缀，理论上不会撞上保留 tag，
        // 但导入外部配置时仍需兜底。
        input.nodes.forEach { node ->
            if (node.outboundTag in WellKnownTag.ALL) {
                errors += ConfigError.ReservedTag(node.outboundTag)
            }
        }
        input.inbounds.forEach { inbound ->
            if (inbound.udpTimeout.isNotBlank() && !SingBoxFormats.isDuration(inbound.udpTimeout)) {
                errors += ConfigError.InvalidUdpTimeout(inbound.tag, inbound.udpTimeout)
            }
        }

        return errors
    }

    // ---------------------------------------------------------------- log

    private fun buildLog(input: ConfigInput): JsonObject = buildJsonObject {
        put("level", input.log.level.singBoxValue)
        put("timestamp", input.log.timestamp)
    }

    // ---------------------------------------------------------------- DNS

    private fun buildDns(
        input: ConfigInput,
        ruleSets: List<RuleSetRef>,
        hasProxy: Boolean,
    ): JsonObject {
        // 1.12+ 的类型化 DNS 服务器默认就用「空 direct 出站」作为拨号器，
        // 再显式写 detour: direct 会被内核判定为无意义配置并拒绝启动：
        //   "detour to an empty direct outbound makes no sense"
        // 所以只有真的要绕道代理时才写 detour。
        val remoteDetour = WellKnownTag.PROXY.takeIf { hasProxy }
        val remote = DnsServerParser.parse(DNS_REMOTE, input.dns.remoteServer, remoteDetour)
        val local = DnsServerParser.parse(DNS_LOCAL, input.dns.localServer, detour = null)

        return buildJsonObject {
            putJsonArray("servers") {
                add(remote)
                add(local)
            }
            if (input.dns.splitByRuleSet) {
                // 只能引用最终真的声明出来的那批，否则 DNS 规则会指向一个
                // route.rule_set 里不存在的 tag，内核直接拒绝加载
                val cnRuleSets = ruleSets
                    .filter { it.tag in DOMESTIC_RULE_SET_TAGS }
                    .map { it.tag }
                if (cnRuleSets.isNotEmpty()) {
                    putJsonArray("rules") {
                        add(
                            buildJsonObject {
                                putIfNotEmpty("rule_set", cnRuleSets)
                                put("server", DNS_LOCAL)
                            },
                        )
                    }
                }
            }
            put("final", if (hasProxy) DNS_REMOTE else DNS_LOCAL)
            put("strategy", resolveStrategy(input))
            putIfTrue("disable_cache", input.dns.disableCache)
        }
    }

    private fun resolveStrategy(input: ConfigInput): String =
        if (!input.ipv6Enabled) "ipv4_only" else input.dns.strategy

    // ---------------------------------------------------------------- 入站

    private fun buildInbounds(input: ConfigInput): JsonArray = buildJsonArray {
        input.inbounds
            .filter { it.enabled && it.type.isSingBoxManaged }
            .sortedBy { it.sortOrder }
            .forEach { inbound ->
                add(
                    buildJsonObject {
                        put("type", requireNotNull(inbound.type.singBoxType))
                        put("tag", inbound.tag)
                        put("listen", inbound.listen)
                        put("listen_port", inbound.listenPort)
                        inbound.auth?.let { auth ->
                            putJsonArray("users") {
                                add(
                                    buildJsonObject {
                                        put("username", auth.username)
                                        put("password", auth.password)
                                    },
                                )
                            }
                        }
                        putIfTrue("tcp_fast_open", inbound.tcpFastOpen)
                        if (inbound.udpTimeout != DEFAULT_UDP_TIMEOUT) {
                            put("udp_timeout", inbound.udpTimeout)
                        }
                    },
                )
            }
    }

    // ---------------------------------------------------------------- 出站

    private data class NodeOutbound(
        val node: ServerProfile,
        val json: JsonObject,
        val isEndpoint: Boolean,
    ) {
        val tag: String get() = node.outboundTag
    }

    private fun buildNodeOutbounds(
        input: ConfigInput,
        warnings: MutableList<ConfigError>,
    ): List<NodeOutbound> {
        val factory = OutboundFactory()
        val built = input.nodes
            .sortedBy { it.sortOrder }
            .mapNotNull { node ->
                when (val result = factory.create(node)) {
                    is OutboundResult.Ok -> {
                        if (result.insecureTls) {
                            warnings += ConfigError.InsecureTls(node.id, node.name)
                        }
                        NodeOutbound(node, result.json, result.endpoint)
                    }
                    is OutboundResult.Invalid -> {
                        warnings += ConfigError.InvalidNode(node.id, node.name, result.reason)
                        null
                    }
                }
            }

        // C-5：tag 撞车会让内核拒绝加载整份配置。节点 id 来自数据库主键，
        // 正常路径下不会重复，但备份恢复与外部配置导入不受主键保护。
        val seen = mutableSetOf<String>()
        val unique = built.filter { candidate ->
            seen.add(candidate.tag).also { fresh ->
                if (!fresh) warnings += ConfigError.DuplicateOutboundTag(candidate.tag)
            }
        }

        return dropBrokenChains(unique, warnings)
    }

    /**
     * FR-2.10：把链式代理里成环或悬空的那些节点摘掉。
     *
     * 必须在这一步做而不是留给 `OutboundFactory`：判定需要看到「最终真的生成
     * 出来的那一批 tag」，而那要等到工厂把所有节点都过完才知道。
     */
    private fun dropBrokenChains(
        nodes: List<NodeOutbound>,
        warnings: MutableList<ConfigError>,
    ): List<NodeOutbound> {
        val detours = nodes
            .mapNotNull { candidate ->
                candidate.node.detour
                    ?.takeIf { it.isNotBlank() }
                    ?.let { candidate.tag to it }
            }
            .toMap()
        if (detours.isEmpty()) return nodes

        val rejected = DetourResolver.reject(detours, nodes.mapTo(mutableSetOf()) { it.tag })
        if (rejected.isEmpty()) return nodes

        return nodes.filter { candidate ->
            val reason = rejected[candidate.tag] ?: return@filter true
            warnings += ConfigError.InvalidNode(candidate.node.id, candidate.node.name, reason)
            false
        }
    }

    private fun buildOutbounds(input: ConfigInput, nodes: List<NodeOutbound>): JsonArray {
        // 策略组的候选要把 endpoint 一起算上：它跟 outbound 共用 tag 命名空间，
        // 漏掉的话用户在界面上根本选不到自己的 WireGuard 节点
        val nodeTags = nodes.map { it.tag }
        return buildJsonArray {
            // C-7：无可用节点时只保留 direct，应用退化为纯中继模式（Every Proxy 等价行为）。
            if (nodeTags.isNotEmpty()) {
                add(buildSelector(input, nodeTags))
                add(buildUrlTest(input, nodeTags))
            }
            nodes.filterNot { it.isEndpoint }.forEach { add(it.json) }
            add(
                buildJsonObject {
                    put("type", "direct")
                    put("tag", WellKnownTag.DIRECT)
                },
            )
        }
    }

    private fun buildSelector(input: ConfigInput, nodeTags: List<String>): JsonObject {
        val candidates = buildList {
            add(WellKnownTag.AUTO)
            addAll(nodeTags)
            add(WellKnownTag.DIRECT)
        }
        val selected = input.outbound.selectedTag.takeIf { it in candidates } ?: WellKnownTag.AUTO
        return buildJsonObject {
            put("type", "selector")
            put("tag", WellKnownTag.PROXY)
            putIfNotEmpty("outbounds", candidates)
            put("default", selected)
            putIfTrue("interrupt_exist_connections", input.outbound.interruptExistConnections)
        }
    }

    private fun buildUrlTest(input: ConfigInput, nodeTags: List<String>): JsonObject =
        buildJsonObject {
            put("type", "urltest")
            put("tag", WellKnownTag.AUTO)
            putIfNotEmpty("outbounds", nodeTags)
            put("url", input.outbound.urlTestUrl)
            put("interval", input.outbound.urlTestInterval)
            put("tolerance", input.outbound.urlTestTolerance)
        }

    // ---------------------------------------------------------------- 路由

    private fun buildRoute(
        input: ConfigInput,
        rules: List<RoutingRule>,
        ruleSets: List<RuleSetRef>,
        hasProxy: Boolean,
    ): JsonObject {
        val finalTag = if (hasProxy) WellKnownTag.PROXY else WellKnownTag.DIRECT

        return buildJsonObject {
            put("rules", buildRouteRules(input, rules, ruleSets))
            if (ruleSets.isNotEmpty()) {
                put("rule_set", buildRuleSets(ruleSets, hasProxy))
            }
            put("final", finalTag)
            // C-3：无 TUN，且接口绑定由 Android 侧的 ConnectivityManager 负责。
            put("auto_detect_interface", false)
            // C-4：节点地址是域名时，1.12+ 必须有默认解析器，否则启动失败。
            put("default_domain_resolver", DNS_LOCAL)
        }
    }

    private fun buildRouteRules(
        input: ConfigInput,
        userRules: List<RoutingRule>,
        ruleSets: List<RuleSetRef>,
    ): JsonArray {
        val ipRuleSetTags = ruleSets
            .filter { it.containsIpRules }
            .mapTo(mutableSetOf()) { it.tag }
        val firstIpRuleIndex = userRules.indexOfFirst { it.matcher.needsResolvedIp(ipRuleSetTags) }

        return buildJsonArray {
            // C-1：1.13 已移除入站级 sniff 字段，只能用路由 action。
            add(
                buildJsonObject {
                    put("action", "sniff")
                    put("timeout", SNIFF_TIMEOUT)
                },
            )
            // 客户端经 SOCKS5 UDP 发来的 DNS 查询交给内核自己回答，
            // 这样 DNS 分流策略才对局域网客户端生效。
            add(
                buildJsonObject {
                    put("protocol", "dns")
                    put("action", "hijack-dns")
                },
            )
            // 关闭了 UDP 的入站，直接在路由层丢弃其 UDP 流量：
            // sing-box 的 socks/mixed 入站本身没有 UDP 开关。
            input.inbounds
                .filter { it.enabled && it.type.isSingBoxManaged && !it.udpEnabled }
                .forEach { inbound ->
                    add(
                        buildJsonObject {
                            putJsonArray("inbound") { add(inbound.tag) }
                            putJsonArray("network") { add("udp") }
                            put("action", "reject")
                        },
                    )
                }
            add(
                buildJsonObject {
                    put("ip_is_private", true)
                    put("outbound", WellKnownTag.DIRECT)
                },
            )

            userRules.forEachIndexed { index, rule ->
                // 基于 IP 的规则要先把域名解析成 IP 才可能命中。
                // 插在第一条 IP 规则之前，既保证正确性，又不会让前面的域名规则被迫解析。
                if (index == firstIpRuleIndex) {
                    add(
                        buildJsonObject {
                            put("action", "resolve")
                            put("strategy", resolveStrategy(input))
                        },
                    )
                }
                add(buildRule(rule))
            }
        }
    }

    private fun buildRule(rule: RoutingRule): JsonObject = buildJsonObject {
        val m = rule.matcher
        putIfNotEmpty("domain", m.domain)
        putIfNotEmpty("domain_suffix", m.domainSuffix)
        putIfNotEmpty("domain_keyword", m.domainKeyword)
        putIfNotEmpty("domain_regex", m.domainRegex)
        putIfNotEmpty("ip_cidr", m.ipCidr)
        putIfNotEmpty("source_ip_cidr", m.sourceIpCidr)
        putIfNotEmpty("port", m.port)
        putIfNotEmpty("port_range", m.portRange)
        putIfNotEmpty("network", m.network)
        putIfNotEmpty("protocol", m.protocol)
        putIfNotEmpty("inbound", m.inbound)
        putIfNotEmpty("rule_set", m.ruleSet)
        putIfNotNull("ip_is_private", m.ipIsPrivate)
        putIfTrue("invert", m.invert)

        when (val action = rule.action) {
            // 出站 tag 的有效性已在 resolveRules 里收敛过
            is RuleAction.Route -> put("outbound", action.outboundTag)
            // C-2：1.11+ 已废弃 block / dns 类型的 outbound。
            is RuleAction.Reject -> {
                put("action", "reject")
                if (action.method != "default") put("method", action.method)
            }
            RuleAction.HijackDns -> put("action", "hijack-dns")
            is RuleAction.Sniff -> {
                put("action", "sniff")
                putIfNotEmpty("sniffer", action.sniffers)
                // 写错的 timeout 会让整份配置解析失败；这里宁可不写，
                // 让内核用它自己的默认值，也好过让所有节点一起失效
                putIfNotBlank("timeout", action.timeout?.takeIf(SingBoxFormats::isDuration))
            }
            is RuleAction.Resolve -> {
                put("action", "resolve")
                putIfNotBlank("strategy", action.strategy)
                putIfNotBlank("server", action.server)
            }
        }
    }

    private fun buildRuleSets(ruleSets: List<RuleSetRef>, hasProxy: Boolean): JsonArray =
        buildJsonArray {
            ruleSets.forEach { ref ->
                add(
                    buildJsonObject {
                        put("type", if (ref.type == RuleSetType.REMOTE) "remote" else "local")
                        put("tag", ref.tag)
                        put("format", ref.format.name.lowercase())
                        when (ref.type) {
                            RuleSetType.REMOTE -> {
                                put("url", requireNotNull(ref.url) { "远程规则集 ${ref.tag} 缺少 URL" })
                                put(
                                    "download_detour",
                                    if (hasProxy) ref.downloadDetour else WellKnownTag.DIRECT,
                                )
                                put("update_interval", ref.updateInterval)
                            }
                            RuleSetType.LOCAL -> {
                                put("path", requireNotNull(ref.path) { "本地规则集 ${ref.tag} 缺少路径" })
                            }
                        }
                    },
                )
            }
        }

    // ---------------------------------------------------------------- experimental

    private fun buildExperimental(input: ConfigInput): JsonObject = buildJsonObject {
        putJsonObject("clash_api") {
            // NFR-9：恒定绑定回环地址，绝不暴露到局域网。
            put("external_controller", input.clashApi.externalController)
            put("secret", input.clashApi.secret)
        }
        putJsonObject("cache_file") {
            put("enabled", true)
            put("path", "${input.workDir}/cache.db")
            put("store_rdrc", true)
        }
    }

    private companion object {
        const val DNS_REMOTE = "dns-remote"
        const val DNS_LOCAL = "dns-local"
        const val SNIFF_TIMEOUT = "300ms"
        const val DEFAULT_UDP_TIMEOUT = "5m"

        /** 构建失败时随摘要一起给出的节点级明细条数上限。 */
        const val MAX_REPORTED_INVALID_NODES = 3

        /** DNS 分流时视为「国内」的规则集 tag。 */
        val DOMESTIC_RULE_SET_TAGS = setOf("geosite-cn", "geosite-geolocation-cn")
    }
}

/** 该规则是否需要目标 IP 才能判定。 */
private fun RuleMatcher.needsResolvedIp(ipRuleSetTags: Set<String>): Boolean =
    ipCidr.isNotEmpty() || ruleSet.any { it in ipRuleSetTags }

private fun String.sha256(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(toByteArray())
        .joinToString("") { "%02x".format(it) }
