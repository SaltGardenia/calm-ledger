package com.lightledger.app.ui.stats

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lightledger.app.ServiceLocator
import com.lightledger.app.data.BillType
import com.lightledger.app.data.Repository
import com.lightledger.app.ui.AppSegmented
import com.lightledger.app.ui.CalendarHeatmap
import com.lightledger.app.ui.CategoryIcon
import com.lightledger.app.ui.DualBarChart
import com.lightledger.app.ui.GroupCard
import com.lightledger.app.ui.InsetDivider
import com.lightledger.app.ui.LineChart
import com.lightledger.app.ui.LineSeries
import com.lightledger.app.ui.MonthBar
import com.lightledger.app.ui.PieChart
import com.lightledger.app.ui.PieSlice
import com.lightledger.app.ui.rememberReducedMotion
import com.lightledger.app.ui.theme.AppColors
import com.lightledger.app.ui.theme.colorForId
import com.lightledger.app.util.formatMoney
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.YearMonth

enum class StatsView { MONTH, YEAR }

data class RankItem(
    val categoryId: Long?,
    val name: String,
    val icon: String,
    val amount: Long,
    val pct: Float,
    val count: Int = 0,
)

data class StatsUiState(
    val view: StatsView = StatsView.MONTH,
    val month: YearMonth = YearMonth.now(),
    val year: Int = YearMonth.now().year,
    val expenseTotal: Long = 0,
    val incomeTotal: Long = 0,
    val billCount: Int = 0,
    val prevExpense: Long = 0,
    val prevIncome: Long = 0,
    val prevCount: Int = 0,
    val dayExpense: List<Float> = emptyList(),
    val dayIncome: List<Float> = emptyList(),
    val expenseRanks: List<RankItem> = emptyList(),
    val incomeRanks: List<RankItem> = emptyList(),
    val expenseSlices: List<PieSlice> = emptyList(),
    val incomeSlices: List<PieSlice> = emptyList(),
    val monthExpense: List<Long> = emptyList(),
    val monthIncome: List<Long> = emptyList(),
    val yearExpenseRanks: List<RankItem> = emptyList(),
    val yearIncomeRanks: List<RankItem> = emptyList(),
) {
    val balance: Long get() = incomeTotal - expenseTotal
    val prevBalance: Long get() = prevIncome - prevExpense
    val yearExpense: Long get() = monthExpense.sum()
    val yearIncome: Long get() = monthIncome.sum()
    val yearBalance: Long get() = yearIncome - yearExpense
}

private fun buildSlices(ranks: List<RankItem>): List<PieSlice> {
    val top = ranks.take(5)
    val rest = ranks.drop(5).sumOf { it.amount }
    return buildList {
        top.forEach { add(PieSlice(it.name, it.amount.toFloat(), colorForId(it.categoryId))) }
        if (rest > 0) add(PieSlice("其他", rest.toFloat(), Color(0xFF98989F)))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModel(private val repo: Repository) : ViewModel() {

    private val view = MutableStateFlow(StatsView.MONTH)
    private val month = MutableStateFlow(YearMonth.now())
    private val year = MutableStateFlow(YearMonth.now().year)

    val uiState: StateFlow<StatsUiState> =
        combine(view, month, year) { v, m, y -> Triple(v, m, y) }
            .flatMapLatest { (v, m, y) ->
                if (v == StatsView.MONTH) monthly(m) else yearly(y)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())

    private fun monthly(m: YearMonth): kotlinx.coroutines.flow.Flow<StatsUiState> {
        val sums = combine(
            repo.categorySums(m, BillType.EXPENSE),
            repo.categorySums(m, BillType.INCOME),
        ) { expense, income -> expense to income }
        return combine(
            sums,
            repo.allCategories(),
            repo.daySums(m),
            repo.monthStats(m),
            repo.monthStats(m.minusMonths(1)),
        ) { (expenseSums, incomeSums), cats, days, cur, prev ->
        val catsById = cats.associateBy { it.id }
        val expenseTotal = expenseSums.sumOf { it.total }
        val incomeTotal = incomeSums.sumOf { it.total }
        fun ranks(sums: List<com.lightledger.app.data.CategorySum>, total: Long): List<RankItem> =
            sums.map { s ->
                RankItem(
                    categoryId = s.categoryId,
                    name = catsById[s.categoryId]?.name ?: "未分类",
                    icon = catsById[s.categoryId]?.icon ?: "🧾",
                    amount = s.total,
                    pct = if (total > 0) s.total.toFloat() / total else 0f,
                    count = s.count,
                )
            }
        val expenseRanks = ranks(expenseSums, expenseTotal)
        val incomeRanks = ranks(incomeSums, incomeTotal)
        val startDay = m.atDay(1).toEpochDay().toInt()
        StatsUiState(
            view = StatsView.MONTH,
            month = m,
            year = m.year,
            expenseTotal = cur.expense,
            incomeTotal = cur.income,
            billCount = cur.count,
            prevExpense = prev.expense,
            prevIncome = prev.income,
            prevCount = prev.count,
            dayExpense = List(m.lengthOfMonth()) { d ->
                (days.firstOrNull { it.day == startDay + d }?.expense ?: 0L).toFloat()
            },
            dayIncome = List(m.lengthOfMonth()) { d ->
                (days.firstOrNull { it.day == startDay + d }?.income ?: 0L).toFloat()
            },
            expenseRanks = expenseRanks,
            incomeRanks = incomeRanks,
            expenseSlices = buildSlices(expenseRanks),
            incomeSlices = buildSlices(incomeRanks),
        )
        }
    }

    private fun yearly(y: Int) = combine(repo.billsInYear(y), repo.allCategories()) { bills, cats ->
        val catsById = cats.associateBy { it.id }
        val monthExpense = LongArray(12)
        val monthIncome = LongArray(12)
        bills.forEach { b ->
            val idx = LocalDate.ofEpochDay(b.day.toLong()).monthValue - 1
            if (idx in 0..11) {
                if (b.type == BillType.EXPENSE) monthExpense[idx] += b.amount
                else if (b.type == BillType.INCOME) monthIncome[idx] += b.amount
            }
        }
        fun ranks(type: BillType, total: Long): List<RankItem> =
            bills.filter { it.type == type }
                .groupBy { it.categoryId }
                .map { (catId, list) ->
                    val sum = list.sumOf { it.amount }
                    RankItem(
                        categoryId = catId,
                        name = catsById[catId]?.name ?: "未分类",
                        icon = catsById[catId]?.icon ?: "🧾",
                        amount = sum,
                        pct = if (total > 0) sum.toFloat() / total else 0f,
                        count = list.size,
                    )
                }
                .sortedByDescending { it.amount }

        val expenseTotal = monthExpense.sum()
        val incomeTotal = monthIncome.sum()
        StatsUiState(
            view = StatsView.YEAR,
            year = y,
            monthExpense = monthExpense.toList(),
            monthIncome = monthIncome.toList(),
            yearExpenseRanks = ranks(BillType.EXPENSE, expenseTotal),
            yearIncomeRanks = ranks(BillType.INCOME, incomeTotal),
        )
    }

    fun setView(v: StatsView) { view.value = v }
    fun prevMonth() { month.value = month.value.minusMonths(1) }
    fun nextMonth() { if (month.value < YearMonth.now()) month.value = month.value.plusMonths(1) }
    fun prevYear() { year.value -= 1 }
    fun nextYear() { if (year.value < YearMonth.now().year) year.value += 1 }
}

/* ---------------- 页面 ---------------- */

@Composable
fun StatsScreen(
    extraBottomPadding: Dp = 0.dp,
    vm: StatsViewModel = viewModel { StatsViewModel(ServiceLocator.repository) },
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val reducedMotion = rememberReducedMotion()

    val chartKey = "${state.view}-${state.month}-${state.year}"
    val chartProgress = remember(chartKey) { Animatable(0f) }
    LaunchedEffect(chartKey) {
        if (reducedMotion) chartProgress.snapTo(1f)
        else chartProgress.animateTo(1f, spring(dampingRatio = 1f, stiffness = 180f))
    }
    val progress = chartProgress.value

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(6.dp))
        AppSegmented(
            options = listOf("月视图", "年视图"),
            selectedIndex = if (state.view == StatsView.MONTH) 0 else 1,
            onSelect = { index -> vm.setView(if (index == 0) StatsView.MONTH else StatsView.YEAR) },
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(4.dp))
        if (state.view == StatsView.MONTH) {
            MonthBar(month = state.month, onPrev = vm::prevMonth, onNext = vm::nextMonth)
        } else {
            YearBar(year = state.year, onPrev = vm::prevYear, onNext = vm::nextYear)
        }
        Spacer(Modifier.height(8.dp))

        if (state.view == StatsView.MONTH) {
            MonthlyContent(state, progress)
        } else {
            YearlyContent(state, progress)
        }
        Spacer(Modifier.height(24.dp + extraBottomPadding))
    }
}

@Composable
private fun YearBar(year: Int, onPrev: () -> Unit, onNext: () -> Unit) {
    val canNext = year < YearMonth.now().year
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrev) {
            Icon(
                Icons.Filled.ChevronLeft,
                contentDescription = "上一年",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "${year}年",
            style = MaterialTheme.typography.titleMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onNext, enabled = canNext) {
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = if (canNext) "下一年" else "已是最新年份",
                tint = if (canNext) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/* ---------------- 月视图 ---------------- */

@Composable
private fun MonthlyContent(state: StatsUiState, progress: Float) {
    KpiTiles(
        expense = state.expenseTotal,
        income = state.incomeTotal,
        balance = state.balance,
        count = state.billCount,
        prevExpense = state.prevExpense,
        prevIncome = state.prevIncome,
        prevBalance = state.prevBalance,
        prevCount = state.prevCount,
    )
    Spacer(Modifier.height(10.dp))

    GroupCard(Modifier.padding(horizontal = 16.dp)) {
        Column(Modifier.padding(16.dp)) {
            SectionTitle("收支趋势", "每日金额，红=支出 绿=收入")
            Spacer(Modifier.height(14.dp))
            LineChart(
                series = listOf(
                    LineSeries(state.dayExpense, AppColors.expense()),
                    LineSeries(state.dayIncome, AppColors.income()),
                ),
                modifier = Modifier.fillMaxWidth().height(140.dp),
                progress = progress,
            )
            Spacer(Modifier.height(10.dp))
            LegendRow(state)
        }
    }
    Spacer(Modifier.height(10.dp))

    GroupCard(Modifier.padding(horizontal = 16.dp)) {
        Column(Modifier.padding(16.dp)) {
            SectionTitle("每日对比", "同一天的收入与支出并排")
            Spacer(Modifier.height(14.dp))
            DualBarChart(
                primary = state.dayExpense,
                secondary = state.dayIncome,
                primaryColor = AppColors.expense(),
                secondaryColor = AppColors.income(),
                progress = progress,
                modifier = Modifier.fillMaxWidth().height(120.dp),
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("1号", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text(
                    "${state.month.lengthOfMonth()}号",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    Spacer(Modifier.height(10.dp))

    GroupCard(Modifier.padding(horizontal = 16.dp)) {
        Column(Modifier.padding(16.dp)) {
            SectionTitle("收支构成", "左：支出去向 · 右：收入来源")
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth()) {
                DonutColumn(
                    title = "支出",
                    total = state.expenseTotal,
                    slices = state.expenseSlices,
                    color = AppColors.expense(),
                    progress = progress,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                DonutColumn(
                    title = "收入",
                    total = state.incomeTotal,
                    slices = state.incomeSlices,
                    color = AppColors.income(),
                    progress = progress,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
    Spacer(Modifier.height(10.dp))

    GroupCard(Modifier.padding(horizontal = 16.dp)) {
        Column(Modifier.padding(16.dp)) {
            SectionTitle("消费日历", "颜色越深金额越大；格内数字 = 当日支出 / 收入")
            Spacer(Modifier.height(14.dp))
            CalendarHeatmap(
                month = state.month,
                values = state.dayExpense,
                color = AppColors.expense(),
                progress = progress,
                modifier = Modifier.fillMaxWidth(),
                expenseValues = state.dayExpense,
                incomeValues = state.dayIncome,
            )
        }
    }
    Spacer(Modifier.height(10.dp))

    GroupCard(Modifier.padding(horizontal = 16.dp)) {
        Column(Modifier.padding(16.dp)) {
            SectionTitle("分类排行", "支出与收入分开看，按金额降序")
            Spacer(Modifier.height(12.dp))
            RankBlock("支出", state.expenseRanks, AppColors.expense(), progress)
            Spacer(Modifier.height(14.dp))
            InsetDivider(inset = 0.dp)
            Spacer(Modifier.height(14.dp))
            RankBlock("收入", state.incomeRanks, AppColors.income(), progress)
        }
    }
}

/* ---------------- 年视图 ---------------- */

@Composable
private fun YearlyContent(state: StatsUiState, progress: Float) {
    val currentMonth = YearMonth.now()
    val monthsElapsed = if (state.year == currentMonth.year) currentMonth.monthValue else 12
    KpiTiles(
        expense = state.yearExpense,
        income = state.yearIncome,
        balance = state.yearBalance,
        count = state.yearExpenseRanks.sumOf { it.count } + state.yearIncomeRanks.sumOf { it.count },
        prevExpense = 0,
        prevIncome = 0,
        prevBalance = 0,
        prevCount = 0,
        yearLabel = "${state.year}年",
    )
    Spacer(Modifier.height(10.dp))

    GroupCard(Modifier.padding(horizontal = 16.dp)) {
        Column(Modifier.padding(16.dp)) {
            SectionTitle("月度收支", "12 个月收入与支出并排，已过 $monthsElapsed 个月")
            Spacer(Modifier.height(14.dp))
            DualBarChart(
                primary = state.monthExpense.map { it.toFloat() },
                secondary = state.monthIncome.map { it.toFloat() },
                primaryColor = AppColors.expense(),
                secondaryColor = AppColors.income(),
                progress = progress,
                modifier = Modifier.fillMaxWidth().height(150.dp),
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                (1..12).forEach { m ->
                    Text(
                        "$m",
                        fontSize = 10.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                LegendDot(AppColors.expense(), "支出 ${formatMoney(state.yearExpense, true)}")
                Spacer(Modifier.width(16.dp))
                LegendDot(AppColors.income(), "收入 ${formatMoney(state.yearIncome, true)}")
                Spacer(Modifier.weight(1f))
                Text(
                    "结余 ${formatMoney(state.yearBalance, true)}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (state.yearBalance >= 0) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        AppColors.expense()
                    },
                )
            }
        }
    }
    Spacer(Modifier.height(10.dp))

    GroupCard(Modifier.padding(horizontal = 16.dp)) {
        Column(Modifier.padding(16.dp)) {
            SectionTitle("月度结余", "每月收入减支出")
            Spacer(Modifier.height(14.dp))
            LineChart(
                series = listOf(
                    LineSeries(
                        state.monthExpense.zip(state.monthIncome) { e, i -> (i - e).toFloat() },
                        MaterialTheme.colorScheme.primary,
                    ),
                ),
                modifier = Modifier.fillMaxWidth().height(130.dp),
                progress = progress,
            )
        }
    }
    Spacer(Modifier.height(10.dp))

    GroupCard(Modifier.padding(horizontal = 16.dp)) {
        Column(Modifier.padding(16.dp)) {
            SectionTitle("年度分类排行", "全年支出与收入，按金额降序")
            Spacer(Modifier.height(12.dp))
            RankBlock("支出", state.yearExpenseRanks, AppColors.expense(), progress)
            Spacer(Modifier.height(14.dp))
            InsetDivider(inset = 0.dp)
            Spacer(Modifier.height(14.dp))
            RankBlock("收入", state.yearIncomeRanks, AppColors.income(), progress)
        }
    }
}

/* ---------------- 私有小件 ---------------- */

@Composable
private fun SectionTitle(title: String, caption: String? = null) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    if (caption != null) {
        Spacer(Modifier.height(2.dp))
        Text(caption, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LegendRow(state: StatsUiState) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        LegendDot(AppColors.expense(), "支出 ${formatMoney(state.expenseTotal, true)}")
        LegendDot(AppColors.income(), "收入 ${formatMoney(state.incomeTotal, true)}")
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DonutColumn(
    title: String,
    total: Long,
    slices: List<PieSlice>,
    color: Color,
    progress: Float,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Box(contentAlignment = Alignment.Center) {
            PieChart(slices = slices, modifier = Modifier.size(110.dp), progress = progress)
            Text(
                formatMoney(total, withSymbol = true),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = color,
            )
        }
        Spacer(Modifier.height(6.dp))
        if (slices.isEmpty()) {
            Text("暂无", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            slices.take(3).forEach { slice ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).background(slice.color, CircleShape))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        slice.label,
                        fontSize = 10.sp,
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun RankBlock(label: String, ranks: List<RankItem>, color: Color, progress: Float) {
    Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = color)
    Spacer(Modifier.height(8.dp))
    if (ranks.isEmpty()) {
        Text("暂无记录", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    ranks.take(6).forEachIndexed { index, item ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${index + 1}",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(18.dp),
            )
            CategoryIcon(icon = item.icon, color = colorForId(item.categoryId), size = 28.dp)
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.name, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(
                        "${item.count}笔",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        formatMoney(item.amount, withSymbol = true),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = color,
                    )
                }
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { item.pct.coerceIn(0f, 1f) * progress },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    strokeCap = StrokeCap.Round,
                    color = colorForId(item.categoryId),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
        }
        if (index != minOf(ranks.size, 6) - 1) Spacer(Modifier.height(9.dp))
    }
}

@Composable
private fun DeltaChip(cur: Long, prev: Long, invert: Boolean) {
    if (prev <= 0L || cur == prev) {
        Text("较上期 —", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val diffPct = (cur - prev).toDouble() / prev * 100.0
    val up = diffPct >= 0
    val good = if (invert) !up else up
    val color = if (good) AppColors.income() else AppColors.expense()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("较上期", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(3.dp))
        Text(if (up) "▲" else "▼", fontSize = 9.sp, color = color)
        Text(
            "${kotlin.math.abs(diffPct).toInt()}%",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = color,
        )
    }
}

@Composable
private fun VHairline() {
    Box(
        Modifier
            .width(0.5.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

@Composable
private fun KpiTile(
    title: String,
    value: String,
    valueColor: Color,
    caption: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(horizontal = 16.dp, vertical = 13.dp)) {
        Text(title, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(5.dp))
        Text(
            value,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.3).sp,
            color = valueColor,
        )
        Spacer(Modifier.height(5.dp))
        caption()
    }
}

@Composable
private fun KpiTiles(
    expense: Long,
    income: Long,
    balance: Long,
    count: Int,
    prevExpense: Long,
    prevIncome: Long,
    prevBalance: Long,
    prevCount: Int,
    yearLabel: String? = null,
) {
    GroupCard(Modifier.padding(horizontal = 16.dp)) {
        Column(Modifier.padding(vertical = 4.dp)) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                KpiTile(
                    if (yearLabel == null) "总支出" else "$yearLabel 支出",
                    formatMoney(expense, true),
                    AppColors.expense(),
                    { DeltaChip(expense, prevExpense, invert = true) },
                    Modifier.weight(1f),
                )
                VHairline()
                KpiTile(
                    if (yearLabel == null) "总收入" else "$yearLabel 收入",
                    formatMoney(income, true),
                    AppColors.income(),
                    { DeltaChip(income, prevIncome, invert = false) },
                    Modifier.weight(1f),
                )
            }
            InsetDivider(inset = 16.dp)
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                KpiTile(
                    "结余",
                    formatMoney(balance, true),
                    if (balance >= 0) MaterialTheme.colorScheme.onSurface else AppColors.expense(),
                    { DeltaChip(balance, prevBalance, invert = false) },
                    Modifier.weight(1f),
                )
                VHairline()
                KpiTile(
                    "笔数",
                    "$count 笔",
                    MaterialTheme.colorScheme.onSurface,
                    { DeltaChip(count.toLong(), prevCount.toLong(), invert = false) },
                    Modifier.weight(1f),
                )
            }
        }
    }
}
