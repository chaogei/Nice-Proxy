package com.niceproxy.core.designsystem.component

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.core.content.ContextCompat
import com.niceproxy.core.designsystem.theme.NiceTheme
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 极光渐变背景。
 *
 * 毛玻璃只有在背后有内容可模糊时才成立 —— 纯色背景糊出来还是纯色。
 * 这里用几团缓慢漂移的彩色光斑作为「可被模糊的素材」，
 * 面板叠在上面才会呈现出玻璃的通透与色彩渗透。
 *
 * 动画周期取得很长（38 秒），慢到几乎察觉不到，避免变成视觉噪音。
 *
 * 相位是**分档跳**的，不逐帧推进。原因不在这三团光斑本身有多贵，而在于这层背景
 * 是所有面板的 `hazeSource`：它每重绘一次，屏幕上每一块毛玻璃都要重跑一遍模糊。
 * 对一个会开一整天的代理网关来说，为了每帧几个像素的位移付出这个代价完全不成
 * 比例（NFR-5）。分档后重绘频率从 60~120 Hz 降到约 7 Hz，肉眼没有差别。
 */
@Composable
fun GlassBackground(
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val glass = NiceTheme.glass
    val blobs = if (glass.isDark) DarkBlobs else LightBlobs
    val base = if (glass.isDark) DarkBase else LightBase
    val phase = rememberAuroraPhase()

    Box(modifier = modifier) {
        Box(
            Modifier
                .fillMaxSize()
                .background(base)
                .drawWithCache {
                    // 相位读在 cache 块里：跳档才重建画笔。每支 radialGradient 背后
                    // 都要新建一个原生 Shader，逐帧重建是这段代码里最贵的一笔。
                    val layers = blobs.map { it.layerAt(phase.floatValue, size) }
                    onDrawBehind {
                        layers.forEach { drawCircle(it.brush, it.radius, it.center) }
                    }
                }
                .hazeSource(hazeState),
        )
        content()
    }
}

/**
 * 极光相位，取值 0~2π。
 *
 * 用 [withInfiniteAnimationFrameNanos] 而不是 `delay` 循环：它走的是 Compose 的帧时钟，
 * 而这个时钟在 Activity `ON_STOP`（灭屏、退到后台）时会被 `PausableMonotonicFrameClock`
 * 挂起。灭屏后动画自然停住，不需要在这里再写一遍生命周期判断。
 */
@Composable
private fun rememberAuroraPhase(): FloatState {
    val phase = remember { mutableFloatStateOf(0f) }
    // 预览与截图测试里关掉动画，否则每帧都在变，无法做视觉回归
    val animated = if (LocalInspectionMode.current) false else !powerSaveEnabled()

    LaunchedEffect(animated) {
        if (!animated) return@LaunchedEffect
        var origin = -1L
        while (true) {
            withInfiniteAnimationFrameNanos { now ->
                // 从当前相位接着走，省电模式开关时光斑才不会瞬移
                if (origin < 0) origin = now - (phase.floatValue / TwoPi * CycleNanos).toLong()

                val step = (now - origin) / StepNanos % PhaseSteps
                val value = step * TwoPi / PhaseSteps
                // 绝大多数帧落在同一档内，不写 state 就不会触发重绘
                if (value != phase.floatValue) phase.floatValue = value
            }
        }
    }
    return phase
}

/**
 * 省电模式下冻结极光。
 *
 * 用户主动开省电模式，说明他此刻在乎的是续航而不是观感。静态渐变依然是「有内容的
 * 渐变」，毛玻璃该糊什么还糊什么（§8.3 的第一条约束仍然成立），损失的只是「有没有
 * 在动」这一点点。
 */
@Composable
private fun powerSaveEnabled(): Boolean {
    val context = LocalContext.current
    val power = remember(context) {
        ContextCompat.getSystemService(context, PowerManager::class.java)
    }
    var enabled by remember(power) { mutableStateOf(power?.isPowerSaveMode == true) }

    DisposableEffect(power) {
        val manager = power ?: return@DisposableEffect onDispose { }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                enabled = manager.isPowerSaveMode
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }
    return enabled
}

private class Blob(
    color: Color,
    /** 中心位置，取值 0~1 的相对坐标。 */
    private val cx: Float,
    private val cy: Float,
    /** 半径相对于画布较长边的比例。 */
    private val radius: Float,
    /** 漂移幅度与相位偏移，让几团光斑不同步。 */
    private val drift: Float,
    private val phaseOffset: Float,
) {
    // 色标不随相位变化，提前建好，重建画笔时就只剩几何计算
    private val stops = listOf(color.copy(alpha = 0.55f), color.copy(alpha = 0f))

    fun layerAt(phase: Float, size: Size): BlobLayer {
        val r = maxOf(size.width, size.height) * radius
        val dx = cos(phase + phaseOffset) * drift * size.width
        val dy = sin(phase * 0.7f + phaseOffset) * drift * size.height
        val center = Offset(cx * size.width + dx, cy * size.height + dy)
        return BlobLayer(Brush.radialGradient(stops, center, r), center, r)
    }
}

private class BlobLayer(val brush: Brush, val center: Offset, val radius: Float)

private val LightBase = Color(0xFFEFF4F6)
private val DarkBase = Color(0xFF060C0E)

private val LightBlobs = listOf(
    Blob(Color(0xFF5EEAD4), cx = 0.18f, cy = 0.12f, radius = 0.62f, drift = 0.07f, phaseOffset = 0f),
    Blob(Color(0xFFA5B4FC), cx = 0.86f, cy = 0.24f, radius = 0.55f, drift = 0.06f, phaseOffset = 2.1f),
    Blob(Color(0xFFFBCFE8), cx = 0.62f, cy = 0.88f, radius = 0.58f, drift = 0.08f, phaseOffset = 4.2f),
)

private val DarkBlobs = listOf(
    Blob(Color(0xFF0E7490), cx = 0.16f, cy = 0.10f, radius = 0.68f, drift = 0.08f, phaseOffset = 0f),
    Blob(Color(0xFF4338CA), cx = 0.88f, cy = 0.26f, radius = 0.60f, drift = 0.07f, phaseOffset = 2.1f),
    Blob(Color(0xFF9D174D), cx = 0.60f, cy = 0.92f, radius = 0.62f, drift = 0.09f, phaseOffset = 4.2f),
)

/** 一圈 38 秒，见 docs/DESIGN.md §8.3。 */
private const val CycleNanos = 38_000_000_000L

/**
 * 相位档数。38 秒切 256 档 ≈ 每 148 ms 走一步，约 7 Hz。
 *
 * 按 1080×2400 估算，光斑中心每步最多挪 4 px；而它是一团半径上千像素、边缘 alpha
 * 一路渐变到 0 的软光晕，4 px 位移引起的局部亮度变化不到 1/255，看不出来。
 */
private const val PhaseSteps = 256
private const val StepNanos = CycleNanos / PhaseSteps

private val TwoPi = (2 * PI).toFloat()
