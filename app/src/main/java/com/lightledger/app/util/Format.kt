package com.lightledger.app.util

import java.text.DecimalFormat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val GROUPED = DecimalFormat("#,##0")

/** 分 -> "1,234.56"；withSymbol 时带 ¥ 前缀 */
fun formatMoney(cents: Long, withSymbol: Boolean = false): String {
    val negative = cents < 0
    val abs = kotlin.math.abs(cents)
    val s = "${GROUPED.format(abs / 100)}.${(abs % 100).toString().padStart(2, '0')}"
    val sign = if (negative) "-" else ""
    return if (withSymbol) "$sign¥$s" else "$sign$s"
}

/** 键盘输入的金额文本 -> 分。容错处理非法输入 */
fun parseMoneyToCents(text: String): Long {
    val cleaned = text.trim().replace(",", "")
    if (cleaned.isEmpty() || cleaned == "." || cleaned == "-") return 0
    val negative = cleaned.startsWith("-")
    val body = cleaned.removePrefix("-")
    val parts = body.split(".")
    val yuan = parts[0].toLongOrNull() ?: return 0
    val fen = if (parts.size > 1) {
        (parts[1].take(2).padEnd(2, '0').toLongOrNull() ?: 0L)
    } else 0L
    val value = yuan * 100 + fen
    return if (negative) -value else value
}

/** YearMonth -> 用于查询的起始 epochDay（含） */
fun YearMonth.startDay(): Int = atDay(1).toEpochDay().toInt()

/** YearMonth -> 用于查询的结束 epochDay（不含） */
fun YearMonth.endDayExclusive(): Int = plusMonths(1).atDay(1).toEpochDay().toInt()

fun YearMonth.monthTitle(): String = "${year}年${monthValue}月"

private val WEEK_LABELS = mapOf(
    DayOfWeek.MONDAY to "周一", DayOfWeek.TUESDAY to "周二", DayOfWeek.WEDNESDAY to "周三",
    DayOfWeek.THURSDAY to "周四", DayOfWeek.FRIDAY to "周五", DayOfWeek.SATURDAY to "周六",
    DayOfWeek.SUNDAY to "周日",
)

fun LocalDate.weekLabel(): String = WEEK_LABELS.getValue(dayOfWeek)

/** 账单分组头：今天 / 昨天 / 11月2日 周六 */
fun dayGroupTitle(day: Int): String {
    val date = LocalDate.ofEpochDay(day.toLong())
    val today = LocalDate.now()
    return when (date) {
        today -> "今天 ${date.weekLabel()}"
        today.minusDays(1) -> "昨天 ${date.weekLabel()}"
        else -> "${date.monthValue}月${date.dayOfMonth}日 ${date.weekLabel()}"
    }
}

fun LocalDate.dayTitle(): String = "${monthValue}月${dayOfMonth}日 ${weekLabel()}"

/** 时间戳 -> "2026-11-02 14:33" */
fun formatDateTime(millis: Long): String =
    LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

/** LocalDate -> 账单时间戳（当天按当前时刻，符合记账直觉） */
fun LocalDate.toBillMillis(): Long =
    atTime(LocalDateTime.now().toLocalTime()).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

/** CSV 单元格转义 */
fun csvField(value: String): String =
    if (value.contains(',') || value.contains('"') || value.contains('\n')) {
        "\"${value.replace("\"", "\"\"")}\""
    } else value
