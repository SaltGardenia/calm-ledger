package com.lightledger.app.ui.budget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lightledger.app.ServiceLocator
import com.lightledger.app.data.Repository
import com.lightledger.app.ui.GroupCard
import com.lightledger.app.ui.TopBar
import com.lightledger.app.ui.iosTextFieldColors
import com.lightledger.app.ui.theme.AppColors
import com.lightledger.app.util.formatMoney
import com.lightledger.app.util.parseMoneyToCents
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

data class BudgetUiState(
    val month: YearMonth = YearMonth.now(),
    val budget: Long = 0,
    val spent: Long = 0,
) {
    val remaining: Long get() = budget - spent
    val usedPct: Float
        get() = if (budget > 0) (spent.toDouble() / budget).coerceIn(0.0, 1.0).toFloat() else 0f
}

class BudgetViewModel(private val repo: Repository) : ViewModel() {

    private val month = YearMonth.now()

    /** 输入框里真实的文本；保存与显示都以它为准 */
    private val input = MutableStateFlow("")
    val inputFlow = input.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    val uiState: StateFlow<BudgetUiState> =
        combine(repo.budget(month.toString()), repo.monthTotals(month)) { budget, totals ->
            BudgetUiState(budget = budget?.amount ?: 0, spent = totals.expense)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BudgetUiState())

    init {
        viewModelScope.launch {
            repo.budget(month.toString()).first()?.let { input.value = formatMoney(it.amount) }
        }
    }

    fun onInput(text: String) {
        // 只保留数字、小数点和逗号，避免输入法插入奇怪字符后与保存值不一致
        input.value = text.filter { it.isDigit() || it == '.' || it == ',' || it == '-' }
    }

    fun onPreset(cents: Long) { input.value = formatMoney(cents) }

    /** 输入框里当前要保存的金额（分）；按钮文案与保存动作共用这一个值 */
    fun pendingCents(): Long = parseMoneyToCents(input.value)

    fun save(onDone: () -> Unit) {
        val cents = pendingCents()
        if (cents <= 0) {
            _message.value = "请先输入一个大于 0 的预算金额"
            return
        }
        viewModelScope.launch {
            repo.setBudget(month.toString(), cents)
            // 保存后把输入框规整成「2,000.00」，让按钮、输入框、顶部卡片三者一致
            input.value = formatMoney(cents)
            _message.value = "预算已保存：${formatMoney(cents, true)}"
            onDone()
        }
    }

    fun clear(onDone: () -> Unit) {
        viewModelScope.launch {
            repo.setBudget(month.toString(), 0)
            input.value = ""
            onDone()
        }
    }

    fun consumeMessage() { _message.value = null }
}

@Composable
fun BudgetScreen(
    onBack: () -> Unit,
    vm: BudgetViewModel = viewModel { BudgetViewModel(ServiceLocator.repository) },
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val input by vm.inputFlow.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    androidx.compose.runtime.LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    // 按钮上直接显示「即将保存的金额」，避免输入与保存值看起来对不上
    val pending = vm.pendingCents()

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            TopBar("月度预算", onBack = onBack)

            GroupCard(Modifier.padding(16.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("本月预算", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        Text(
                            if (state.budget > 0) formatMoney(state.budget, true) else "未设置",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.4).sp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    if (state.budget > 0) {
                        LinearProgressIndicator(
                            progress = { state.usedPct },
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                            strokeCap = StrokeCap.Round,
                            color = if (state.remaining >= 0) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                AppColors.expense()
                            },
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        Row {
                            Text(
                                "已花 ${formatMoney(state.spent, true)}",
                                fontSize = 13.sp,
                                color = AppColors.expense(),
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                if (state.remaining >= 0) {
                                    "剩余 ${formatMoney(state.remaining, true)}"
                                } else {
                                    "超支 ${formatMoney(-state.remaining, true)}"
                                },
                                fontSize = 13.sp,
                                color = if (state.remaining >= 0) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    AppColors.expense()
                                },
                            )
                        }
                        val daysLeft = if (state.month == YearMonth.now()) {
                            state.month.lengthOfMonth() - LocalDate.now().dayOfMonth + 1
                        } else {
                            state.month.lengthOfMonth()
                        }
                        if (state.remaining > 0) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "日均可用 ${formatMoney(state.remaining / daysLeft, true)}（还剩 $daysLeft 天）",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Text(
                            "设置每月预算，超支前及时提醒自己",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            GroupCard(Modifier.padding(horizontal = 16.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("设置预算（元）", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(2000L to "2k", 3000L to "3k", 5000L to "5k", 10000L to "1万").forEach { (preset, label) ->
                            SuggestionChip(
                                onClick = { vm.onPreset(preset) },
                                label = { Text(label) },
                                shape = RoundedCornerShape(9.dp),
                            )
                        }
                    }
                    OutlinedTextField(
                        value = input,
                        onValueChange = vm::onInput,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        placeholder = { Text("输入本月总预算") },
                        supportingText = {
                            Text(
                                if (pending > 0) "将保存 ${formatMoney(pending, true)}" else "输入金额后按钮会显示确认值",
                                fontSize = 11.sp,
                            )
                        },
                        shape = MaterialTheme.shapes.medium,
                        colors = iosTextFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { vm.save(onDone = onBack) },
                        enabled = pending > 0,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = MaterialTheme.shapes.extraLarge,
                    ) {
                        Text(
                            if (pending > 0) "保存预算 · ${formatMoney(pending, true)}" else "保存预算",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    if (state.budget > 0) {
                        TextButton(
                            onClick = { vm.clear(onDone = onBack) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("清除本月预算", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            Box(Modifier.height(24.dp))
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp),
        )
    }
}
