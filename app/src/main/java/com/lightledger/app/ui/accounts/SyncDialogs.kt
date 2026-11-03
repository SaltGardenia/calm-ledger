package com.lightledger.app.ui.accounts

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lightledger.app.data.Account
import com.lightledger.app.data.AccountGroup
import com.lightledger.app.data.displayName
import com.lightledger.app.sync.BillSyncService
import com.lightledger.app.sync.SyncBus
import com.lightledger.app.sync.SyncPhase
import com.lightledger.app.sync.SyncRequest
import com.lightledger.app.sync.SyncState
import com.lightledger.app.sync.WechatConst
import com.lightledger.app.sync.WechatFlow
import java.time.YearMonth

/** 目前只有「微信 · 零钱」能全自动同步；零钱通/支付宝/银行走截图导入 */
fun canAutoSync(group: AccountGroup, name: String): Boolean =
    group == AccountGroup.WECHAT && (name.contains("零钱") || name.contains("零钱通"))

fun canAutoSync(account: Account): Boolean = canAutoSync(account.group, account.name)

/** 按账户名选择微信里的流水入口 */
fun wechatFlowFor(account: Account): WechatFlow =
    if (account.name.contains("零钱通")) WechatFlow.LINGQIANTONG else WechatFlow.CHANGE

/** 直接跳到本服务的无障碍详情页（MIUI 上也比在列表里翻找快）；失败则退回总列表 */
fun openAccessibilitySettings(context: Context) {
    val component = ComponentName(context, BillSyncService::class.java)
    val detail = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
        .putExtra(Intent.EXTRA_COMPONENT_NAME, component)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(detail) }.onFailure {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}


/**
 * 无障碍服务是否已开启。
 * 不用 Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES：MIUI 对第三方读取该值做了限制，
 * 会误判为未开启。改用 AccessibilityManager 的已启用服务列表。
 */
fun isSyncServiceEnabled(context: Context): Boolean {
    // 同进程内服务已连接 = 一定可用（最可靠，且不受 OEM 查询限制影响）
    if (BillSyncService.connected) return true

    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ).orEmpty()
    val expected = ComponentName(context, BillSyncService::class.java)
    if (enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }) return true

    val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
    if (!manager.isEnabled) return false
    return manager.getEnabledAccessibilityServiceList(
        AccessibilityServiceInfo.FEEDBACK_ALL_MASK,
    ).any { info ->
        val si = info.resolveInfo?.serviceInfo ?: return@any false
        si.packageName == context.packageName && si.name == BillSyncService::class.java.name
    }
}

@Composable
fun SyncMonthDialog(
    account: Account,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    // 从系统设置返回后服务可能刚连上，持续刷新，避免误报「未开启」
    var enabled by remember { mutableStateOf(isSyncServiceEnabled(context)) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            enabled = isSyncServiceEnabled(context)
            kotlinx.coroutines.delay(800)
        }
    }
    val months = remember { List(12) { YearMonth.now().minusMonths(it.toLong()) } }
    var selected by remember { mutableStateOf(setOf(months.first())) }
    var forceFull by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("同步「${account.displayName}」") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "选择要拉取的月份，可多选。同步会全自动完成：打开微信 → 零钱明细 → 逐屏识别 → 按同步规则归类，未命中的留作未分类。",
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (!enabled) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f))
                            .padding(12.dp),
                    ) {
                        Column {
                            Text(
                                "需要先开启「账单同步」无障碍服务",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "只用于读取微信账单页面，不会操作其他应用；随时可关闭。",
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            TextButton(
                                onClick = { openAccessibilitySettings(context) },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                            ) { Text("去系统设置开启") }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickPick("最近1个月", selected.size == 1 && selected.first() == months[0]) {
                        selected = setOf(months[0])
                    }
                    QuickPick("最近3个月", selected == months.take(3).toSet()) {
                        selected = months.take(3).toSet()
                    }
                    QuickPick("最近6个月", selected == months.take(6).toSet()) {
                        selected = months.take(6).toSet()
                    }
                }

                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { forceFull = !forceFull }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(18.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(
                                if (forceFull) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (forceFull) Text("✓", fontSize = 11.sp, color = MaterialTheme.colorScheme.onPrimary)
                    }
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("从头完整扫描", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text(
                            "忽略上次同步位置，逐笔核对余额。较慢，用于校准",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                LazyColumn(Modifier.height(240.dp)) {
                    items(months.size) { index ->
                        val month = months[index]
                        val checked = month in selected
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selected = if (checked) selected - month else selected + month
                                }
                                .padding(vertical = 9.dp, horizontal = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (checked) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (checked) {
                                    Text("✓", fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimary)
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "${month.year}年${month.monthValue}月",
                                fontSize = 14.sp,
                                fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected.isNotEmpty() && enabled,
                onClick = {
                    val monthsToSync = selected.sorted()
                    val launch = context.packageManager
                        .getLaunchIntentForPackage(WechatConst.PACKAGE)
                        ?: Intent().setComponent(
                            ComponentName(WechatConst.PACKAGE, WechatConst.LAUNCHER),
                        )
                    runCatching {
                        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    SyncBus.request(
                        SyncRequest(
                            accountId = account.id,
                            accountName = account.displayName,
                            months = monthsToSync,
                            flow = wechatFlowFor(account),
                            forceFull = forceFull,
                            anchorBalance = account.lastSyncedBalance,
                            anchorDay = account.lastSyncedDay,
                        ),
                    )
                    onDismiss()
                },
            ) {
                Text(if (enabled) "开始同步" else "请先开启无障碍")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun QuickPick(text: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .clip(MaterialTheme.shapes.extraLarge)
            .background(
                if (selected) scheme.primary.copy(alpha = 0.16f)
                else scheme.surfaceContainerHighest.copy(alpha = 0.6f),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp),
    ) {
        Text(
            text,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) scheme.primary else scheme.onSurface,
        )
    }
}

@Composable
fun SyncProgressDialog(
    state: SyncState,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!state.running) onClose() },
        title = {
            Text(
                when (state.phase) {
                    SyncPhase.DONE -> "同步完成"
                    SyncPhase.ERROR -> "同步失败"
                    SyncPhase.CANCELLED -> "已停止"
                    else -> "正在同步"
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.running) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(state.message, fontSize = 14.sp)
                    }
                    Text(
                        "第 ${state.pages} 屏 · 已识别 ${state.found} 笔",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "同步期间请勿操作手机，结束后会自动回到本应用。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(state.error ?: state.message, fontSize = 14.sp, lineHeight = 21.sp)
                    if (state.phase == SyncPhase.DONE) {
                        Text(
                            "新增 ${state.added} 笔${if (state.duplicates > 0) "，${state.duplicates} 笔已存在" else ""}。" +
                                "去「明细 → 多选 → 未分类」统一归类即可。",
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (state.running) {
                TextButton(onClick = { SyncBus.cancel() }) { Text("停止") }
            } else {
                TextButton(onClick = onClose) { Text("完成") }
            }
        },
    )
}
