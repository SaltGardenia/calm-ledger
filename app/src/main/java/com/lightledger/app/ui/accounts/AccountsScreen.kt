@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.lightledger.app.ui.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.lightledger.app.data.Account
import com.lightledger.app.data.AccountGroup
import com.lightledger.app.data.AccountKind
import com.lightledger.app.data.AccountWithBalance
import com.lightledger.app.data.Repository
import com.lightledger.app.data.displayName
import com.lightledger.app.data.subtitle
import com.lightledger.app.ui.CategoryIcon
import com.lightledger.app.ui.ConfirmDialog
import com.lightledger.app.ui.EmptyHint
import com.lightledger.app.ui.GroupCard
import com.lightledger.app.ui.InsetDivider
import com.lightledger.app.ui.Pressable
import com.lightledger.app.ui.TopBar
import com.lightledger.app.ui.iosTextFieldColors
import com.lightledger.app.ui.stateLayerColor
import com.lightledger.app.ui.theme.AppColors
import com.lightledger.app.ui.theme.colorForId
import com.lightledger.app.util.formatMoney
import com.lightledger.app.util.parseMoneyToCents
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs

/** 同一机构下的一组账户（账户页的第二层） */
data class InstitutionGroup(
    val group: AccountGroup,
    val institution: String,
    val items: List<AccountWithBalance>,
) {
    val total: Long get() = items.sumOf { it.effectiveBalance }
}

data class AccountsUiState(
    val groups: List<InstitutionGroup> = emptyList(),
    /** 储蓄类资产合计 */
    val assets: Long = 0,
    /** 信用卡欠款合计（正数） */
    val debts: Long = 0,
) {
    val net: Long get() = assets - debts
}

/** 现金很少用，排最后；其余按微信 → 支付宝 → 银行 → 其他 */
private fun groupOrder(group: AccountGroup): Int = when (group) {
    AccountGroup.CASH -> 4
    AccountGroup.WECHAT -> 0
    AccountGroup.ALIPAY -> 1
    AccountGroup.BANK -> 2
    AccountGroup.OTHER -> 3
}

private fun buildAccountsState(list: List<AccountWithBalance>): AccountsUiState {
    var assets = 0L
    var debts = 0L
    list.forEach { item ->
        if (item.account.kind == AccountKind.CREDIT) {
            if (item.effectiveBalance < 0) debts += -item.effectiveBalance else assets += item.effectiveBalance
        } else {
            assets += item.effectiveBalance
        }
    }
    val groups = list
        .groupBy { it.account.group }
        .toList()
        .sortedBy { groupOrder(it.first) }
        .flatMap { (group, items) ->
            items.groupBy { it.account.institution.ifBlank { group.label } }
                .toList()
                .sortedBy { (_, accs) -> accs.minOf { it.account.sortOrder } }
                .map { (institution, accs) ->
                    InstitutionGroup(
                        group = group,
                        institution = institution,
                        items = accs.sortedWith(compareBy({ it.account.sortOrder }, { it.account.id })),
                    )
                }
        }
    return AccountsUiState(groups = groups, assets = assets, debts = debts)
}

class AccountsViewModel(repo: Repository) : ViewModel() {
    val uiState: StateFlow<AccountsUiState> = repo.accountsWithBalance()
        .map(::buildAccountsState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountsUiState())
}

@Composable
fun AccountsScreen(
    onEditAccount: (Long?) -> Unit,
    onAddAccount: (AccountGroup, String) -> Unit,
    extraBottomPadding: Dp = 0.dp,
    vm: AccountsViewModel = viewModel { AccountsViewModel(ServiceLocator.repository) },
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    var syncTarget by remember { mutableStateOf<AccountWithBalance?>(null) }

    Column(Modifier.fillMaxSize()) {
        // 顶部资产总览：只靠字号与留白建立层级
        Column(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            Text("总资产", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(
                formatMoney(state.net, withSymbol = true),
                style = MaterialTheme.typography.displaySmall,
                color = if (state.net < 0) AppColors.expense() else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                AssetTag("资产", state.assets, MaterialTheme.colorScheme.onSurfaceVariant)
                AssetTag("负债", state.debts, AppColors.expense())
            }
        }

        if (state.groups.isEmpty()) {
            EmptyHint(
                title = "还没有账户",
                subtitle = "点下方「新增账户」创建一个",
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        } else {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 6.dp,
                    bottom = 8.dp + extraBottomPadding,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                state.groups.forEach { group ->
                    item(key = "${group.group}-${group.institution}", contentType = "institution") {
                        InstitutionCard(
                            group = group,
                            onEditAccount = onEditAccount,
                            onAddAccount = onAddAccount,
                            onSync = { syncTarget = it },
                        )
                    }
                }
                item(key = "add", contentType = "add") {
                    GroupCard {
                        Pressable(
                            onClick = { onEditAccount(null) },
                            pressedBackground = stateLayerColor(),
                        ) {
                            Text(
                                "＋ 新增账户",
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                            )
                        }
                    }
                }
                item(key = "hint", contentType = "hint") {
                    Text(
                        "两级账户：先选归属（微信 / 支付宝 / 银行），再填具体账户（零钱 / 余额宝 / 卡1）。\n" +
                            "信用卡余额计入负债，总资产 = 资产 − 负债。",
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }

    syncTarget?.let { target ->
        if (canAutoSync(target.account)) {
            SyncMonthDialog(account = target.account, onDismiss = { syncTarget = null })
        } else {
            SyncIntroDialog(account = target, onDismiss = { syncTarget = null })
        }
    }

    // 同步进度/结果：由无障碍服务写进 SyncBus，回到前台后仍能看到
    val syncState by com.lightledger.app.sync.SyncBus.state.collectAsStateWithLifecycle()
    if (syncState.phase != com.lightledger.app.sync.SyncPhase.IDLE) {
        SyncProgressDialog(state = syncState, onClose = { com.lightledger.app.sync.SyncBus.reset() })
    }
}

@Composable
private fun AssetTag(label: String, value: Long, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$label ", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            formatMoney(value, withSymbol = true),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = color,
        )
    }
}

@Composable
private fun InstitutionCard(
    group: InstitutionGroup,
    onEditAccount: (Long?) -> Unit,
    onAddAccount: (AccountGroup, String) -> Unit,
    onSync: (AccountWithBalance) -> Unit,
) {
    GroupCard {
        // 第一层：机构（微信 / 支付宝 / 中国银行 …）
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 13.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CategoryIcon(
                icon = group.group.icon,
                color = colorForId(group.institution.hashCode().toLong()),
                size = 32.dp,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(group.institution, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "${group.items.size} 个账户",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                formatMoney(group.total, withSymbol = true),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (group.total < 0) AppColors.expense() else MaterialTheme.colorScheme.onSurface,
            )
        }
        InsetDivider()
        // 第二层：机构下的具体账户
        group.items.forEachIndexed { index, item ->
            AccountRow(
                item = item,
                onClick = { onEditAccount(item.account.id) },
                onSync = { onSync(item) },
            )
            if (index != group.items.lastIndex) InsetDivider(inset = 68.dp)
        }
        InsetDivider()
        Pressable(
            onClick = { onAddAccount(group.group, group.institution) },
            pressedBackground = stateLayerColor(),
        ) {
            Text(
                if (group.group == AccountGroup.BANK) {
                    "＋ 添加（${group.institution}的卡2、卡3…）"
                } else {
                    "＋ 添加子账户"
                },
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 11.dp),
            )
        }
    }
}

@Composable
private fun AccountRow(
    item: AccountWithBalance,
    onClick: () -> Unit,
    onSync: () -> Unit,
) {
    val account = item.account
    // 现金不支持同步（只能手填），所以连按钮都不给
    val showSyncButton = account.group != AccountGroup.CASH
    Pressable(
        onClick = onClick,
        pressedBackground = stateLayerColor(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CategoryIcon(
                icon = account.icon,
                color = colorForId(account.id),
                size = 40.dp,
                tintAlpha = 0.13f,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(account.displayName, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(1.dp))
                Text(
                    if (account.group == AccountGroup.BANK) account.kind.label else account.subtitle,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = formatMoney(item.effectiveBalance, withSymbol = true),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.2).sp,
                    color = if (item.effectiveBalance < 0) {
                        AppColors.expense()
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                if (account.lastSyncedAt > 0) {
                    Spacer(Modifier.height(1.dp))
                    Text(
                        "同步于 ${syncTimeText(account.lastSyncedAt)}",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (showSyncButton) {
                IconButton(onClick = onSync, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Filled.Sync,
                        contentDescription = "同步 ${account.displayName}",
                        tint = if (account.lastSyncedAt > 0) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                        modifier = Modifier.size(18.dp),
                    )
                }
            } else {
                Spacer(Modifier.width(8.dp))
            }
        }
    }
}

@Composable
private fun SyncIntroDialog(account: AccountWithBalance, onDismiss: () -> Unit) {
    val name = "${account.account.institution} · ${account.account.displayName}"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("同步待开发") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "目前只接通了「微信 · 零钱」的自动同步。支付宝、银行和零钱通的页面结构不同，" +
                        "需要各自的适配，还没实现。",
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                )
                Text(
                    "现在可以先手动记一笔，或用「我的 → 导出/导入备份」做整机迁移。",
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
    )
}

/** 账户行里的同步时间：今天显示「今天 13:53」，其余显示「11-02 13:53」 */
private fun syncTimeText(millis: Long): String {
    val dateTime = java.time.Instant.ofEpochMilli(millis)
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalDateTime()
    val today = java.time.LocalDate.now()
    return if (dateTime.toLocalDate() == today) {
        "今天 %02d:%02d".format(dateTime.hour, dateTime.minute)
    } else {
        "%02d-%02d %02d:%02d".format(dateTime.monthValue, dateTime.dayOfMonth, dateTime.hour, dateTime.minute)
    }
}

// ======================= 账户编辑 =======================

data class AccountEditUiState(
    val isEdit: Boolean = false,
    val group: AccountGroup = AccountGroup.CASH,
    val institution: String = "",
    val name: String = "",
    val icon: String = "💵",
    val kind: AccountKind = AccountKind.DEBIT,
    val initialText: String = "",
    val done: Boolean = false,
    val error: String? = null,
)

private val ACCOUNT_EMOJIS = listOf(
    "💵", "💳", "🏦", "💎", "🐷", "🧧", "💚", "💙", "💜", "🪙", "📱", "🏠", "🚗", "✈️", "🎓", "🎮",
)

private val BANKS = listOf(
    "中国银行", "工商银行", "建设银行", "农业银行", "交通银行",
    "招商银行", "邮储银行", "浦发银行", "民生银行", "中信银行",
    "光大银行", "兴业银行", "平安银行", "广发银行",
)

private val SUB_ACCOUNT_PRESETS = mapOf(
    AccountGroup.CASH to listOf("现金", "钱包"),
    AccountGroup.WECHAT to listOf("零钱", "零钱通"),
    AccountGroup.ALIPAY to listOf("余额", "余额宝"),
    AccountGroup.BANK to listOf("卡1", "卡2", "信用卡"),
    AccountGroup.OTHER to listOf("其他"),
)

class AccountEditViewModel(
    private val repo: Repository,
    private val accountId: Long,
    prefillGroup: AccountGroup?,
    prefillInstitution: String?,
) : ViewModel() {

    private val _state = MutableStateFlow(
        AccountEditUiState(
            isEdit = accountId > 0,
            group = prefillGroup ?: AccountGroup.CASH,
            institution = prefillInstitution ?: "",
            icon = (prefillGroup ?: AccountGroup.CASH).icon,
        ),
    )
    val state = _state.asStateFlow()

    private var original: Account? = null

    init {
        if (accountId > 0) {
            viewModelScope.launch {
                repo.account(accountId)?.let { a ->
                    original = a
                    _state.update {
                        it.copy(
                            group = a.group,
                            institution = a.institution,
                            name = a.name,
                            icon = a.icon,
                            kind = a.kind,
                            initialText = formatMoney(abs(a.initialBalance)),
                        )
                    }
                }
            }
        } else {
            // 新建时按机构给出默认名（微信 → 零钱，银行 → 卡1）
            viewModelScope.launch {
                val group = _state.value.group
                val institution = _state.value.institution
                val defaultName = when (group) {
                    AccountGroup.BANK -> "卡${repo.countInInstitution(institution) + 1}"
                    AccountGroup.CASH -> "现金"
                    AccountGroup.OTHER -> "其他"
                    else -> SUB_ACCOUNT_PRESETS[group]?.firstOrNull() ?: ""
                }
                if (_state.value.name.isBlank()) _state.update { it.copy(name = defaultName) }
            }
        }
    }

    fun onGroup(group: AccountGroup) = _state.update {
        val institution = when (group) {
            AccountGroup.WECHAT -> "微信"
            AccountGroup.ALIPAY -> "支付宝"
            AccountGroup.CASH -> "现金"
            AccountGroup.OTHER -> ""
            AccountGroup.BANK -> it.institution.ifBlank { "中国银行" }
        }
        it.copy(
            group = group,
            institution = institution,
            icon = if (group == AccountGroup.BANK && institution.isNotBlank()) "💳" else group.icon,
            name = when (group) {
                AccountGroup.BANK -> "卡1"
                AccountGroup.CASH -> "现金"
                AccountGroup.OTHER -> it.name
                else -> SUB_ACCOUNT_PRESETS[group]?.firstOrNull() ?: it.name
            },
        )
    }

    fun onInstitution(value: String) = _state.update { it.copy(institution = value.take(12)) }

    fun onName(name: String) = _state.update { it.copy(name = name.take(12)) }

    fun onIcon(icon: String) = _state.update { it.copy(icon = icon) }

    fun onKind(kind: AccountKind) = _state.update { it.copy(kind = kind) }

    fun onInitial(text: String) = _state.update { it.copy(initialText = text) }

    fun consumeError() = _state.update { it.copy(error = null) }

    fun save() {
        val s = _state.value
        if (s.name.isBlank()) { _state.update { it.copy(error = "请输入账户名称") }; return }
        val institution = s.institution.trim()
        if (s.group == AccountGroup.BANK && institution.isBlank()) {
            _state.update { it.copy(error = "请选择或输入银行名称") }
            return
        }
        viewModelScope.launch {
            val raw = parseMoneyToCents(s.initialText)
            // 能同步的账户余额由流水决定，期初一律置 0
            val initial = when {
                canAutoSync(s.group, s.name) -> 0L
                s.kind == AccountKind.CREDIT -> -abs(raw)
                else -> raw
            }
            val account = (original ?: Account(name = "", icon = s.icon)).copy(
                name = s.name.trim(),
                icon = s.icon,
                initialBalance = initial,
                group = s.group,
                institution = when (s.group) {
                    AccountGroup.BANK -> institution
                    AccountGroup.OTHER -> ""
                    else -> s.group.label
                },
                kind = if (s.group == AccountGroup.BANK) s.kind else AccountKind.DEBIT,
            )
            repo.saveAccount(account)
            _state.update { it.copy(done = true) }
        }
    }

    fun delete() {
        val account = original ?: return
        viewModelScope.launch {
            if (!repo.deleteAccount(account)) {
                _state.update { it.copy(error = "该账户仍有账单记录，无法删除") }
            } else {
                _state.update { it.copy(done = true) }
            }
        }
    }
}

@Composable
fun AccountEditScreen(
    accountId: Long,
    onBack: () -> Unit,
    prefillGroup: String = "",
    prefillInstitution: String = "",
    vm: AccountEditViewModel = viewModel(key = "accountEdit_${accountId}_${prefillGroup}_$prefillInstitution") {
        AccountEditViewModel(
            repo = ServiceLocator.repository,
            accountId = accountId,
            prefillGroup = runCatching { AccountGroup.valueOf(prefillGroup) }.getOrNull(),
            prefillInstitution = prefillInstitution.ifBlank { null },
        )
    },
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.done) { if (state.done) onBack() }
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it, withDismissAction = true)
            vm.consumeError()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            TopBar(if (state.isEdit) "编辑账户" else "新增账户", onBack = onBack)
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

                // ① 归属：机构大类
                GroupCard {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionLabel("账户归属")
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            AccountGroup.entries.forEach { group ->
                                ChoicePill(
                                    text = "${group.icon} ${group.label}",
                                    selected = state.group == group,
                                    onClick = { vm.onGroup(group) },
                                )
                            }
                        }

                        // ② 银行：具体到哪家银行
                        if (state.group == AccountGroup.BANK) {
                            SectionLabel("哪家银行")
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                BANKS.forEach { bank ->
                                    ChoicePill(
                                        text = bank,
                                        selected = state.institution == bank,
                                        onClick = { vm.onInstitution(bank) },
                                    )
                                }
                            }
                            OutlinedTextField(
                                value = state.institution,
                                onValueChange = vm::onInstitution,
                                label = { Text("银行名称") },
                                singleLine = true,
                                shape = MaterialTheme.shapes.medium,
                                colors = iosTextFieldColors(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            SectionLabel("卡类型")
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                AccountKind.entries.forEach { kind ->
                                    ChoicePill(
                                        text = kind.label,
                                        selected = state.kind == kind,
                                        onClick = { vm.onKind(kind) },
                                    )
                                }
                            }
                            Text(
                                if (state.kind == AccountKind.CREDIT) {
                                    "信用卡按负债计入总资产：填当前欠款，消费越多欠款越大。"
                                } else {
                                    "储蓄卡按资产计入总资产。"
                                },
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // ③ 具体账户名
                GroupCard {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SectionLabel(if (state.group == AccountGroup.BANK) "这张卡的名字" else "账户名称")
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            SUB_ACCOUNT_PRESETS[state.group].orEmpty().forEach { preset ->
                                ChoicePill(
                                    text = preset,
                                    selected = state.name == preset,
                                    onClick = { vm.onName(preset) },
                                )
                            }
                        }
                        OutlinedTextField(
                            value = state.name,
                            onValueChange = vm::onName,
                            label = { Text("自定义名称") },
                            singleLine = true,
                            shape = MaterialTheme.shapes.medium,
                            colors = iosTextFieldColors(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text("图标", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            ACCOUNT_EMOJIS.forEach { emoji ->
                                val selected = emoji == state.icon
                                Text(
                                    text = emoji,
                                    fontSize = 18.sp,
                                    modifier = Modifier
                                        .clickable { vm.onIcon(emoji) }
                                        .background(
                                            if (selected) MaterialTheme.colorScheme.primaryContainer
                                            else androidx.compose.ui.graphics.Color.Transparent,
                                            MaterialTheme.shapes.small,
                                        )
                                        .padding(horizontal = 5.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                }

                // ④ 期初：只有现金/其他这类不能同步的账户才需要手填
                GroupCard {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (canAutoSync(state.group, state.name)) {
                            Text("余额由同步流水自动校准", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(
                                "每笔流水都带交易后余额，同步后账户余额直接取最新一笔的余额，不需要填期初。",
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            OutlinedTextField(
                                value = state.initialText,
                                onValueChange = vm::onInitial,
                                label = {
                                    Text(
                                        if (state.kind == AccountKind.CREDIT) "当前欠款（元）" else "期初余额（元）",
                                    )
                                },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                shape = MaterialTheme.shapes.medium,
                                colors = iosTextFieldColors(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                "该账户暂不支持同步，余额 = 期初 + 收入 − 支出。",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Button(
                    onClick = vm::save,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                ) { Text("保存", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }

                if (state.isEdit) {
                    TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("删除账户", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp),
        )
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "删除账户",
            message = "确定删除账户「${state.name}」吗？已有账单的账户无法删除。",
            onConfirm = { vm.delete() },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ChoicePill(text: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .background(
                if (selected) scheme.primary.copy(alpha = 0.16f) else scheme.surfaceContainerHighest.copy(alpha = 0.6f),
                MaterialTheme.shapes.extraLarge,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 7.dp),
    ) {
        Text(
            text,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) scheme.primary else scheme.onSurface,
        )
    }
}
