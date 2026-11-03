# 开源记账 App 源码研究笔记

> 本仓库源码已下载到 `reference/`（gitignore，仅作学习阅读用）。以下为实际阅读源码后与轻记账设计的对照结论。

## TallyApp（xiaojinzi123/TallyApp，Compose + Room 组件化）

实际考察文件：

- `app-tally/module-datasource/src/main/java/com/xiaojinzi/tally/datasource/db/` —— `TallyBillDao / TallyAccountDao / TallyBookDao / TallyCategoryDao / TallyBillStatDao` 等
- `app-tally/module-base/.../service/datasource/` —— `TallyBillService / TallyBookService / TallyBudgetService`（Service DTO 层）

**可借鉴点**：
1. **Book（账本）是独立实体**，Bill 挂在 Book 下 —— 随手记式多账本的核心。轻记账 v1 未做，`docs/DEVELOPMENT.md` 路线图已给出扩展路径。
2. **统计层独立成 Dao**（`TallyBillStatDao` 聚合查询与明细查询分离）。轻记账把聚合查询直接放在 `BillDao`（`monthTotals / categorySums / daySums`），单模块规模下更简单，量大后可拆分。
3. 分层较重（Router/Service/ViewModel 多模块），适合大团队；轻记账有意采用单模块 + ServiceLocator，降低二开门槛。

**不同点**：TallyApp 金额在部分 DTO 里用 `Float`，轻记账统一用 `Long` 分，从数据模型根上规避浮点误差。

## GrainLedger 余粮（HuangZhuoRui/GrainLedger）

实际考察文件：`app/src/main/java/com/vincent/grainledger/data/local/GrainLedgerDatabase.kt`

**表结构要点**（手写 SQL 建表）：
- `budget_categories(id, name, icon, color_value, sort_order, is_income)` —— 与轻记账 `categories` 字段几乎一一对应（我们的 `type` = 它的 `is_income`），设计得到交叉验证。
- `budget_items(year, month, category_name, item_name, unit_price, quantity, total_price, actual_allocated)` —— **信封式预算**：预算按「分类×月份」逐项分配，而不是总额。

**借鉴方向**：轻记账 v1 的预算是"月度总预算"（对标鲨鱼记账），若要升级为信封式，可参考其 `budget_items` 结构按分类拆分（见 DEVELOPMENT.md）。

## Ivy Wallet / Money Manager Ex（未下载源码，基于公开文档与发布说明）

- Ivy Wallet：Compose 架构标杆，但仓库已停止维护、体量大，不适合作为二开基座。
- MMEx Android：复式记账模型（double-entry），对个人流水场景偏重；轻记账取其"账户余额实时推导"思想（余额永远由账单计算，不落库）。

## 结论：轻记账 v1 的设计取舍

| 决策 | 依据 |
|---|---|
| 金额 Long 分存储 | 规避 TallyApp 存在的 Float 精度隐患 |
| Bill.day(epochDay) 索引 | 按日分组/月度统计是钱迹式流水的高频查询 |
| 余额不落库实时 SQL 计算 | 任何账单变动自动同步账户页，杜绝不一致 |
| 无 Hilt、单模块 | 二开门槛最低；需要时再演进 |
| 分类/账户 emoji 图标 | 零资源依赖，与三款对标产品的视觉惯例兼容 |
