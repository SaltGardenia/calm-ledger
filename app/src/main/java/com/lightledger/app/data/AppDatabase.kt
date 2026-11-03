package com.lightledger.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Converters {
    @TypeConverter
    fun billTypeToString(type: BillType): String = type.name

    @TypeConverter
    fun stringToBillType(value: String): BillType = BillType.valueOf(value)

    @TypeConverter
    fun accountGroupToString(group: AccountGroup): String = group.name

    @TypeConverter
    fun stringToAccountGroup(value: String): AccountGroup =
        runCatching { AccountGroup.valueOf(value) }.getOrDefault(AccountGroup.OTHER)

    @TypeConverter
    fun accountKindToString(kind: AccountKind): String = kind.name

    @TypeConverter
    fun stringToAccountKind(value: String): AccountKind =
        runCatching { AccountKind.valueOf(value) }.getOrDefault(AccountKind.DEBIT)
}

/**
 * v1 → v2：账户升级为「机构 + 具体账户」两级结构。
 *
 * 旧的三条预置账户就地改名（微信→零钱、支付宝→余额、银行卡→卡1），
 * 这样历史账单的 accountId 依然指向正确的账户，不会出现「账户消失、余额归零」。
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE accounts ADD COLUMN accountGroup TEXT NOT NULL DEFAULT 'CASH'")
        db.execSQL("ALTER TABLE accounts ADD COLUMN institution TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE accounts ADD COLUMN kind TEXT NOT NULL DEFAULT 'DEBIT'")

        db.execSQL(
            "UPDATE accounts SET accountGroup = 'WECHAT', institution = '微信', name = '零钱', icon = '💚' " +
                "WHERE name = '微信'",
        )
        db.execSQL(
            "UPDATE accounts SET accountGroup = 'ALIPAY', institution = '支付宝', name = '余额', icon = '💙' " +
                "WHERE name = '支付宝'",
        )
        db.execSQL(
            "UPDATE accounts SET accountGroup = 'BANK', institution = '中国银行', name = '卡1', icon = '💳' " +
                "WHERE name = '银行卡'",
        )
        db.execSQL("UPDATE accounts SET accountGroup = 'CASH', institution = '现金' WHERE name = '现金'")
    }
}

/** v2 → v3：账单增加来源指纹，用于「同步不重不漏」 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE bills ADD COLUMN sourceKey TEXT")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_bills_sourceKey ON bills(sourceKey)")
    }
}

/** v3 → v4：账户同步状态 + 同步分类规则表 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE accounts ADD COLUMN lastSyncedAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE accounts ADD COLUMN lastSyncedBalance INTEGER")
        db.execSQL("ALTER TABLE accounts ADD COLUMN lastSyncedDay INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS sync_rules (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "keyword TEXT NOT NULL, " +
                "categoryId INTEGER NOT NULL, " +
                "sortOrder INTEGER NOT NULL DEFAULT 0)",
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_sync_rules_keyword ON sync_rules(keyword)")
    }
}

/** v4 → v5：账户权威余额 + 账单逐笔余额（余额链校验用） */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE accounts ADD COLUMN syncedBalance INTEGER")
        db.execSQL("ALTER TABLE bills ADD COLUMN balanceAfter INTEGER")
    }
}

@Database(
    entities = [Account::class, Category::class, Bill::class, Budget::class, SyncRule::class],
    version = 5,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun accountDao(): AccountDao
    abstract fun categoryDao(): CategoryDao
    abstract fun billDao(): BillDao
    abstract fun budgetDao(): BudgetDao
    abstract fun syncRuleDao(): SyncRuleDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "lightledger.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
    }
}
