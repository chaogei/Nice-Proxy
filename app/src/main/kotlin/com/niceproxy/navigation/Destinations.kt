package com.niceproxy.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.AltRoute
import androidx.compose.ui.graphics.vector.ImageVector
import com.niceproxy.R

/**
 * 一级页面，对应底部导航的四个 Tab。
 *
 * 标签存资源 id 而不是字符串：这个 enum 是编译期常量，写死字符串会让四个
 * Tab 成为整个应用里唯一不跟随语言设置的文字。
 */
enum class TopLevelDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    HOME("home", R.string.nav_home, Icons.Outlined.Home),
    NODES("nodes", R.string.nav_nodes, Icons.Outlined.Dns),
    ROUTING("routing", R.string.nav_routing, Icons.Outlined.AltRoute),
    MORE("more", R.string.nav_more, Icons.Outlined.MoreHoriz),
}

/** 二级页面。 */
object Routes {
    const val INBOUND_EDIT = "inbound/{inboundId}"
    const val NODE_EDIT = "node/{nodeId}"
    const val RULE_EDIT = "rule/{ruleId}"
    const val LOGS = "logs"
    const val MONITOR = "monitor"
    const val SETTINGS = "settings"
    const val SCAN = "scan"
    const val CONFIG_PREVIEW = "config-preview"

    const val NEW_ID = "new"

    fun inboundEdit(id: String) = "inbound/$id"
    fun nodeEdit(id: String) = "node/$id"
    fun ruleEdit(id: String) = "rule/$id"
}
