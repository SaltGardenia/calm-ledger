package com.lightledger.app.sync

import java.time.LocalDate

/** 余额链断裂点 */
data class ChainBreak(
    val index: Int,
    val day: LocalDate,
    val expected: Long,
    val actual: Long,
    val title: String,
)

/**
 * 余额链校验：流水每笔都带「交易后余额」，相邻两笔必须严格满足
 *
 *     新一笔余额 = 旧一笔余额 + 收入金额（或 − 支出金额）
 *
 * 全部对得上，说明这一段流水一笔不漏；任何一处对不上，就说明中间漏识别了账单
 * （或者余额读错），此时宁可整批不入库，也不写一份不完整的数据。
 *
 * 余额缺失（OCR 没读到）的相邻对会被跳过——没读到不代表错。
 */
object BalanceChain {

    fun verify(bills: List<ParsedBill>): ChainBreak? {
        // 注意：这里**不能**先过滤掉没有余额的行，否则会跨过中间某笔做比较，
        // 制造「614 → 916 对不上」这种假断裂。只比较真正相邻的两笔。
        val sorted = bills.sortedWith(compareBy({ it.date }, { it.time }))
        if (sorted.size < 2) return null

        for (i in 1 until sorted.size) {
            val prev = sorted[i - 1]
            val cur = sorted[i]
            val prevBalance = prev.balanceAfter ?: continue
            val curBalance = cur.balanceAfter ?: continue
            val expected = when (cur.type) {
                com.lightledger.app.data.BillType.INCOME -> prevBalance + cur.amount
                else -> prevBalance - cur.amount
            }
            if (curBalance != expected) {
                return ChainBreak(
                    index = i,
                    day = cur.date,
                    expected = expected,
                    actual = curBalance,
                    title = cur.title,
                )
            }
        }
        return null
    }

    /** 有多少笔带余额（用来判断校验是否有效） */
    fun coverage(bills: List<ParsedBill>): Int = bills.count { it.balanceAfter != null }
}
