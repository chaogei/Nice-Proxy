package com.niceproxy.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.AltRoute
import androidx.compose.ui.graphics.vector.ImageVector

/** 一级页面，对应底部导航的四个 Tab。 */
enum class TopLevelDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    HOME("home", "首页", Icons.Outlined.Home),
    NODES("nodes", "节点", Icons.Outlined.Dns),
    ROUTING("routing", "分流", Icons.Outlined.AltRoute),
    MORE("more", "更多", Icons.Outlined.MoreHoriz),
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

    const val NEW_ID = "new"

    fun inboundEdit(id: String) = "inbound/$id"
    fun nodeEdit(id: String) = "node/$id"
    fun ruleEdit(id: String) = "rule/$id"
}
