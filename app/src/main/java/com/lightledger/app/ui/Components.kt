package com.lightledger.app.ui

import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lightledger.app.ui.theme.colorForId
import com.lightledger.app.util.monthTitle
import java.time.YearMonth

/*
 * Material 3 交互原则落地：
 * - 反馈在按下瞬间：Pressable 无水波纹，按下即有 state-layer 高亮 / 微缩放
 * - 组件语义优先：分段互斥选择用 M3 SegmentedButton，不用自制控件重造轮子
 * - 无障碍：可点目标 ≥48dp、图标按钮带语义名、状态不只靠颜色表达
 * - 尊重系统「移除动画」设置（reduced motion）：动效只作强化，不作唯一说明
 */

/** 触觉反馈：只用于有因果关系的时刻（按键、切换、保存成功） */
object Haptics {
    fun tap(view: View) = view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

    fun tick(view: View) = view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)

    fun confirm(view: View) {
        if (Build.VERSION.SDK_INT >= 30) {
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        } else {
            tap(view)
        }
    }
}

/** 系统「移除动画/动画时长 0」时返回 true：装饰性动效应退化为瞬时状态切换 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }
}

/** M3 state-layer：列表行/次要可点元素的按下高亮（onSurface 8%） */
@Composable
fun stateLayerColor(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)

/**
 * 可按压容器：按下的瞬间给出反馈（state-layer 高亮 / 轻微缩放），不使用水波纹。
 * pressedBackground 为按下高亮色；pressedScale 为按下缩放（1.0 以下）；
 * role 用于语义（键盘键位传 Role.Button 等）。
 */
@Composable
fun Pressable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    background: Color = Color.Unspecified,
    pressedBackground: Color? = null,
    pressedScale: Float? = null,
    shape: Shape = RectangleShape,
    enabled: Boolean = true,
    role: Role? = null,
    content: @Composable () -> Unit,
) {
    val reducedMotion = rememberReducedMotion()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val base = if (background == Color.Unspecified) Color.Transparent else background
    val bgColor by animateColorAsState(
        targetValue = if (pressed && pressedBackground != null) pressedBackground else base,
        animationSpec = tween(if (reducedMotion) 0 else 90),
        label = "pressBg",
    )
    val scale by animateFloatAsState(
        targetValue = if (pressed && pressedScale != null && !reducedMotion) pressedScale else 1f,
        animationSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMedium),
        label = "pressScale",
    )
    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(bgColor)
            .semantics { if (role != null) this.role = role }
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
    ) {
        content()
    }
}

/**
 * 分组卡片：全站统一的液态玻璃容器（折射 + 高斯模糊 + 发丝描边 + 顶部高光）。
 * 没有玻璃宿主时自动退化为半透明卡片。
 */
@Composable
fun GroupCard(
    modifier: Modifier = Modifier,
    blurRadius: Dp = 20.dp,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    com.lightledger.app.ui.glass.GlassCard(
        modifier = modifier,
        blurRadius = blurRadius,
        content = content,
    )
}

/** M3 分隔线：1dp outlineVariant，默认按内容缩进 */
@Composable
fun InsetDivider(inset: Dp = 16.dp, modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(start = inset),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/** 分类图标：低透明度色块 + emoji */
@Composable
fun CategoryIcon(
    icon: String,
    color: Color,
    size: Dp = 44.dp,
    modifier: Modifier = Modifier,
    tintAlpha: Float = 0.13f,
) {
    Box(
        modifier = modifier
            .size(size)
            .background(color.copy(alpha = tintAlpha), RoundedCornerShape(size * 0.3f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = icon, fontSize = (size.value * 0.52f).sp)
    }
}

@Composable
fun EmptyHint(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("📒", fontSize = 40.sp)
        Spacer(Modifier.height(10.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp,
            )
        }
    }
}

/** 子页面通用顶栏（M3 top app bar 语义：surface 底、居中标题、返回按钮带语义名） */
@Composable
fun TopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(56.dp),
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center),
        )
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically,
            content = actions,
        )
    }
}

/** 月份切换条：居中标题 + 两侧箭头（禁用态用 outline 表达，不只靠颜色） */
@Composable
fun MonthBar(
    month: YearMonth,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canNext = month < YearMonth.now()
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrev) {
            Icon(
                Icons.Filled.ChevronLeft,
                contentDescription = "上一月",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = month.monthTitle(),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onNext, enabled = canNext) {
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = if (canNext) "下一月" else "已是最新月份",
                tint = if (canNext) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/**
 * 互斥分段选择（M3 SegmentedButton）：同一组互斥选项的既定控件，
 * 自带选中容器色、边框与无障碍语义；切换伴随轻触觉。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = index == selectedIndex,
                onClick = {
                    Haptics.tick(view)
                    onSelect(index)
                },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                label = {
                    Text(
                        option,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (index == selectedIndex) FontWeight.SemiBold else FontWeight.Normal,
                    )
                },
            )
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String = "删除",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(confirmText, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

data class PickerOption(val id: Long, val label: String, val icon: String? = null)

/** 单选弹窗：选账户 / 选分类 */
@Composable
fun PickerDialog(
    title: String,
    options: List<PickerOption>,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(modifier = Modifier.height(320.dp)) {
                items(options, key = { it.id }, contentType = { "picker" }) { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(option.id); onDismiss() }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (option.icon != null) {
                            CategoryIcon(
                                icon = option.icon,
                                color = colorForId(option.id),
                                size = 34.dp,
                            )
                            Spacer(Modifier.width(12.dp))
                        }
                        Text(
                            text = option.label,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (option.id == selectedId) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (option.id == selectedId) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 表单输入框：卡片内填充风格（无边框、聚焦时主色描边） */
@Composable
fun iosTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = Color.Transparent,
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
)

/** 行内无边框输入（备注等） */
@Composable
fun inlineTextFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
)

/**
 * 数字键盘（鲨鱼记账式布局）：surfaceContainerLowest 底 + surfaceContainerHighest 键，
 * 「保存」为 primary 实心键，全程触觉反馈。onKey 取值："0".."9"、"."、"del"、"clear"、"today"
 */
@Composable
fun MoneyKeyboard(
    onKey: (String) -> Unit,
    onSave: () -> Unit,
    saveEnabled: Boolean,
    onSaveAgain: (() -> Unit)? = null,
    transparent: Boolean = false,
) {
    val view = LocalView.current
    val keys: @Composable () -> Unit = {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            KeyboardRow {
                DigitKey("7", view, onKey)
                DigitKey("8", view, onKey)
                DigitKey("9", view, onKey)
                IconKey(Icons.Filled.Backspace, "del", view, onKey)
            }
            KeyboardRow {
                DigitKey("4", view, onKey)
                DigitKey("5", view, onKey)
                DigitKey("6", view, onKey)
                GhostKey("清空") { onKey("clear") }
            }
            KeyboardRow {
                DigitKey("1", view, onKey)
                DigitKey("2", view, onKey)
                DigitKey("3", view, onKey)
                // 「再记」与小数点交换位置：连续记账放在拇指更容易够到的右侧
                if (onSaveAgain != null) {
                    GhostKey("再记", filled = true) { onSaveAgain() }
                } else {
                    GhostKey("今天") { onKey("today") }
                }
            }
            KeyboardRow {
                DigitKey(".", view, onKey)
                DigitKey("0", view, onKey)
                PrimaryKey(
                    "保存",
                    enabled = saveEnabled,
                    weight = 2f,
                ) {
                    Haptics.confirm(view)
                    onSave()
                }
            }
        }
    }
    if (transparent) {
        // 透明模式：容器由调用方的液态玻璃面板提供
        keys()
    } else {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest) {
            keys()
        }
    }
}

@Composable
private fun KeyboardRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
private fun RowScope.DigitKey(label: String, view: View, onKey: (String) -> Unit) {
    Pressable(
        onClick = { Haptics.tap(view); onKey(label) },
        modifier = Modifier.weight(1f),
        background = MaterialTheme.colorScheme.surfaceContainerHighest,
        pressedBackground = MaterialTheme.colorScheme.outlineVariant,
        pressedScale = 0.94f,
        shape = MaterialTheme.shapes.medium,
        role = Role.Button,
    ) {
        Box(Modifier.fillMaxWidth().height(52.dp), contentAlignment = Alignment.Center) {
            Text(label, fontSize = 21.sp, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun RowScope.GhostKey(label: String, filled: Boolean = false, onClick: () -> Unit) {
    val view = LocalView.current
    Pressable(
        onClick = { Haptics.tap(view); onClick() },
        modifier = Modifier.weight(1f),
        background = if (filled) MaterialTheme.colorScheme.secondaryContainer else Color.Unspecified,
        pressedBackground = MaterialTheme.colorScheme.outlineVariant,
        pressedScale = 0.94f,
        shape = MaterialTheme.shapes.medium,
        role = Role.Button,
    ) {
        Box(Modifier.fillMaxWidth().height(52.dp), contentAlignment = Alignment.Center) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (filled) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RowScope.IconKey(
    icon: ImageVector,
    key: String,
    view: View,
    onKey: (String) -> Unit,
) {
    Pressable(
        onClick = { Haptics.tap(view); onKey(key) },
        modifier = Modifier.weight(1f),
        background = MaterialTheme.colorScheme.surfaceContainerHighest,
        pressedBackground = MaterialTheme.colorScheme.outlineVariant,
        pressedScale = 0.94f,
        shape = MaterialTheme.shapes.medium,
        role = Role.Button,
    ) {
        Box(Modifier.fillMaxWidth().height(52.dp), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = "删除一位", tint = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun RowScope.PrimaryKey(
    label: String,
    enabled: Boolean,
    weight: Float = 1f,
    onClick: () -> Unit,
) {
    Pressable(
        onClick = onClick,
        modifier = Modifier.weight(weight),
        background = if (enabled) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
        pressedBackground = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
        pressedScale = 0.97f,
        shape = MaterialTheme.shapes.medium,
        role = Role.Button,
        enabled = enabled,
    ) {
        Box(Modifier.fillMaxWidth().height(52.dp), contentAlignment = Alignment.Center) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            )
        }
    }
}

/** 圆形图标按钮（月份切换等） */
@Composable
fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Pressable(
        onClick = onClick,
        modifier = modifier.size(44.dp),
        background = MaterialTheme.colorScheme.surfaceContainerHigh,
        pressedBackground = MaterialTheme.colorScheme.outlineVariant,
        pressedScale = 0.9f,
        shape = CircleShape,
        enabled = enabled,
        role = Role.Button,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = contentDescription,
                tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
