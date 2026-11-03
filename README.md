# 不烧心记账 · Calm Ledger

![License](https://img.shields.io/badge/License-GPL--3.0-blue)
![Platform](https://img.shields.io/badge/Platform-Android-green)
![UI](https://img.shields.io/badge/UI-Jetpack%20Compose-orange)

一款 **本地优先、不联网、完全开源** 的 Android 记账应用。「不烧心」取意记账这件事不该让你焦虑——
钱花在哪、还剩多少，打开就知道，剩下的交给自动同步。功能形态对标「随手记 / 鲨鱼记账 / 钱迹」，数据模型与架构学习自 GitHub 上的优秀开源记账项目，全部代码为独立实现。

> ⚠️ **合规声明**：本项目**没有**反编译或使用随手记、鲨鱼记账、钱迹等闭源商业应用的任何代码、资源或接口。所谓"复现"仅指按公开可见的产品功能形态进行独立实现。商业应用的 APK 逆向属于侵权行为，请勿尝试。

## 功能清单（对标说明）

| 功能 | 对标产品 | 状态 |
|---|---|---|
| 3 秒记一笔：分类九宫格 + 自定义数字键盘 + 「再记」连续记账 | 鲨鱼记账 | ✅ |
| 支出 / 收入 两类流水（记账界面不提供转账） | 随手记 | ✅ |
| 日维度流水页：按日分组、每日小计、月度收支汇总、月份切换 | 钱迹 | ✅ |
| 统计：**月视图 / 年视图**两页，每张图同时呈现收入与支出（双线、双柱、双环） | 随手记 / 鲨鱼记账 | ✅ |
| **两级账户**：微信→零钱/零钱通、支付宝→余额/余额宝、银行→卡1/卡2，储蓄卡与信用卡 | 随手记 | ✅ |
| **账单自动同步**：选月份 → 自动打开微信零钱明细 → 逐屏 OCR → 去重导入（标记未分类） | — | ✅ |
| 信用卡按负债计入总资产（总资产 = 资产 − 负债），每张卡可自定义名称 | 随手记 | ✅ |
| 月度预算：进度条、剩余额度、日均可用 | 鲨鱼记账 | ✅ |
| 分类管理：增删改、emoji 图标、有账单的分类禁止删除 | 钱迹 | ✅ |
| 账单导出 CSV（带 BOM，Excel 直接打开不乱码） | 钱迹 | ✅ |
| 完整备份导出 / 导入（JSON，换机手动迁移，不联网） | 钱迹 | ✅ |
| 明细多选：批量删除、批量改分类；左滑呼出删除按钮 | 钱迹 | ✅ |
| 消费日历格内直接显示当日支出 / 收入金额 | 随手记 | ✅ |
| 深色模式 | — | ✅ |
| 支付宝 / 银行账单同步（页面结构不同，需逐个适配） | — | 🚧 路线图 |
| 多账本、循环记账、二级分类、云同步、照片凭证 | 随手记 | 🚧 路线图 |

## 设计语言

UI 遵循 **Material Design 3 (Material You)** 语义设计系统，并保留「大金额数字」作为全 App 唯一的表达性时刻（M3 Expressive 克制原则）：

- **语义 token**：屏幕层只引用 `MaterialTheme.colorScheme` 语义角色（surfaceContainer 分层容器、onSurfaceVariant 次级文字、outlineVariant 分隔线），不写散装色值；绿色种子生成完整明暗双色板，品牌绿固定（不随动态取色漂移，因为收支语义色需要稳定）
- **记账域语义层**：收入 = `primary` 绿、支出 = `error` 红（`AppColors`），金额同时带 +/- 前缀，不单靠颜色传达
- **M3 字阶**：displayLarge（记一笔金额）/ displaySmall（总资产）是仅有的强调字级，其余走 Title/Body/Label 语义层级
- **形状阶梯**：extraSmall 4 / small 8 / medium 12 / large 16 / extraLarge 28（大按钮近胶囊），同一角色同一形状
- **液态玻璃（Liquid Glass）**：全站容器都是真实「玻璃」——卡片、导航条、数字键盘统一走一条渲染管线：AGSL 边缘折射 + 高斯模糊 + vibrancy 提饱和（配方学习 [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)，Apache-2.0，用 Compose 1.7 自实现、零新依赖），再叠 34%–46% 极薄容器膜、发丝描边与顶部高光；API 33+ 全效、31-32 无折射、<31 优雅退化为半透明面板
  - **环境光背景层**：根节点铺一层「底色 + 三团柔光」并录进独立 GraphicsLayer，页面内所有玻璃都采样它。卡片采样的是稳定图层而不是自身祖先，不会形成绘制环
  - **导航条 / 键盘采样页面**：二者是页面节点的兄弟，直接采样页面图层，滚动内容从玻璃下穿过，边缘折射随内容实时流动
  - 版式参考 [HFUT-Schedule](https://github.com/Chiu-xaH/HFUT-Schedule) 的液态玻璃底栏（68dp 满宽胶囊、5dp 内缩、选中指示胶囊带 0.5dp 描边与按压缩放），膜更薄、模糊提到 26dp
- **组件语义**：互斥分段用 `SegmentedButton`、非阻断反馈用 `Snackbar`（不用 Toast）、导航条为悬浮玻璃胶囊（4 tab + 中央主色「＋」滑动高亮指示）、分类九宫格为 `Role.RadioButton` 单选语义（选中=描边环+字重，不单靠颜色）
- **交互反馈**：按下瞬间 state-layer 高亮（onSurface 8%）+ 临界阻尼弹簧微缩放，无水波纹；键盘全程触觉（KEYBOARD_TAP / CLOCK_TICK / CONFIRM）
- **动效礼仪**：图表生长/转场为弹簧动画，进出镜像同路径；系统「移除动画」开启时全部退化为瞬时状态切换（reduced motion）
- **无障碍**：触达目标 ≥44dp（键盘键位 52dp）、图标按钮带语义名、明暗主题对比度按 M3 角色对验证、edge-to-edge + IME insets 全量适配
- **性能**（android 官方 compose-performance skill）：LazyColumn 全部带 `key` + `contentType`，状态派生走 Room Flow，无 SubcomposeLayout 热路径

## 账单同步（微信零钱 / 零钱通）

账户页每个账户右侧的 ⟳ 按钮 → 选月份（可多选）→ 开始同步。全程不需要手动操作微信：

1. 无障碍服务拉起微信，按入口导航：
   - 零钱：我 → 服务 → 钱包 → 零钱 → 零钱明细
   - 零钱通：我 → 服务 → 钱包 → 零钱通 → 资金明细（转入/转出属于内部划转，跳过不记）
2. 每屏截图后用 **ML Kit 中文 OCR（模型随 APK 打包，离线、不上传）** 识别；
3. 依赖 OCR 文本定位并点击（微信不向无障碍节点暴露控件树），中文误识别用编辑距离容错；
4. 流水由新到旧连续滚动，越过所选最早月份即停止；
5. **增量同步**：以上次同步的最后一笔（日期+金额+时间，余额能读到就一起核对）为锚点，
   命中即停，只处理之后的流水；没命中就退回整月扫描，靠指纹去重兜底；
6. 以「账户 + 日期 + 时间 + 收支 + 金额 + 标题」生成指纹去重，重复同步不会翻倍；
7. **同步规则**（我的 → 同步规则）：按商户关键词自动归类，如「京东→购物」「美团→餐饮」，
   未命中留作未分类；可一键把规则套用到已有未分类账单；
8. 退款：标了「已全额退款」的整笔跳过；部分退款并入原消费扣减，不额外记一笔收入；
9. 账户页显示上次同步时间；现金账户不支持同步（也不显示按钮），其余平台点击提示「待开发」。

> 需要一次性在系统设置里开启「不烧心记账 · 账单同步」无障碍服务；服务只做读屏、点击与截图，
> 不注入账号信息、不改动微信数据，可随时关闭。Android 11+（截图 API 限制）。

## 技术栈

- **Kotlin 2.0** + **Jetpack Compose (Material 3)**，单 Activity
- **Room** 持久化（金额一律以 `Long` 分存储，避免浮点误差）
- **Coroutines Flow** 响应式数据流：改一笔账，流水/统计/账户余额全自动刷新
- **Navigation Compose** 页面路由
- 无第三方 SDK、无网络权限，纯本地应用
- 图表（饼图/柱状图）为 Compose `Canvas` 手绘，无图表库依赖

## 目录结构

```
app/src/main/java/com/lightledger/app/
├── App.kt                    # Application + ServiceLocator（无 Hilt，降低二开门槛）
├── MainActivity.kt
├── data/
│   ├── Entities.kt           # Account / Category / Bill / Budget 实体
│   ├── Daos.kt               # 各表 DAO（余额、日聚合、分类聚合均为 SQL 计算）
│   ├── AppDatabase.kt        # Room 数据库 + 类型转换 + 账户两级结构迁移
│   ├── Backup.kt             # JSON 全量备份编解码（换机迁移）
│   └── Repository.kt         # 业务仓库 + 出厂数据预置 + CSV 导出
├── ui/
│   ├── AppNav.kt             # 路由 + 悬浮玻璃导航条骨架
│   ├── Components.kt         # 通用组件（数字键盘、月份条、选择弹窗…）
│   ├── Charts.kt             # Canvas 手绘饼图/柱状图
│   ├── glass/                # 液态玻璃渲染管线（环境光背景/采样/折射/模糊/高光）
│   ├── theme/                # 配色（支出红/收入绿/品牌青绿）
│   ├── bills/                # 明细页
│   ├── add/                  # 记一笔 / 编辑账单
│   ├── stats/                # 统计页
│   ├── accounts/             # 账户页 + 账户编辑
│   ├── budget/               # 预算页
│   └── settings/             # 我的 / 分类管理 / 关于
└── util/Format.kt            # 金额(分↔元)/日期/CSV 工具
```

## 构建运行

```bash
git clone https://github.com/SaltGardenia/calm-ledger.git
cd calm-ledger
# 需要 JDK 17 与 Android SDK（platforms;android-35）
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

或直接用 Android Studio 打开本目录运行。

## 数据与隐私

- 所有数据只存在本机 SQLite，应用没有网络权限，无任何上报；
- 换机迁移：我的 → 导出备份 / 导入备份（JSON 文件手动拷贝）；
- 账单同步在设备本地完成，OCR 模型随 APK 打包，图片不上传。

## 二次开发指南

见 [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)：数据模型、新增页面套路、常见扩展点（多账本、循环记账、导入钱迹 CSV 等）。

## 源码学习参考

以下开源项目的设计被阅读学习（未复制代码）：

- [TallyApp](https://github.com/xiaojinzi123/TallyApp) — Compose + Room 组件化记账
- [GrainLedger 余粮](https://github.com/HuangZhuoRui/GrainLedger) — 预算信封模式
- [Ivy Wallet](https://github.com/Ivy-Apps/ivy-wallet) — 开源 Compose 记账标杆
- [HFUT-Schedule](https://github.com/Chiu-xaH/HFUT-Schedule) — 液态玻璃底栏的版式与交互（胶囊轨道、内缩指示器、按压缩放）
- [Money Manager Ex Android](https://github.com/moneymanagerex/android-money-manager-ex) — 老牌复式记账

## License

[GPL-3.0](LICENSE)

本项目以 **GNU General Public License v3** 发布：你可以自由使用、学习、修改和分发，
但基于本项目的衍生作品必须同样以 GPL-3.0 开源，并保留原作者版权声明。
商业闭源二次分发不在授权范围内。
