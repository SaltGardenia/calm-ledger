package com.lightledger.app.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.net.Uri
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lightledger.app.ui.accounts.AccountEditScreen
import com.lightledger.app.ui.accounts.AccountsScreen
import com.lightledger.app.ui.add.AddBillScreen
import com.lightledger.app.ui.bills.BillsScreen
import com.lightledger.app.ui.budget.BudgetScreen
import com.lightledger.app.ui.glass.GlassBackdrop
import com.lightledger.app.ui.glass.GlassPanel
import com.lightledger.app.ui.glass.glassBackdropSource
import com.lightledger.app.ui.glass.glassTint
import com.lightledger.app.ui.glass.rememberGlassBackdrop
import com.lightledger.app.ui.settings.AboutScreen
import com.lightledger.app.ui.settings.CategoryManageScreen
import com.lightledger.app.ui.settings.SettingsScreen
import com.lightledger.app.ui.stats.StatsScreen

object Routes {
    const val MAIN = "main"
    const val ADD_BILL = "add/{billId}"
    const val ACCOUNT_EDIT = "accountEdit/{accountId}/{group}/{institution}"
    const val CATEGORIES = "categories"
    const val BUDGET = "budget"
    const val SYNC_RULES = "syncRules"
    const val ABOUT = "about"

    fun addBill(billId: Long = -1L) = "add/$billId"

    /** 路径段传参（而非 query）：空值用 "-" 占位，避免可选参数解析失败 */
    fun accountEdit(
        accountId: Long = -1L,
        group: String = "",
        institution: String = "",
    ) = "accountEdit/$accountId/${group.ifBlank { "-" }}/${Uri.encode(institution.ifBlank { "-" })}"
}

private data class MainTab(val label: String, val icon: ImageVector)

private val TABS = listOf(
    MainTab("明细", Icons.AutoMirrored.Filled.List),
    MainTab("统计", Icons.Filled.BarChart),
    MainTab("账户", Icons.Filled.AccountBalanceWallet),
    MainTab("我的", Icons.Filled.Person),
)

/** 悬浮玻璃导航条占据的内容高度（条体 68 + 底部悬浮 14），四个主 tab 页据此让位 */
val GlassNavBarHeight = 68.dp + 14.dp

/*
 * 转场遵循空间一致性：进与出走同一条路径（右侧推入 / 右侧滑出），
 * 时长与缓动镜像对称，进程可被打断（Compose 动画天然可打断）。
 * 系统「移除动画」开启时退化为纯淡入淡出。
 */
private const val PUSH_MILLIS = 280
private val PushEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

@Composable
fun AppNav() {
    val navController = rememberNavController()
    val reducedMotion = rememberReducedMotion()
    // 应用根：环境光背景 + 页面内玻璃的采样源
    com.lightledger.app.ui.glass.GlassHost {
        NavHost(
            navController = navController,
            startDestination = Routes.MAIN,
        enterTransition = {
            if (reducedMotion) {
                fadeIn(tween(90))
            } else {
                fadeIn(tween(PUSH_MILLIS)) +
                    slideInHorizontally(tween(PUSH_MILLIS, easing = PushEasing)) { it / 6 }
            }
        },
        exitTransition = { fadeOut(tween(180)) },
        popEnterTransition = { fadeIn(tween(PUSH_MILLIS)) },
        popExitTransition = {
            if (reducedMotion) {
                fadeOut(tween(90))
            } else {
                fadeOut(tween(PUSH_MILLIS)) +
                    slideOutHorizontally(tween(PUSH_MILLIS, easing = PushEasing)) { it / 6 }
            }
        },
    ) {
        composable(Routes.MAIN) {
            MainScreen(
                onAddBill = { navController.navigate(Routes.addBill()) },
                onEditBill = { id -> navController.navigate(Routes.addBill(id)) },
                onEditAccount = { id -> navController.navigate(Routes.accountEdit(id ?: -1L)) },
                onAddAccount = { group, institution ->
                    navController.navigate(Routes.accountEdit(group = group.name, institution = institution))
                },
                onOpenBudget = { navController.navigate(Routes.BUDGET) },
                onOpenCategories = { navController.navigate(Routes.CATEGORIES) },
                onOpenSyncRules = { navController.navigate(Routes.SYNC_RULES) },
                onOpenAbout = { navController.navigate(Routes.ABOUT) },
            )
        }
        composable(
            Routes.ADD_BILL,
            arguments = listOf(navArgument("billId") { type = NavType.LongType }),
        ) { entry ->
            val billId = entry.arguments?.getLong("billId") ?: -1L
            AddBillScreen(billId = billId, onBack = { navController.popBackStack() })
        }
        composable(
            Routes.ACCOUNT_EDIT,
            arguments = listOf(
                navArgument("accountId") { type = NavType.LongType },
                navArgument("group") { type = NavType.StringType },
                navArgument("institution") { type = NavType.StringType },
            ),
        ) { entry ->
            val accountId = entry.arguments?.getLong("accountId") ?: -1L
            val group = entry.arguments?.getString("group").orEmpty()
            val institution = entry.arguments?.getString("institution").orEmpty()
            AccountEditScreen(
                accountId = accountId,
                onBack = { navController.popBackStack() },
                prefillGroup = group.takeUnless { it == "-" }.orEmpty(),
                prefillInstitution = institution.takeUnless { it == "-" }.orEmpty(),
            )
        }
        composable(Routes.CATEGORIES) { CategoryManageScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.BUDGET) { BudgetScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.SYNC_RULES) {
            com.lightledger.app.ui.settings.SyncRulesScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.ABOUT) { AboutScreen(onBack = { navController.popBackStack() }) }
        }
    }
}

@Composable
private fun MainScreen(
    onAddBill: () -> Unit,
    onEditBill: (Long) -> Unit,
    onEditAccount: (Long?) -> Unit,
    onAddAccount: (com.lightledger.app.data.AccountGroup, String) -> Unit,
    onOpenBudget: () -> Unit,
    onOpenCategories: () -> Unit,
    onOpenSyncRules: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val backdrop = rememberGlassBackdrop()
    // 主 tab 页底部让位：导航条高度 + 系统导航栏
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(Modifier.fillMaxSize()) {
        // 页面内容：整体录制进背景层，供玻璃导航条采样
        Box(
            Modifier
                .fillMaxSize()
                .glassBackdropSource(backdrop),
        ) {
            when (tab) {
                0 -> BillsScreen(onEditBill = onEditBill, extraBottomPadding = GlassNavBarHeight + navInset)
                1 -> StatsScreen(extraBottomPadding = GlassNavBarHeight + navInset)
                2 -> AccountsScreen(
                    onEditAccount = onEditAccount,
                    onAddAccount = onAddAccount,
                    extraBottomPadding = GlassNavBarHeight + navInset,
                )
                3 -> SettingsScreen(
                    onOpenBudget = onOpenBudget,
                    onOpenCategories = onOpenCategories,
                    onOpenSyncRules = onOpenSyncRules,
                    onOpenAbout = onOpenAbout,
                    extraBottomPadding = GlassNavBarHeight + navInset,
                )
            }
        }

        // 悬浮液态玻璃导航条：滚动内容从玻璃下穿过（配方转译自 Kyant0 LiquidBottomTabs）
        GlassNavBar(
            backdrop = backdrop,
            selectedTab = tab,
            onTabSelect = { tab = it },
            onAddBill = onAddBill,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .padding(bottom = 14.dp),
        )
    }
}

/**
 * 悬浮玻璃导航条：68dp 胶囊，4 个 tab + 中央「＋」主按钮，滑动高亮胶囊指示选中态。
 *
 * 版式参考 Chiu-xaH/HFUT-Schedule 的液态玻璃底栏（dev 分支 NavigationBarItemData.kt）：
 *  - 69dp 满宽胶囊轨道，内容整体内缩 5dp（其 CARD_NORMAL_DP×2）；
 *  - 指示器是按选中项宽度收紧的胶囊，四周留 5dp、带 0.25–0.5dp 发丝描边；
 *  - 未选中项按下时 0.89 缩放（此处取 0.9）；
 *  - 玻璃膜比参考实现更薄，模糊更重，背景色块能真正透过玻璃。
 */
@Composable
private fun GlassNavBar(
    backdrop: GlassBackdrop,
    selectedTab: Int,
    onTabSelect: (Int) -> Unit,
    onAddBill: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val reducedMotion = rememberReducedMotion()
    val tabSlots = listOf(0, 1, 3, 4) // 中央槽位留给「＋」
    val interactions = remember { List(TABS.size) { MutableInteractionSource() } }
    val scheme = MaterialTheme.colorScheme
    // 低饱和抹茶色：去掉描边，用一块安静的色块标记当前页
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val indicatorFill = if (dark) Color(0xFF3E4C36) else Color(0xFFD9E5C7)
    val indicatorInk = if (dark) Color(0xFFDCEBC8) else Color(0xFF38462C)

    GlassPanel(
        backdrop = backdrop,
        modifier = modifier.height(68.dp),
        shape = CircleShape,
        tint = glassTint(),
        blurRadius = 26.dp,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(5.dp)) {
            val slot = maxWidth / 5

            // 滑动高亮胶囊：弹簧跟随选中项，横向略收窄让两枚胶囊之间留出玻璃缝
            val pillX by animateDpAsState(
                targetValue = slot * tabSlots[selectedTab],
                animationSpec = if (reducedMotion) tween(0) else spring(
                    dampingRatio = 0.8f,
                    stiffness = Spring.StiffnessLow * 1.4f,
                ),
                label = "navPillX",
            )
            Box(
                Modifier
                    .offset(x = pillX)
                    .width(slot)
                    .fillMaxHeight()
                    .padding(horizontal = 1.dp)
                    .clip(CircleShape)
                    .background(indicatorFill),
            )

            Row(Modifier.fillMaxSize()) {
                TABS.forEachIndexed { index, tabItem ->
                    val selected = selectedTab == index
                    val pressed by interactions[index].collectIsPressedAsState()
                    // 未选中项按下时轻微缩回，松开弹回（弹簧，与参考实现同量级）
                    val itemScale by animateFloatAsState(
                        targetValue = if (pressed && !selected) 0.9f else 1f,
                        animationSpec = if (reducedMotion) tween(0) else tween(300),
                        label = "navItemScale",
                    )
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .scale(itemScale)
                            .selectable(
                                selected = selected,
                                role = Role.Tab,
                                interactionSource = interactions[index],
                                indication = null,
                            ) {
                                if (!selected) {
                                    Haptics.tick(view)
                                    onTabSelect(index)
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Icon(
                                tabItem.icon,
                                contentDescription = tabItem.label,
                                tint = if (selected) indicatorInk else scheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp),
                            )
                            Text(
                                tabItem.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) indicatorInk else scheme.onSurfaceVariant,
                                fontWeight = if (selected) {
                                    FontWeight.SemiBold
                                } else {
                                    FontWeight.Medium
                                },
                            )
                        }
                    }
                    if (index == 1) {
                        // 中央「＋」：实心主色圆钮，按压果冻缩放
                        GlassAddButton(onClick = onAddBill, modifier = Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        }
    }
}

@Composable
private fun GlassAddButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reducedMotion = rememberReducedMotion()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.86f else 1f,
        animationSpec = if (reducedMotion) tween(0) else spring(
            dampingRatio = 0.55f,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "glassAddScale",
    )
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(48.dp)
                .scale(scale)
                .shadow(6.dp, CircleShape, clip = false)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
                .selectable(
                    selected = false,
                    role = Role.Button,
                    interactionSource = interaction,
                    indication = null,
                ) {
                    Haptics.confirm(view)
                    onClick()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = "记一笔",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}
