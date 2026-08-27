package com.niceproxy.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.niceproxy.core.model.RoutingRule
import com.niceproxy.core.model.RuleAction
import com.niceproxy.core.model.RuleMatcher
import com.niceproxy.core.model.RuleSetFormat
import com.niceproxy.core.model.RuleSetRef
import com.niceproxy.core.model.RuleSetType

@Entity(tableName = "routing_rules")
data class RoutingRuleEntity(
    @PrimaryKey val id: String,
    val name: String,
    val enabled: Boolean,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    @ColumnInfo(name = "matcher_json") val matcherJson: String,
    @ColumnInfo(name = "action_json") val actionJson: String,
    @ColumnInfo(name = "locked", defaultValue = "0") val locked: Boolean = false,
)

fun RoutingRuleEntity.toDomain(): RoutingRule = RoutingRule(
    id = id,
    name = name,
    enabled = enabled,
    sortOrder = sortOrder,
    matcher = entityJson.decodeFromString(RuleMatcher.serializer(), matcherJson),
    action = entityJson.decodeFromString(RuleAction.serializer(), actionJson),
    locked = locked,
)

fun RoutingRule.toEntity(): RoutingRuleEntity = RoutingRuleEntity(
    id = id,
    name = name,
    enabled = enabled,
    sortOrder = sortOrder,
    matcherJson = entityJson.encodeToString(RuleMatcher.serializer(), matcher),
    actionJson = entityJson.encodeToString(RuleAction.serializer(), action),
    locked = locked,
)

@Entity(tableName = "rule_sets")
data class RuleSetEntity(
    @PrimaryKey val id: String,
    val tag: String,
    val type: RuleSetType,
    val format: RuleSetFormat,
    val url: String?,
    val path: String?,
    @ColumnInfo(name = "download_detour") val downloadDetour: String,
    @ColumnInfo(name = "update_interval") val updateInterval: String,
    val enabled: Boolean,
    @ColumnInfo(name = "contains_ip_rules") val containsIpRules: Boolean,
)

fun RuleSetEntity.toDomain(): RuleSetRef = RuleSetRef(
    id = id,
    tag = tag,
    type = type,
    format = format,
    url = url,
    path = path,
    downloadDetour = downloadDetour,
    updateInterval = updateInterval,
    enabled = enabled,
    containsIpRules = containsIpRules,
)

fun RuleSetRef.toEntity(): RuleSetEntity = RuleSetEntity(
    id = id,
    tag = tag,
    type = type,
    format = format,
    url = url,
    path = path,
    downloadDetour = downloadDetour,
    updateInterval = updateInterval,
    enabled = enabled,
    containsIpRules = containsIpRules,
)
