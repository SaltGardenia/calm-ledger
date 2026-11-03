package com.lightledger.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lightledger.app.ServiceLocator
import com.lightledger.app.data.BillType
import com.lightledger.app.data.Category
import com.lightledger.app.data.Repository
import com.lightledger.app.data.SyncRule
import com.lightledger.app.ui.AppSegmented
import com.lightledger.app.ui.CategoryIcon
import com.lightledger.app.ui.GroupCard
import com.lightledger.app.ui.InsetDivider
import com.lightledger.app.ui.TopBar
import com.lightledger.app.ui.iosTextFieldColors
import com.lightledger.app.ui.theme.colorForId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SyncRulesUiState(
    val rules: List<SyncRule> = emptyList(),
    val categories: List<Category> = emptyList(),
) {
    fun categoriesOf(type: BillType) = categories.filter { it.type == type }
}

class SyncRulesViewModel(private val repo: Repository) : ViewModel() {

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    val uiState: StateFlow<SyncRulesUiState> =
        combine(repo.syncRules(), repo.allCategories()) { rules, categories ->
            SyncRulesUiState(rules = rules, categories = categories)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncRulesUiState())

    fun save(existing: SyncRule?, keyword: String, categoryId: Long) {
        val word = keyword.trim()
        if (word.isEmpty()) {
            _message.value = "请输入商户关键词"
            return
        }
        viewModelScope.launch {
            repo.saveSyncRule(
                (existing ?: SyncRule(keyword = "", categoryId = categoryId)).copy(
                    keyword = word,
                    categoryId = categoryId,
                ),
            )
            _message.value = "已保存规则：$word"
        }
    }

    fun applyToExisting() {
        viewModelScope.launch {
            val changed = repo.applyRulesToUncategorized()
            _message.value = if (changed > 0) "已按规则归类 $changed 笔账单" else "没有可归类的未分类账单"
        }
    }

    fun canonicalizeNotes() {
        viewModelScope.launch {
            val changed = repo.canonicalizeNotes()
            _message.value = if (changed > 0) "已统一 $changed 笔商户名写法" else "商户名已经是统一写法"
        }
    }

    fun delete(rule: SyncRule) {
        viewModelScope.launch {
            repo.deleteSyncRule(rule)
            _message.value = "已删除规则：${rule.keyword}"
        }
    }

    fun consumeMessage() { _message.value = null }
}

@Composable
fun SyncRulesScreen(
    onBack: () -> Unit,
    vm: SyncRulesViewModel = viewModel { SyncRulesViewModel(ServiceLocator.repository) },
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<SyncRule?>(null) }
    var creating by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar("同步规则", onBack = onBack)
            Text(
                "同步进来的流水会按商户名匹配下面的关键词；命中就用对应分类，没命中就留作未分类。",
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                item(key = "list") {
                    GroupCard {
                        if (state.rules.isEmpty()) {
                            Text(
                                "还没有规则",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                        state.rules.forEachIndexed { index, rule ->
                            val category = state.categories.firstOrNull { it.id == rule.categoryId }
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { editing = rule }
                                    .padding(start = 16.dp, end = 6.dp, top = 9.dp, bottom = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    rule.keyword,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f),
                                )
                                Text("→", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(8.dp))
                                if (category != null) {
                                    CategoryIcon(category.icon, colorForId(category.id), size = 28.dp)
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text(
                                    category?.name ?: "已删除的分类",
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                IconButton(
                                    onClick = { vm.delete(rule) },
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "删除规则",
                                        tint = MaterialTheme.colorScheme.outline,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                            if (index != state.rules.lastIndex) InsetDivider(inset = 2.dp)
                        }
                    }
                }
            }
            TextButton(
                onClick = vm::applyToExisting,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) { Text("把规则应用到已有未分类账单") }
            TextButton(
                onClick = vm::canonicalizeNotes,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) { Text("统一商户名写法（修错别字）") }
            Button(
                onClick = { creating = true },
                modifier = Modifier.fillMaxWidth().padding(16.dp).height(46.dp),
                shape = MaterialTheme.shapes.extraLarge,
            ) { Text("新增规则", fontWeight = FontWeight.SemiBold) }
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
        )
    }

    val target = editing
    if (creating || target != null) {
        RuleEditDialog(
            initialKeyword = target?.keyword.orEmpty(),
            initialCategoryId = target?.categoryId,
            categories = state.categories,
            onDismiss = { creating = false; editing = null },
            onConfirm = { keyword, categoryId ->
                vm.save(target, keyword, categoryId)
                creating = false
                editing = null
            },
        )
    }
}

@Composable
private fun RuleEditDialog(
    initialKeyword: String,
    initialCategoryId: Long?,
    categories: List<Category>,
    onDismiss: () -> Unit,
    onConfirm: (String, Long) -> Unit,
) {
    var keyword by remember { mutableStateOf(initialKeyword) }
    var tab by remember { mutableStateOf(BillType.EXPENSE) }
    var categoryId by remember {
        mutableStateOf(initialCategoryId ?: categories.firstOrNull { it.type == BillType.EXPENSE }?.id)
    }
    val visible = categories
        .filter { it.type == tab }
        .sortedBy { it.sortOrder * 1000 + it.id }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialKeyword.isEmpty()) "新增同步规则" else "编辑规则") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = keyword,
                    onValueChange = { keyword = it.take(12) },
                    label = { Text("商户关键词") },
                    placeholder = { Text("如：京东、拼多多、美团") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    colors = iosTextFieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth(),
                )
                AppSegmented(
                    options = listOf("支出分类", "收入分类"),
                    selectedIndex = if (tab == BillType.EXPENSE) 0 else 1,
                    onSelect = { index ->
                        tab = if (index == 0) BillType.EXPENSE else BillType.INCOME
                        categoryId = categories.firstOrNull { it.type == tab }?.id
                    },
                )
                LazyColumn(Modifier.height(240.dp)) {
                    items(visible.size, key = { visible[it].id }) { index ->
                        val c = visible[index]
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { categoryId = c.id }
                                .background(
                                    if (c.id == categoryId) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                    } else {
                                        androidx.compose.ui.graphics.Color.Transparent
                                    },
                                    RoundedCornerShape(10.dp),
                                )
                                .padding(vertical = 7.dp, horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CategoryIcon(c.icon, colorForId(c.id), size = 30.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(c.name, fontSize = 14.sp)
                            Spacer(Modifier.weight(1f))
                            if (c.id == categoryId) {
                                Text("✓", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = keyword.isNotBlank() && categoryId != null,
                onClick = { categoryId?.let { onConfirm(keyword, it) } },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
