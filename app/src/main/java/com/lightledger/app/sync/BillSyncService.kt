package com.lightledger.app.sync

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.util.Log
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import com.lightledger.app.MainActivity
import com.lightledger.app.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.time.YearMonth
import kotlin.coroutines.resume

/**
 * 账单同步的无障碍服务：
 * 启动微信 → 我 / 服务 / 钱包 / 零钱 / 零钱明细 → 逐屏截图 OCR → 自动上滑 → 到月份边界停。
 *
 * 只做「读屏 + 点击 + 截图」，不注入账号信息、不改动微信数据。
 * 关闭本服务即可完全停止该能力。
 */
class BillSyncService : AccessibilityService() {

    companion object {
        private const val TAG = "BillSync"

        /** 微信底部 tab 的文案：这些标签只认屏幕最下方的一条 */
        private val TAB_LABELS = setOf("我", "通讯录", "发现", "微信")

        /** 服务是否已连接（同进程内最可靠的就绪判断，绕开各 ROM 对系统接口的差异） */
        @Volatile
        var connected: Boolean = false
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private val ocr = OcrEngine()

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        // 部分 ROM（含 MIUI）从 XML 读不到手势/截图能力，这里在代码里再兜一次
        serviceInfo = serviceInfo?.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        Log.i(TAG, "connected flags=${serviceInfo?.flags}")
        scope.launch {
            SyncBus.requests.collect { request ->
                // 拿到就清空 replay，避免服务重连时把旧任务再跑一遍
                SyncBus.consume()
                // 上一次任务若还挂着（例如被系统挂起），直接取消，保证新请求能跑
                job?.cancel()
                job = scope.launch { runSync(request) }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        connected = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        connected = false
        super.onDestroy()
    }

    // ---------------------------------------------------------------------

    private suspend fun runSync(request: SyncRequest) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                fail("系统版本过低：自动同步需要 Android 11 及以上")
                return
            }
            SyncBus.update {
                it.copy(phase = SyncPhase.NAVIGATING, message = "正在打开微信「${request.flow.label}明细」")
            }
            var opened = navigateToWalletDetail(request.flow)
            if (!opened) {
                // 偶发 OCR 抖动：回到聊天列表再走一遍
                SyncBus.update { it.copy(message = "重试打开账单页…") }
                ensureWechatHome()
                opened = navigateToWalletDetail(request.flow)
            }
            if (!opened) {
                fail("没能在微信里找到「零钱明细」，请确认微信已登录、停留在可以打开钱包的状态")
                return
            }

            SyncBus.update { it.copy(phase = SyncPhase.SCANNING, message = "开始识别账单…") }
            // 零钱明细是连续流水（由新到旧），一次滑到底，滑过所选最早月份就停
            val stopBefore = request.months.minOf { it.atDay(1) }
            // 锚点：该账户已导入的最新一笔（日期+金额+时间），比余额更稳定——
            // OCR 经常读不到小号灰字「零钱余额」，但日期和金额很少错
            val anchor = if (request.forceFull) null else ServiceLocator.repository.latestBill(request.accountId)?.let { b ->
                SyncAnchor(
                    day = b.day,
                    amount = b.amount,
                    time = runCatching {
                        java.time.Instant.ofEpochMilli(b.date)
                            .atZone(java.time.ZoneId.systemDefault())
                            .toLocalTime()
                            .withSecond(0)
                            .withNano(0)
                    }.getOrNull(),
                )
            }
            var attempt = 0
            var scan = scanUntil(stopBefore, anchor, request.anchorBalance)
            var chainBreak = BalanceChain.verify(scan.bills + listOfNotNull(scan.anchorBill))
            while (chainBreak != null && attempt < 2) {
                attempt++
                Log.w(TAG, "余额链断裂，第 $attempt 次重扫：$chainBreak")
                SyncBus.update { it.copy(message = "余额对不上，正在重新打开账单页（第 $attempt 次）…") }
                // 回到聊天列表再重新进一次明细，页面会从顶部重新加载，
                // 比连续下滑回顶可靠（WebView 对高频手势会吞事件）
                performGlobalAction(GLOBAL_ACTION_BACK)
                delay(800)
                ensureWechatHome()
                if (!navigateToWalletDetail(request.flow)) break
                delay(1_200)
                scan = scanUntil(stopBefore, anchor, request.anchorBalance)
                chainBreak = BalanceChain.verify(scan.bills + listOfNotNull(scan.anchorBill))
            }
            val scanned = scan.bills
            // 余额链校验：整段流水的「交易后余额」必须首尾相接，任何一处对不上都说明漏识别，
            // 这时宁可整批不入库，也不写一份不完整的账。
            Log.d(
                TAG,
                "chain check: bills=${scanned.size} withBalance=" +
                    BalanceChain.coverage(scanned + listOfNotNull(scan.anchorBill)),
            )
            if (chainBreak != null) {
                fail(
                    "余额对不上：${chainBreak.day}「${chainBreak.title}」应为 " +
                        "¥${"%.2f".format(chainBreak.expected / 100.0)}，" +
                        "实际 ¥${"%.2f".format(chainBreak.actual / 100.0)}。" +
                        "可能有一笔没识别到，已取消本次导入，请重试。",
                )
                return
            }

            val parsed = scanned.filter { YearMonth.from(it.date) in request.months }

            Log.d(TAG, "sync done: parsed=${parsed.size} months=${request.months}")
            if (SyncBus.cancelRequested) {
                SyncBus.update { it.copy(phase = SyncPhase.CANCELLED, message = "已停止，未写入数据") }
                return
            }

            SyncBus.update {
                it.copy(phase = SyncPhase.SAVING, found = parsed.size, message = "正在写入 ${parsed.size} 笔…")
            }
            // 锚点取「最新一笔有余额的账单」：顶部那行常被状态栏裁掉，OCR 读不到余额
            val newest = (scanned + listOfNotNull(scan.anchorBill))
                .filter { it.balanceAfter != null }
                .maxByOrNull { it.date }
            val result = ServiceLocator.repository.importSyncedBills(
                accountId = request.accountId,
                bills = parsed,
                source = "wx",
            )
            // 记下余额锚点，下次只同步更新的部分
            if (newest != null) {
                ServiceLocator.repository.markAccountSynced(
                    accountId = request.accountId,
                    balance = newest.balanceAfter,
                    day = newest.date.toEpochDay().toInt(),
                )
            }
            SyncBus.update {
                it.copy(
                    phase = SyncPhase.DONE,
                    added = result.added,
                    duplicates = result.duplicates,
                    message = when {
                        result.added > 0 -> "已导入 ${result.added} 笔，全部标记为未分类"
                        parsed.isEmpty() -> "没有识别到账单：请确认微信「零钱明细」能正常打开"
                        else -> "没有新账单（这几个月都已同步过）"
                    },
                )
            }
        } catch (t: Throwable) {
            fail(t.message ?: "同步失败")
        } finally {
            bringAppToFront()
        }
    }

    private fun fail(message: String) {
        SyncBus.update { it.copy(phase = SyncPhase.ERROR, error = message, message = message) }
    }

    // ---------------------------------------------------------------------
    // 微信内导航
    // ---------------------------------------------------------------------

    private suspend fun navigateToWalletDetail(flow: WechatFlow): Boolean {
        if (!waitForPackage(WechatConst.PACKAGE, 25_000)) {
            SyncBus.update { it.copy(message = "微信没有切到前台") }
            return false
        }
        delay(1_200)
        // 上一次同步可能把微信留在了钱包里，先退回到聊天列表再走路径
        ensureWechatHome()

        flow.path.forEachIndexed { index, label ->
            if (SyncBus.cancelRequested) return false
            SyncBus.update { it.copy(message = "正在打开「$label」") }
            // 前三步都在同一屏，最后一步「零钱明细」可能需要往下滚
            var ok = clickText(label, timeoutMs = 12_000, allowScroll = index >= 3)
            // 「我」是整条路径的入口：点了没反应就再点一次，别带着聊天页往下走
            if (ok && index == 0 && !pageContains("服务", 6_000)) {
                Log.w(TAG, "点击「我」后没有进入个人页，重试")
                ok = clickText(label, timeoutMs = 6_000, allowScroll = false)
            }
            if (!ok) {
                SyncBus.update { it.copy(message = "没找到「$label」") }
                return false
            }
            delay(if (index == 0) 1_200 else 900)
        }
        return true
    }

    /**
     * 微信不向无障碍节点接口暴露内容（root 可见但 childCount=0），
     * 所以导航用「截图 → OCR 定位文字 → 手势点击」，不依赖对方应用的节点树。
     */
    /** 连续下滑把流水列表拉回顶部（重扫前用） */
    private suspend fun scrollToTop() {
        repeat(8) {
            if (SyncBus.cancelRequested) return
            swipeDown()
            delay(280)
        }
    }

    private suspend fun swipeDown(): Boolean {
        val metrics = resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val path = Path().apply {
            moveTo(x, metrics.heightPixels * 0.30f)
            lineTo(x, metrics.heightPixels * 0.80f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 260))
            .build()
        return dispatch(gesture)
    }

    /** 当前页面是否出现某段文字（用于确认点击后真的切页了） */
    private suspend fun pageContains(text: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val bitmap = grabScreen()
            if (bitmap != null) {
                val lines = ocr.recognize(bitmap)
                bitmap.recycle()
                if (lines.any { it.text.replace(" ", "").contains(text) }) return true
            }
            delay(400)
        }
        return false
    }

    /** 把微信退回到主界面（聊天列表），否则「我」会点到别的文字上 */
    private suspend fun ensureWechatHome() {
        repeat(4) {
            if (isWechatHome()) return
            performGlobalAction(GLOBAL_ACTION_BACK)
            delay(900)
            // 不再在这里重新拉起微信：同步开始前 UI 已经拉起过一次，
            // 这里再拉会和收尾的「回到本应用」互相打架，把刚切回来的记账页顶掉。
            if (rootInActiveWindow?.packageName?.toString() != WechatConst.PACKAGE) {
                Log.w(TAG, "已离开微信，停止回退")
                return
            }
        }
    }

    private suspend fun isWechatHome(): Boolean {
        val bitmap = grabScreen() ?: return false
        val texts = ocr.recognize(bitmap).map { it.text.replace(" ", "") }
        bitmap.recycle()
        // 底部四个 tab 同时出现即认为是聊天列表
        return texts.any { it == "通讯录" } && texts.any { it == "发现" }
    }

    private suspend fun clickText(
        label: String,
        timeoutMs: Long,
        allowScroll: Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var scrolls = 0
        while (System.currentTimeMillis() < deadline) {
            if (SyncBus.cancelRequested) return false
            val bitmap = grabScreen()
            if (bitmap != null) {
                val lines = ocr.recognize(bitmap)
                val screenHeight = bitmap.height
                bitmap.recycle()
                val matched = lines.filter { fuzzyMatch(label, it.text) }
                val exact = matched.filter { it.text.replace(" ", "") == label }
                val pool = exact.ifEmpty { matched }
                // 底部 tab（我 / 通讯录 / 发现）只认最下方那条，避免命中聊天名称
                val hit = if (label in TAB_LABELS) {
                    pool.filter { it.centerY > screenHeight * 0.80f }.maxByOrNull { it.centerY }
                        ?: pool.maxByOrNull { it.centerY }
                } else {
                    pool.minByOrNull { it.top }
                }
                if (hit != null && hit.right > hit.left) {
                    Log.d(TAG, "tap($label) at ${hit.centerX.toInt()},${hit.centerY.toInt()}")
                    if (tap(hit.centerX, hit.centerY)) {
                        delay(700)
                        return true
                    }
                } else {
                    Log.d(TAG, "click($label) not found, texts=" + lines.joinToString("/") { it.text })
                }
            }
            if (allowScroll && scrolls < 4) {
                swipeUp()
                scrolls++
            }
            delay(400)
        }
        return false
    }

    /**
     * OCR 对中文常差一个字（「零钱明细」→「零线明细」），
     * 长度 ≥3 时允许 1 个字的编辑距离，避免因识别噪声整条流程失败。
     */
    private fun fuzzyMatch(label: String, rawText: String): Boolean {
        val a = label.replace(" ", "")
        val b = rawText.replace(" ", "")
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        // 单字标签（我 / 发现）：OCR 常把底部图标读成「|我」「8我」这类两字符，
        // 只允许首尾包含，且整行不超过 2 个字符，避免匹配到聊天名称
        if (a.length == 1) {
            return b.length <= 2 && (b.startsWith(a) || b.endsWith(a))
        }
        if (a.length >= 3 && b.length >= 3 && kotlin.math.abs(a.length - b.length) <= 1) {
            return levenshtein(a, b) <= 1
        }
        // 标签是长行的一部分，如「零钱通收益率0.98%」里的「零线通」。
        // 只对 ≥3 字的标签生效：单字标签（我/发现）否则会匹配任意一行。
        if (a.length >= 3 && b.length > a.length && levenshtein(a, b.take(a.length)) <= 1) return true
        // OCR 会把图标读成数字/字母混进标签，如「8服务」
        if (a.length >= 2 && b.contains(a) && b.length <= a.length + 3) return true
        // 只读到标签的一部分，如「零钱明」
        if (a.length >= 3 && b.length >= 2 && a.contains(b) && b.length >= a.length - 2) return true
        return false
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        a.forEachIndexed { i, ca ->
            var prev = dp[0]
            dp[0] = i + 1
            b.forEachIndexed { j, cb ->
                val temp = dp[j + 1]
                dp[j + 1] = minOf(
                    dp[j + 1] + 1,
                    dp[j] + 1,
                    prev + if (ca == cb) 0 else 1,
                )
                prev = temp
            }
        }
        return dp[b.length]
    }

    /** 单点点击（按下 60ms 后抬起） */
    private suspend fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        return dispatch(gesture)
    }

    private suspend fun dispatch(gesture: GestureDescription): Boolean =
        kotlinx.coroutines.withTimeoutOrNull(3_000) { dispatchInner(gesture) } ?: run {
            Log.w(TAG, "dispatchGesture timeout")
            false
        }

    private suspend fun dispatchInner(gesture: GestureDescription): Boolean =
        suspendCancellableCoroutine { cont ->
            val started = runCatching {
                dispatchGesture(
                    gesture,
                    object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            if (cont.isActive) cont.resume(false)
                        }
                    },
                    null,
                )
            }.getOrDefault(false)
            Log.d(TAG, "dispatchGesture started=$started")
            if (!started && cont.isActive) cont.resume(false)
        }

    private suspend fun waitForPackage(pkg: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (rootInActiveWindow?.packageName?.toString() == pkg) return true
            delay(300)
        }
        return false
    }

    // ---------------------------------------------------------------------
    // 逐屏识别
    // ---------------------------------------------------------------------

    /**
     * 连续向下扫描：每屏 OCR → 解析 → 上滑，直到越过 [stopBefore]（所选最早月份的 1 号）
     * 或连续两屏内容不变（到底了）。
     */
    private data class SyncAnchor(
        val day: Int,
        val amount: Long,
        val time: java.time.LocalTime?,
    )

    private data class ScanResult(
        val bills: List<ParsedBill>,
        /** 上次同步的锚点那笔（用于余额链与权威余额，不再重复入库） */
        val anchorBill: ParsedBill?,
    )

    private suspend fun scanUntil(
        stopBefore: java.time.LocalDate,
        anchor: SyncAnchor?,
        anchorBalance: Long?,
    ): ScanResult {
        Log.d(TAG, "anchor=$anchor balance=$anchorBalance")
        val collected = LinkedHashMap<String, ParsedBill>()
        var pages = 0
        var stale = 0
        var lastHash = 0

        while (pages < 150) {
            if (SyncBus.cancelRequested) break
            delay(450)
            val bitmap = grabScreen()
            if (bitmap == null) {
                Log.w(TAG, "scan page=$pages screenshot=null")
                break
            }
            val lines = ocr.recognize(bitmap)
            bitmap.recycle()

            val pageHash = lines.joinToString("|") { it.text }.hashCode()
            val pageBills = WechatBillParser.parse(lines)
            Log.d(TAG, "page bills=" + pageBills.joinToString { "${it.date}/${it.balanceAfter}" })

            // 增量同步：遇到上次同步的最后一笔就停，只保留它之前的（更新的）流水
            val anchorIndex = if (anchor != null) {
                pageBills.indexOfFirst { bill ->
                    bill.date.toEpochDay().toInt() == anchor.day &&
                        bill.amount == anchor.amount &&
                        (anchor.time == null || bill.time == null || bill.time == anchor.time) &&
                        (anchorBalance == null || bill.balanceAfter == null || bill.balanceAfter == anchorBalance)
                }
            } else {
                -1
            }
            if (anchorIndex >= 0) {
                pageBills.take(anchorIndex).forEach { collected[it.key()] = it }
                Log.d(TAG, "anchor hit on page=$pages at index=$anchorIndex, 增量结束")
                return ScanResult(collected.values.toList(), pageBills[anchorIndex])
            }

            pageBills.forEach { collected[it.key()] = it }
            pages++
            SyncBus.update {
                it.copy(pages = pages, found = collected.size, message = "已识别 ${collected.size} 笔（第 $pages 屏）")
            }
            val oldest = collected.values.minOfOrNull { it.date }
            Log.d(
                TAG,
                "scan page=$pages lines=${lines.size} parsed=${pageBills.size} total=${collected.size} oldest=$oldest",
            )

            if (oldest != null && oldest.isBefore(stopBefore)) break

            if (pageHash == lastHash) {
                stale++
                if (stale >= 3) break
            } else {
                stale = 0
            }
            lastHash = pageHash

            if (!swipeUp()) break
        }

        return ScanResult(collected.values.toList(), null)
    }

    private var lastShotAt = 0L

    /**
     * 无障碍截图有最小间隔（约 333ms），连续截会返回 code=1。
     * 这里统一限流到 420ms，并对失败做一次短重试，避免丢帧造成漏行。
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun grabScreen(): Bitmap? {
        val gap = 420L - (System.currentTimeMillis() - lastShotAt)
        if (gap > 0) delay(gap)
        lastShotAt = System.currentTimeMillis()
        var bitmap = kotlinx.coroutines.withTimeoutOrNull(4_000) { grabScreenInner() }
        if (bitmap == null) {
            delay(450)
            lastShotAt = System.currentTimeMillis()
            bitmap = kotlinx.coroutines.withTimeoutOrNull(4_000) { grabScreenInner() }
        }
        if (bitmap == null) Log.w(TAG, "takeScreenshot retry failed")
        return bitmap
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun grabScreenInner(): Bitmap? = suspendCancellableCoroutine { cont ->
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    val bitmap = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, false)
                    buffer.close()
                    if (cont.isActive) cont.resume(bitmap)
                }

                override fun onFailure(errorCode: Int) {
                    Log.w(TAG, "takeScreenshot failed code=$errorCode")
                    if (cont.isActive) cont.resume(null)
                }
            },
        )
    }

    /**
     * 上滑一屏。
     *
     * 之前用 320ms 的快速滑动会带惯性（fling），实际滚动距离不可控，
     * 容易整屏跳过一段 → 相邻两笔的余额对不上。改成 650ms 的慢拖，
     * 滚动距离≈手指移动距离，配合上下重叠，保证每一行至少完整出现在一屏里。
     */
    private suspend fun swipeUp(): Boolean {
        val metrics = resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val startY = metrics.heightPixels * 0.76f
        val endY = metrics.heightPixels * 0.34f
        val path = Path().apply {
            moveTo(x, startY)
            lineTo(x, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 650))
            .build()
        return dispatch(gesture)
    }

    /**
     * 同步结束回到本应用。
     * MIUI 会拦截无障碍服务从后台拉起 Activity（BAL），所以先试 startActivity，
     * 不行就按返回键把微信任务退下去——本应用的 task 就在微信下面，退掉自然露出来。
     */
    private suspend fun bringAppToFront() {
        val intent = android.content.Intent(this, MainActivity::class.java).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        runCatching { startActivity(intent) }
            .onFailure { Log.w(TAG, "startActivity 被系统拦截：${it.message}") }
        delay(1_000)
        val focus = rootInActiveWindow?.packageName?.toString()
        Log.d(TAG, "同步结束后前台=$focus")
        if (focus != packageName) {
            // MIUI 需要「后台弹出界面」权限才能把 Activity 拉到前台；没授权时提示用户
            SyncBus.update {
                it.copy(
                    message = it.message +
                        "（系统拦截了自动返回，请在「我的 → 同步后自动返回」里开启权限）",
                )
            }
        }
    }
}
