package com.summer.journal.domain.holiday

import java.time.LocalDate
import java.time.Month

/**
 * 法定节假日。
 *
 * ⚠️ 数据来源与维护责任（重要，别跳过）：
 *  中国法定节假日的**调休安排**由国务院办公厅每年 11 月左右发布下一年通知，
 *  不是纯算法能推出来的（例如「国庆放假 10/1–10/7，10/10 补班」）。
 *  所以这里的策略是：
 *
 *    固定日期节日  → 代码里写死（元旦 / 劳动节 / 国庆），这些不会变
 *    农历节日      → 需要农历库或年度数据导入（春节 / 清明 / 端午 / 中秋），见 TODO
 *    调休补班日    → 同上，必须每年从官方通知更新
 *
 *  ！！发布前必须做的事：把当年 + 次年的 holiday 数据核对一遍。
 *    数据错了会直接导致用户「以为放假结果要上课」，这比不做这个功能还糟。
 */
object HolidayProvider {

    /** 是否法定节假日（含放假的第一天标记，用于日历角标） */
    fun isHoliday(date: LocalDate): Boolean = holidayName(date) != null

    /** 节日名；null 表示不是节假日 */
    fun holidayName(date: LocalDate): String? {
        // ── 固定日期 ──
        if (date.month == Month.JANUARY && date.dayOfMonth == 1) return "元旦"
        if (date.month == Month.MAY && date.dayOfMonth == 1) return "劳动节"
        if (date.month == Month.OCTOBER && date.dayOfMonth in 1..7) return "国庆"

        // ── 年度数据（农历节日 + 调休）──
        // TODO 接入农历库（如 cn.6tail:lunar）或改为从 assets/holidays.json 读取，
        //      数据每年从国务院办公厅《关于 X 年部分节假日安排的通知》更新。
        //      在那之前，农历节日不会显示 —— 宁可少显示，也不要显示错的日期。
        return ANNUAL_TABLE[date]
    }

    /** 是否调休补班日（周末但需要上班） */
    fun isMakeupWorkday(date: LocalDate): Boolean = MAKEUP_WORKDAYS.contains(date)

    /**
     * 年度数据表。格式：日期 → 节日名。
     * 目前为空是有意为之 —— 见上面的 TODO。
     */
    private val ANNUAL_TABLE: Map<LocalDate, String> = emptyMap()

    /** 调休补班日。同上，需要年度更新。 */
    private val MAKEUP_WORKDAYS: Set<LocalDate> = emptySet()

    /** 让 UI 能提示「节假日数据可能尚未更新」 */
    val dataIsComplete: Boolean
        get() = ANNUAL_TABLE.isNotEmpty()
}
