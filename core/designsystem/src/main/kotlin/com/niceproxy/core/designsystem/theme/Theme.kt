package com.niceproxy.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** 品牌主色：青蓝。动态取色不可用时的回退方案。 */
private val Teal = Color(0xFF006A70)
private val TealDark = Color(0xFF4FD8E0)

private val LightColors = lightColorScheme(
    primary = Teal,
    secondary = Color(0xFF4A6365),
    tertiary = Color(0xFF4F5F7E),
    background = Color(0xFFF4F7F8),
)

private val DarkColors = darkColorScheme(
    primary = TealDark,
    secondary = Color(0xFFB1CBCD),
    tertiary = Color(0xFFB7C7EA),
    background = Color(0xFF0B1113),
)

/** 运行状态色，跨页面复用以保持语义一致。 */
object StatusColors {
    val running = Color(0xFF22C55E)
    val connecting = Color(0xFFF59E0B)
    val stopped = Color(0xFF94A3B8)
    val error = Color(0xFFEF4444)
}

/** 延迟档位色，节点列表与测速结果共用。 */
object LatencyColors {
    val good = Color(0xFF22C55E)
    val fair = Color(0xFFF59E0B)
    val poor = Color(0xFFF97316)
    val timeout = Color(0xFFEF4444)
    val unknown = Color(0xFF94A3B8)
}

/**
 * 毛玻璃面板的观感参数。
 *
 * 亮色与暗色下需要完全不同的取值：暗色背景上要用浅色高光叠加才有「玻璃」感，
 * 亮色背景上则要用白色底 + 更低的透明度，否则会糊成一片灰。
 *
 * 标 [Immutable] 是把「建好之后不会变」写进契约而不是交给推断：每一块面板都拿它当
 * `remember` 的 key，推断一旦失手，下游每次重组都要重建画笔和描边。
 */
@Immutable
data class GlassTokens(
    val panelTint: Color,
    val panelBorder: Color,
    val panelHighlight: Color,
    val blurRadiusDp: Float,
    val noiseFactor: Float,
    val isDark: Boolean,
)

private val LightGlass = GlassTokens(
    panelTint = Color.White.copy(alpha = 0.55f),
    panelBorder = Color.White.copy(alpha = 0.70f),
    panelHighlight = Color.White.copy(alpha = 0.35f),
    blurRadiusDp = 24f,
    noiseFactor = 0.04f,
    isDark = false,
)

private val DarkGlass = GlassTokens(
    panelTint = Color(0xFF0E1A1D).copy(alpha = 0.55f),
    panelBorder = Color.White.copy(alpha = 0.14f),
    panelHighlight = Color.White.copy(alpha = 0.06f),
    blurRadiusDp = 28f,
    noiseFactor = 0.06f,
    isDark = true,
)

/**
 * 用 static 版本：这套参数只在亮暗色切换时变，而那种时候整棵树本来就要重画
 * （Material 的 `LocalColorScheme` 同样是 static）。换成普通 `compositionLocalOf`
 * 只会让每一块面板都多挂一份读订阅，换不来任何东西。
 */
val LocalGlassTokens: ProvidableCompositionLocal<GlassTokens> =
    staticCompositionLocalOf { LightGlass }

object NiceTheme {
    val glass: GlassTokens
        @Composable @ReadOnlyComposable get() = LocalGlassTokens.current
}

@Composable
fun NiceProxyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    CompositionLocalProvider(
        LocalGlassTokens provides if (darkTheme) DarkGlass else LightGlass,
    ) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}
