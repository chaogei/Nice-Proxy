package com.niceproxy.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * 速率火花图：一条随时间左移的折线，外加一层渐隐的填充。
 *
 * 自己用 [Canvas] 画而不是引入图表库。这里要画的只是「一串等间距的数」，
 * 而任何一个通用图表库都会连坐标轴、图例、手势、动画引擎一起带进包体 ——
 * 为了首页上这么一小条曲线，那笔交易划不来。
 *
 * [peak] 由调用方给，而不是每次取 [values] 自己的最大值：多条曲线（上行/下行）
 * 必须共用同一把纵向标尺，各画各的会让「下载是上传的一百倍」看起来一样高。
 */
@Composable
fun Sparkline(
    values: List<Long>,
    peak: Long,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawSparkline(values, peak, color)
        }
    }
}

/**
 * 拆成非 Composable 的扩展函数，好让绘制逻辑本身可以被直接调用与推敲。
 *
 * 少于两个点时什么都不画：一个点连不成线，而画一条横贯全宽的直线会
 * 谎称「这一整段时间的速率都是这个值」。
 */
internal fun DrawScope.drawSparkline(values: List<Long>, peak: Long, color: Color) {
    if (values.size < MIN_POINTS) return

    val stepX = size.width / (values.size - 1)
    // peak 为 0 表示这段时间一直没流量。除零会画出 NaN 坐标，
    // 而 NaN 会让整个 Canvas 静默不绘制（不是崩溃，是「图没了」，更难查）。
    val scale = if (peak <= 0) 0f else size.height / peak.toFloat()

    val line = Path()
    values.forEachIndexed { index, value ->
        val x = index * stepX
        val y = size.height - (value.coerceAtLeast(0) * scale).coerceIn(0f, size.height)
        if (index == 0) line.moveTo(x, y) else line.lineTo(x, y)
    }

    // 填充路径复制折线再收口到底边。直接在 line 上补两笔会让描边也跟着
    // 沿底边画一圈，那条横线看起来像是一根不存在的坐标轴。
    val fill = Path().apply {
        addPath(line)
        lineTo(size.width, size.height)
        lineTo(0f, size.height)
        close()
    }

    drawPath(
        path = fill,
        brush = Brush.verticalGradient(
            colors = listOf(color.copy(alpha = FILL_ALPHA), Color.Transparent),
            startY = 0f,
            endY = size.height,
        ),
    )
    drawPath(path = line, color = color, style = Stroke(width = STROKE_WIDTH_PX))
}

/**
 * 没有数据时的占位：一条贴着底边的虚线。
 *
 * 存在的理由是「还没开始采样」和「速率一直是 0」在一张空白图上长得一模一样，
 * 而用户会据此判断代理通没通。
 */
@Composable
fun SparklinePlaceholder(color: Color, modifier: Modifier = Modifier) {
    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            var x = 0f
            while (x < size.width) {
                drawLine(
                    color = color,
                    start = Offset(x, size.height),
                    end = Offset((x + DASH_PX).coerceAtMost(size.width), size.height),
                    strokeWidth = STROKE_WIDTH_PX,
                )
                x += DASH_PX * 2
            }
        }
    }
}

private const val MIN_POINTS = 2
private const val STROKE_WIDTH_PX = 2.5f
private const val FILL_ALPHA = 0.22f
private val DASH_PX = 6.dp.value
