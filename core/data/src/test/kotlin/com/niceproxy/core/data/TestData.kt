package com.niceproxy.core.data

import com.niceproxy.core.model.GroupType
import com.niceproxy.core.model.InboundService
import com.niceproxy.core.model.InboundType
import com.niceproxy.core.model.ProtocolParams
import com.niceproxy.core.model.ProxyProtocol
import com.niceproxy.core.model.RoutingRule
import com.niceproxy.core.model.RuleAction
import com.niceproxy.core.model.RuleMatcher
import com.niceproxy.core.model.RuleSetRef
import com.niceproxy.core.model.ServerGroup
import com.niceproxy.core.model.ServerProfile
import com.niceproxy.core.model.TlsConfig
import com.niceproxy.core.model.WellKnownTag

internal fun node(
    id: String,
    groupId: String = "g1",
    name: String = id,
    server: String = "a.example.com",
    port: Int = 443,
    password: String = "pw",
    latencyMs: Int? = null,
    createdAt: Long = 0L,
) = ServerProfile(
    id = id,
    groupId = groupId,
    name = name,
    protocol = ProxyProtocol.TROJAN,
    server = server,
    serverPort = port,
    params = ProtocolParams.Trojan(password),
    tls = TlsConfig(enabled = true, serverName = server),
    latencyMs = latencyMs,
    createdAt = createdAt,
    updatedAt = createdAt,
)

internal fun group(
    id: String,
    name: String = id,
    type: GroupType = GroupType.MANUAL,
    url: String? = null,
    remarksFilter: String? = null,
    filterExclude: Boolean = true,
    extraHeaders: String? = null,
) = ServerGroup(
    id = id,
    name = name,
    type = type,
    url = url,
    remarksFilter = remarksFilter,
    filterExclude = filterExclude,
    extraHeaders = extraHeaders,
)

internal fun inbound(
    id: String,
    port: Int = 8080,
    type: InboundType = InboundType.MIXED,
    enabled: Boolean = true,
) = InboundService(id = id, type = type, listenPort = port, enabled = enabled)

internal fun rule(
    id: String,
    name: String = id,
    order: Int = 0,
    enabled: Boolean = true,
    locked: Boolean = false,
    ruleSets: List<String> = emptyList(),
) = RoutingRule(
    id = id,
    name = name,
    enabled = enabled,
    sortOrder = order,
    matcher = RuleMatcher(domainSuffix = listOf("$id.example.com"), ruleSet = ruleSets),
    action = RuleAction.Route(WellKnownTag.PROXY),
    locked = locked,
)

internal fun ruleSet(tag: String, enabled: Boolean = true) = RuleSetRef(
    id = tag,
    tag = tag,
    url = "https://example.com/$tag.srs",
    enabled = enabled,
)
