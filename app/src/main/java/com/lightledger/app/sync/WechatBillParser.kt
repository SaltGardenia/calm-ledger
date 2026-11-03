package com.lightledger.app.sync

import com.lightledger.app.data.BillType
import java.time.LocalDate
import java.time.LocalTime

/** OCR 出来的一行文字（保留左上角坐标，便于按版面顺序处理） */
data class OcrLine(
    val text: String,
    val top: Float = 0f,
    val left: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

/** 从流水页解析出的一条账单 */
data class ParsedBill(
    val date: LocalDate,
    val time: LocalTime?,
    val type: BillType,
    /** 金额（分，正数） */
    val amount: Long,
    val title: String,
    val rawDate: String,
    /** 这笔之后的零钱余额（分），用于增量同步定位锚点 */
    val balanceAfter: Long? = null,
    /** 行内出现「已全额退款」：整笔跳过 */
    val fullyRefunded: Boolean = false,
    /** 退款行（标题含「退款」）：与对应消费合并，不单独记收入 */
    val isRefund: Boolean = false,
    /** 账户内部划转（转入/转出）：影响余额，但不算收支 */
    val isTransfer: Boolean = false,
)

/**
 * 微信「零钱明细」列表的文字解析。
 *
 * 页面结构稳定为「标题 / 日期时间 / 金额」三行一组，金额在右侧带 + 或 −。
 * 解析只依赖文字顺序，不依赖具体像素位置，因此不同分辨率、不同 OCR 分行都能用。
 *
 * 年份推断：明细页只给「10月5日 14:32」，没有年份。列表是倒序的，所以用游标法——
 * 从今天开始，遇到比游标更晚的月日就退一年；跨年（12 月 → 1 月）也能对上。
 */
/**
 * 入库指纹：**不含余额**。
 * 余额会随 OCR 是否读到而变化，若混进指纹，同一笔在两次识别里会得到不同 key，
 * 造成重复入库。余额只用于「同屏去重」和「余额链校验」。
 */
fun ParsedBill.fingerprint(): String = buildString {
    append(date).append('|')
    append(time ?: "-").append('|')
    append(type.name).append('|')
    append(amount).append('|')
    append(title)
}

/** 同一屏内去重用的稳定键（含余额，能把「同分钟同金额的两笔」区分开） */
fun ParsedBill.key(): String = buildString {
    append(date).append('|')
    append(time ?: "-").append('|')
    append(type.name).append('|')
    append(amount).append('|')
    append(title).append('|')
    // 余额是每笔唯一的，用它区分「同一分钟同金额的两笔」，也让跨屏重叠能精确去重
    append(balanceAfter ?: "-")
}

object WechatBillParser {

    private val AMOUNT = Regex("""^([+\-])\s*¥?\s*(\d{1,3}(?:,\d{3})+|\d+)(?:\.(\d{1,2}))?$""")
    private val DATE_FULL = Regex("""(\d{4})年(\d{1,2})月(\d{1,2})日(?:\s*(\d{1,2})[:：](\d{2}))?""")
    // 零钱通「资金明细」用 2026-10-05 12:24:41 这种格式
    private val DATE_ISO = Regex("""(\d{4})-(\d{1,2})-(\d{1,2})(?:\s+(\d{1,2}):(\d{2})(?::(\d{2}))?)? """.trimEnd())
    private val DATE_MD = Regex("""(?:^|\s)(\d{1,2})月(\d{1,2})日(?:\s*(\d{1,2})[:：](\d{2}))?""")
    private val DATE_RELATIVE = Regex("""^(今天|昨天|前天)(?:\s*(\d{1,2})[:：](\d{2}))?""")
    private val TIME_ONLY = Regex("""^(\d{1,2})[:：](\d{2})$""")
    private val YEAR_MONTH = Regex("""^(\d{4})年(\d{1,2})月$""")
    private val BALANCE = Regex("""(?:零钱余额|余额)\s*¥?\s*([\d,]+\.\d{2})""")

    private val NOISE = listOf(
        "零钱明细", "收支明细", "账单明细", "全部", "筛选", "收支统计", "月账单",
        "微信支付", "常见问题", "零钱余额", "余额", "帮助中心", "申请", "钱包",
        "服务", "返回", "完成", "取消", "已退款", "已全额退款", "对方已收钱",
        "已收款", "收款成功", "转账", "评论", "更多",
    )

    fun parse(lines: List<OcrLine>, today: LocalDate = LocalDate.now()): List<ParsedBill> {
        if (lines.isEmpty()) return emptyList()
        val ordered = lines
            .map { it.copy(text = normalize(it.text)) }
            .filter { it.text.isNotBlank() }
            .sortedWith(compareBy({ it.top }, { it.left }))

        val result = ArrayList<ParsedBill>()
        // 游标：当前已解析到的最早日期，用于没有年份时推断
        var cursor: LocalDate? = null
        var explicitYear: Int? = null

        ordered.forEachIndexed { index, line ->
            YEAR_MONTH.matchEntire(line.text)?.let {
                explicitYear = it.groupValues[1].toInt()
                return@forEachIndexed
            }

            val amountMatch = AMOUNT.matchEntire(line.text) ?: return@forEachIndexed

            // 日期：优先在本行，其次往前找（最多 4 行），再往后找 1 行
            val dateInfo = findDate(ordered, index, today) ?: return@forEachIndexed
            val title = findTitle(ordered, index)

            val date = resolveDate(dateInfo, explicitYear, cursor, today) ?: return@forEachIndexed
            cursor = date

            val sign = amountMatch.groupValues[1]
            val yuan = amountMatch.groupValues[2].replace(",", "").toLongOrNull() ?: return@forEachIndexed
            val fen = amountMatch.groupValues[3].padEnd(2, '0').take(2).toLongOrNull() ?: 0L
            val amount = yuan * 100 + fen
            if (amount <= 0) return@forEachIndexed

            val blockText = (maxOf(0, index - 3)..minOf(ordered.lastIndex, index + 2))
                .joinToString(" ") { ordered[it].text }
            val balance = (maxOf(0, index - 2)..minOf(ordered.lastIndex, index + 2))
                .firstNotNullOfOrNull { BALANCE.find(ordered[it].text) }
                ?.groupValues?.get(1)
                ?.replace(",", "")
                ?.toDoubleOrNull()
                ?.let { Math.round(it * 100) }
                ?: balanceFromDateLine(ordered, dateInfo, amount)

            result += ParsedBill(
                date = date,
                time = dateInfo.time,
                type = if (sign == "-") BillType.EXPENSE else BillType.INCOME,
                amount = amount,
                title = title,
                rawDate = dateInfo.raw,
                balanceAfter = balance,
                fullyRefunded = blockText.contains("全额退款"),
                isRefund = title.contains("退款"),
                isTransfer = title.contains("转入") || title.contains("转出"),
            )
        }
        return result
    }

    private data class DateInfo(
        val month: Int,
        val day: Int,
        val year: Int?,
        val time: LocalTime?,
        val relativeDays: Int?, // 今天=0 昨天=1
        val raw: String,
        val lineIndex: Int = -1,
    )

    private fun lineHeight(line: OcrLine): Float = (line.bottom - line.top).coerceAtLeast(1f)

    /**
     * 找这一笔的日期。
     *
     * 微信这版明细是「标题 / 金额 / 日期+余额」上下排，同一行里金额在右、标题在左，
     * 所以按文字顺序往前找会抓到**上一行**的日期（曾导致「转账-来自温子豪」被记到次日，
     * 余额链错位）。有坐标时按纵向距离就近取，没有坐标才退回顺序查找。
     */
    private fun findDate(lines: List<OcrLine>, amountIndex: Int, today: LocalDate): DateInfo? {
        val anchor = lines[amountIndex]
        if (anchor.bottom > anchor.top) {
            val maxDy = lineHeight(anchor) * 2.6f
            val nearby = lines.indices
                .filter { it != amountIndex && lines[it].bottom > lines[it].top }
                .sortedBy { kotlin.math.abs(lines[it].centerY - anchor.centerY) }
            for (idx in nearby) {
                if (kotlin.math.abs(lines[idx].centerY - anchor.centerY) > maxDy) break
                parseDate(lines[idx].text, today)?.let { return it.copy(lineIndex = idx) }
            }
        }
        // 兜底：没有坐标时按顺序找
        val candidates = buildList {
            add(amountIndex)
            for (i in 1..4) {
                val idx = amountIndex - i
                if (idx >= 0) add(idx)
            }
            if (amountIndex + 1 < lines.size) add(amountIndex + 1)
        }
        candidates.forEach { idx ->
            parseDate(lines[idx].text, today)?.let { return it.copy(lineIndex = idx) }
        }
        return null
    }

    /**
     * 兜底取余额：日期那一行（例如「11月2日 16:16   零钱余额 1.75」）里，
     * 去掉日期和时间后剩下的小数就是余额。这样即使「零钱余额」四个字被 OCR 读花，
     * 也不影响取数。
     */
    private fun balanceFromDateLine(
        lines: List<OcrLine>,
        dateInfo: DateInfo,
        amountCents: Long,
    ): Long? {
        val idx = dateInfo.lineIndex
        if (idx !in lines.indices) return null
        // 余额可能和日期同一行，也可能被 OCR 拆到下一行，两边都看
        val candidates = listOf(idx, idx + 1).filter { it in lines.indices }
        for (i in candidates) {
            val raw = lines[i].text
            // 页头「2026年10月 收入 ¥16069.97 支出 ¥7686.69」不是余额，跳过
            if (raw.contains("收入") && raw.contains("支出")) continue
            if (raw.contains("全部明细") || raw.contains("筛选")) continue
            var text = raw
                .replace(DATE_FULL, " ")
                .replace(DATE_ISO, " ")
                .replace(DATE_MD, " ")
                .replace(DATE_RELATIVE, " ")
            val value = Regex("""([\d,]+\.\d{2})""").findAll(text)
                .lastOrNull()
                ?.groupValues?.get(1)
                ?.replace(",", "")
                ?.toDoubleOrNull()
                ?.let { Math.round(it * 100) }
            // 兜底值和本笔金额相同，多半是把金额行又读了一遍，不能当余额
            if (value != null && value != amountCents) return value
        }
        return null
    }

    private fun parseDate(text: String, today: LocalDate): DateInfo? {
        DATE_ISO.find(text)?.let { m ->
            val time = if (m.groupValues[4].isNotEmpty()) {
                runCatching {
                    LocalTime.of(
                        m.groupValues[4].toInt(),
                        m.groupValues[5].toInt(),
                        m.groupValues[6].ifEmpty { "0" }.toInt(),
                    )
                }.getOrNull()
            } else {
                null
            }
            return DateInfo(
                month = m.groupValues[2].toInt(),
                day = m.groupValues[3].toInt(),
                year = m.groupValues[1].toInt(),
                time = time,
                relativeDays = null,
                raw = m.value,
            )
        }
        DATE_FULL.find(text)?.let { m ->
            return DateInfo(
                month = m.groupValues[2].toInt(),
                day = m.groupValues[3].toInt(),
                year = m.groupValues[1].toInt(),
                time = if (m.groupValues[4].isNotEmpty()) {
                    runCatching { LocalTime.of(m.groupValues[4].toInt(), m.groupValues[5].toInt()) }.getOrNull()
                } else null,
                relativeDays = null,
                raw = m.value,
            )
        }
        DATE_RELATIVE.find(text)?.let { m ->
            val days = when (m.groupValues[1]) {
                "今天" -> 0
                "昨天" -> 1
                else -> 2
            }
            val d = today.minusDays(days.toLong())
            return DateInfo(
                month = d.monthValue,
                day = d.dayOfMonth,
                year = d.year,
                time = if (m.groupValues[2].isNotEmpty()) {
                    runCatching { LocalTime.of(m.groupValues[2].toInt(), m.groupValues[3].toInt()) }.getOrNull()
                } else null,
                relativeDays = days,
                raw = m.value,
            )
        }
        DATE_MD.find(text)?.let { m ->
            return DateInfo(
                month = m.groupValues[1].toInt(),
                day = m.groupValues[2].toInt(),
                year = null,
                time = if (m.groupValues[3].isNotEmpty()) {
                    runCatching { LocalTime.of(m.groupValues[3].toInt(), m.groupValues[4].toInt()) }.getOrNull()
                } else null,
                relativeDays = null,
                raw = m.value,
            )
        }
        return null
    }

    private fun resolveDate(
        info: DateInfo,
        explicitYear: Int?,
        cursor: LocalDate?,
        today: LocalDate,
    ): LocalDate? {
        info.relativeDays?.let { return today.minusDays(it.toLong()) }
        val year = info.year ?: explicitYear
        return runCatching {
            if (year != null) {
                LocalDate.of(year, info.month, info.day)
            } else {
                var y = (cursor ?: today).year
                var d = LocalDate.of(y, info.month, info.day)
                while (d.isAfter(cursor ?: today)) {
                    y -= 1
                    d = LocalDate.of(y, info.month, info.day)
                }
                d
            }
        }.getOrNull()
    }

    private fun findTitle(lines: List<OcrLine>, amountIndex: Int): String {
        fun acceptable(text: String): Boolean {
            if (text.length < 2) return false
            if (AMOUNT.matches(text)) return false
            if (parseDate(text, LocalDate.now()) != null) return false
            if (YEAR_MONTH.matches(text)) return false
            if (NOISE.any { text == it || text.endsWith(it) && text.length <= it.length + 2 }) return false
            if (text.none { it.isLetter() }) return false
            return true
        }

        val anchor = lines[amountIndex]
        if (anchor.bottom > anchor.top) {
            val maxDy = lineHeight(anchor) * 1.4f
            val nearby = lines.indices
                .filter { it != amountIndex && lines[it].bottom > lines[it].top }
                .sortedBy { kotlin.math.abs(lines[it].centerY - anchor.centerY) }
            for (idx in nearby) {
                if (kotlin.math.abs(lines[idx].centerY - anchor.centerY) > maxDy) break
                if (acceptable(lines[idx].text)) return lines[idx].text.take(24)
            }
        }
        for (i in 1..4) {
            val idx = amountIndex - i
            if (idx < 0) break
            if (acceptable(lines[idx].text)) return lines[idx].text.take(24)
        }
        return ""
    }

    /** 全角转半角、统一负号、去掉金额里的空格 */
    private fun normalize(raw: String): String = raw
        .replace('\u2212', '-') // −
        .replace('\u2014', '-') // —
        .replace('\u2013', '-') // –
        .replace('￥', '¥')
        .replace('\u00A0', ' ')
        .replace(Regex("""[ 	]{2,}"""), " ")
        .trim()
}
