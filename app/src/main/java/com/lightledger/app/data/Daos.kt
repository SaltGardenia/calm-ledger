package com.lightledger.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {

    @Query(
        """
        SELECT a.*, a.initialBalance
            + IFNULL((SELECT SUM(amount) FROM bills b WHERE b.type = 'INCOME'   AND b.accountId = a.id), 0)
            - IFNULL((SELECT SUM(amount) FROM bills b WHERE b.type = 'EXPENSE' AND b.accountId = a.id), 0)
            + IFNULL((SELECT SUM(amount) FROM bills b WHERE b.type = 'TRANSFER' AND b.targetAccountId = a.id), 0)
            - IFNULL((SELECT SUM(amount) FROM bills b WHERE b.type = 'TRANSFER' AND b.accountId = a.id), 0)
            AS balance
        FROM accounts a
        WHERE a.archived = 0
        ORDER BY a.sortOrder, a.id
        """
    )
    fun accountsWithBalance(): Flow<List<AccountWithBalance>>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun byId(id: Long): Account?

    @Query(
        "UPDATE accounts SET lastSyncedAt = :at, lastSyncedBalance = :balance, " +
            "syncedBalance = COALESCE(:balance, syncedBalance), lastSyncedDay = :day WHERE id = :id",
    )
    suspend fun updateSyncState(id: Long, at: Long, balance: Long?, day: Int)

    @Query("SELECT * FROM accounts ORDER BY sortOrder, id")
    suspend fun allOnce(): List<Account>

    @Insert
    suspend fun insert(account: Account): Long

    @Update
    suspend fun update(account: Account)

    @Delete
    suspend fun delete(account: Account)

    @Query("SELECT COUNT(*) FROM bills WHERE accountId = :accountId OR targetAccountId = :accountId")
    suspend fun billCountFor(accountId: Long): Int

    // ---- 备份 / 恢复 ----
    @Query("SELECT * FROM accounts ORDER BY sortOrder, id")
    suspend fun allForBackup(): List<Account>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(accounts: List<Account>)

    @Query("DELETE FROM accounts")
    suspend fun clear()
}

@Dao
interface CategoryDao {

    @Query("SELECT * FROM categories WHERE type = :type ORDER BY sortOrder, id")
    fun byType(type: BillType): Flow<List<Category>>

    @Query("SELECT * FROM categories ORDER BY type, sortOrder, id")
    fun all(): Flow<List<Category>>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun byId(id: Long): Category?

    @Query("SELECT * FROM categories ORDER BY type, sortOrder, id")
    suspend fun allOnce(): List<Category>

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int

    @Insert
    suspend fun insert(category: Category): Long

    @Update
    suspend fun update(category: Category)

    @Delete
    suspend fun delete(category: Category)

    @Query("SELECT COUNT(*) FROM bills WHERE categoryId = :categoryId")
    suspend fun billCountFor(categoryId: Long): Int

    // ---- 备份 / 恢复 ----
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(categories: List<Category>)

    @Query("DELETE FROM categories")
    suspend fun clear()
}

@Dao
interface BillDao {

    @Query("SELECT * FROM bills WHERE day >= :startDay AND day < :endDay ORDER BY date DESC, id DESC")
    fun inDayRange(startDay: Int, endDay: Int): Flow<List<Bill>>

    @Query("SELECT * FROM bills WHERE id = :id")
    suspend fun byId(id: Long): Bill?

    @Query(
        """
        SELECT COALESCE(SUM(CASE WHEN type = 'EXPENSE' THEN amount ELSE 0 END), 0) AS expense,
               COALESCE(SUM(CASE WHEN type = 'INCOME'  THEN amount ELSE 0 END), 0) AS income
        FROM bills WHERE day >= :startDay AND day < :endDay
        """
    )
    fun monthTotals(startDay: Int, endDay: Int): Flow<MonthTotals>

    @Query(
        """
        SELECT categoryId AS categoryId, SUM(amount) AS total, COUNT(*) AS count
        FROM bills
        WHERE type = :type AND day >= :startDay AND day < :endDay
        GROUP BY categoryId
        ORDER BY total DESC
        """
    )
    fun categorySums(type: BillType, startDay: Int, endDay: Int): Flow<List<CategorySum>>

    @Query(
        """
        SELECT day AS day,
               SUM(CASE WHEN type = 'EXPENSE' THEN amount ELSE 0 END) AS expense,
               SUM(CASE WHEN type = 'INCOME'  THEN amount ELSE 0 END) AS income
        FROM bills
        WHERE day >= :startDay AND day < :endDay
        GROUP BY day
        ORDER BY day
        """
    )
    fun daySums(startDay: Int, endDay: Int): Flow<List<DaySum>>

    @Query(
        """
        SELECT COALESCE(SUM(CASE WHEN type = 'EXPENSE' THEN amount ELSE 0 END), 0) AS expense,
               COALESCE(SUM(CASE WHEN type = 'INCOME'  THEN amount ELSE 0 END), 0) AS income,
               COUNT(*) AS count
        FROM bills WHERE day >= :startDay AND day < :endDay
        """
    )
    fun monthStats(startDay: Int, endDay: Int): Flow<MonthStats>

    @Query(
        """
        SELECT * FROM bills
        WHERE type = :type AND day >= :startDay AND day < :endDay
        ORDER BY amount DESC, id ASC
        LIMIT :limit
        """
    )
    fun topBills(type: BillType, startDay: Int, endDay: Int, limit: Int): Flow<List<Bill>>

    @Insert
    suspend fun insert(bill: Bill): Long

    @Update
    suspend fun update(bill: Bill)

    @Delete
    suspend fun delete(bill: Bill)

    @Query("SELECT * FROM bills ORDER BY date")
    suspend fun allForExport(): List<Bill>

    // ---- 批量操作 ----
    @Query("DELETE FROM bills WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    /** 批量改分类（同时改类型，保证账单类型与分类类型一致） */
    @Query("UPDATE bills SET categoryId = :categoryId, type = :type, updatedAt = :now WHERE id IN (:ids)")
    suspend fun moveToCategory(ids: List<Long>, categoryId: Long, type: BillType, now: Long)

    /** 某账户各备注的出现次数（用于跨批次统一商户写法） */
    @Query("SELECT note, COUNT(*) AS count FROM bills WHERE accountId = :accountId AND note <> '' GROUP BY note")
    suspend fun noteCounts(accountId: Long): List<NoteCount>

    @Query("SELECT * FROM bills WHERE accountId = :accountId")
    suspend fun allForAccount(accountId: Long): List<Bill>

    @Query("SELECT * FROM bills WHERE categoryId IS NULL ORDER BY date DESC")
    suspend fun uncategorized(): List<Bill>

    /** 某个账户最新的一笔账单（增量同步的锚点） */
    @Query("SELECT * FROM bills WHERE accountId = :accountId ORDER BY date DESC, id DESC LIMIT 1")
    suspend fun latestForAccount(accountId: Long): Bill?

    /** 已导入过的来源指纹（同步去重） */
    @Query("SELECT sourceKey FROM bills WHERE sourceKey IS NOT NULL AND sourceKey LIKE :prefix || '%'")
    suspend fun sourceKeysWithPrefix(prefix: String): List<String>

    /** 同步导入：遇到相同 sourceKey 直接忽略，返回每行 rowId（-1 = 已存在） */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoringDuplicates(bills: List<Bill>): List<Long>

    // ---- 备份 / 恢复 ----
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(bills: List<Bill>)

    @Query("DELETE FROM bills")
    suspend fun clear()
}

@Dao
interface BudgetDao {

    @Query("SELECT * FROM budgets WHERE month = :month")
    fun byMonth(month: String): Flow<Budget?>

    @Upsert
    suspend fun upsert(budget: Budget)

    @Query("DELETE FROM budgets WHERE month = :month")
    suspend fun delete(month: String)

    // ---- 备份 / 恢复 ----
    @Query("SELECT * FROM budgets")
    suspend fun allOnce(): List<Budget>

    @Upsert
    suspend fun insertAll(budgets: List<Budget>)

    @Query("DELETE FROM budgets")
    suspend fun clear()
}

@Dao
interface SyncRuleDao {

    @Query("SELECT * FROM sync_rules ORDER BY sortOrder, id")
    fun all(): Flow<List<SyncRule>>

    @Query("SELECT * FROM sync_rules ORDER BY sortOrder, id")
    suspend fun allOnce(): List<SyncRule>

    @Query("SELECT COUNT(*) FROM sync_rules")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rule: SyncRule): Long

    @Update
    suspend fun update(rule: SyncRule)

    @Delete
    suspend fun delete(rule: SyncRule)

    // 备份
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rules: List<SyncRule>)

    @Query("DELETE FROM sync_rules")
    suspend fun clear()
}

/** 备注出现次数（商户名归一用） */
data class NoteCount(val note: String, val count: Int)
