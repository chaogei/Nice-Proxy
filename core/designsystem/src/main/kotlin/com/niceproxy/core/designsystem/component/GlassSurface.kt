package com.niceproxy.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.niceproxy.core.designsystem.theme.NiceTheme
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/**
 * 毛玻璃面板。
 *
 * 在 API 31+ 上 Haze 走 RenderEffect 做真正的背景模糊；更低版本自动降级为
 * 半透明着色 —— 观感会弱一些，但不会破版，也不需要我们写两套布局。
 *
 * 边缘的浅色描边不是装饰：没有它，毛玻璃在浅色背景上会和底色糊成一片，
 * 失去「一块悬浮的玻璃」的边界感。
 */
@Composable
fun GlassPanel(
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    shape: Shape = PanelShape,
    onClick: (() -> Unit)? = null,
    contentPadding: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val glass = NiceTheme.glass
    // hazeEffect 的配置块不是 @Composable，主题色必须先在外层读出来
    val surface = MaterialTheme.colorScheme.surface
    // 面板在长列表里成批出现，而这两个对象只跟主题走。不 remember 的话，
    // 每块面板的每次重组都要新建一支渐变画笔和一条描边。
    val panelTints = remember(glass.panelTint) { listOf(HazeTint(glass.panelTint)) }
    val panelBorder = remember(glass.panelBorder) {
        BorderStroke(
            width = 1.dp,
            brush = Brush.verticalGradient(
                listOf(glass.panelBorder, glass.panelBorder.copy(alpha = 0.15f)),
            ),
        )
    }
    Column(
        modifier = modifier
            .clip(shape)
            .hazeEffect(state = hazeState) {
                backgroundColor = surface
                tints = panelTints
                blurRadius = glass.blurRadiusDp.dp
                noiseFactor = glass.noiseFactor
            }
            .border(border = panelBorder, shape = shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(contentPadding),
        content = content,
    )
}

/** 顶栏、底栏这类需要贴边、不加描边的毛玻璃条。 */
@Composable
fun GlassBar(
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val glass = NiceTheme.glass
    val surface = MaterialTheme.colorScheme.surface
    val barTints = remember(glass.panelTint) {
        listOf(HazeTint(glass.panelTint.copy(alpha = glass.panelTint.alpha * 0.85f)))
    }
    Box(
        modifier = modifier.hazeEffect(state = hazeState) {
            backgroundColor = surface
            tints = barTints
            blurRadius = glass.blurRadiusDp.dp
            noiseFactor = glass.noiseFactor
        },
        content = content,
    )
}

/** 带光晕的圆形容器，用于首页大开关这类需要吸引视线的元素。 */
@Composable
fun GlowCircle(
    color: Color,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    listOf(color.copy(alpha = 0.32f), color.copy(alpha = 0.05f)),
                ),
            ),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

// 默认参数每次调用都会重新求值，而面板在长列表里是成批出现的
private val PanelShape = RoundedCornerShape(22.dp)
