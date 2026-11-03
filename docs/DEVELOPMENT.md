# 二次开发指南

## 一、数据模型

四张表，金额一律 `Long`（**分**），杜绝浮点误差：

```
Account  账户   id / name / icon(emoji) / initialBalance / sortOrder
Category 分类   id / name / icon / type(EXPENSE|INCOME) / parentId(预留二级分类) / sortOrder
Bill     账单   id / type(EXPENSE|INCOME|TRANSFER) / amount / categoryId / accountId
                / targetAccountId(转账对方) / date(毫秒) / day(epochDay,按日聚合键) / note
Budget   预算   month("2026-11" 主键) / amount
```

要点：

- `Bill.day` 是 `LocalDate.toEpochDay()`，所有"按日分组/月度统计/每日趋势"都走 `day` 索引区间查询，别用 `date` 做聚合。
- 余额不落库，`AccountDao.accountsWithBalance()` 用一条 SQL 实时算：`期初 + 收入 - 支出 + 转入 - 转出`。任何账单变动（增删改）Room 自动触发刷新。
- 数据库版本迁移：目前 `version = 1, exportSchema = false`。加字段时把版本 +1 并写 Migration，或开发期直接卸载重装。

## 二、加一个新页面的套路

1. `data/Repository.kt` 加业务方法（返回 `Flow` 的查询 + 挂起写操作）；
2. 新建 `ui/xxx/XxxScreen.kt` + `XxxViewModel`：
   ```kotlin
   class XxxViewModel(private val repo: Repository) : ViewModel() {
       val uiState = repo.someFlow()
           .map { ... }
           .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 初始值)
   }

   @Composable
   fun XxxScreen(onBack: () -> Unit,
       vm: XxxViewModel = viewModel { XxxViewModel(ServiceLocator.repository) }) {
       val state by vm.uiState.collectAsStateWithLifecycle()
       ...
   }
   ```
3. `ui/AppNav.kt` 的 `Routes` 加路由 + `NavHost { composable(...) }` 加目的地；
4. 从 `MainScreen` 或 `SettingsScreen` 挂入口。

依赖注入走 `ServiceLocator`（`App.kt`），有意不用 Hilt——单模块应用里它只增加二开者的理解成本。

## 三、常见扩展点（路线图）

| 扩展 | 建议改法 |
|---|---|
| **多账本**（随手记核心） | 加 `Ledger` 表；`Bill/Account/Category` 加 `ledgerId` 列；BillsViewModel 的 Flow 全部加一个 `ledgerId` 参数；顶部加账本切换器 |
| **循环记账**（房贷/房租） | 加 `RecurringRule` 表（周期、下次执行日）；`WorkManager` 每日检查到期规则自动 `saveBill` |
| **二级分类** | `Category.parentId` 已预留；`AddBillScreen` 的九宫格选中一级分类后弹出二级横滑条 |
| **导入钱迹 CSV** | 参照 `Repository.buildCsvExport()` 反向解析：列映射 `分类→Category(不存在则自动建)`、`账户→Account`；入口放 `SettingsScreen` |
| **搜索** | `BillDao` 加 `WHERE note LIKE '%'||:kw||'%'` 查询；明细页顶栏加搜索图标 |
| **Widget / 快捷磁贴** | `Bill` 直接插入今天+默认分类+0 金额，跳转记一笔预填 |

## 四、构建环境备忘

- JDK 17：`C:\Users\icepa\tools\jdk\jdk-17.0.20.1+1`
- Android SDK：`C:\Users\icepa\AppData\Local\Android\Sdk`（platform-35 + build-tools 34/35）
- Gradle 8.9 发行版：`C:\Users\icepa\tools\gradle\gradle-8.9`
- `local.properties` 已写 `sdk.dir`（gitignore，换机器要重写）
- 国内网络：`settings.gradle.kts` 里 google()/mavenCentral() 失败时自动落到阿里云镜像；Gradle 发行版走腾讯镜像

命令行构建：

```bash
export JAVA_HOME=/c/Users/icepa/tools/jdk/jdk-17.0.20.1+1
./gradlew :app:assembleDebug
```
