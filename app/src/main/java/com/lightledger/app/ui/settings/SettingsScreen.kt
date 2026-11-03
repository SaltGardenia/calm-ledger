package com.lightledger.app.ui.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lightledger.app.ServiceLocator
import com.lightledger.app.data.BackupCodec
import com.lightledger.app.data.BackupPayload
import com.lightledger.app.data.BillType
import com.lightledger.app.data.Category
import com.lightledger.app.data.Repository
import com.lightledger.app.ui.AppSegmented
import com.lightledger.app.ui.accounts.isSyncServiceEnabled
import com.lightledger.app.ui.accounts.openAccessibilitySettings
import com.lightledger.app.ui.CategoryIcon
import com.lightledger.app.ui.ConfirmDialog
import com.lightledger.app.ui.GroupCard
import com.lightledger.app.ui.InsetDivider
import com.lightledger.app.ui.Pressable
import com.lightledger.app.ui.TopBar
import com.lightledger.app.ui.iosTextFieldColors
import com.lightledger.app.ui.stateLayerColor
import com.lightledger.app.ui.theme.colorForId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

// ================= 我的 / 设置 =================

private const val DEV_QQ = "3910046114"

@Composable
fun SettingsScreen(
    onOpenBudget: () -> Unit,
    onOpenCategories: () -> Unit,
    onOpenSyncRules: () -> Unit,
    onOpenAbout: () -> Unit,
    extraBottomPadding: Dp = 0.dp,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current

    // 导入前先解析出内容做确认，避免直接覆盖
    var pendingImport by remember { mutableStateOf<Pair<String, BackupPayload>?>(null) }

    val exportCsv = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    val csv = ServiceLocator.repository.buildCsvExport()
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(csv.toByteArray(Charsets.UTF_8))
                    } ?: error("无法写入文件")
                }.onSuccess { snackbar.showSnackbar("CSV 导出成功") }
                    .onFailure { snackbar.showSnackbar("导出失败：${it.message}") }
            }
        }
    }

    val exportBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    val json = ServiceLocator.repository.buildBackupJson()
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(json.toByteArray(Charsets.UTF_8))
                    } ?: error("无法写入文件")
                }.onSuccess { snackbar.showSnackbar("备份已导出，把它拷到新手机即可恢复") }
                    .onFailure { snackbar.showSnackbar("导出失败：${it.message}") }
            }
        }
    }

    val importBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    val text = context.contentResolver.openInputStream(uri)
                        ?.bufferedReader(Charsets.UTF_8)
                        ?.use { it.readText() }
                        ?: error("无法读取文件")
                    text to BackupCodec.decode(text)
                }.onSuccess { pendingImport = it }
                    .onFailure { snackbar.showSnackbar("导入失败：${it.message}") }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Column(Modifier.statusBarsPadding().padding(20.dp)) {
                // 应用图标感：primaryContainer 色块上的 emoji（M3 容器语义）
                Box(
                    Modifier
                        .size(56.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.medium),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("📒", fontSize = 30.sp)
                }
                Spacer(Modifier.height(12.dp))
                Text("不烧心记账 v1.0", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(3.dp))
                Text(
                    "本地开源记账 · 不联网 · 数据只保存在你的手机",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))

            GroupCard(Modifier.padding(horizontal = 16.dp)) {
                SettingsItem("月度预算", "设置每月支出上限") { onOpenBudget() }
                InsetDivider()
                SettingsItem("分类管理", "自定义支出/收入分类") { onOpenCategories() }
                InsetDivider()
                SettingsItem("同步规则", "按商户名自动归类，减少未分类") { onOpenSyncRules() }
            }
            Spacer(Modifier.height(10.dp))

            // 同步服务状态：开启一次即可长期有效，这里给出即时状态和直达入口
            var syncOn by remember { mutableStateOf(isSyncServiceEnabled(context)) }
            androidx.compose.runtime.LaunchedEffect(Unit) {
                while (true) {
                    syncOn = isSyncServiceEnabled(context)
                    kotlinx.coroutines.delay(1_000)
                }
            }
            GroupCard(Modifier.padding(horizontal = 16.dp)) {
                SettingsItem(
                    title = "账单同步服务",
                    subtitle = if (syncOn) "已开启 · 长期有效，重启手机也不会关" else "未开启 · 点击去系统设置开启",
                    onClick = { openAccessibilitySettings(context) },
                )
                InsetDivider()
                SettingsItem(
                    title = "同步后自动返回",
                    subtitle = "需要系统「后台弹出界面」权限，点此开启",
                    onClick = { openBackgroundPopupPermission(context) },
                )
            }
            Spacer(Modifier.height(10.dp))

            // 数据迁移：应用不联网，换机靠这份文件手动搬
            GroupCard(Modifier.padding(horizontal = 16.dp)) {
                SettingsItem("导出备份", "账户 / 分类 / 账单 / 预算，换机迁移用") {
                    exportBackup.launch("不烧心记账备份_${LocalDate.now()}.json")
                }
                InsetDivider()
                SettingsItem("导入备份", "从备份文件恢复，会覆盖当前全部数据") {
                    importBackup.launch(arrayOf("*/*"))
                }
                InsetDivider()
                SettingsItem("导出账单 CSV", "只导出流水，用 Excel 查看") {
                    exportCsv.launch("不烧心记账账单_${LocalDate.now()}.csv")
                }
            }
            Spacer(Modifier.height(10.dp))

            GroupCard(Modifier.padding(horizontal = 16.dp)) {
                SettingsItem(
                    title = "联系开发者",
                    subtitle = "QQ $DEV_QQ · 点击复制",
                    onClick = {
                        clipboard.setText(AnnotatedString(DEV_QQ))
                        scope.launch { snackbar.showSnackbar("QQ 号已复制：$DEV_QQ") }
                    },
                )
                InsetDivider()
                SettingsItem("关于不烧心记账", "功能说明与开源致谢") { onOpenAbout() }
            }
            Spacer(Modifier.height(24.dp + extraBottomPadding))
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp + extraBottomPadding),
        )
    }

    pendingImport?.let { (text, payload) ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("导入备份？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "将从备份恢复 ${payload.accounts.size} 个账户、${payload.categories.size} 个分类、" +
                            "${payload.bills.size} 笔账单、${payload.budgets.size} 个月预算。",
                        fontSize = 14.sp,
                        lineHeight = 21.sp,
                    )
                    Text(
                        "当前手机上的数据会被全部覆盖，且无法撤销。",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingImport = null
                        scope.launch {
                            runCatching { ServiceLocator.repository.restoreBackupJson(text) }
                                .onSuccess {
                                    snackbar.showSnackbar("已导入 ${it.bills.size} 笔账单")
                                }
                                .onFailure { snackbar.showSnackbar("导入失败：${it.message}") }
                        }
                    },
                ) { Text("覆盖导入", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingImport = null }) { Text("取消") }
            },
        )
    }
}

/** MIUI：跳到本应用的权限编辑页（后台弹出界面 / 锁屏显示等）；非 MIUI 退回应用详情 */
private fun openBackgroundPopupPermission(context: Context) {
    val miui = Intent("miui.intent.action.APP_PERM_EDITOR")
        .setClassName(
            "com.miui.securitycenter",
            "com.miui.permcenter.permissions.PermissionsEditorActivity",
        )
        .putExtra("extra_pkgname", context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(miui) }.onFailure {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.parse("package:" + context.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

@Composable
private fun SettingsItem(title: String, subtitle: String, onClick: () -> Unit) {
    Pressable(
        onClick = onClick,
        pressedBackground = stateLayerColor(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

// ================= 分类管理 =================

data class CategoryManageUiState(
    val tab: BillType = BillType.EXPENSE,
    val all: List<Category> = emptyList(),
) {
    val visible: List<Category> get() = all.filter { it.type == tab }
}

class CategoryManageViewModel(private val repo: Repository) : ViewModel() {

    private val _state = MutableStateFlow(CategoryManageUiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repo.allCategories().collect { list ->
                _state.update { it.copy(all = list.sortedBy { c -> c.sortOrder * 1000 + c.id }) }
            }
        }
    }

    fun setTab(tab: BillType) = _state.update { it.copy(tab = tab) }

    fun saveCategory(editingId: Long?, name: String, icon: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val type = _state.value.tab
            val original = editingId?.let { id -> _state.value.all.firstOrNull { it.id == id } }
            repo.saveCategory(
                (original ?: Category(name = "", icon = icon, type = type)).copy(
                    name = name.trim(),
                    icon = icon,
                ),
            )
        }
    }

    /** 返回 false 表示分类下仍有账单，不能删除 */
    suspend fun delete(category: Category): Boolean = repo.deleteCategory(category)
}

private val CATEGORY_EMOJIS = listOf(
    "🍜", "🛍️", "🚌", "🎮", "🏠", "💊", "📱", "👔", "📚", "⚽", "🐱", "✈️",
    "🎁", "🧾", "💰", "🎉", "📈", "💼", "🧧", "↩️", "🪙", "☕", "🚕", "🎬",
    "💡", "🍼", "🎵", "🀄",
)

@Composable
fun CategoryManageScreen(
    onBack: () -> Unit,
    vm: CategoryManageViewModel = viewModel { CategoryManageViewModel(ServiceLocator.repository) },
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // null 关闭；-1 表示新增；其他为编辑的分类 id
    var editingId by remember { mutableStateOf<Long?>(null) }
    var deleteTarget by remember { mutableStateOf<Category?>(null) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar("分类管理", onBack = onBack)
            AppSegmented(
                options = listOf("支出分类", "收入分类"),
                selectedIndex = if (state.tab == BillType.EXPENSE) 0 else 1,
                onSelect = { index -> vm.setTab(if (index == 0) BillType.EXPENSE else BillType.INCOME) },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(14.dp))
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                item(key = "list", contentType = "categories") {
                    GroupCard {
                        if (state.visible.isEmpty()) {
                            Text(
                                "暂无分类",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                        state.visible.forEachIndexed { index, category ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CategoryIcon(icon = category.icon, color = colorForId(category.id), size = 38.dp)
                                Spacer(Modifier.width(12.dp))
                                Text(category.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                TextButton(onClick = { editingId = category.id }) { Text("编辑") }
                                TextButton(onClick = { deleteTarget = category }) {
                                    Text("删除", color = MaterialTheme.colorScheme.error)
                                }
                            }
                            if (index != state.visible.lastIndex) {
                                InsetDivider(inset = 66.dp)
                            }
                        }
                    }
                }
            }
            Button(
                onClick = { editingId = -1L },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .height(46.dp),
                shape = MaterialTheme.shapes.extraLarge,
            ) {
                Text(
                    "新增${if (state.tab == BillType.EXPENSE) "支出" else "收入"}分类",
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp),
        )
    }

    if (editingId != null) {
        CategoryEditDialog(
            initialName = state.all.firstOrNull { it.id == editingId }?.name ?: "",
            initialIcon = state.all.firstOrNull { it.id == editingId }?.icon ?: "🧾",
            onConfirm = { name, icon ->
                vm.saveCategory(if (editingId == -1L) null else editingId, name, icon)
                editingId = null
            },
            onDismiss = { editingId = null },
        )
    }

    deleteTarget?.let { category ->
        ConfirmDialog(
            title = "删除分类",
            message = "确定删除分类「${category.name}」吗？分类下有账单时将无法删除。",
            onConfirm = {
                scope.launch {
                    if (!vm.delete(category)) {
                        snackbar.showSnackbar("该分类下仍有账单，无法删除", withDismissAction = true)
                    }
                }
            },
            onDismiss = { deleteTarget = null },
        )
    }
}

@Composable
private fun CategoryEditDialog(
    initialName: String,
    initialIcon: String,
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var icon by remember { mutableStateOf(initialIcon) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialName.isEmpty()) "新增分类" else "编辑分类") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(6) },
                    singleLine = true,
                    label = { Text("分类名称") },
                    shape = MaterialTheme.shapes.medium,
                    colors = iosTextFieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                )
                Spacer(Modifier.height(12.dp))
                CATEGORY_EMOJIS.chunked(7).forEach { rowEmojis ->
                    Row {
                        rowEmojis.forEach { emoji ->
                            val selected = emoji == icon
                            // 触达目标 ≥44dp（无障碍最低标准）
                            Text(
                                text = emoji,
                                fontSize = 22.sp,
                                modifier = Modifier
                                    .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
                                    .clickable { icon = emoji }
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primaryContainer
                                        else Color.Transparent,
                                        MaterialTheme.shapes.small,
                                    )
                                    .wrapContentSize(Alignment.Center)
                                    .padding(4.dp),
                            )
                            Spacer(Modifier.width(2.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onConfirm(name, icon) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// ================= 关于 =================

@Composable
fun AboutScreen(onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        TopBar("关于不烧心记账", onBack = onBack)
        GroupCard(Modifier.padding(horizontal = 16.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                Text("不烧心记账 v1.0", style = MaterialTheme.typography.titleMedium)
                Text(
                    "一款本地优先的开源记账应用。功能形态参考随手记、鲨鱼记账、钱迹等主流记账产品：" +
                        "快速记一笔、日维度流水、分类统计图表、多账户与转账、月度预算。",
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text("开源致谢", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "数据模型与架构设计学习并参考了以下开源记账项目的公开源码：\n" +
                        "· TallyApp（Compose + Room 组件化记账）\n" +
                        "· GrainLedger（余粮 · 预算信封记账）\n" +
                        "· Ivy Wallet（开源 Compose 记账）\n" +
                        "· Money Manager Ex Android",
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "本项目为独立实现，未使用上述项目的任何代码，也未反编译任何闭源商业应用。",
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
