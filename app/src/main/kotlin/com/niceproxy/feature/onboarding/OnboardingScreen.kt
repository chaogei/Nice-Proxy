package com.niceproxy.feature.onboarding

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.niceproxy.R
import com.niceproxy.core.designsystem.component.GlassPanel
import com.niceproxy.core.designsystem.component.GlowCircle
import com.niceproxy.core.designsystem.component.LocalHazeState
import com.niceproxy.core.designsystem.theme.StatusColors
import com.niceproxy.keepalive.KeepAlive
import com.niceproxy.keepalive.rememberIgnoringBatteryOptimizations

/**
 * 首启引导。
 *
 * 存在的唯一理由是第一屏那句话：这个应用代理的是**别的设备**的流量。用户装完点启动、
 * 看到绿灯和「运行中」、打开手机浏览器发现没走代理，如果没人在此之前告诉他这是设计
 * 如此，他得出的结论只会是「这软件是坏的」。见 docs/DESIGN.md §8.2。
 *
 * 三屏分别回答「这是什么」「另一台设备怎么配」「为什么必须关电池优化」，
 * 都是用户在前五分钟内一定会撞上的问题。
 */
@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    var step by rememberSaveable { mutableStateOf(0) }
    val page = OnboardingPage.entries[step]
    val isLast = step == OnboardingPage.entries.lastIndex
    val accent = if (page == OnboardingPage.BATTERY) {
        StatusColors.connecting
    } else {
        MaterialTheme.colorScheme.primary
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 16.dp,
                    bottom = WindowInsets.navigationBars.asPaddingValues()
                        .calculateBottomPadding() + 20.dp,
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
        ) {
            GlassPanel(
                hazeState = LocalHazeState.current,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = 24.dp,
            ) {
                GlowCircle(
                    color = accent,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .size(84.dp),
                ) {
                    Icon(
                        imageVector = page.icon,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(36.dp),
                    )
                }

                Spacer(Modifier.height(20.dp))
                Text(
                    text = stringResource(page.titleRes),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(page.bodyRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (page == OnboardingPage.BATTERY) {
                    Spacer(Modifier.height(16.dp))
                    BatteryOptimizationAction()
                    VendorAutoStartAction()
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        StepDots(current = step, total = OnboardingPage.entries.size)
        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            // 「跳过」必须一直在：强迫读完三屏只会让人机械连点，反而记不住第一屏
            TextButton(onClick = onFinish) { Text(stringResource(R.string.onboarding_skip)) }
            Spacer(Modifier.weight(1f))
            Button(onClick = { if (isLast) onFinish() else step++ }) {
                Text(
                    stringResource(
                        if (isLast) R.string.onboarding_start else R.string.onboarding_next,
                    ),
                )
            }
        }
    }
}

@Composable
private fun BatteryOptimizationAction() {
    val context = LocalContext.current
    val ignoring = rememberIgnoringBatteryOptimizations()

    if (ignoring) {
        Text(
            text = stringResource(R.string.onboarding_battery_done),
            style = MaterialTheme.typography.bodyMedium,
            color = StatusColors.running,
        )
    } else {
        FilledTonalButton(
            onClick = { KeepAlive.requestIgnoreBatteryOptimizations(context) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.onboarding_battery_action))
        }
    }
}

/**
 * 厂商自启动白名单。
 *
 * 和电池优化是**并列的两道关卡**，不是补充说明：国产 ROM 的手机管家有一套独立于
 * AOSP 的自启动管控，不在名单里的话前台服务照样被清掉，连 `START_STICKY` 都会被
 * 忽略——进程根本不会被重建。
 *
 * 放进引导而不是只留在设置页，是因为这一项**没有任何 API 能检测用户加没加**。
 * 电池优化至少还能查状态、在首页报警提醒；这一项一旦错过，之后就再没有任何时机
 * 提起它了，用户只会在某天发现「昨晚又断了」而不知道该去开什么。
 */
@Composable
private fun VendorAutoStartAction() {
    val context = LocalContext.current
    // 组件解析要遍历十几个候选，且装了哪个手机管家不会在引导期间变
    val hasVendorSettings = remember(context) { KeepAlive.hasVendorAutoStartSettings(context) }
    if (!hasVendorSettings) return

    Spacer(Modifier.height(12.dp))
    Text(
        text = stringResource(R.string.onboarding_autostart_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    FilledTonalButton(
        onClick = { KeepAlive.openAutoStartSettings(context) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.onboarding_autostart_action))
    }
}

@Composable
private fun StepDots(current: Int, total: Int) {
    // 这些点是纯装饰性的 Box，读屏软件在这里什么都不会念 —— 于是「还有几屏」
    // 这个信息只有看得见的人拿得到，而「下一步」按钮又不说自己会走到哪。
    val progress = stringResource(R.string.onboarding_step, current + 1, total)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = progress },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(total) { index ->
            val active = index == current
            Box(
                modifier = Modifier
                    .size(if (active) 9.dp else 7.dp)
                    .clip(CircleShape)
                    .background(
                        if (active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                    ),
            )
            if (index != total - 1) Spacer(Modifier.width(8.dp))
        }
    }
}

private enum class OnboardingPage(
    val icon: ImageVector,
    @StringRes val titleRes: Int,
    @StringRes val bodyRes: Int,
) {
    WHAT_IT_IS(
        icon = Icons.Outlined.Devices,
        titleRes = R.string.onboarding_what_title,
        bodyRes = R.string.onboarding_what_body,
    ),
    HOW_TO_CONNECT(
        icon = Icons.Outlined.Wifi,
        titleRes = R.string.onboarding_connect_title,
        bodyRes = R.string.onboarding_connect_body,
    ),
    BATTERY(
        icon = Icons.Outlined.BatteryAlert,
        titleRes = R.string.onboarding_battery_title,
        bodyRes = R.string.onboarding_battery_body,
    ),
}
