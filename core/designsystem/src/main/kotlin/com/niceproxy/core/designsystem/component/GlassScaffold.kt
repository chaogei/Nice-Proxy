package com.niceproxy.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.rememberHazeState

/**
 * 毛玻璃需要一个共享的 [HazeState] 把「背景」和「面板」连起来。
 * 用 CompositionLocal 传递，避免每个页面的签名里都挂一个 hazeState 参数。
 *
 * static 版本：这个值在 [GlassScaffold] 里 remember 一次之后就不再变，
 * 而读它的是屏幕上每一块面板 —— 没必要给每次读都建一份状态订阅。
 */
val LocalHazeState: ProvidableCompositionLocal<HazeState> =
    staticCompositionLocalOf { error("LocalHazeState 未提供，页面必须包裹在 GlassScaffold 内") }

/**
 * 应用级外壳：铺满的极光背景 + 透明的 Scaffold。
 *
 * Scaffold 自身必须是透明的，否则它的 surface 会挡住背景，
 * 面板再怎么模糊也只能糊出一片纯色。
 */
@Composable
fun GlassScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    snackbarHostState: SnackbarHostState? = null,
    content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit,
) {
    val hazeState = rememberHazeState()

    CompositionLocalProvider(LocalHazeState provides hazeState) {
        GlassBackground(hazeState = hazeState, modifier = modifier.fillMaxSize()) {
            Scaffold(
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurface,
                topBar = topBar,
                bottomBar = bottomBar,
                floatingActionButton = floatingActionButton,
                snackbarHost = {
                    snackbarHostState?.let { SnackbarHost(it) }
                },
                content = content,
            )
        }
    }
}

/** 页面级容器，供已经处在 [GlassScaffold] 内的子页面使用。 */
@Composable
fun GlassContent(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier = modifier, content = content)
}
