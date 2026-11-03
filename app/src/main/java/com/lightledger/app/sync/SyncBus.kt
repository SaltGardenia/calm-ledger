package com.lightledger.app.sync

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import java.time.YearMonth

/** 微信内两条不同的流水入口：零钱（消费）与零钱通（理财） */
enum class WechatFlow(val label: String, val path: List<String>) {
    CHANGE("零钱", listOf("我", "服务", "钱包", "零钱", "零钱明细")),
    LINGQIANTONG("零钱通", listOf("我", "服务", "钱包", "零钱通", "资金明细")),
}

/** 一次同步请求：目标账户 + 要拉取的月份 */
data class SyncRequest(
    val accountId: Long,
    val accountName: String,
    val months: List<YearMonth>,
    val flow: WechatFlow = WechatFlow.CHANGE,
    /** 强制从头完整扫描（忽略上次同步位置），用于重新校准余额 */
    val forceFull: Boolean = false,
    /** 上次同步后的余额（分）；命中它之前的都算新流水，可以提前结束 */
    val anchorBalance: Long? = null,
    val anchorDay: Int = 0,
)

enum class SyncPhase { IDLE, LAUNCHING, NAVIGATING, SCANNING, SAVING, DONE, ERROR, CANCELLED }

data class SyncState(
    val phase: SyncPhase = SyncPhase.IDLE,
    val message: String = "",
    val pages: Int = 0,
    val found: Int = 0,
    val added: Int = 0,
    val duplicates: Int = 0,
    val error: String? = null,
) {
    val running: Boolean
        get() = phase == SyncPhase.LAUNCHING || phase == SyncPhase.NAVIGATING ||
            phase == SyncPhase.SCANNING || phase == SyncPhase.SAVING
}

/**
 * Activity 与无障碍服务之间的指令/进度总线。
 * 用单例而不是 Binder：同步任务很短，进程内共享足够，且不需要跨进程。
 */
object SyncBus {

    val state = MutableStateFlow(SyncState())

    // replay=1：服务可能比请求晚连上，缓存最后一条指令；消费后清空
    private val _requests = MutableSharedFlow<SyncRequest>(replay = 1, extraBufferCapacity = 1)
    val requests: SharedFlow<SyncRequest> = _requests

    @Volatile
    var cancelRequested: Boolean = false
        private set

    fun request(req: SyncRequest): Boolean {
        // 单飞：同步进行中重复点按直接忽略，避免多个任务互相拉起微信
        if (state.value.running) return false
        cancelRequested = false
        state.value = SyncState(SyncPhase.LAUNCHING, "正在启动微信…")
        return _requests.tryEmit(req)
    }

    fun update(block: (SyncState) -> SyncState) {
        state.value = block(state.value)
    }

    /** 服务消费完指令后调用，避免重连时重复执行旧任务 */
    fun consume() {
        _requests.resetReplayCache()
    }

    fun cancel() {
        cancelRequested = true
        update { it.copy(message = "正在停止…") }
    }

    fun reset() {
        state.value = SyncState()
    }
}

/** 微信包名与账单页入口文案 */
object WechatConst {
    const val PACKAGE = "com.tencent.mm"
    const val LAUNCHER = "com.tencent.mm.ui.LauncherUI"
    /** 我 → 服务 → 钱包 → 零钱 → 零钱明细 */
    val WALLET_PATH = listOf("我", "服务", "钱包", "零钱", "零钱明细")
}
