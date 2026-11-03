package com.lightledger.app.ui.glass

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 页面内组件（卡片 / 面板 / 弹层）的玻璃采样源。
 *
 * 为什么不用页面自身图层：玻璃卡片是页面节点的子节点，采样祖先图层会形成绘制环，
 * 整层被丢弃（表现为卡片变成纯色块）。所以这里单独提供一层「环境光」——
 * 底色 + 三团柔光色块，静态录制一次，所有页面内玻璃都采样它。
 * 导航条与数字键盘是页面的兄弟节点，仍采样页面内容，能看到真实滚动内容。
 */
val LocalGlassBackdrop = staticCompositionLocalOf<GlassBackdrop?> { null }

/** 卡片膜的默认浓度：比导航条更实一点，保证正文可读性 */
@Composable
fun glassCardTint(): Color = MaterialTheme.colorScheme.surfaceContainerLow.copy(
    alpha = if (isSystemInDarkTheme()) 0.42f else 0.46f,
)

/**
 * 应用根容器：铺一层环境光背景，并把它的图层作为所有页面内玻璃的采样源。
 * 内容区应保持透明背景，环境光才能透上来。
 */
@Composable
fun GlassHost(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val backdrop = rememberGlassBackdrop()
    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .glassBackdropSource(backdrop),
        ) {
            AmbientLight()
        }
        CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
            content()
        }
    }
}

/** 底色 + 三团柔光：给玻璃提供可被折射的色差，静态绘制，几乎零开销 */
@Composable
private fun AmbientLight() {
    val scheme = MaterialTheme.colorScheme
    val dark = isSystemInDarkTheme()
    val base = scheme.background
    val blobAlpha = if (dark) 0.34f else 0.30f
    val topColor = scheme.primary.copy(alpha = blobAlpha)
    val sideColor = scheme.tertiary.copy(alpha = blobAlpha * 0.85f)
    val bottomColor = scheme.secondary.copy(alpha = blobAlpha * 0.75f)

    Canvas(Modifier.fillMaxSize()) {
        drawRect(base)
        val w = size.width
        val h = size.height

        // 左上：品牌绿，最大一团
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(topColor, Color.Transparent),
                center = Offset(w * 0.12f, h * 0.06f),
                radius = w * 0.95f,
            ),
            radius = w * 0.95f,
            center = Offset(w * 0.12f, h * 0.06f),
        )
        // 右侧中部：第三种色，制造冷暖对比
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(sideColor, Color.Transparent),
                center = Offset(w * 1.02f, h * 0.34f),
                radius = w * 0.72f,
            ),
            radius = w * 0.72f,
            center = Offset(w * 1.02f, h * 0.34f),
        )
        // 左下：更淡的一团，底部导航条背后有内容可折射
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(bottomColor, Color.Transparent),
                center = Offset(w * 0.08f, h * 0.92f),
                radius = w * 0.86f,
            ),
            radius = w * 0.86f,
            center = Offset(w * 0.08f, h * 0.92f),
        )
    }
}

/**
 * 玻璃卡片：有 [GlassHost] 时是真玻璃（背景折射 + 高斯模糊 + 发丝描边 + 顶部高光），
 * 没有时退化为半透明卡片，保证任何宿主下都能正常渲染。
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    tint: Color = glassCardTint(),
    blurRadius: Dp = 20.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val backdrop = LocalGlassBackdrop.current
    if (backdrop != null) {
        GlassPanel(
            backdrop = backdrop,
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            tint = tint,
            blurRadius = blurRadius,
        ) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
    } else {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .clip(shape)
                .background(tint)
                .border(0.75.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), shape),
            content = content,
        )
    }
}

