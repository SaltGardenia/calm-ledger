package com.lightledger.app.sync

/**
 * 商户名纠错。
 *
 * OCR 出来的名字常见三类问题：
 * 1. 象形字错：零钱通→零线通、拼多多→拼夕夕、瑞幸→端幸；
 * 2. 尾部噪声：省略号、逗号点、图标残影（如「合肥録之鑫图女设,..」）；
 * 3. 同一家商户在不同屏被识别成不同写法。
 *
 * 这里用三步处理：清洗 → 已知词典一对一纠错 → 同批次多数投票（出现最多的写法胜出）。
 */
object MerchantText {

    /** 常见商户的标准写法（只放平台/高频商户，避免误伤自定义备注） */
    private val KNOWN = listOf(
        "拼多多", "京东", "京东商城平台商户", "淘宝", "天猫", "闲鱼",
        "美团", "饿了么", "瑞幸咖啡", "星巴克", "麦当劳", "肯德基",
        "滴滴出行", "滴滴", "高德打车", "12306",
        "零钱通", "收益入账", "扫二维码付款", "转账",
    )

    /** 高频形近字/繁简误识别，一对一直接换掉 */
    private val CHAR_FIX: Map<Char, Char> = mapOf(
        '線' to '钱', '綫' to '钱', '線' to '钱', '録' to '录', '錄' to '录',
        '適' to '滴', '錢' to '钱', '亊' to '事', '萬' to '万', '點' to '点',
        '飲' to '饮', '東' to '东', '車' to '车', '馬' to '马', '門' to '门',
        '買' to '买', '賣' to '卖', '銀' to '银', '網' to '网', '電' to '电',
        '話' to '话', '國' to '国', '華' to '华', '雲' to '云', '樂' to '乐',
        '兒' to '儿', '時' to '时', '間' to '间', '開' to '开', '關' to '关',
    )

    private val TRAILING_JUNK = Regex("""[\s,，.。·、…\.\-—_=~|/\\]+$""")
    private val LEADING_JUNK = Regex("""^[\s,，.。·、…\.\-—_=~|/\\]+""")
    private val MULTI_SPACE = Regex("""\s{2,}""")

    /** 去掉首尾标点/省略号/图标残影，压缩空格 */
    fun clean(raw: String): String = raw
        .replace(' ', ' ')
        .replace(MULTI_SPACE, " ")
        .trim()
        // 形近字/繁简误识别：録→录、適→滴、線→钱…
        .map { CHAR_FIX[it] ?: it }
        .joinToString("")
        .replace(TRAILING_JUNK, "")
        .replace(LEADING_JUNK, "")
        .trim()
        .take(24)

    /** 单个名字对已知词典做 1 字容错纠错 */
    fun correctOne(raw: String): String {
        val text = clean(raw)
        if (text.length < 3) return text
        // 完全相等或只差一个字的，归一到标准写法
        val hit = KNOWN.firstOrNull { known ->
            when {
                text == known -> true
                kotlin.math.abs(text.length - known.length) <= 1 &&
                    levenshtein(text, known) <= 1 -> true
                else -> false
            }
        }
        return hit ?: text
    }

    /**
     * 整批纠错：先逐条纠错，再对「长度≥3 且只差一个字」的名字做多数投票，
     * 让少数派错字向多数派写法靠拢。
     */
    fun normalizeBatch(
        rawTitles: List<String>,
        priorCounts: Map<String, Int> = emptyMap(),
    ): Map<String, String> {
        val corrected = rawTitles.associateWith { correctOne(it) }
        // 历史备注也算进票数：新批次里出现一次「商戶」，库里已有十次「商户」，就归到后者
        val counts = HashMap<String, Int>(priorCounts)
        corrected.values.forEach { counts[it] = (counts[it] ?: 0) + 1 }

        val result = HashMap<String, String>(rawTitles.size)
        rawTitles.forEach { raw ->
            val text = corrected.getValue(raw)
            if (text.length < 3) {
                result[raw] = text
                return@forEach
            }
            // 找一个「出现次数更多、且只差一个字」的写法作为标准
            val better = counts.entries
                .filter { (candidate, count) ->
                    candidate != text &&
                        count > (counts[text] ?: 0) &&
                        kotlin.math.abs(candidate.length - text.length) <= 1 &&
                        levenshtein(candidate, text) <= 1
                }
                .maxByOrNull { it.value }
                ?.key
            result[raw] = better ?: text
        }
        return result
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        a.forEachIndexed { i, ca ->
            var prev = dp[0]
            dp[0] = i + 1
            b.forEachIndexed { j, cb ->
                val temp = dp[j + 1]
                dp[j + 1] = minOf(dp[j + 1] + 1, dp[j] + 1, prev + if (ca == cb) 0 else 1)
                prev = temp
            }
        }
        return dp[b.length]
    }
}
