package com.niceproxy.feature.onboarding

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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
                    text = page.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = page.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (page == OnboardingPage.BATTERY) {
                    Spacer(Modifier.height(16.dp))
                    BatteryOptimizationAction()
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        StepDots(current = step, total = OnboardingPage.entries.size)
        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            // 「跳过」必须一直在：强迫读完三屏只会让人机械连点，反而记不住第一屏
            TextButton(onClick = onFinish) { Text("跳过") }
            Spacer(Modifier.weight(1f))
            Button(onClick = { if (isLast) onFinish() else step++ }) {
                Text(if (isLast) "开始使用" else "下一步")
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
            text = "已关闭电池优化，这一步不用管了。",
            style = MaterialTheme.typography.bodyMedium,
            color = StatusColors.running,
        )
    } else {
        FilledTonalButton(
            onClick = { KeepAlive.requestIgnoreBatteryOptimizations(context) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("去关闭电池优化")
        }
    }
}

@Composable
private fun StepDots(current: Int, total: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
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
    val title: String,
    val body: String,
) {
    WHAT_IT_IS(
        icon = Icons.Outlined.Devices,
        title = "它代理的是别的设备",
        body = "Nice Proxy 在这台手机上开一个代理端口，供 Switch、PS5、电视盒子、" +
            "公司电脑这类装不了代理软件的设备使用。\n\n" +
            "它不申请 VPN 权限，因此不会代理这台手机自己的 App。" +
            "装好之后用手机浏览器测试，看到的仍然是没走代理 —— 这是正常的，不是坏了。",
    ),
    HOW_TO_CONNECT(
        icon = Icons.Outlined.Wifi,
        title = "在另一台设备上怎么填",
        body = "1. 让手机和那台设备连同一个 Wi-Fi，或者直接开手机热点让它连过来。\n\n" +
            "2. 回到首页的「在其他设备上填写」，把任意一个地址填进电脑或游戏机的" +
            "代理设置里 —— 卡片右侧就是复制按钮。\n\n" +
            "3. 代理类型选 HTTP 或 SOCKS5 都可以，默认入站两种都收。\n\n" +
            "换了网络之后地址会变，记得回首页重新抄一次。",
    ),
    BATTERY(
        icon = Icons.Outlined.BatteryAlert,
        title = "别让系统把它冻住",
        body = "代理跑在前台服务里。息屏一段时间后，系统的电池优化会冻结它 —— " +
            "表现是所有指过来的设备一起断网，而手机这边看不出任何异常。\n\n" +
            "现在关掉可以避免这件事，之后也能在「更多 → 设置 → 后台保活」里改。",
    ),
}
