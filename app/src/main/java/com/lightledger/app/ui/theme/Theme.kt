package com.lightledger.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Material 3 语义色彩系统（绿色种子）：
 * - 屏幕/组件一律引用 MaterialTheme.colorScheme 语义角色，不写裸色值
 * - 记账域语义（收入=primary 绿 / 支出=error 红）作为 M3 角色之上的领域层，
 *   由 AppColors 统一出口；分类取色盘属于数据可视化 token，独立维护
 * - 品牌绿为固定色板（未启用动态取色）：记账应用的收支语义色不能随壁纸漂移
 */

private val LightColors = lightColorScheme(
    primary = Color(0xFF006E24),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF9CF6A7),
    onPrimaryContainer = Color(0xFF002109),
    secondary = Color(0xFF52634F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD5E8CC),
    onSecondaryContainer = Color(0xFF101F10),
    tertiary = Color(0xFF38656A),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFBCEBF1),
    onTertiaryContainer = Color(0xFF002023),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF7FBF2),
    onBackground = Color(0xFF191D17),
    surface = Color(0xFFF7FBF2),
    onSurface = Color(0xFF191D17),
    surfaceVariant = Color(0xFFDEE5DA),
    onSurfaceVariant = Color(0xFF414941),
    outline = Color(0xFF717970),
    outlineVariant = Color(0xFFC1C9BE),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF2E312C),
    inverseOnSurface = Color(0xFFEFF2EA),
    inversePrimary = Color(0xFF86D987),
    surfaceDim = Color(0xFFD8DBD3),
    surfaceBright = Color(0xFFF7FBF2),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F5EC),
    surfaceContainer = Color(0xFFEBF0E6),
    surfaceContainerHigh = Color(0xFFE5EAE1),
    surfaceContainerHighest = Color(0xFFDFE4DB),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF86D987),
    onPrimary = Color(0xFF003911),
    primaryContainer = Color(0xFF00531C),
    onPrimaryContainer = Color(0xFF9CF6A7),
    secondary = Color(0xFFB9CCB2),
    onSecondary = Color(0xFF243423),
    secondaryContainer = Color(0xFF3A4B38),
    onSecondaryContainer = Color(0xFFD5E8CC),
    tertiary = Color(0xFFA0CFD5),
    onTertiary = Color(0xFF00363C),
    tertiaryContainer = Color(0xFF1F4D53),
    onTertiaryContainer = Color(0xFFBCEBF1),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF11140F),
    onBackground = Color(0xFFE1E3DC),
    surface = Color(0xFF11140F),
    onSurface = Color(0xFFE1E3DC),
    surfaceVariant = Color(0xFF414941),
    onSurfaceVariant = Color(0xFFC1C9BE),
    outline = Color(0xFF8B9389),
    outlineVariant = Color(0xFF414941),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFE1E3DC),
    inverseOnSurface = Color(0xFF2E312C),
    inversePrimary = Color(0xFF006E24),
    surfaceDim = Color(0xFF11140F),
    surfaceBright = Color(0xFF373A34),
    surfaceContainerLowest = Color(0xFF0C0F0A),
    surfaceContainerLow = Color(0xFF191D18),
    surfaceContainer = Color(0xFF1D211B),
    surfaceContainerHigh = Color(0xFF272B25),
    surfaceContainerHighest = Color(0xFF323630),
)

/** 分类/图表取色盘：数据可视化 token，低饱和、彼此可分辨 */
val CategoryPalette = listOf(
    Color(0xFF34C759), Color(0xFF007AFF), Color(0xFFFF9500), Color(0xFFFF2D55),
    Color(0xFFAF52DE), Color(0xFF5AC8FA), Color(0xFFFF3B30), Color(0xFF4CD964),
    Color(0xFF5856D6), Color(0xFFFFCC00), Color(0xFF32ADE6), Color(0xFFFF6482),
)

fun colorForId(id: Long?): Color =
    CategoryPalette[(id?.toInt() ?: 0).mod(CategoryPalette.size)]

/**
 * 记账域语义色：收入 = primary（绿），支出 = error（红）。
 * 唯一允许在屏幕层直接使用的非 M3 容器语义——金额的正负是领域事实，
 * 不是装饰性强调，且同时以 +/- 前缀表达（不单靠颜色传达）。
 */
@Stable
object AppColors {
    @Composable
    fun expense(): Color = MaterialTheme.colorScheme.error

    @Composable
    fun income(): Color = MaterialTheme.colorScheme.primary
}

/**
 * M3 字阶 + 记账表达时刻：
 * displayLarge = 记一笔大金额，displaySmall = 总资产/月度合计（全 App 唯二的强调字级），
 * 其余回归 M3 语义层级（Title/Body/Label），屏幕层不写散装字号。
 */
private val AppTypography = Typography(
    displayLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 40.sp,
        letterSpacing = (-1).sp,
    ),
    displaySmall = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        letterSpacing = (-0.3).sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        letterSpacing = (-0.3).sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        letterSpacing = (-0.1).sp,
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontSize = 16.sp,
        letterSpacing = 0.3.sp,
    ),
    bodyMedium = TextStyle(fontSize = 15.sp),
    bodySmall = TextStyle(fontSize = 13.sp),
    labelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.1.sp,
    ),
)

/** M3 形状阶梯：extraSmall 4 / small 8 / medium 12 / large 16 / extraLarge 28（大按钮取近胶囊） */
private val AppShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
)

@Composable
fun LightLedgerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
