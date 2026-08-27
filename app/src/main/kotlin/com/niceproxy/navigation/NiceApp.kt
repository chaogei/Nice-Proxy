package com.niceproxy.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.niceproxy.core.designsystem.component.GlassBar
import com.niceproxy.core.designsystem.component.GlassScaffold
import com.niceproxy.core.designsystem.component.LocalHazeState
import com.niceproxy.feature.home.HomeScreen
import com.niceproxy.feature.inbound.InboundEditScreen
import com.niceproxy.feature.logs.LogsScreen
import com.niceproxy.feature.monitor.MonitorScreen
import com.niceproxy.feature.more.MoreScreen
import com.niceproxy.feature.nodes.NodeEditScreen
import com.niceproxy.feature.nodes.NodesScreen
import com.niceproxy.feature.onboarding.OnboardingScreen
import com.niceproxy.feature.routing.RoutingScreen
import com.niceproxy.feature.routing.RuleEditScreen
import com.niceproxy.feature.scan.ScanScreen
import com.niceproxy.feature.settings.SettingsScreen

private const val SCAN_RESULT_KEY = "scan_result"

@Composable
fun NiceApp(
    navController: NavHostController = rememberNavController(),
    viewModel: AppShellViewModel = hiltViewModel(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination

    val isTopLevel = TopLevelDestination.entries.any { dest ->
        currentRoute?.hierarchy?.any { it.route == dest.route } == true
    }

    val configOutdated by viewModel.configOutdated.collectAsStateWithLifecycle()
    val configMessage by viewModel.configMessage.collectAsStateWithLifecycle()
    val onboardingCompleted by viewModel.onboardingCompleted.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // 配置改动不立刻重启内核：那会在用户还在连续调整设置时反复断流。
    // 改成攒着提示一次，由用户决定何时应用。见 docs/DESIGN.md §8.2。
    LaunchedEffect(configOutdated) {
        if (!configOutdated) return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "配置已变更",
            actionLabel = "应用",
            duration = SnackbarDuration.Indefinite,
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.reapplyConfig()
    }

    // 应用失败时旧配置仍在跑，过期标记也还在，用户改好再点一次即可
    LaunchedEffect(configMessage) {
        configMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeConfigMessage()
        }
    }

    GlassScaffold(
        snackbarHostState = snackbarHostState,
        bottomBar = {
            // 引导页期间不给底部导航：此时还没有「切到别的 Tab」这回事
            if (isTopLevel && onboardingCompleted) {
                GlassBottomBar(
                    current = TopLevelDestination.entries.firstOrNull { dest ->
                        currentRoute?.hierarchy?.any { it.route == dest.route } == true
                    } ?: TopLevelDestination.HOME,
                    onSelect = { dest -> navController.navigateToTopLevel(dest) },
                )
            }
        },
    ) { padding ->
        if (!onboardingCompleted) {
            // 整个 NavHost 都不组合：首启时不该有任何 ViewModel 在后面预热，
            // 尤其是首页那条连接监控的 WebSocket
            OnboardingScreen(onFinish = viewModel::completeOnboarding)
            return@GlassScaffold
        }

        NavHost(
            navController = navController,
            startDestination = TopLevelDestination.HOME.route,
        ) {
            composable(TopLevelDestination.HOME.route) {
                HomeScreen(
                    contentPadding = padding,
                    onEditInbound = { navController.navigate(Routes.inboundEdit(it)) },
                    onAddInbound = { navController.navigate(Routes.inboundEdit(Routes.NEW_ID)) },
                    onOpenNodes = { navController.navigateToTopLevel(TopLevelDestination.NODES) },
                    onOpenMonitor = { navController.navigate(Routes.MONITOR) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                )
            }
            composable(TopLevelDestination.NODES.route) { entry ->
                NodesScreen(
                    contentPadding = padding,
                    onEditNode = { navController.navigate(Routes.nodeEdit(it)) },
                    onAddNode = { navController.navigate(Routes.nodeEdit(Routes.NEW_ID)) },
                    onScan = { navController.navigate(Routes.SCAN) },
                    // 扫码页把结果写回节点页的 SavedStateHandle，
                    // 这样导入提示能在返回后正常显示
                    scanResult = entry.savedStateHandle.remove<String>(SCAN_RESULT_KEY),
                )
            }
            composable(TopLevelDestination.ROUTING.route) {
                RoutingScreen(
                    contentPadding = padding,
                    onEditRule = { navController.navigate(Routes.ruleEdit(it)) },
                    onAddRule = { navController.navigate(Routes.ruleEdit(Routes.NEW_ID)) },
                )
            }
            composable(TopLevelDestination.MORE.route) {
                MoreScreen(
                    contentPadding = padding,
                    onOpenLogs = { navController.navigate(Routes.LOGS) },
                    onOpenMonitor = { navController.navigate(Routes.MONITOR) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                )
            }

            composable(Routes.INBOUND_EDIT) {
                InboundEditScreen(onNavigateBack = navController::popBackStack)
            }
            composable(Routes.NODE_EDIT) {
                NodeEditScreen(onNavigateBack = navController::popBackStack)
            }
            composable(Routes.RULE_EDIT) {
                RuleEditScreen(onNavigateBack = navController::popBackStack)
            }
            composable(Routes.LOGS) {
                LogsScreen(onNavigateBack = navController::popBackStack)
            }
            composable(Routes.MONITOR) {
                MonitorScreen(onNavigateBack = navController::popBackStack)
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(onNavigateBack = navController::popBackStack)
            }
            composable(Routes.SCAN) {
                ScanScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onImported = { message ->
                        navController.previousBackStackEntry
                            ?.savedStateHandle
                            ?.set(SCAN_RESULT_KEY, message)
                    },
                )
            }
        }
    }
}

private fun NavHostController.navigateToTopLevel(destination: TopLevelDestination) {
    navigate(destination.route) {
        // 回到起始页而不是无限堆栈，并保存/恢复各 Tab 的滚动位置
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun GlassBottomBar(
    current: TopLevelDestination,
    onSelect: (TopLevelDestination) -> Unit,
) {
    val hazeState = LocalHazeState.current
    GlassBar(hazeState = hazeState, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(WindowInsets.navigationBars.asPaddingValues())
                .height(64.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TopLevelDestination.entries.forEach { destination ->
                val selected = destination == current
                val tint by animateColorAsState(
                    if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    label = "navTint",
                )
                androidx.compose.foundation.layout.Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .selectable(
                            selected = selected,
                            onClick = { onSelect(destination) },
                            role = Role.Tab,
                        )
                        .padding(horizontal = 18.dp, vertical = 6.dp),
                ) {
                    Icon(
                        imageVector = destination.icon,
                        contentDescription = destination.label,
                        tint = tint,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = destination.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = tint,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}
