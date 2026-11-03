package com.lightledger.app.data

import org.json.JSONArray
import org.json.JSONObject

/** 备份内容（导入后回传给 UI 做统计提示） */
data class BackupPayload(
    val accounts: List<Account> = emptyList(),
    val categories: List<Category> = emptyList(),
    val bills: List<Bill> = emptyList(),
    val budgets: List<Budget> = emptyList(),
    val exportedAt: Long = 0L,
)

/**
 * 备份文件的编解码。用 android 自带的 org.json，零额外依赖；
 * 所有字段都带默认值，老备份（v1）缺的字段能安全降级。
 */
object BackupCodec {

    const val FORMAT = 2
    const val APP_ID = "bushaoxin.ledger"

    fun encode(
        accounts: List<Account>,
        categories: List<Category>,
        bills: List<Bill>,
        budgets: List<Budget>,
        exportedAt: Long = System.currentTimeMillis(),
    ): String {
        val root = JSONObject()
        root.put("app", APP_ID)
        root.put("format", FORMAT)
        root.put("exportedAt", exportedAt)
        root.put("accounts", JSONArray().apply {
            accounts.forEach { a ->
                put(JSONObject().apply {
                    put("id", a.id)
                    put("name", a.name)
                    put("icon", a.icon)
                    put("initialBalance", a.initialBalance)
                    put("sortOrder", a.sortOrder)
                    put("archived", a.archived)
                    put("group", a.group.name)
                    put("institution", a.institution)
                    put("kind", a.kind.name)
                })
            }
        })
        root.put("categories", JSONArray().apply {
            categories.forEach { c ->
                put(JSONObject().apply {
                    put("id", c.id)
                    put("name", c.name)
                    put("icon", c.icon)
                    put("type", c.type.name)
                    put("parentId", c.parentId ?: JSONObject.NULL)
                    put("sortOrder", c.sortOrder)
                })
            }
        })
        root.put("bills", JSONArray().apply {
            bills.forEach { b ->
                put(JSONObject().apply {
                    put("id", b.id)
                    put("type", b.type.name)
                    put("amount", b.amount)
                    put("categoryId", b.categoryId ?: JSONObject.NULL)
                    put("accountId", b.accountId)
                    put("targetAccountId", b.targetAccountId ?: JSONObject.NULL)
                    put("date", b.date)
                    put("day", b.day)
                    put("note", b.note)
                    put("createdAt", b.createdAt)
                    put("updatedAt", b.updatedAt)
                })
            }
        })
        root.put("budgets", JSONArray().apply {
            budgets.forEach { b ->
                put(JSONObject().apply {
                    put("month", b.month)
                    put("amount", b.amount)
                })
            }
        })
        return root.toString()
    }

    fun decode(text: String): BackupPayload {
        val root = JSONObject(text)
        val app = root.optString("app")
        require(app.isEmpty() || app == APP_ID) { "这不是「不烧心记账」的备份文件" }

        val accounts = root.optJSONArray("accounts").mapAccounts { o ->
            Account(
                id = o.optLong("id"),
                name = o.optString("name"),
                icon = o.optString("icon", "💳"),
                initialBalance = o.optLong("initialBalance"),
                sortOrder = o.optInt("sortOrder"),
                archived = o.optBoolean("archived"),
                group = o.optString("group")
                    .let { runCatching { AccountGroup.valueOf(it) }.getOrNull() }
                    ?: guessGroup(o.optString("name")),
                institution = o.optString("institution")
                    .ifBlank { guessInstitution(o.optString("name")) },
                kind = o.optString("kind")
                    .let { runCatching { AccountKind.valueOf(it) }.getOrNull() }
                    ?: AccountKind.DEBIT,
            )
        }
        val categories = root.optJSONArray("categories").mapCategories { o ->
            Category(
                id = o.optLong("id"),
                name = o.optString("name"),
                icon = o.optString("icon", "🧾"),
                type = o.optString("type")
                    .let { runCatching { BillType.valueOf(it) }.getOrNull() }
                    ?: BillType.EXPENSE,
                parentId = o.optLongOrNull("parentId"),
                sortOrder = o.optInt("sortOrder"),
            )
        }
        val bills = root.optJSONArray("bills").mapBills { o ->
            Bill(
                id = o.optLong("id"),
                type = o.optString("type")
                    .let { runCatching { BillType.valueOf(it) }.getOrNull() }
                    ?: BillType.EXPENSE,
                amount = o.optLong("amount"),
                categoryId = o.optLongOrNull("categoryId"),
                accountId = o.optLong("accountId"),
                targetAccountId = o.optLongOrNull("targetAccountId"),
                date = o.optLong("date"),
                day = o.optInt("day"),
                note = o.optString("note"),
                createdAt = o.optLong("createdAt"),
                updatedAt = o.optLong("updatedAt"),
            )
        }
        val budgets = root.optJSONArray("budgets").mapBudgets { o ->
            Budget(month = o.optString("month"), amount = o.optLong("amount"))
        }
        require(accounts.isNotEmpty() || bills.isNotEmpty()) { "备份文件里没有账户或账单" }
        return BackupPayload(
            accounts = accounts,
            categories = categories,
            bills = bills,
            budgets = budgets,
            exportedAt = root.optLong("exportedAt"),
        )
    }

    /** 老版本没有 group 字段：按账户名猜一个，保证导入后分组不塌成一类 */
    private fun guessGroup(name: String): AccountGroup = when {
        name.contains("微信") || name.contains("零钱") -> AccountGroup.WECHAT
        name.contains("支付宝") || name.contains("余额宝") -> AccountGroup.ALIPAY
        name.contains("银行") || name.contains("卡") -> AccountGroup.BANK
        name.contains("现金") -> AccountGroup.CASH
        else -> AccountGroup.OTHER
    }

    private fun guessInstitution(name: String): String = when (guessGroup(name)) {
        AccountGroup.WECHAT -> "微信"
        AccountGroup.ALIPAY -> "支付宝"
        AccountGroup.BANK -> if (name.contains("中国银行")) "中国银行" else "银行卡"
        AccountGroup.CASH -> "现金"
        AccountGroup.OTHER -> ""
    }

    private fun JSONArray?.mapAccounts(block: (JSONObject) -> Account): List<Account> =
        if (this == null) emptyList() else List(length()) { block(getJSONObject(it)) }

    private fun JSONArray?.mapCategories(block: (JSONObject) -> Category): List<Category> =
        if (this == null) emptyList() else List(length()) { block(getJSONObject(it)) }

    private fun JSONArray?.mapBills(block: (JSONObject) -> Bill): List<Bill> =
        if (this == null) emptyList() else List(length()) { block(getJSONObject(it)) }

    private fun JSONArray?.mapBudgets(block: (JSONObject) -> Budget): List<Budget> =
        if (this == null) emptyList() else List(length()) { block(getJSONObject(it)) }

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (isNull(key) || !has(key)) null else optLong(key)
}
