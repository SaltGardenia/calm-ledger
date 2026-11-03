@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.lightledger.app.ui.add

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lightledger.app.ServiceLocator
import com.lightledger.app.data.Account
import com.lightledger.app.data.Bill
import com.lightledger.app.data.BillType
import com.lightledger.app.data.Category
import com.lightledger.app.data.Repository
import com.lightledger.app.data.shortLabel
import com.lightledger.app.ui.AppSegmented
import com.lightledger.app.ui.CategoryIcon
import com.lightledger.app.ui.GroupCard
import com.lightledger.app.ui.InsetDivider
import com.lightledger.app.ui.MoneyKeyboard
import com.lightledger.app.ui.PickerDialog
import com.lightledger.app.ui.PickerOption
import com.lightledger.app.ui.TopBar
import com.lightledger.app.ui.glass.GlassPanel
import com.lightledger.app.ui.glass.glassBackdropSource
import com.lightledger.app.ui.glass.glassTint
import com.lightledger.app.ui.glass.rememberGlassBackdrop
import com.lightledger.app.ui.inlineTextFieldColors
import com.lightledger.app.ui.rememberReducedMotion
import com.lightledger.app.ui.theme.colorForId
import com.lightledger.app.util.dayTitle
import com.lightledger.app.util.formatMoney
import com.lightledger.app.util.parseMoneyToCents
import com.lightledger.app.util.toBillMillis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

data class AddBillUiState(
    val isEdit: Boolean = false,
    val type: BillType = BillType.EXPENSE,
    val amountText: String = "",
    val categoryId: Long? = null,
    val accountId: Long? = null,
    val targetAccountId: Long? = null,
    val date: LocalDate = LocalDate.now(),
    val note: String = "",
    val categories: List<Category> = emptyList(),
    val accounts: List<Account> = emptyList(),
    val done: Boolean = false,
    val error: String? = null,
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AddBillViewModel(
    private val repo: Repository,
    private val billId: Long,
) : ViewModel() {

    private val _state = MutableStateFlow(AddBillUiState(isEdit = billId > 0))
    val state = _state.asStateFlow()

    private var originalBill: Bill? = null

    init {
        viewModelScope.launch {
            repo.accountsWithBalance().map { list -> list.map { it.account } }.collect { accounts ->
                _state.update { s ->
                    val acc = s.accountId?.takeIf { id -> accounts.any { it.id == id } }
                        ?: accounts.firstOrNull()?.id
                    s.copy(accounts = accounts, accountId = acc)
                }
            }
        }
        viewModelScope.launch {
            _state.map { it.type }.distinctUntilChanged().flatMapLatest { repo.categories(it) }
                .collect { cats ->
                    _state.update { s ->
                        val cid = s.categoryId?.takeIf { id -> cats.any { it.id == id } }
                            ?: cats.firstOrNull()?.id
                        s.copy(categories = cats, categoryId = cid)
                    }
                }
        }
        if (billId > 0) {
            viewModelScope.launch {
                repo.bill(billId)?.let { b ->
                    originalBill = b
                    _state.update { s ->
                        s.copy(
                            isEdit = true,
                            type = b.type,
                            amountText = formatMoney(b.amount),
                            categoryId = b.categoryId,
                            accountId = b.accountId,
                            targetAccountId = b.targetAccountId,
                            date = LocalDate.ofEpochDay(b.day.toLong()),
                            note = b.note,
                        )
                    }
                }
            }
        }
    }

    fun onTypeChange(type: BillType) = _state.update { it.copy(type = type, categoryId = null) }

    fun onCategory(id: Long) = _state.update { it.copy(categoryId = id) }

    fun onAccount(id: Long) = _state.update { it.copy(accountId = id) }

    fun onTargetAccount(id: Long) = _state.update { it.copy(targetAccountId = id) }

    fun onDate(date: LocalDate) = _state.update { it.copy(date = date) }

    fun onNote(note: String) = _state.update { it.copy(note = note.take(30)) }

    fun consumeError() = _state.update { it.copy(error = null) }

    fun onKey(key: String) {
        when (key) {
            "today" -> _state.update { it.copy(date = LocalDate.now()) }
            else -> _state.update { s -> s.copy(amountText = nextAmount(s.amountText, key)) }
        }
    }

    private fun nextAmount(current: String, key: String): String = when (key) {
        "del" -> current.dropLast(1)
        "clear" -> ""
        "." -> when {
            current.contains('.') -> current
            current.isEmpty() -> "0."
            else -> "$current."
        }
        else -> {
            val digit = key
            val decimals = current.split('.').getOrNull(1)?.length ?: 0
            when {
                current == "0" -> digit
                decimals >= 2 -> current
                current.replace(".", "").length >= 9 -> current
                else -> current + digit
            }
        }
    }

    fun save(again: Boolean) {
        val s = _state.value
        val cents = parseMoneyToCents(s.amountText)
        if (cents <= 0) { _state.update { it.copy(error = "请输入正确的金额") }; return }
        val accountId = s.accountId ?: run { _state.update { it.copy(error = "请选择账户") }; return }

        val categoryId = s.categoryId ?: run { _state.update { it.copy(error = "请选择分类") }; return }
        val bill = buildBill(s, cents, accountId, categoryId)
        viewModelScope.launch {
            repo.saveBill(bill)
            if (again && !s.isEdit) {
                _state.update { it.copy(amountText = "", note = "", error = null) }
            } else {
                _state.update { it.copy(done = true) }
            }
        }
    }

    private fun buildBill(
        s: AddBillUiState,
        cents: Long,
        accountId: Long,
        categoryId: Long?,
    ): Bill {
        val origin = originalBill
        return Bill(
            id = origin?.id ?: 0L,
            type = s.type,
            amount = cents,
            categoryId = categoryId,
            accountId = accountId,
            targetAccountId = null,
            date = s.date.toBillMillis(),
            day = s.date.toEpochDay().toInt(),
            note = s.note.trim(),
            createdAt = origin?.createdAt ?: 0L,
            updatedAt = 0L,
        )
    }
}

@Composable
fun AddBillScreen(
    billId: Long,
    onBack: () -> Unit,
    vm: AddBillViewModel = viewModel(key = "addBill_$billId") {
        AddBillViewModel(ServiceLocator.repository, billId)
    },
) {
    val state by vm.state.collectAsStateWithLifecycle()

    var showAccountPicker by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.done) { if (state.done) onBack() }
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it, withDismissAction = true)
            vm.consumeError()
        }
    }

    val selectedAccount = state.accounts.firstOrNull { it.id == state.accountId }

    val backdrop = rememberGlassBackdrop()
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // 键盘高度 = 4×52 键 + 3×8 间距 + 16 内边距；滚动内容与 Snackbar 据此让位
    val keyboardHeight = 248.dp
    // 系统键盘（备注输入）出现时，隐藏玻璃数字键盘，避免双键盘叠在一起
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val reducedMotion = rememberReducedMotion()

    Box(Modifier.fillMaxSize()) {
        // 页面内容：整体录制进背景层，供玻璃键盘采样
        Column(
            Modifier
                .fillMaxSize()
                .glassBackdropSource(backdrop)
                .imePadding(),
        ) {
            TopBar(if (state.isEdit) "编辑账单" else "记一笔", onBack = onBack)

            // 只记支出与收入；转账入口已移除
        AppSegmented(
            options = listOf("支出", "收入"),
            selectedIndex = if (state.type == BillType.INCOME) 1 else 0,
            onSelect = { index ->
                vm.onTypeChange(if (index == 0) BillType.EXPENSE else BillType.INCOME)
            },
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        // 大金额：粗体 + 收紧字距
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                "¥",
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 5.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = state.amountText.ifEmpty { "0.00" },
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            if (state.type != BillType.TRANSFER) {
                CategoryGrid(
                    categories = state.categories,
                    selectedId = state.categoryId,
                    onSelect = vm::onCategory,
                )
                Spacer(Modifier.height(14.dp))
            }
            GroupCard {
                SelectorRow("账户", selectedAccount?.shortLabel ?: "请选择") { showAccountPicker = true }
                InsetDivider()
                SelectorRow("日期", state.date.dayTitle()) { showDatePicker = true }
                InsetDivider()
                NoteRow(note = state.note, onNote = vm::onNote)
            }
            Spacer(Modifier.height(16.dp))
            // 悬浮玻璃键盘让位
            Spacer(Modifier.height(keyboardHeight + navInset + 8.dp))
        }
        }

        // 悬浮液态玻璃数字键盘（配方转译自 Kyant0 LiquidBottomTabs）；
        // 备注输入弹出系统键盘时滑出隐藏
        AnimatedVisibility(
            visible = !imeVisible,
            enter = if (reducedMotion) {
                EnterTransition.None
            } else {
                slideInVertically(tween(220)) { it } + fadeIn(tween(160))
            },
            exit = if (reducedMotion) {
                ExitTransition.None
            } else {
                slideOutVertically(tween(200)) { it } + fadeOut(tween(140))
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            GlassPanel(
                backdrop = backdrop,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding(),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                tint = glassTint(alpha = 0.44f),
                blurRadius = 24.dp,
            ) {
                MoneyKeyboard(
                    onKey = vm::onKey,
                    onSave = { vm.save(again = false) },
                    saveEnabled = state.amountText.isNotEmpty(),
                    onSaveAgain = if (state.isEdit) null else ({ vm.save(again = true) }),
                    transparent = true,
                )
            }
        }

        // M3 Snackbar：非阻断反馈，浮于玻璃键盘上方
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = keyboardHeight + navInset + 16.dp),
        )
    }

    if (showAccountPicker) {
        PickerDialog(
            title = "选择账户",
            options = state.accounts.map { PickerOption(it.id, it.shortLabel, it.icon) },
            selectedId = state.accountId,
            onSelect = vm::onAccount,
            onDismiss = { showAccountPicker = false },
        )
    }
    if (showDatePicker) {
        val dateState = rememberDatePickerState(
            initialSelectedDateMillis = state.date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        dateState.selectedDateMillis?.let { millis ->
                            vm.onDate(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                        }
                        showDatePicker = false
                    },
                ) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("取消") } },
        ) {
            DatePicker(state = dateState)
        }
    }
}

@Composable
private fun CategoryGrid(
    categories: List<Category>,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        categories.chunked(5).forEach { rowCats ->
            Row(Modifier.fillMaxWidth()) {
                rowCats.forEach { category ->
                    val selected = category.id == selectedId
                    // 单选语义：Role.RadioButton + selected 状态；选中态以描边环 + 标签字重双重表达
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .selectable(
                                selected = selected,
                                role = Role.RadioButton,
                                onClick = { onSelect(category.id) },
                            )
                            .padding(vertical = 2.dp),
                    ) {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(44.dp * 0.3f))
                                .border(
                                    width = if (selected) 2.dp else 0.dp,
                                    color = if (selected) MaterialTheme.colorScheme.primary
                                    else androidx.compose.ui.graphics.Color.Transparent,
                                    shape = RoundedCornerShape(44.dp * 0.3f),
                                ),
                        ) {
                            CategoryIcon(
                                icon = category.icon,
                                color = colorForId(category.id),
                                size = 44.dp,
                                tintAlpha = if (selected) 0.24f else 0.13f,
                            )
                        }
                        Spacer(Modifier.height(5.dp))
                        Text(
                            text = category.name,
                            fontSize = 12.sp,
                            maxLines = 1,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                repeat(5 - rowCats.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun SelectorRow(label: String, value: String, onClick: () -> Unit) {
    val isPlaceholder = value == "请选择"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = false,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (isPlaceholder) FontWeight.Normal else FontWeight.SemiBold,
            color = if (isPlaceholder) MaterialTheme.colorScheme.outline
            else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun NoteRow(note: String, onNote: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("备注", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(24.dp))
        TextField(
            value = note,
            onValueChange = onNote,
            placeholder = {
                Text("添加备注…", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            },
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            ),
            colors = inlineTextFieldColors(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            modifier = Modifier.weight(1f),
        )
    }
}
