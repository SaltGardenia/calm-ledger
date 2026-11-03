package com.lightledger.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.YearMonth
import kotlin.math.ceil

data class PieSlice(
    val label: String,
    val value: Float,
    val color: Color,
)

/** 环形饼图：progress 从 0→1 的弹簧动画由调用方驱动，弧线顺时针生长 */
@Composable
fun PieChart(
    slices: List<PieSlice>,
    modifier: Modifier = Modifier,
    progress: Float = 1f,
) {
    Canvas(modifier = modifier) {
        val total = slices.sumOf { it.value.toDouble() }.toFloat()
        if (total <= 0f) return@Canvas
        val p = progress.coerceIn(0f, 1f)
        val stroke = size.minDimension * 0.13f
        val diameter = size.minDimension - stroke
        val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
        var startAngle = -90f
        val gap = if (slices.size > 1) 2.5f else 0f
        slices.forEach { slice ->
            val sweep = slice.value / total * 360f
            drawArc(
                color = slice.color,
                startAngle = startAngle + gap / 2f,
                sweepAngle = (sweep * p - gap).coerceAtLeast(0.4f),
                useCenter = false,
                topLeft = topLeft,
                size = Size(diameter, diameter),
                style = Stroke(width = stroke, cap = StrokeCap.Butt),
            )
            startAngle += sweep
        }
    }
}

/** 每日柱状图：柱高随 progress 生长；average 非空时叠加虚线均值线 */
@Composable
fun BarChart(
    values: List<Float>,
    modifier: Modifier = Modifier,
    barColor: Color = Color.Transparent,
    progress: Float = 1f,
    average: Float? = null,
) {
    Canvas(modifier = modifier) {
        if (values.isEmpty()) return@Canvas
        val color = if (barColor == Color.Transparent) Color(0xFF34C759) else barColor
        val p = progress.coerceIn(0f, 1f)
        val maxVal = (values.maxOrNull() ?: 0f).coerceAtLeast(1f)
        val slot = size.width / values.size
        val barWidth = (slot * 0.5f).coerceAtMost(16.dp.toPx())
        val baselineY = size.height
        drawLine(
            color = color.copy(alpha = 0.2f),
            start = Offset(0f, baselineY - 1f),
            end = Offset(size.width, baselineY - 1f),
            strokeWidth = 2f,
        )
        values.forEachIndexed { index, value ->
            if (value <= 0f) return@forEachIndexed
            val h = (value / maxVal) * (size.height - 8f) * p
            val left = index * slot + (slot - barWidth) / 2f
            drawRoundRect(
                color = color,
                topLeft = Offset(left, baselineY - h - 2f),
                size = Size(barWidth, ceil(h) + 2f),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
        if (average != null && average > 0f) {
            val y = baselineY - (average / maxVal) * (size.height - 8f) * p - 2f
            drawLine(
                color = color.copy(alpha = 0.6f),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 2.5f,
                cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 9f)),
            )
        }
    }
}

data class LineSeries(
    val values: List<Float>,
    val color: Color,
)

/**
 * 多序列平滑折线（AA 式多实体时间线）：随 progress 从左向右揭示，
 * 末位非零点带实心圆点。序列各自独立，允许长度不同。
 */
@Composable
fun LineChart(
    series: List<LineSeries>,
    modifier: Modifier = Modifier,
    progress: Float = 1f,
) {
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier = modifier) {
        if (series.isEmpty()) return@Canvas
        val p = progress.coerceIn(0f, 1f)
        val maxVal = series.maxOf { s -> s.values.maxOrNull() ?: 0f }.coerceAtLeast(1f)
        val count = series.maxOf { it.values.size }.coerceAtLeast(2)
        val stepX = size.width / (count - 1)
        val topPad = 8f
        val axis = axisColor.copy(alpha = 0.25f)
        // 顶部参考线 + 基线
        drawLine(
            color = axis,
            start = Offset(0f, topPad),
            end = Offset(size.width, topPad),
            strokeWidth = 1.5f,
        )
        drawLine(
            color = axis,
            start = Offset(0f, size.height),
            end = Offset(size.width, size.height),
            strokeWidth = 1.5f,
        )
        series.forEach { s ->
            if (s.values.size < 2) return@forEach
            val pts = s.values.mapIndexed { i, v ->
                Offset(
                    i * stepX,
                    size.height - topPad - (v / maxVal) * (size.height - topPad * 2f),
                )
            }
            val path = Path().apply {
                moveTo(pts[0].x, pts[0].y)
                for (i in 1 until pts.size - 1) {
                    quadraticBezierTo(
                        pts[i].x, pts[i].y,
                        (pts[i].x + pts[i + 1].x) / 2f,
                        (pts[i].y + pts[i + 1].y) / 2f,
                    )
                }
                lineTo(pts.last().x, pts.last().y)
            }
            clipRect(right = size.width * p) {
                drawPath(path, color = s.color, style = Stroke(width = 5f, cap = StrokeCap.Round))
                val lastIdx = s.values.indexOfLast { it > 0f }
                if (lastIdx >= 0) {
                    drawCircle(color = s.color, radius = 7f, center = pts[lastIdx])
                    drawCircle(color = Color.White, radius = 2.8f, center = pts[lastIdx])
                }
            }
        }
    }
}

data class BubblePoint(
    /** 横轴值（笔数） */
    val x: Float,
    /** 纵轴值（单笔均价） */
    val y: Float,
    /** 0..1，气泡大小（金额占比） */
    val sizeFactor: Float,
    val color: Color,
)

/**
 * 消费象限散点图（致敬 AA 的象限标注）：虚线十字线为全体均值，
 * 气泡半径随 progress 从 0 生长。
 */
@Composable
fun BubbleChart(
    points: List<BubblePoint>,
    modifier: Modifier = Modifier,
    progress: Float = 1f,
) {
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier = modifier) {
        if (points.isEmpty()) return@Canvas
        val p = progress.coerceIn(0f, 1f)
        val pad = 14.dp.toPx()
        val maxX = points.maxOf { it.x }.coerceAtLeast(1f)
        val maxY = points.maxOf { it.y }.coerceAtLeast(1f)
        val meanX = points.map { it.x }.average().toFloat()
        val meanY = points.map { it.y }.average().toFloat()
        val px = { x: Float -> pad + x / maxX * (size.width - pad * 2) }
        val py = { y: Float -> size.height - pad - y / maxY * (size.height - pad * 2) }
        val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
        val axis = axisColor.copy(alpha = 0.5f)
        drawLine(axis, Offset(px(meanX), pad), Offset(px(meanX), size.height - pad), 1.5f, pathEffect = dash)
        drawLine(axis, Offset(pad, py(meanY)), Offset(size.width - pad, py(meanY)), 1.5f, pathEffect = dash)
        points.forEach { b ->
            val r = (8.dp.toPx() + b.sizeFactor.coerceIn(0f, 1f) * 16.dp.toPx()) * p
            val c = Offset(px(b.x), py(b.y))
            drawCircle(color = b.color.copy(alpha = 0.30f), radius = r, center = c)
            drawCircle(color = b.color, radius = r, center = c, style = Stroke(width = 4f))
        }
    }
}

/**
 * 月度日历热力图（GitHub 提交热力风格）：颜色深浅 = 当日金额占当月峰值的比例，
 * 周一为首列；progress 驱动所有格子同时加深。
 *
 * 颜色仍然只由「当前类型」的 [values] 决定；格子里另外用两行小字显示当日的
 * 支出 / 收入具体金额（[expenseValues] / [incomeValues]），互不干扰。
 */
@Composable
fun CalendarHeatmap(
    month: YearMonth,
    values: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
    progress: Float = 1f,
    expenseValues: List<Float> = emptyList(),
    incomeValues: List<Float> = emptyList(),
) {
    val offset = month.atDay(1).dayOfWeek.value - 1 // 周一=0
    val maxVal = (values.maxOrNull() ?: 0f).coerceAtLeast(1f)
    val p = progress.coerceIn(0f, 1f)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(Modifier.fillMaxWidth()) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach { d ->
                Text(
                    d,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(1.dp))
        val cells: List<Int?> = List(offset) { null } + (1..month.lengthOfMonth()).toList()
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(7) { col ->
                    val day = week.getOrNull(col)
                    Box(Modifier.weight(1f)) {
                        if (day != null) {
                            val v = values.getOrElse(day - 1) { 0f }
                            val dayExpense = expenseValues.getOrElse(day - 1) { 0f }
                            val dayIncome = incomeValues.getOrElse(day - 1) { 0f }
                            val intensity = (v / maxVal).coerceIn(0f, 1f) * p
                            val bg = if (v > 0f) {
                                color.copy(alpha = 0.14f + 0.86f * intensity)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                            }
                            val onDark = intensity > 0.62f
                            val numColor = if (onDark) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(0.78f)
                                    .background(bg, RoundedCornerShape(8.dp))
                                    .padding(vertical = 3.dp, horizontal = 2.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    Text("$day", fontSize = 9.sp, color = numColor)
                                    if (dayExpense > 0f || dayIncome > 0f) {
                                        if (dayExpense > 0f) {
                                            Text(
                                                "-${compactAmount(dayExpense)}",
                                                fontSize = 8.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = if (onDark) {
                                                    Color.White.copy(alpha = 0.92f)
                                                } else {
                                                    Color(0xFFB3261E)
                                                },
                                                maxLines = 1,
                                            )
                                        }
                                        if (dayIncome > 0f) {
                                            Text(
                                                "+${compactAmount(dayIncome)}",
                                                fontSize = 8.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = if (onDark) {
                                                    Color.White.copy(alpha = 0.92f)
                                                } else {
                                                    Color(0xFF1B6B3A)
                                                },
                                                maxLines = 1,
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            Spacer(Modifier.fillMaxWidth().aspectRatio(0.78f))
                        }
                    }
                }
            }
        }
    }
}

/** 格子里的紧凑金额（分 → 元）：12.5 / 268 / 1.2万 */
private fun compactAmount(cents: Float): String {
    val yuan = cents / 100.0
    return when {
        yuan >= 100_000 -> String.format("%.0f万", yuan / 10_000)
        yuan >= 10_000 -> String.format("%.1f万", yuan / 10_000)
        yuan >= 100 -> String.format("%.0f", yuan)
        yuan >= 10 -> String.format("%.1f", yuan).removeSuffix(".0")
        else -> String.format("%.1f", yuan).removeSuffix(".0")
    }
}

/**
 * 双序列分组柱状图：同一个横轴位置并排画「支出 / 收入」两根柱，
 * 让两个方向的金额在同一张图里直接可比（统计页月视图/年视图共用）。
 */
@Composable
fun DualBarChart(
    primary: List<Float>,
    secondary: List<Float>,
    primaryColor: Color,
    secondaryColor: Color,
    modifier: Modifier = Modifier,
    progress: Float = 1f,
    labels: List<String> = emptyList(),
) {
    val maxVal = maxOf(
        primary.maxOrNull() ?: 0f,
        secondary.maxOrNull() ?: 0f,
        1f,
    )
    val p = progress.coerceIn(0f, 1f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier = modifier) {
        val count = maxOf(primary.size, secondary.size)
        if (count == 0) return@Canvas
        val baseline = size.height - 14.dp.toPx()
        val slot = size.width / count
        val barWidth = (slot * 0.30f).coerceAtMost(10.dp.toPx())
        val gap = slot * 0.06f

        drawLine(
            color = labelColor.copy(alpha = 0.25f),
            start = Offset(0f, baseline),
            end = Offset(size.width, baseline),
            strokeWidth = 1f,
        )

        for (i in 0 until count) {
            val a = primary.getOrElse(i) { 0f }
            val b = secondary.getOrElse(i) { 0f }
            val cx = slot * i + slot / 2f
            val hA = (a / maxVal) * (baseline - 6.dp.toPx()) * p
            val hB = (b / maxVal) * (baseline - 6.dp.toPx()) * p
            if (hA > 0f) {
                drawRoundRect(
                    color = primaryColor,
                    topLeft = Offset(cx - barWidth - gap / 2f, baseline - hA),
                    size = Size(barWidth, hA),
                    cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
                )
            }
            if (hB > 0f) {
                drawRoundRect(
                    color = secondaryColor,
                    topLeft = Offset(cx + gap / 2f, baseline - hB),
                    size = Size(barWidth, hB),
                    cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
                )
            }
        }
    }
}
