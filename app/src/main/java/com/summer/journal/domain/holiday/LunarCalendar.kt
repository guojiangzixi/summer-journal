package com.summer.journal.domain.holiday

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 农历换算（1900–2100），纯算法、不联网、不依赖任何第三方库。
 *
 * ★ 数据来源：通行的「压缩农历表」lunarInfo —— 每年一个 16 进制数，编码了
 *   12 个月的大小月、闰月月份、闰月天数。这是中文日历领域的标准做法。
 *
 * ★ 为什么超出范围要返回 null 而不是硬算：
 *   与 HolidayProvider 同一原则 —— 算不准宁可不显示，也不能给一个错的农历。
 *   1900 之前 / 2100 之后一律 null。
 *
 * ★ 本类只做「换算」与「农历节日」，不碰 24 节气（节气需要另一张表，
 *   数据错了代价大，暂不引入）。
 */
object LunarCalendar {

    /** 压缩农历表，索引 = 年份 - 1900，覆盖 1900..2100（共 201 项） */
    private val LUNAR_INFO = intArrayOf(
        0x04bd8, 0x04ae0, 0x0a570, 0x054d5, 0x0d260, 0x0d950, 0x16554, 0x056a0, 0x09ad0, 0x055d2, // 1900-1909
        0x04ae0, 0x0a5b6, 0x0a4d0, 0x0d250, 0x1d255, 0x0b540, 0x0d6a0, 0x0ada2, 0x095b0, 0x14977, // 1910-1919
        0x04970, 0x0a4b0, 0x0b4b5, 0x06a50, 0x06d40, 0x1ab54, 0x02b60, 0x09570, 0x052f2, 0x04970, // 1920-1929
        0x06566, 0x0d4a0, 0x0ea50, 0x06e95, 0x05ad0, 0x02b60, 0x186e3, 0x092e0, 0x1c8d7, 0x0c950, // 1930-1939
        0x0d4a0, 0x1d8a6, 0x0b550, 0x056a0, 0x1a5b4, 0x025d0, 0x092d0, 0x0d2b2, 0x0a950, 0x0b557, // 1940-1949
        0x06ca0, 0x0b550, 0x15355, 0x04da0, 0x0a5b0, 0x14573, 0x052b0, 0x0a9a8, 0x0e950, 0x06aa0, // 1950-1959
        0x0aea6, 0x0ab50, 0x04b60, 0x0aae4, 0x0a570, 0x05260, 0x0f263, 0x0d950, 0x05b57, 0x056a0, // 1960-1969
        0x096d0, 0x04dd5, 0x04ad0, 0x0a4d0, 0x0d4d4, 0x0d250, 0x0d558, 0x0b540, 0x0b6a0, 0x195a6, // 1970-1979
        0x095b0, 0x049b0, 0x0a974, 0x0a4b0, 0x0b27a, 0x06a50, 0x06d40, 0x0af46, 0x0ab60, 0x09570, // 1980-1989
        0x04af5, 0x04970, 0x064b0, 0x074a3, 0x0ea50, 0x06b58, 0x05ac0, 0x0ab60, 0x096d5, 0x092e0, // 1990-1999
        0x0c960, 0x0d954, 0x0d4a0, 0x0da50, 0x07552, 0x056a0, 0x0abb7, 0x025d0, 0x092d0, 0x0cab5, // 2000-2009
        0x0a950, 0x0b4a0, 0x0baa4, 0x0ad50, 0x055d9, 0x04ba0, 0x0a5b0, 0x15176, 0x052b0, 0x0a930, // 2010-2019
        0x07954, 0x06aa0, 0x0ad50, 0x05b52, 0x04b60, 0x0a6e6, 0x0a4e0, 0x0d260, 0x0ea65, 0x0d530, // 2020-2029
        0x05aa0, 0x076a3, 0x096d0, 0x04afb, 0x04ad0, 0x0a4d0, 0x1d0b6, 0x0d250, 0x0d520, 0x0dd45, // 2030-2039
        0x0b5a0, 0x056d0, 0x055b2, 0x049b0, 0x0a577, 0x0a4b0, 0x0aa50, 0x1b255, 0x06d20, 0x0ada0, // 2040-2049
        0x14b63, 0x09370, 0x049f8, 0x04970, 0x064b0, 0x168a6, 0x0ea50, 0x06b20, 0x1a6c4, 0x0aae0, // 2050-2059
        0x0a2e0, 0x0d2e3, 0x0c960, 0x0d557, 0x0d4a0, 0x0da50, 0x05d55, 0x056a0, 0x0a6d0, 0x055d4, // 2060-2069
        0x052d0, 0x0a9b8, 0x0a950, 0x0b4a0, 0x0b6a6, 0x0ad50, 0x055a0, 0x0aba4, 0x0a5b0, 0x052b0, // 2070-2079
        0x0b273, 0x06930, 0x07337, 0x06aa0, 0x0ad50, 0x14b55, 0x04b60, 0x0a570, 0x054e4, 0x0d160, // 2080-2089
        0x0e968, 0x0d520, 0x0daa0, 0x16aa6, 0x056d0, 0x04ae0, 0x0a9d4, 0x0a2d0, 0x0d150, 0x0f252, // 2090-2099
        0x0d520,                                                                                    // 2100
    )

    /** 1900 年正月初一对应的公历日期，换算锚点 */
    private val BASE_DATE: LocalDate = LocalDate.of(1900, 1, 31)

    private val DAY_NAMES = arrayOf(
        "初一", "初二", "初三", "初四", "初五", "初六", "初七", "初八", "初九", "初十",
        "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九", "二十",
        "廿一", "廿二", "廿三", "廿四", "廿五", "廿六", "廿七", "廿八", "廿九", "三十",
    )

    private val MONTH_NAMES = arrayOf("正", "二", "三", "四", "五", "六", "七", "八", "九", "十", "冬", "腊")

    data class LunarDate(
        val year: Int,
        /** 1..12 */
        val month: Int,
        /** 1..30 */
        val day: Int,
        val isLeap: Boolean,
    ) {
        /** 「初十」这样的日名 */
        val dayText: String get() = DAY_NAMES[day - 1]

        /** 「八月」这样的月名（闰月带「闰」） */
        val monthText: String get() = (if (isLeap) "闰" else "") + MONTH_NAMES[month - 1] + "月"

        /** 完整：「闰六月初十」 */
        val fullText: String get() = monthText + dayText

        /**
         * 日历格子里的短标签：
         *   初一  → 显示月名（「八月」），让人一眼看出「进这个月了」
         *   其余  → 显示日名（「初十」「十五」）
         */
        val shortLabel: String get() = if (day == 1) monthText else dayText
    }

    /** 某年闰几月；无闰月返回 0 */
    private fun leapMonth(year: Int): Int = LUNAR_INFO[year - 1900] and 0xf

    /** 闰月天数；无闰月返回 0 */
    private fun leapDays(year: Int): Int = when {
        leapMonth(year) == 0 -> 0
        (LUNAR_INFO[year - 1900] and 0x10000) != 0 -> 30
        else -> 29
    }

    /** 某农历年共多少天 */
    private fun yearDays(year: Int): Int {
        var sum = 348   // 12 个「小月」= 12 × 29
        var mask = 0x8000
        while (mask > 0x8) {
            if ((LUNAR_INFO[year - 1900] and mask) != 0) sum++
            mask = mask shr 1
        }
        return sum + leapDays(year)
    }

    /** 某农历年第 m 月（非闰）的天数 */
    private fun monthDays(year: Int, month: Int): Int =
        if ((LUNAR_INFO[year - 1900] and (0x10000 shr month)) == 0) 29 else 30

    /**
     * 公历 → 农历。超出 1900–2100 返回 null（宁缺勿错）。
     */
    fun from(date: LocalDate): LunarDate? {
        if (date.isBefore(BASE_DATE) || date.year > 2100) return null

        var offset = ChronoUnit.DAYS.between(BASE_DATE, date).toInt()

        // ① 定位农历年（带边界保护，避免越界访问 LUNAR_INFO）
        var year = 1900
        while (year <= 2100) {
            val days = yearDays(year)
            if (offset < days) break
            offset -= days
            year++
        }
        if (year > 2100) return null

        // ② 定位农历月（含闰月：月份序列为 1,2,…,L,闰L,L+1,…,12）
        val leap = leapMonth(year)
        var isLeap = false
        var month = 1
        while (true) {
            val days = if (isLeap) leapDays(year) else monthDays(year, month)
            if (offset < days) break
            offset -= days
            if (leap > 0 && month == leap && !isLeap) {
                isLeap = true          // 该月之后紧跟一个闰月
            } else {
                if (isLeap) isLeap = false
                month++
            }
        }

        return LunarDate(year = year, month = month, day = offset + 1, isLeap = isLeap)
    }

    /** 该日农历节日名；没有则 null */
    fun festivalOf(date: LocalDate): String? {
        val lunar = from(date) ?: return null
        // 除夕 = 腊月的最后一天，判断依据是「次日为正月初一」
        if (lunar.month == 12 && !lunar.isLeap) {
            val next = from(date.plusDays(1))
            if (next != null && next.month == 1 && next.day == 1 && !next.isLeap) return "除夕"
        }
        if (lunar.isLeap) return null
        return when (lunar.month to lunar.day) {
            1 to 1 -> "春节"
            1 to 15 -> "元宵"
            2 to 2 -> "龙抬头"
            5 to 5 -> "端午"
            7 to 7 -> "七夕"
            8 to 15 -> "中秋"
            9 to 9 -> "重阳"
            12 to 8 -> "腊八"
            else -> null
        }
    }
}
