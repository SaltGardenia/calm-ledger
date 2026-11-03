package com.lightledger.app.data

import com.lightledger.app.sync.MerchantText
import com.lightledger.app.sync.ParsedBill
import com.lightledger.app.sync.fingerprint
import com.lightledger.app.sync.key
import com.lightledger.app.util.csvField
import com.lightledger.app.util.endDayExclusive
import com.lightledger.app.util.startDay
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 数据仓库：屏蔽 DAO 细节，为 ViewModel 提供面向业务的接口。
 * 所有写操作均为挂起函数；所有查询返回 Flow 以便 UI 自动刷新。
 */
class Repository(db: AppDatabase) {

    private val accountDao = db.accountDao()
    private val categoryDao = db.categoryDao()
    private val billDao = db.billDao()
    private val budgetDao = db.budgetDao()
    private val syncRuleDao = db.syncRuleDao()

    // ---------- 初始化 ----------

    /** 首次启动时预置默认分类与两级账户（对标随手记/钱迹的出厂分类） */
    suspend fun ensureSeeded() {
        if (categoryDao.count() > 0) return
        val expenseCategories = listOf(
            "餐饮" to "🍜", "购物" to "🛍️", "交通" to "🚌", "娱乐" to "🎮",
            "居家" to "🏠", "医疗" to "💊", "通讯" to "📱", "服饰" to "👔",
            "学习" to "📚", "运动" to "⚽", "宠物" to "🐱", "旅行" to "✈️",
            "人情" to "🎁", "其他" to "🧾",
        )
        val incomeCategories = listOf(
            "工资" to "💰", "奖金" to "🎉", "理财" to "📈", "兼职" to "💼",
            "红包" to "🧧", "退款" to "↩️", "其他" to "🪙",
        )
        expenseCategories.forEachIndexed { i, (name, icon) ->
            categoryDao.insert(Category(name = name, icon = icon, type = BillType.EXPENSE, sortOrder = i))
        }
        incomeCategories.forEachIndexed { i, (name, icon) ->
            categoryDao.insert(Category(name = name, icon = icon, type = BillType.INCOME, sortOrder = i))
        }

        // 两级账户：机构 → 具体账户
        val accounts = listOf(
            Account(name = "现金", icon = "💵", group = AccountGroup.CASH, institution = "现金", sortOrder = 0),
            Account(name = "零钱", icon = "💚", group = AccountGroup.WECHAT, institution = "微信", sortOrder = 10),
            Account(name = "零钱通", icon = "💚", group = AccountGroup.WECHAT, institution = "微信", sortOrder = 11),
            Account(name = "余额", icon = "💙", group = AccountGroup.ALIPAY, institution = "支付宝", sortOrder = 20),
            Account(name = "余额宝", icon = "💙", group = AccountGroup.ALIPAY, institution = "支付宝", sortOrder = 21),
            Account(name = "卡1", icon = "💳", group = AccountGroup.BANK, institution = "中国银行", sortOrder = 30),
        )
        accounts.forEach { accountDao.insert(it) }
    }

    // ---------- 账户 ----------

    fun accountsWithBalance(): Flow<List<AccountWithBalance>> = accountDao.accountsWithBalance()

    suspend fun account(id: Long): Account? = accountDao.byId(id)

    suspend fun saveAccount(account: Account): Long =
        if (account.id == 0L) {
            accountDao.insert(account)
        } else {
            accountDao.update(account)
            account.id
        }

    /** 有账单关联的账户不允许删除，返回 false 由 UI 提示 */
    suspend fun deleteAccount(account: Account): Boolean {
        if (accountDao.billCountFor(account.id) > 0) return false
        accountDao.delete(account)
        return true
    }

    /** 记录一次同步完成：时间 + 余额锚点（下次增量同步用） */
    suspend fun markAccountSynced(accountId: Long, balance: Long?, day: Int) {
        accountDao.updateSyncState(
            id = accountId,
            at = System.currentTimeMillis(),
            balance = balance,
            day = day,
        )
    }

    /** 同一机构下已有几个账户（用于「卡1 / 卡2」默认命名） */
    suspend fun countInInstitution(institution: String): Int =
        accountDao.allOnce().count { it.institution == institution }

    // ---------- 分类 ----------

    fun categories(type: BillType): Flow<List<Category>> = categoryDao.byType(type)

    fun allCategories(): Flow<List<Category>> = categoryDao.all()

    suspend fun category(id: Long): Category? = categoryDao.byId(id)

    suspend fun saveCategory(category: Category): Long =
        if (category.id == 0L) {
            categoryDao.insert(category)
        } else {
            categoryDao.update(category)
            category.id
        }

    suspend fun deleteCategory(category: Category): Boolean {
        if (categoryDao.billCountFor(category.id) > 0) return false
        categoryDao.delete(category)
        return true
    }

    // ---------- 同步分类规则 ----------

    fun syncRules(): Flow<List<SyncRule>> = syncRuleDao.all()

    suspend fun allSyncRules(): List<SyncRule> = syncRuleDao.allOnce()

    suspend fun saveSyncRule(rule: SyncRule): Long =
        if (rule.id == 0L) syncRuleDao.insert(rule) else {
            syncRuleDao.update(rule)
            rule.id
        }

    suspend fun deleteSyncRule(rule: SyncRule) = syncRuleDao.delete(rule)

    /**
     * 统一历史备注写法：把同一账户里「只差一个字」的商户名归到出现最多的写法，
     * 并做一次词典纠错。返回被改写的笔数。
     */
    suspend fun canonicalizeNotes(): Int {
        var changed = 0
        accountDao.allOnce().forEach { account ->
            val bills = billDao.allForAccount(account.id).filter { it.note.isNotBlank() }
            if (bills.isEmpty()) return@forEach

            // 第一遍：清洗噪声 + 形近字/繁简纠正（确保每个字符串都过一遍字符表）
            val cleaned = bills.map { it to MerchantText.clean(it.note) }

            // 第二遍：同账户内「只差一个字」的写法做多数投票
            val counts = cleaned.groupingBy { it.second }.eachCount()
            cleaned.forEach { (bill, text) ->
                val better = if (text.length >= 3) {
                    counts.entries
                        .filter { (candidate, count) ->
                            candidate != text &&
                                count > (counts[text] ?: 0) &&
                                kotlin.math.abs(candidate.length - text.length) <= 1 &&
                                editDistance(candidate, text) <= 1
                        }
                        .maxByOrNull { it.value }
                        ?.key
                } else {
                    null
                }
                val fixed = better ?: text
                if (fixed != bill.note) {
                    android.util.Log.d("NoteFix", "acct=${account.id} '${bill.note}' -> '$fixed'")
                    billDao.update(bill.copy(note = fixed, updatedAt = System.currentTimeMillis()))
                    changed++
                }
            }
        }
        return changed
    }

    private fun editDistance(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        a.forEachIndexed { i, ca ->
            var prev = dp[0]
            dp[0] = i + 1
            b.forEachIndexed { j, cb ->
                val temp = dp[j + 1]
                dp[j + 1] = minOf(dp[j + 1] + 1, dp[j] + 1, prev + if (ca == cb) 0 else 1)
                prev = temp
            }
        }
        return dp[b.length]
    }

    /** 把规则应用到所有未分类账单，返回命中笔数 */
    suspend fun applyRulesToUncategorized(): Int {
        val rules = syncRuleDao.allOnce()
        if (rules.isEmpty()) return 0
        var changed = 0
        billDao.uncategorized().forEach { bill ->
            val rule = rules.firstOrNull { bill.note.contains(it.keyword, ignoreCase = true) }
            if (rule != null) {
                billDao.update(bill.copy(categoryId = rule.categoryId, updatedAt = System.currentTimeMillis()))
                changed++
            }
        }
        return changed
    }

    /** 首次启用同步时给几条最常见的商户规则，用户可改可删 */
    suspend fun ensureDefaultSyncRules() {
        if (syncRuleDao.count() > 0) return
        val cats = categoryDao.allOnce().filter { it.type == BillType.EXPENSE }
        fun cat(vararg names: String): Long? = cats.firstOrNull { it.name in names }?.id
        val shopping = cat("购物")
        val food = cat("餐饮")
        val transport = cat("交通")
        val defaults = listOfNotNull(
            shopping?.let { "京东" to it },
            shopping?.let { "淘宝" to it },
            shopping?.let { "拼多多" to it },
            food?.let { "美团" to it },
            food?.let { "饿了么" to it },
            food?.let { "瑞幸" to it },
            transport?.let { "滴滴" to it },
            transport?.let { "12306" to it },
        )
        defaults.forEachIndexed { i, (keyword, catId) ->
            syncRuleDao.insert(SyncRule(keyword = keyword, categoryId = catId, sortOrder = i))
        }
    }

    // ---------- 账单 ----------

    fun billsInMonth(month: YearMonth): Flow<List<Bill>> =
        billDao.inDayRange(month.startDay(), month.endDayExclusive())

    fun billsInYear(year: Int): Flow<List<Bill>> =
        billDao.inDayRange(
            LocalDate.of(year, 1, 1).toEpochDay().toInt(),
            LocalDate.of(year + 1, 1, 1).toEpochDay().toInt(),
        )

    suspend fun bill(id: Long): Bill? = billDao.byId(id)

    suspend fun latestBill(accountId: Long): Bill? = billDao.latestForAccount(accountId)

    suspend fun saveBill(bill: Bill): Long =
        if (bill.id == 0L) {
            val now = System.currentTimeMillis()
            billDao.insert(bill.copy(createdAt = now, updatedAt = now))
        } else {
            billDao.update(bill.copy(updatedAt = System.currentTimeMillis()))
            bill.id
        }

    suspend fun deleteBill(bill: Bill) = billDao.delete(bill)

    suspend fun deleteBills(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        billDao.deleteByIds(ids.toList())
    }

    /** 批量改分类：账单类型跟随分类类型（支出分类→支出账单） */
    suspend fun moveBillsToCategory(ids: Collection<Long>, category: Category) {
        if (ids.isEmpty()) return
        billDao.moveToCategory(ids.toList(), category.id, category.type, System.currentTimeMillis())
    }

    fun monthTotals(month: YearMonth): Flow<MonthTotals> =
        billDao.monthTotals(month.startDay(), month.endDayExclusive())

    fun categorySums(month: YearMonth, type: BillType): Flow<List<CategorySum>> =
        billDao.categorySums(type, month.startDay(), month.endDayExclusive())

    fun daySums(month: YearMonth): Flow<List<DaySum>> =
        billDao.daySums(month.startDay(), month.endDayExclusive())

    fun monthStats(month: YearMonth): Flow<MonthStats> =
        billDao.monthStats(month.startDay(), month.endDayExclusive())

    /** 单笔之最：本月某类型金额最大的前 N 笔 */
    fun topBills(month: YearMonth, type: BillType, limit: Int = 5): Flow<List<Bill>> =
        billDao.topBills(type, month.startDay(), month.endDayExclusive(), limit)

    suspend fun allBillsForExport(): List<Bill> = billDao.allForExport()

    /** 导出 CSV（带 BOM，Excel 直接打开不乱码） */
    suspend fun buildCsvExport(): String {
        val bills = billDao.allForExport()
        val cats = categoryDao.allOnce().associateBy { it.id }
        val accs = accountDao.allOnce().associateBy { it.id }
        val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        val sb = StringBuilder("日期,类型,金额(元),分类,账户,备注\n")
        bills.forEach { b ->
            val typeStr = when (b.type) {
                BillType.EXPENSE -> "支出"
                BillType.INCOME -> "收入"
                BillType.TRANSFER -> "转账"
            }
            val categoryStr = if (b.type == BillType.TRANSFER) "转账" else cats[b.categoryId]?.name ?: ""
            val accountStr = if (b.type == BillType.TRANSFER) {
                "${accs[b.accountId]?.subtitle ?: "?"}-${accs[b.accountId]?.displayName ?: "?"}->" +
                    "${accs[b.targetAccountId]?.displayName ?: "?"}"
            } else {
                accs[b.accountId]?.let { "${it.subtitle}-${it.displayName}" } ?: ""
            }
            val dateStr = LocalDateTime.ofInstant(Instant.ofEpochMilli(b.date), ZoneId.systemDefault()).format(dateFmt)
            sb.append(csvField(dateStr)).append(',')
                .append(csvField(typeStr)).append(',')
                .append(csvField(String.format("%.2f", b.amount / 100.0))).append(',')
                .append(csvField(categoryStr)).append(',')
                .append(csvField(accountStr)).append(',')
                .append(csvField(b.note)).append('\n')
        }
        return "\uFEFF$sb"
    }

    // ---------- 微信/支付宝流水同步导入 ----------

    /**
     * 写入同步识别出的账单。
     *
     * 去重规则：[source] + 账户 + 日期 + 时间 + 收支 + 金额 + 标题 作为指纹；
     * 同一指纹在流水里出现多次（同一天同一商户同金额）时，按出现次序补 #1/#2，
     * 保证「重复同步同一月份不会翻倍」，同时「真的有两笔」也不会被吃掉。
     * 分类一律留空（未分类），交给用户在明细页批量归类。
     */
    suspend fun importSyncedBills(
        accountId: Long,
        bills: List<ParsedBill>,
        source: String,
    ): SyncImportResult {
        if (bills.isEmpty()) return SyncImportResult(added = 0, duplicates = 0)

        // 1) 全额退款：直接丢弃
        // 2) 退款行：并入对应的消费，扣减金额，不产生收入
        val cleaned = netRefunds(bills.filter { !it.fullyRefunded })
        // 商户名纠错：清洗尾部噪声 + 已知商户词典 + 同批/历史多数投票
        val priorCounts = billDao.noteCounts(accountId).associate { it.note to it.count }
        val titleMap = MerchantText.normalizeBatch(cleaned.map { it.title }, priorCounts)
        val rules = syncRuleDao.allOnce()
        fun categoryFor(title: String): Long? =
            rules.firstOrNull { title.contains(it.keyword, ignoreCase = true) }?.categoryId
        val prefix = "$source:$accountId:"
        val existing = billDao.sourceKeysWithPrefix(prefix).toHashSet()
        val now = System.currentTimeMillis()
        val occurrences = HashMap<String, Int>()
        val toInsert = ArrayList<Bill>()
        var duplicates = 0

        cleaned.sortedWith(compareBy({ it.date }, { it.time })).forEach { parsed ->
            val title = titleMap[parsed.title] ?: parsed.title
            val base = prefix + parsed.fingerprint()
            val seen = occurrences[base] ?: 0
            occurrences[base] = seen + 1
            val key = if (seen == 0) base else "$base#$seen"
            if (key in existing) {
                duplicates++
                return@forEach
            }
            val time = parsed.time ?: LocalTime.of(12, 0)
            toInsert += Bill(
                // 账户内部划转只影响余额，不计入收支统计
                type = if (parsed.isTransfer) BillType.TRANSFER else parsed.type,
                amount = parsed.amount,
                categoryId = if (parsed.isTransfer) null else categoryFor(title),
                accountId = accountId,
                date = parsed.date.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                day = parsed.date.toEpochDay().toInt(),
                note = title,
                createdAt = now,
                updatedAt = now,
                sourceKey = key,
                balanceAfter = parsed.balanceAfter,
            )
        }

        if (toInsert.isEmpty()) return SyncImportResult(added = 0, duplicates = duplicates)
        val ids = billDao.insertIgnoringDuplicates(toInsert)
        val added = ids.count { it != -1L }
        return SyncImportResult(added = added, duplicates = duplicates + (toInsert.size - added))
    }

    /**
     * 退款合并：微信零钱明细里「部分退款」是原消费 + 一条退款流水。
     * 这里把退款金额从对应消费里扣掉，只保留净额，不再生成一笔收入。
     * 找不到对应消费的退款行（例如原消费在更早的同步里）直接丢弃，避免虚增收入。
     */
    private fun netRefunds(bills: List<ParsedBill>): List<ParsedBill> {
        val refunds = bills.filter { it.isRefund }.sortedBy { it.date }
        if (refunds.isEmpty()) return bills

        val result = bills.toMutableList()
        val used = HashSet<Int>()

        refunds.forEach { refund ->
            val candidates = result.indices.filter { i ->
                i !in used &&
                    !result[i].isRefund &&
                    result[i].type == BillType.EXPENSE &&
                    !result[i].date.isAfter(refund.date) &&
                    !result[i].date.isBefore(refund.date.minusDays(30)) &&
                    result[i].amount >= refund.amount
            }
            // 优先商户名相近的，其次日期最近的
            val best = candidates.minWithOrNull(
                compareBy(
                    { result[it].date.toEpochDay() * -1 },
                    { result[it].amount },
                ),
            ) ?: return@forEach
            used += best
            val net = result[best].amount - refund.amount
            result[best] = if (net > 0) result[best].copy(amount = net) else result[best].copy(amount = 0)
        }

        return result.filter { !it.isRefund && it.amount > 0 }
    }

    // ---------- 完整备份 / 迁移 ----------

    /** 导出全量 JSON：账户、分类、账单、预算 */
    suspend fun buildBackupJson(): String = BackupCodec.encode(
        accounts = accountDao.allForBackup(),
        categories = categoryDao.allOnce(),
        bills = billDao.allForExport(),
        budgets = budgetDao.allOnce(),
    )

    /**
     * 导入全量 JSON（换机迁移）。采用「整体替换」语义：
     * 先清空四张表，再写入备份内容并校正自增序列，避免 id 冲突。
     */
    suspend fun restoreBackupJson(text: String): BackupPayload {
        val payload = BackupCodec.decode(text)
        billDao.clear()
        accountDao.clear()
        categoryDao.clear()
        budgetDao.clear()
        if (payload.categories.isNotEmpty()) categoryDao.insertAll(payload.categories)
        if (payload.accounts.isNotEmpty()) accountDao.insertAll(payload.accounts)
        if (payload.bills.isNotEmpty()) billDao.insertAll(payload.bills)
        if (payload.budgets.isNotEmpty()) budgetDao.insertAll(payload.budgets)
        return payload
    }

    // ---------- 预算 ----------

    fun budget(month: String): Flow<Budget?> = budgetDao.byMonth(month)

    suspend fun setBudget(month: String, cents: Long) {
        if (cents <= 0) budgetDao.delete(month) else budgetDao.upsert(Budget(month, cents))
    }
}

/** 同步导入结果：新增 / 已存在（去重命中） */
data class SyncImportResult(
    val added: Int,
    val duplicates: Int,
)
