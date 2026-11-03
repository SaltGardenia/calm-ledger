package com.lightledger.app.ui.bills

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import com.lightledger.app.ServiceLocator
import com.lightledger.app.data.Account
import com.lightledger.app.data.Bill
import com.lightledger.app.data.BillType
import com.lightledger.app.data.Category
import com.lightledger.app.data.MonthTotals
import com.lightledger.app.data.Repository
import com.lightledger.app.data.displayName
import com.lightledger.app.data.shortLabel
import com.lightledger.app.ui.AppSegmented
import com.lightledger.app.ui.CategoryIcon
import com.lightledger.app.ui.EmptyHint
import com.lightledger.app.ui.GroupCard
import com.lightledger.app.ui.InsetDivider
import com.lightledger.app.ui.MonthBar
import com.lightledger.app.ui.Pressable
import com.lightledger.app.ui.stateLayerColor
import com.lightledger.app.ui.theme.AppColors
import com.lightledger.app.ui.theme.colorForId
import com.lightledger.app.util.dayGroupTitle
import com.lightledger.app.util.formatMoney
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.YearMonth
import kotlin.math.roundToInt

/** 按日分组的一组账单 */
data class DayGroup(
    val day: Int,
    val bills: List<Bill>,
    val expense: Long,
    val income: Long,
)

data class BillsUiState(
    val month: YearMonth = YearMonth.now(),
    val groups: List<DayGroup> = emptyList(),
    val expense: Long = 0,
    val income: Long = 0,
    val budget: Long = 0,
    val accounts: Map<Long, Account> = emptyMap(),
    val categories: Map<Long, Category> = emptyMap(),
) {
    val balance: Long get() = income - expense
}

@OptIn(ExperimentalCoroutinesApi::class)
class BillsViewModel(private val repo: Repository) : ViewModel() {

    private val month = MutableStateFlow(YearMonth.now())

    /** 多选模式下的勾选集 */
    private val _selected = MutableStateFlow<Set<Long>>(emptySet())
    val selected: StateFlow<Set<Long>> = _selected

    private val _selecting = MutableStateFlow(false)
    val selecting: StateFlow<Boolean> = _selecting

    /** 只看未分类（同步导入的账单都落在这里） */
    private val _uncategorizedOnly = MutableStateFlow(false)
    val uncategorizedOnly: StateFlow<Boolean> = _uncategorizedOnly

    val uiState: StateFlow<BillsUiState> =
        combine(
            repo.accountsWithBalance(),
            repo.allCategories(),
        ) { accounts, categories -> accounts to categories.associateBy { it.id } }
            .flatMapLatest { (accounts, categoriesById) ->
                combine(month, _uncategorizedOnly) { m, onlyUncategorized -> m to onlyUncategorized }
                    .flatMapLatest { (ym, onlyUncategorized) ->
                    combine(
                        repo.billsInMonth(ym),
                        repo.monthTotals(ym),
                        repo.budget(ym.toString()),
                    ) { allBills, totals: MonthTotals, budget ->
                        val bills = if (onlyUncategorized) allBills.filter { it.categoryId == null } else allBills
                        val groups = bills.groupBy { it.day }
                            .toSortedMap(compareByDescending { it })
                            .map { (day, dayBills) ->
                                DayGroup(
                                    day = day,
                                    bills = dayBills,
                                    expense = dayBills.filter { it.type == BillType.EXPENSE }.sumOf { it.amount },
                                    income = dayBills.filter { it.type == BillType.INCOME }.sumOf { it.amount },
                                )
                            }
                        BillsUiState(
                            month = ym,
                            groups = groups,
                            expense = totals.expense,
                            income = totals.income,
                            budget = budget?.amount ?: 0,
                            accounts = accounts.associate { it.account.id to it.account },
                            categories = categoriesById,
                        )
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BillsUiState())

    fun prevMonth() {
        month.value = month.value.minusMonths(1)
        exitSelection()
    }

    fun nextMonth() {
        if (month.value < YearMonth.now()) month.value = month.value.plusMonths(1)
        exitSelection()
    }

    // ---- 多选 ----
    fun enterSelection() { _selecting.value = true }

    fun exitSelection() {
        _selecting.value = false
        _selected.value = emptySet()
    }

    fun toggle(id: Long) {
        _selecting.value = true
        _selected.update { if (id in it) it - id else it + id }
    }

    fun selectAll() {
        _selected.value = uiState.value.groups.flatMap { g -> g.bills.map { it.id } }.toSet()
    }

    fun clearAll() { _selected.value = emptySet() }

    fun toggleUncategorizedOnly() {
        _uncategorizedOnly.value = !_uncategorizedOnly.value
        _selected.value = emptySet()
    }

    fun deleteSelected() {
        val ids = _selected.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            repo.deleteBills(ids)
            exitSelection()
        }
    }

    fun deleteOne(bill: Bill) {
        viewModelScope.launch { repo.deleteBill(bill) }
    }

    fun changeCategory(category: Category) {
        val ids = _selected.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            repo.moveBillsToCategory(ids, category)
            exitSelection()
        }
    }
}

@Composable
fun BillsScreen(
    onEditBill: (Long) -> Unit,
    extraBottomPadding: Dp = 0.dp,
    vm: BillsViewModel = viewModel { BillsViewModel(ServiceLocator.repository) },
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val selecting by vm.selecting.collectAsStateWithLifecycle()
    val uncategorizedOnly by vm.uncategorizedOnly.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var showBulkCategory by remember { mutableStateOf(false) }
    // 同一时间只允许一行处于「已滑开」状态
    var openRowId by remember { mutableStateOf<Long?>(null) }
    val listState = rememberLazyListState()
    androidx.compose.runtime.LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) openRowId = null
    }
    androidx.compose.runtime.LaunchedEffect(selecting) { openRowId = null }
    val totalBills = state.groups.sumOf { it.bills.size }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (selecting) {
                // 多选顶栏：选中数 + 全选 + 完成
                Row(
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (selected.isEmpty()) "选择账单" else "已选 ${selected.size} 笔",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            if (selected.isNotEmpty() && selected.size == totalBills) vm.clearAll() else vm.selectAll()
                        },
                    ) {
                        Text(if (selected.isNotEmpty() && selected.size == totalBills) "取消全选" else "全选")
                    }
                    TextButton(onClick = vm::exitSelection) { Text("完成") }
                }
                // 批量操作条
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    BulkAction(
                        text = "未分类",
                        enabled = true,
                        active = uncategorizedOnly,
                        modifier = Modifier.weight(1f),
                    ) { vm.toggleUncategorizedOnly() }
                    BulkAction(
                        text = "改分类",
                        enabled = selected.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) { showBulkCategory = true }
                    BulkAction(
                        text = "删除",
                        enabled = selected.isNotEmpty(),
                        danger = true,
                        modifier = Modifier.weight(1f),
                    ) {
                        val count = selected.size
                        vm.deleteSelected()
                        scope.launch { snackbar.showSnackbar("已删除 $count 笔") }
                    }
                }
                Spacer(Modifier.height(4.dp))
            } else {
                // 普通顶栏：月份 + 月度汇总 + 预算进度
                Column(
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 20.dp),
                ) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            MonthBar(month = state.month, onPrev = vm::prevMonth, onNext = vm::nextMonth)
                        }
                        IconButton(onClick = vm::enterSelection) {
                            Icon(
                                Icons.Filled.Checklist,
                                contentDescription = "多选账单",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row {
                        HeaderStat("支出", formatMoney(state.expense), AppColors.expense(), Modifier.weight(1f))
                        HeaderStat("收入", formatMoney(state.income), AppColors.income(), Modifier.weight(1f))
                        HeaderStat(
                            "结余",
                            formatMoney(state.balance, withSymbol = true),
                            MaterialTheme.colorScheme.onSurface,
                            Modifier.weight(1f),
                        )
                    }
                    if (state.budget > 0) {
                        Spacer(Modifier.height(16.dp))
                        val used = (state.expense.toDouble() / state.budget).coerceIn(0.0, 1.0)
                        val remaining = state.budget - state.expense
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth(used.toFloat())
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(
                                        if (remaining >= 0) MaterialTheme.colorScheme.primary
                                        else AppColors.expense(),
                                    ),
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "预算 ${formatMoney(state.budget, true)} · 已用 ${(used * 100).toInt()}% · " +
                                if (remaining >= 0) {
                                    "剩余 ${formatMoney(remaining, true)}"
                                } else {
                                    "超支 ${formatMoney(-remaining, true)}"
                                },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }

            if (state.groups.isEmpty()) {
                EmptyHint(
                    title = if (uncategorizedOnly) "没有未分类账单" else "本月还没有账单",
                    subtitle = if (uncategorizedOnly) "同步导入的账单会出现在这里" else "点下方 ＋ 记一笔",
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            } else {
                LazyColumn(
                    Modifier.weight(1f),
                    state = listState,
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 8.dp,
                        bottom = 8.dp + extraBottomPadding,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.groups.forEach { group ->
                        item(key = group.day, contentType = "day") {
                            DayCard(
                                group = group,
                                categories = state.categories,
                                accounts = state.accounts,
                                selectionMode = selecting,
                                selectedIds = selected,
                                openRowId = openRowId,
                                onOpenChange = { id, open -> openRowId = if (open) id else null },
                                onCloseOpenRow = { openRowId = null },
                                onEditBill = onEditBill,
                                onToggle = vm::toggle,
                                onDeleteOne = { bill ->
                                    vm.deleteOne(bill)
                                    scope.launch {
                                        snackbar.showSnackbar("已删除「${bill.note.ifBlank { "这笔账" }}」")
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp + extraBottomPadding),
        )
    }

    if (showBulkCategory && selected.isNotEmpty()) {
        BulkCategoryDialog(
            categories = state.categories.values.toList(),
            onDismiss = { showBulkCategory = false },
            onPick = { category ->
                vm.changeCategory(category)
                showBulkCategory = false
                scope.launch { snackbar.showSnackbar("已改为「${category.name}」") }
            },
        )
    }
}

@Composable
private fun BulkAction(
    text: String,
    enabled: Boolean,
    danger: Boolean = false,
    active: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier
            .height(40.dp)
            .clip(MaterialTheme.shapes.extraLarge)
            .background(
                when {
                    danger -> scheme.error.copy(alpha = if (enabled) 0.14f else 0.06f)
                    active -> scheme.primary.copy(alpha = 0.85f)
                    else -> scheme.primary.copy(alpha = if (enabled) 0.14f else 0.06f)
                },
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = if (active) 13.sp else 14.sp,
            fontWeight = FontWeight.Medium,
            color = when {
                !enabled -> scheme.onSurface.copy(alpha = 0.38f)
                active -> scheme.onPrimary
                danger -> scheme.error
                else -> scheme.primary
            },
        )
    }
}

@Composable
private fun HeaderStat(label: String, value: String, valueColor: Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(3.dp))
        Text(
            value,
            fontSize = 21.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.4).sp,
            color = valueColor,
        )
    }
}

@Composable
private fun DayCard(
    group: DayGroup,
    categories: Map<Long, Category>,
    accounts: Map<Long, Account>,
    selectionMode: Boolean,
    selectedIds: Set<Long>,
    openRowId: Long?,
    onOpenChange: (Long, Boolean) -> Unit,
    onCloseOpenRow: () -> Unit,
    onEditBill: (Long) -> Unit,
    onToggle: (Long) -> Unit,
    onDeleteOne: (Bill) -> Unit,
) {
    GroupCard {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = dayGroupTitle(group.day),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (group.expense > 0) {
                Text("支 ${formatMoney(group.expense)}", fontSize = 12.sp, color = AppColors.expense())
                Spacer(Modifier.width(10.dp))
            }
            if (group.income > 0) {
                Text("收 ${formatMoney(group.income)}", fontSize = 12.sp, color = AppColors.income())
            }
        }
        group.bills.forEachIndexed { index, bill ->
            val row: @Composable () -> Unit = {
                BillRow(
                    bill = bill,
                    category = categories[bill.categoryId],
                    account = accounts[bill.accountId],
                    targetAccount = bill.targetAccountId?.let { accounts[it] },
                    selected = bill.id in selectedIds,
                    selectionMode = selectionMode,
                    onClick = {
                        when {
                            // 有行处于滑开状态时，点任何地方先收起它
                            openRowId != null -> onCloseOpenRow()
                            selectionMode -> onToggle(bill.id)
                            else -> onEditBill(bill.id)
                        }
                    },
                )
            }
            if (selectionMode) {
                row()
            } else {
                SwipeToDelete(
                    open = openRowId == bill.id,
                    onOpenChange = { open -> onOpenChange(bill.id, open) },
                    onDelete = { onDeleteOne(bill) },
                ) { row() }
            }
            if (index != group.bills.lastIndex) {
                InsetDivider(inset = 68.dp)
            }
        }
    }
}

/**
 * 左滑呼出删除按钮：横向拖拽露出 76dp 红色按钮，点一下才真正删除。
 *
 * 用 Animatable 统一承载「拖拽中」和「松手回弹」两种位移，避免原来
 * `animateFloatAsState` 在松手瞬间从旧目标值重新起跳造成的抽搐/闪烁。
 */
@Composable
private fun SwipeToDelete(
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val revealPx = with(density) { 76.dp.toPx() }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }

    // 外部状态变化（点了别处 / 别的行被滑开）时归位；拖拽中不打断
    LaunchedEffect(open, dragging) {
        if (!dragging) {
            offset.animateTo(
                if (open) -revealPx else 0f,
                spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow),
            )
        }
    }

    Box(Modifier.fillMaxWidth()) {
        // 底层：删除按钮。宽度 = 已滑开的距离，关闭时宽度为 0
        Row(Modifier.matchParentSize(), horizontalArrangement = Arrangement.End) {
            Box(
                Modifier
                    .layout { measurable, constraints ->
                        val w = (-offset.value).coerceIn(0f, revealPx).roundToInt()
                        val placeable = measurable.measure(
                            constraints.copy(minWidth = w, maxWidth = w),
                        )
                        layout(w, placeable.height) { placeable.place(0, 0) }
                    }
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.error)
                    .clickable {
                        onOpenChange(false)
                        onDelete()
                    },
            ) {
                Box(Modifier.width(76.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.DeleteOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onError,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "删除",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onError,
                        )
                    }
                }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .pointerInput(revealPx) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            dragging = true
                            // 先收起别的行，保证同时只有一行打开
                            if (!open) onOpenChange(false)
                            scope.launch { offset.stop() }
                        },
                        onDragCancel = {
                            dragging = false
                            onOpenChange(false)
                        },
                        onDragEnd = {
                            dragging = false
                            // 左滑过阈值才展开；右滑回去就收起
                            onOpenChange(offset.value <= -revealPx * 0.45f)
                        },
                        onHorizontalDrag = { _, amount ->
                            scope.launch {
                                offset.snapTo((offset.value + amount).coerceIn(-revealPx, 0f))
                            }
                        },
                    )
                },
        ) {
            content()
        }
    }
}

@Composable
fun BillRow(
    bill: Bill,
    category: Category?,
    account: Account?,
    targetAccount: Account? = null,
    selected: Boolean = false,
    selectionMode: Boolean = false,
    onClick: () -> Unit = {},
) {
    Pressable(
        onClick = onClick,
        pressedBackground = stateLayerColor(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(
                    if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else Color.Transparent,
                )
                .padding(
                    start = if (selectionMode) 10.dp else 16.dp,
                    end = 16.dp,
                    top = 11.dp,
                    bottom = 11.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionMode) {
                Box(
                    Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) {
                        Text("✓", fontSize = 13.sp, color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
                Spacer(Modifier.width(10.dp))
            }
            val color = when (bill.type) {
                BillType.EXPENSE, BillType.INCOME -> colorForId(bill.categoryId)
                BillType.TRANSFER -> colorForId(bill.accountId)
            }
            CategoryIcon(
                icon = category?.icon ?: account?.icon ?: "🧾",
                color = color,
                size = 40.dp,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                val title = when (bill.type) {
                    BillType.TRANSFER ->
                        "转账 · ${account?.displayName ?: "?"} → ${targetAccount?.displayName ?: "?"}"
                    else -> buildString {
                        append(category?.name ?: "未分类")
                        if (bill.note.isNotBlank()) append(" · ").append(bill.note)
                    }
                }
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(1.dp))
                Text(
                    text = account?.shortLabel ?: "",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            val amountColor = when (bill.type) {
                BillType.EXPENSE -> AppColors.expense()
                BillType.INCOME -> AppColors.income()
                BillType.TRANSFER -> MaterialTheme.colorScheme.onSurface
            }
            val prefix = when (bill.type) {
                BillType.EXPENSE -> "-"
                BillType.INCOME -> "+"
                BillType.TRANSFER -> ""
            }
            Text(
                text = "$prefix${formatMoney(bill.amount, withSymbol = true)}",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.2).sp,
                color = amountColor,
            )
        }
    }
}

/** 批量改分类：先选支出/收入，再点分类；账单类型会跟随分类类型一起改 */
@Composable
private fun BulkCategoryDialog(
    categories: List<Category>,
    onDismiss: () -> Unit,
    onPick: (Category) -> Unit,
) {
    var tab by remember { mutableStateOf(BillType.EXPENSE) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("改为哪个分类") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AppSegmented(
                    options = listOf("支出", "收入"),
                    selectedIndex = if (tab == BillType.EXPENSE) 0 else 1,
                    onSelect = { tab = if (it == 0) BillType.EXPENSE else BillType.INCOME },
                )
                val visible = categories
                    .filter { it.type == tab }
                    .sortedBy { it.sortOrder * 1000 + it.id }
                LazyColumn(Modifier.height(300.dp)) {
                    items(visible.size, key = { visible[it].id }) { index ->
                        val c = visible[index]
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(c) }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CategoryIcon(c.icon, colorForId(c.id), size = 34.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(c.name, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
