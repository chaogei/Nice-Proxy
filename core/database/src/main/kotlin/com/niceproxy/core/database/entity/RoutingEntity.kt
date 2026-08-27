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
import com.niceproxy.core.model.WellKnownTag

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

/**
 * 匹配条件读不出来时顶替上去的值。
 *
 * 空 matcher 是这里最强的「不会命中」：`SingBoxConfigBuilder.resolveRules`
 * 对它有专门的处理 —— 直接丢弃并记一条 `ConfigError.EmptyRule` 警告，
 * 而不是把它当成「匹配全部流量」写进配置去截胡后面所有规则。
 * 也就是说这个占位值不仅进不了内核，还自带一条能给用户看的说明。
 */
private val UNREADABLE_MATCHER = RuleMatcher()

/**
 * 动作读不出来时顶替上去的值。
 *
 * 配合下面的 `enabled = false`，它实际上永远不会被用到；选 `Route(PROXY)`
 * 只是为了万一将来有人绕过 enabled 直接读这个字段时，得到的是「走代理」
 * 而不是「直连」—— 后者会在用户毫不知情的情况下把流量放出去。
 */
private val UNREADABLE_ACTION = RuleAction.Route(WellKnownTag.PROXY)

/**
 * `matcher_json` / `action_json` 都不可空，读不出来只能退化成占位值。
 *
 * 关键是同时把 `enabled` 置 false：一条条件和动作都被换掉的规则，
 * 无论怎么执行都不是用户当初写的那条，让它参与分流只会产生莫名其妙的
 * 行为，而用户在界面上看到的还是原来的规则名。停用之后
 * `SingBoxConfigBuilder` 连看都不会看它，用户能自己判断要不要重写。
 */
fun RoutingRuleEntity.toDomain(): RoutingRule {
    val matcher = decodeOrNull(RuleMatcher.serializer(), matcherJson)
    val action = decodeOrNull(RuleAction.serializer(), actionJson)
    return RoutingRule(
        id = id,
        name = name,
        enabled = enabled && matcher != null && action != null,
        sortOrder = sortOrder,
        matcher = matcher ?: UNREADABLE_MATCHER,
        action = action ?: UNREADABLE_ACTION,
        locked = locked,
    )
}

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
