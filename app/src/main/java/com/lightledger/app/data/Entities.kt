package com.lightledger.app.data

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 账单类型：支出 / 收入。TRANSFER 仅为兼容旧版本数据保留，新账单不再产生 */
enum class BillType { EXPENSE, INCOME, TRANSFER }

/** 账户归属的「机构」大类：决定账户页的第一层分组 */
enum class AccountGroup {
    CASH, WECHAT, ALIPAY, BANK, OTHER;

    val label: String
        get() = when (this) {
            CASH -> "现金"
            WECHAT -> "微信"
            ALIPAY -> "支付宝"
            BANK -> "银行卡"
            OTHER -> "其他"
        }

    val icon: String
        get() = when (this) {
            CASH -> "💵"
            WECHAT -> "💚"
            ALIPAY -> "💙"
            BANK -> "🏦"
            OTHER -> "🪙"
        }
}

/** 卡类型：储蓄卡（资产）/ 信用卡（负债，余额为负） */
enum class AccountKind {
    DEBIT, CREDIT;

    val label: String
        get() = if (this == DEBIT) "储蓄卡" else "信用卡"
}

/**
 * 账户：两级结构 = 机构（微信 / 支付宝 / 中国银行 …）+ 机构下的具体账户（零钱 / 余额宝 / 卡1）。
 * [institution] 即第一层名称，同一机构的账户在账户页归入同一张卡片。
 */
@Entity(tableName = "accounts")
data class Account(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val icon: String,
    /** 初始净值（分）。储蓄卡为正；信用卡存欠款的负数形式，余额 = 初始 + 收入 - 支出 */
    val initialBalance: Long = 0,
    val sortOrder: Int = 0,
    val archived: Boolean = false,
    @ColumnInfo(name = "accountGroup") val group: AccountGroup = AccountGroup.CASH,
    /** 第一层名称：微信 / 支付宝 / 中国银行 …；现金与其他可为空 */
    val institution: String = "",
    val kind: AccountKind = AccountKind.DEBIT,
    /** 上次同步完成时间（毫秒），0 = 从未同步 */
    val lastSyncedAt: Long = 0L,
    /** 上次同步时最新一笔流水之后的余额（分），用作下次增量同步的锚点 */
    val lastSyncedBalance: Long? = null,
    /** 同步得到的权威余额（分）：账户页直接用它，不再拿期初+收支推算 */
    val syncedBalance: Long? = null,
    /** 锚点那笔流水的 epochDay，余额相同但日期不同时用来消歧 */
    val lastSyncedDay: Int = 0,
)

/**
 * 同步分类规则：流水标题命中 [keyword] 时，导入的分类直接用 [categoryId]。
 * 命中不到的流水保持未分类，交给用户批量处理。
 */
@Entity(
    tableName = "sync_rules",
    indices = [Index(value = ["keyword"], unique = true)],
)
data class SyncRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val keyword: String,
    val categoryId: Long,
    val sortOrder: Int = 0,
)

/** 账户页展示的全名：名字为空时退回机构名 */
val Account.displayName: String
    get() = name.ifBlank { institution.ifBlank { group.label } }

/** 账户名的第二行说明：机构 · 卡种 */
val Account.subtitle: String
    get() = when {
        institution.isBlank() -> group.label
        group == AccountGroup.BANK -> "$institution · ${kind.label}"
        else -> institution
    }

/** 选择器里的单行文案：微信 · 零钱 */
val Account.pickerLabel: String
    get() = if (institution.isBlank()) displayName else "$institution · $displayName"

/** 列表副标题用的短文案：现金不重复，微信显示「微信 · 零钱」 */
val Account.shortLabel: String
    get() = if (institution.isBlank() || institution == displayName) displayName else "$institution · $displayName"

/** 分类：支持通过 parentId 扩展二级分类（随手记风格） */
@Entity(
    tableName = "categories",
    indices = [Index("type"), Index("parentId")],
)
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val icon: String,
    val type: BillType,
    val parentId: Long? = null,
    val sortOrder: Int = 0,
)

/** 账单。金额一律以「分」为单位的 Long 存储，避免浮点误差；day 为 epochDay 便于按日聚合 */
@Entity(
    tableName = "bills",
    indices = [
        Index("day"), Index("accountId"), Index("categoryId"), Index("type"),
        // 同步来源指纹：同一笔账单重复同步不会重复入库（NULL 不参与唯一约束）
        Index(value = ["sourceKey"], unique = true),
    ],
)
data class Bill(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: BillType,
    val amount: Long,
    val categoryId: Long? = null,
    val accountId: Long,
    /** 转账目标账户（仅旧版 TRANSFER 数据使用，新账单不再写入） */
    val targetAccountId: Long? = null,
    /** 账单时间（毫秒时间戳，用于排序与展示） */
    val date: Long,
    /** 账单日期（LocalDate.toEpochDay()，用于按日分组与统计） */
    val day: Int,
    val note: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    /** 外部导入指纹（微信/支付宝流水），手动记账为 null */
    val sourceKey: String? = null,
    /** 流水里这笔之后的余额（分）；同步进来的账才有，用于余额链校验 */
    val balanceAfter: Long? = null,
)

/** 月度预算（key 为 "2026-11" 形式） */
@Entity(tableName = "budgets")
data class Budget(
    @PrimaryKey val month: String,
    val amount: Long,
)

/** 账户 + 实时余额查询结果 */
data class AccountWithBalance(
    @Embedded val account: Account,
    /** 按账单推算的余额（期初 + 收入 - 支出） */
    val balance: Long,
) {
    /** 对外展示的余额：同步过的账户以流水余额为准，没同步过的才用推算值 */
    val effectiveBalance: Long get() = account.syncedBalance ?: balance
}

/** 按分类聚合的统计结果 */
data class CategorySum(
    val categoryId: Long?,
    val total: Long,
    val count: Int,
)

/** 按日聚合的统计结果 */
data class DaySum(
    val day: Int,
    val expense: Long,
    val income: Long,
)

/** 月度支出/收入汇总 */
data class MonthTotals(
    val expense: Long,
    val income: Long,
)

/** 月度统计总览（含笔数，用于 KPI 瓦片与环比） */
data class MonthStats(
    val expense: Long,
    val income: Long,
    val count: Int,
)

/** 资产汇总：总资产 = 储蓄类余额之和 + 信用卡余额（负数）之和 */
data class NetWorth(
    val assets: Long,
    val debts: Long,
) {
    val net: Long get() = assets - debts
}
