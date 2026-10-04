package com.summer.journal.domain.model

/**
 * 周次。
 *
 * ══════════════════════════════════════════════════════════════════════
 *  ★ 为什么单独一个类，而不是在 Course 里塞两个字段到处传：
 *
 *  「周次」有两种表达方式，而且必须同时支持：
 *      连续区间  3-16 周
 *      离散周次  第 8 周和第 12 周（教务真的会写成 `8,12(周)`）
 *
 *  离散周次用 IntRange 存不了 —— 存成 8..12 会把第 9/10/11 周也算成有课，
 *  于是「明明不上课却弹了提醒」。
 *
 *  教务解析器和「手动编辑课程时间」**共用这一份解析规则**。
 *  如果各写一套，迟早出现「导进来的课对了、手改的课错了」这种诡异 bug。
 * ══════════════════════════════════════════════════════════════════════
 */
data class WeekInput(
    /** 用于「单双周」与粗略展示；有 weekSet 时它是 weekSet 的 min..max */
    val weekRange: IntRange,
    /** 离散周次。null 表示就是连续区间 */
    val weekSet: Set<Int>?,
    val parity: WeekParity = WeekParity.ALL,
) {

    /** 存库用的字符串：`"8,12"`；连续区间返回 null */
    val storageValue: String?
        get() = weekSet?.sorted()?.joinToString(",")

    /** 给人看的：`第 8、12 周` 或 `3-16 周` */
    val label: String
        get() = when {
            weekSet != null -> "第 " + weekSet.sorted().joinToString("、") + " 周"
            weekRange.first == weekRange.last -> "第 ${weekRange.first} 周"
            else -> "${weekRange.first}-${weekRange.last} 周"
        }

    /** 单双周角标，连续区间才需要 */
    val parityLabel: String?
        get() = if (weekSet != null) null else when (parity) {
            WeekParity.ALL -> null
            WeekParity.ODD -> "单"
            WeekParity.EVEN -> "双"
        }

    /** 这一周上不上 */
    fun includes(week: Int): Boolean {
        weekSet?.let { return week in it }
        if (week !in weekRange) return false
        return when (parity) {
            WeekParity.ALL -> true
            WeekParity.ODD -> week % 2 == 1
            WeekParity.EVEN -> week % 2 == 0
        }
    }

    companion object {
        /** 离散：8,12 / 8,10,12（逗号、全角逗号、顿号都认） */
        private val LIST = Regex("""\d+(?:\s*[,，、]\s*\d+)+""")

        /** 区间：3-16（各种连字符都认） */
        private val RANGE = Regex("""(\d+)\s*[-–—~～]\s*(\d+)""")

        private val ANY_NUMBER = Regex("""\d+""")

        /**
         * 解析任意周次写法。认得这些：
         *
         *     3-16           3-16周        3-16(周)
         *     3-18(单周)     3-18(双周)    3~16
         *     8,12           8,12(周)      8、10、12
         *     第3周          3
         *
         * @return null 表示解析不出（调用方决定要不要算「识别失败」）
         */
        fun parse(text: String?): WeekInput? {
            val raw = text?.trim().orEmpty()
            if (raw.isEmpty()) return null
            // 必须含数字，否则「周易概论」这种课名会被当成周次
            if (!raw.any(Char::isDigit)) return null

            val parity = when {
                raw.contains('单') -> WeekParity.ODD
                raw.contains('双') -> WeekParity.EVEN
                else -> WeekParity.ALL
            }

            // ① 离散周次优先
            LIST.find(raw)?.let { match ->
                val weeks = match.value
                    .split(',', '，', '、')
                    .mapNotNull { it.trim().toIntOrNull() }
                    .filter { it > 0 }
                    .toSortedSet()
                if (weeks.isNotEmpty()) {
                    return WeekInput(weeks.first()..weeks.last(), weeks, parity)
                }
            }

            // ② 连续区间
            RANGE.find(raw)?.let { match ->
                val from = match.groupValues[1].toInt()
                val to = match.groupValues[2].toInt()
                if (from > 0 && to >= from) return WeekInput(from..to, null, parity)
            }

            // ③ 单个周次
            ANY_NUMBER.find(raw)?.let { match ->
                val week = match.value.toInt()
                if (week > 0) return WeekInput(week..week, null, parity)
            }

            return null
        }

        /** 从库里存的 `"8,12"` 还原。空串/null → null */
        fun fromStorage(value: String?): Set<Int>? =
            value
                ?.split(',')
                ?.mapNotNull { it.trim().toIntOrNull() }
                ?.filter { it > 0 }
                ?.toSet()
                ?.takeIf { it.isNotEmpty() }
    }
}
