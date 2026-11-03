package com.lightledger.app.ui.glass

import android.graphics.BlurMaskFilter
import android.graphics.LinearGradient
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.RenderEffect as AndroidRenderEffect
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * 液态玻璃（Liquid Glass）渲染管线。
 *
 * 配方学习自 Kyant0/AndroidLiquidGlass（Apache-2.0，源码存于 reference/liquidglass/），
 * 用 Compose 1.7 GraphicsLayer 自实现，不引入第三方依赖：
 *  1. 背景采集：整页内容经 [glassBackdropSource] 录制进共享 [GraphicsLayer]；
 *  2. 玻璃节点每帧把背景样本重录进自己的临时图层（四周留 padding 余量），在临时图层上挂
 *     android RenderEffect 链（AGSL 折射 → 模糊 → vibrancy）后 drawLayer 采样；
 *  3. 表面叠加 40% 容器色 + 顶部高光描边；
 *  4. 降级：API < 33 无 AGSL 折射；API < 31 无 RenderEffect，退化为半透明面板。
 */

/** vibrancy：背景饱和度提升（Kyant0 demo 取 1.5） */
private const val VIBRANCY_SATURATION = 1.5f

/**
 * 玻璃表面默认色：比页面背景更亮的极薄磨砂膜（浅色近白 / 深色浅灰）。
 *
 * 膜比早期版本更透（0.52/0.46 → 0.38/0.34），可读性交给更强的模糊和描边，
 * 让背景内容的色块能真正透过玻璃被看见。
 */
@Composable
fun glassTint(alpha: Float = if (isSystemInDarkTheme()) 0.38f else 0.34f): Color {
    val scheme = MaterialTheme.colorScheme
    return if (isSystemInDarkTheme()) {
        scheme.surfaceContainerHigh.copy(alpha = alpha)
    } else {
        scheme.surfaceContainerLowest.copy(alpha = alpha)
    }
}

// ---------------------------------------------------------------------------
// 背景采集
// ---------------------------------------------------------------------------

class GlassBackdrop(
    /** 背景源图层：页面节点自身的 GraphicsLayer，由 Compose 随页面绘制实时录制 */
    internal var layer: GraphicsLayer,
) {
    /** 背景源节点在组合根中的位置（状态：变化时触发玻璃重绘） */
    internal var sourcePosition by mutableStateOf(Offset.Zero)
}

@Composable
fun rememberGlassBackdrop(): GlassBackdrop {
    val layer = rememberGraphicsLayer()
    return remember { GlassBackdrop(layer) }
}

/**
 * 挂在承载页面内容的根节点上，让玻璃拿到「页面已绘制内容」的图层。
 *
 * 关键点：不能自己 `record { drawContent() }` —— 在 Compose 1.7 里 ContentDrawScope.drawContent()
 * 画到的是外层画布，记录出的图层是空的（实机验证：玻璃里能看到的始终是底色而非模糊的背景）。
 * 改成给节点加一层 [graphicsLayer]，直接借用 Compose 自己录好的节点图层作为采样源。
 */
fun Modifier.glassBackdropSource(backdrop: GlassBackdrop): Modifier =
    this
        // Offscreen 强制本节点持有独立图层；否则 drawContext.graphicsLayer 会指向祖先层，
        // 玻璃再采样祖先层会形成绘制环，整层被丢弃（实测表现为玻璃变成纯色块）。
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .onGloballyPositioned { backdrop.sourcePosition = it.boundsInRoot().topLeft }
        .drawWithContent {
            // Compose 在绘制该节点前就把图层挂到 drawContext 上，内容随后由 drawContent() 录进图层
            drawContext.graphicsLayer?.let { layer ->
                if (backdrop.layer !== layer) backdrop.layer = layer
            }
            drawContent()
        }

// ---------------------------------------------------------------------------
// AGSL 折射着色器（API 33+）
// ---------------------------------------------------------------------------

/**
 * 圆角矩形 SDF 边缘折射着色器。SDF/梯度/circleMap 函数改写自
 * Kyant0/AndroidLiquidGlass（Apache-2.0）。
 * 坐标约定：着色器坐标中玻璃区域从 (uOffset, uOffset) 开始、尺寸 uSize；
 * uRadii 依次为 左上/右上/右下/左下（px）；uAmount 传负值实现向内折射。
 */
private const val REFRACTION_SHADER = """
uniform shader content;
uniform float2 uSize;
uniform float uOffset;
uniform float4 uRadii;
uniform float uHeight;
uniform float uAmount;

float radiusAt(float2 centered, float4 radii) {
    if (centered.x >= 0.0) {
        if (centered.y <= 0.0) return radii.y;
        else return radii.z;
    } else {
        if (centered.y <= 0.0) return radii.x;
        else return radii.w;
    }
}

float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    float outside = length(max(cornerCoord, 0.0)) - radius;
    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
    return outside + inside;
}

float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
        return sign(coord) * normalize(max(cornerCoord, 0.0));
    } else {
        float gradX = step(cornerCoord.y, cornerCoord.x);
        return sign(coord) * float2(gradX, 1.0 - gradX);
    }
}

float circleMap(float x) {
    return 1.0 - sqrt(1.0 - x * x);
}

half4 main(float2 coord) {
    float2 halfSize = uSize * 0.5;
    float2 centered = (coord - float2(uOffset, uOffset)) - halfSize;
    float radius = radiusAt(centered, uRadii);

    float2 sampleCoord = coord;
    float sd = sdRoundedRect(centered, halfSize, radius);
    if (-sd < uHeight) {
        sd = min(sd, 0.0);
        float d = circleMap(1.0 - -sd / uHeight) * uAmount;
        float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
        float2 grad = gradSdRoundedRect(centered, halfSize, gradRadius);
        sampleCoord = coord + d * grad;
    }

    // vibrancy：饱和度 1.5（Kyant0 demo 参数，饱和化放在模糊之后视觉等价）
    float4 col = content.eval(sampleCoord);
    float lum = dot(col.rgb, float3(0.2126, 0.7152, 0.0722));
    col.rgb = clamp(lum + (col.rgb - lum) * 1.5, 0.0, 1.0);
    return half4(col);
}
"""

// ---------------------------------------------------------------------------
// 玻璃修饰符
// ---------------------------------------------------------------------------

/**
 * 液态玻璃面板修饰符：内容绘制前先采样 [backdrop] 的「折射+模糊+vibrancy」像，
 * 叠加 [tint] 表面色后绘制内容，最后叠顶部高光描边。
 *
 * @param tint          表面色（建议 surface 系 40% 透明度）
 * @param blurRadius    模糊半径（API < 31 时退化为半透明实底）
 * @param refraction    是否启用 AGSL 边缘折射（API 33+），折射带宽度与强度由其导出
 */
@Composable
fun Modifier.liquidGlass(
    backdrop: GlassBackdrop,
    shape: Shape,
    tint: Color = glassTint(),
    blurRadius: Dp = 20.dp,
    refraction: Boolean = true,
): Modifier {
    val layoutDirection = LocalLayoutDirection.current
    val scratch = rememberGraphicsLayer()
    var selfPosition by remember { mutableStateOf(Offset.Zero) }
    val supportsBlur = remember { Build.VERSION.SDK_INT >= 31 }
    val supportsLens = remember { Build.VERSION.SDK_INT >= 33 }
    val lensShader = remember(supportsLens) {
        if (supportsLens) RuntimeShader(REFRACTION_SHADER) else null
    }
    val highlightAlpha = if (isSystemInDarkTheme()) 0.35f else 0.55f
    // 极薄表面膜下，浅色用暗线、深色用亮线定住胶囊边界
    val rimColor = if (isSystemInDarkTheme()) {
        Color.White.copy(alpha = 0.12f)
    } else {
        Color.Black.copy(alpha = 0.06f)
    }

    return this
        .onGloballyPositioned { selfPosition = it.boundsInRoot().topLeft }
        .graphicsLayer {
            this.shape = shape
            clip = true
        }
        .drawWithContent {
            val w = size.width
            val h = size.height
            if (w < 1f || h < 1f) {
                drawContent()
                return@drawWithContent
            }

            if (supportsBlur) {
                val blurPx = blurRadius.toPx()
                val bandPx = 12.dp.toPx()
                val padPx = ceil(blurPx + bandPx)
                val sw = (w + padPx * 2).toInt().coerceAtLeast(1)
                val sh = (h + padPx * 2).toInt().coerceAtLeast(1)

                // 1. 先挂效果链，再录制样本。
                //    GraphicsLayer 的 renderEffect 必须在 record() 之前设置，否则本帧不生效（模糊会丢失）。
                scratch.renderEffect = buildGlassEffect(
                    widthPx = w,
                    heightPx = h,
                    padPx = padPx,
                    blurPx = blurPx,
                    bandPx = bandPx,
                    radii = cornerRadiiPx(shape, w, h, layoutDirection),
                    withLens = refraction && lensShader != null,
                    lensShader = lensShader,
                ).asComposeRenderEffect()

                // 2. 重录背景样本：玻璃区域在样本层中位于 (pad, pad)
                scratch.record(this, layoutDirection, IntSize(sw, sh)) {
                    val src = backdrop.sourcePosition
                    translate(
                        padPx - (selfPosition.x - src.x),
                        padPx - (selfPosition.y - src.y),
                    ) {
                        drawLayer(backdrop.layer)
                    }
                }

                // 3. 采样绘制（样本层比节点大 pad，平移回来）
                translate(-padPx, -padPx) {
                    drawLayer(scratch)
                }
            } else {
                // API < 31：无 RenderEffect，用更实的表面色保证可读性
                drawRect(tint.copy(alpha = (tint.alpha * 1.8f).coerceAtMost(0.85f)))
            }

            // 4. 表面色
            drawRect(tint)

            // 4.5 玻璃边缘：极薄膜下用一圈发丝线定住胶囊边界
            drawGlassRim(shape, rimColor)

            // 5. 内容
            drawContent()

            // 6. 顶部高光描边（内容之上）
            drawHighlight(shape, highlightAlpha)
        }
}

/**
 * 构建 android.graphics.RenderEffect 效果链：内层模糊 → 外层 AGSL 折射+vibrancy。
 * 与 Kyant0 一致：lens 最外层，其 uniform shader 采到的是已模糊的样本。
 */
private fun buildGlassEffect(
    widthPx: Float,
    heightPx: Float,
    padPx: Float,
    blurPx: Float,
    bandPx: Float,
    radii: FloatArray,
    withLens: Boolean,
    lensShader: RuntimeShader?,
): AndroidRenderEffect {
    val blur = AndroidRenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP)
    if (withLens && lensShader != null) {
        lensShader.setFloatUniform("uSize", widthPx, heightPx)
        lensShader.setFloatUniform("uOffset", padPx)
        lensShader.setFloatUniform("uRadii", radii[0], radii[1], radii[2], radii[3])
        lensShader.setFloatUniform("uHeight", bandPx)
        lensShader.setFloatUniform("uAmount", -bandPx * 1.7f)
        val lens = AndroidRenderEffect.createRuntimeShaderEffect(lensShader, "content")
        return AndroidRenderEffect.createChainEffect(lens, blur)
    }
    return blur
}

/** Shape 四角圆角半径(px)：左上/右上/右下/左下；非圆角形状返回 0（折射自动退化） */
private fun DrawScope.cornerRadiiPx(
    shape: Shape,
    width: Float,
    height: Float,
    layoutDirection: LayoutDirection,
): FloatArray {
    val maxRadius = minOf(width, height) / 2f
    val outline = shape.createOutline(Size(width, height), layoutDirection, this)
    val rounded = (outline as? Outline.Rounded)?.roundRect ?: return floatArrayOf(0f, 0f, 0f, 0f)
    return floatArrayOf(
        rounded.topLeftCornerRadius.x.coerceAtMost(maxRadius),
        rounded.topRightCornerRadius.x.coerceAtMost(maxRadius),
        rounded.bottomRightCornerRadius.x.coerceAtMost(maxRadius),
        rounded.bottomLeftCornerRadius.x.coerceAtMost(maxRadius),
    )
}

// ---------------------------------------------------------------------------
// 高光描边（顶部白 → 渐隐，柔化后沿轮廓描一圈、裁剪到轮廓内侧）
// ---------------------------------------------------------------------------

/** 玻璃边缘发丝线：沿轮廓描一圈 0.75dp 细线（浅色黑 6% / 深色白 12%） */
private fun DrawScope.drawGlassRim(shape: Shape, color: Color) {
    val outline = shape.createOutline(size, layoutDirection, this)
    val path = Path()
    when (val o = outline) {
        is Outline.Rounded -> path.addRoundRect(o.roundRect)
        is Outline.Rectangle -> path.addRect(o.rect)
        is Outline.Generic -> path.addPath(o.path)
    }
    drawPath(path, color, style = Stroke(width = 0.75f.dp.toPx()))
}

private fun DrawScope.drawHighlight(shape: Shape, alpha: Float) {
    val strokeHalf = 0.75f.dp.toPx()
    val aPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        // 描边宽度翻倍 + 裁剪到轮廓：轮廓边缘可见带 = strokeHalf（Kyant0 同款技巧）
        strokeWidth = strokeHalf * 2f
        // 渐变自带 alpha（shader 会覆盖 paint 颜色）
        shader = LinearGradient(
            0f, 0f, 0f, size.height * 0.6f,
            Color.White.copy(alpha = alpha).toArgb(),
            Color.White.copy(alpha = 0f).toArgb(),
            Shader.TileMode.CLAMP,
        )
        if (Build.VERSION.SDK_INT >= 28) {
            maskFilter = BlurMaskFilter(strokeHalf, BlurMaskFilter.Blur.NORMAL)
        }
    }

    val outline = shape.createOutline(size, layoutDirection, this)
    val path = Path()
    when (val o = outline) {
        is Outline.Rounded -> path.addRoundRect(o.roundRect)
        is Outline.Rectangle -> path.addRect(o.rect)
        is Outline.Generic -> path.addPath(o.path)
    }
    val aPath = path.asAndroidPath()
    val canvas = drawContext.canvas.nativeCanvas
    canvas.save()
    canvas.clipPath(aPath)
    canvas.drawPath(aPath, aPaint)
    canvas.restore()
}

// ---------------------------------------------------------------------------
// 面板与按钮封装
// ---------------------------------------------------------------------------

/** 液态玻璃面板：Box + [liquidGlass]，形状默认胶囊 */
@Composable
fun GlassPanel(
    backdrop: GlassBackdrop,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.extraLarge,
    tint: Color = glassTint(),
    blurRadius: Dp = 14.dp,
    content: @Composable BoxScope.() -> Unit = {},
) {
    Box(
        modifier
            .liquidGlass(backdrop, shape, tint, blurRadius),
        content = content,
    )
}
