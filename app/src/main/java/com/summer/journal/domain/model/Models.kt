package com.summer.journal.domain.model

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/* ══════════════════════════════════════════════════════════
   领域模型：纯 Kotlin，不 import 任何 android.*
   好处：单元测试不需要 Robolectric，跑得飞快
   ══════════════════════════════════════════════════════════ */

// ────────────── 学期 ──────────────

data class Semester(
    val id: Long = 0,
    val name: String,                 // "2026–2027 第一学期"
    val startDate: LocalDate,         // 第 1 周的周一
    val totalWeeks: Int = 18,
    val isCurrent: Boolean = false,
    val lastSyncedAt: LocalDateTime? = null,
) {
    /** 某教学周对应的真实日期区间 */
    fun weekRange(week: Int): ClosedRange<LocalDate> {
        val monday = startDate.plusWeeks((week - 1).toLong())
        return monday..monday.plusDays(6)
    }

    companion object {
        /** 教学周从 1 开始，与教务系统口径一致 */
        const val FIRST_WEEK = 1
    }
}

// ────────────── 课程 ──────────────

enum class WeekParity { ALL, ODD, EVEN }

enum class CourseColor { TEAL, VIOLET, PINK, ORANGE, BLUE }

/**
 * 一条 Course = 课表上的一个格子。
 * 注意：这里存的是「规则」不是「每一节课」，避免一学期产生 500+ 条冗余记录。
 */
data class Course(
    val id: Long = 0,
    val semesterId: Long,
    val name: String,
    val teacher: String? = null,
    val room: String? = null,
    /** 1=周一 … 7=周日（与 java.time.DayOfWeek.value 一致） */
    val dayOfWeek: Int,
    /** 节次，如 3..4 表示第 3-4 节 */
    val periodStart: Int,
    val periodEnd: Int,
    val weekParity: WeekParity = WeekParity.ALL,
    val weekRange: IntRange = 1..16,

    /**
     * 离散周次。
     *
     * 教务课表里真实存在「8,12(周)」这种写法 —— 一门课一学期只上第 8 周和第 12 周。
     * 用 IntRange 存不了（存成 8..12 会把第 9/10/11 周也算成有课，直接导致
     * 「明明没课却弹提醒」），所以必须单独一个字段。
     *
     * 为 null 表示「按 weekRange + weekParity 推算」，这是绝大多数课的情况。
     */
    val weekSet: Set<Int>? = null,

    val color: CourseColor = CourseColor.TEAL,
    val note: String? = null,
) {
    /**
     * 这一周到底要不要上这门课。
     *
     * ★ 周视图、日视图、「下一节课」、上课提醒全部调用这里 ——
     *   判断逻辑只允许存在这一份，否则迟早出现「课表显示有课、提醒没响」。
     */
    fun occursInWeek(week: Int): Boolean {
        // 离散周次优先级最高：教务写明了上哪几周，就别再按区间猜
        weekSet?.let { return week in it }
        if (week !in weekRange) return false
        return when (weekParity) {
            WeekParity.ALL -> true
            WeekParity.ODD -> week % 2 == 1
            WeekParity.EVEN -> week % 2 == 0
        }
    }

    /**
     * 把「规则」展开成「真实日期」。
     */
    fun occurrences(semesterStart: LocalDate): List<LocalDate> {
        val firstMonday = semesterStart.with(DayOfWeek.MONDAY)
        // 离散周次可能落在 weekRange 之外（比如 weekRange 被清成 1..1），
        // 所以有 weekSet 时以它为准，否则才遍历区间。
        val weeks = weekSet?.toList() ?: weekRange.toList()
        return weeks
            .filter { occursInWeek(it) }
            .sorted()
            .map { week ->
                // dayOfWeek 1..7 → 偏移 0..6
                firstMonday.plusDays((week - 1) * 7L + (dayOfWeek - 1))
            }
    }

    /** 单双周的中文标签，用于课表格子上的角标 */
    val parityLabel: String?
        get() = when (weekParity) {
            WeekParity.ALL -> null
            WeekParity.ODD -> "单"
            WeekParity.EVEN -> "双"
        }

    /**
     * 周次的中文描述，课表角标和课程详情都用它。
     * 离散周次会写成「第 8、12 周」，不能写成「8-12 周」—— 那是错的。
     */
    val weekLabel: String
        get() = weekSet?.let { set ->
            "第 " + set.sorted().joinToString("、") + " 周"
        } ?: "${weekRange.first}-${weekRange.last} 周"
}

// ────────────── 日程 ──────────────

/** 重复规则：直接存 RFC 5545 的 RRULE 语义，不自己发明格式 */
sealed interface RepeatRule {
    data object Once : RepeatRule
    data class Daily(val interval: Int = 1) : RepeatRule
    data class Weekly(val interval: Int = 1, val byDays: Set<DayOfWeek>) : RepeatRule
    data class Monthly(val dayOfMonth: Int) : RepeatRule
    data class Yearly(val month: Int, val day: Int) : RepeatRule
    /** 与教务一致：按周次 + 单双周 */
    data class AcademicWeeks(val weeks: IntRange, val parity: WeekParity) : RepeatRule

    fun toRruleString(): String = when (this) {
        Once -> "FREQ=ONCE"
        is Daily -> "FREQ=DAILY;INTERVAL=$interval"
        is Weekly -> "FREQ=WEEKLY;INTERVAL=$interval;BYDAY=" +
            byDays.sortedBy { it.value }.joinToString(",") { it.name.take(2) }
        is Monthly -> "FREQ=MONTHLY;BYMONTHDAY=$dayOfMonth"
        is Yearly -> "FREQ=YEARLY;BYMONTH=$month;BYMONTHDAY=$day"
        is AcademicWeeks -> "FREQ=WEEKLY;WKST=MO" +
            (if (parity == WeekParity.ODD) ";INTERVAL=2" else if (parity == WeekParity.EVEN) ";INTERVAL=2" else "")
    }
}

data class ScheduleEvent(
    val id: Long = 0,
    val title: String,
    val note: String? = null,
    val startAt: LocalDateTime,
    val endAt: LocalDateTime? = null,
    val allDay: Boolean = false,
    val location: String? = null,
    val repeatRule: RepeatRule = RepeatRule.Once,
    val color: CourseColor = CourseColor.VIOLET,
    /** 提前量，可多个：准时、提前 30 分钟、提前 1 天 */
    val reminders: List<Duration> = emptyList(),
    val tags: Set<String> = emptySet(),
    /** 是否为户外活动 —— 「天气影响日程」提示要用 */
    val isOutdoor: Boolean = false,
)

// ────────────── 手札 ──────────────

enum class AttachmentType { AUDIO, IMAGE, FILE, SHEET }

data class Memo(
    val id: Long = 0,
    val title: String,
    /** 自定义封面，为空时用第一张图片或渐变色兜底 */
    val coverUri: String? = null,
    val body: String = "",
    val tags: Set<String> = emptySet(),
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val attachments: List<Attachment> = emptyList(),
    /**
     * 附件类型**摘要**（由列表查询的项目投影带出）。
     *
     * ★ 为什么需要它：
     *   手札列表为了性能不加载附件明细（否则每张卡片都要查一次子表），
     *   但卡片上的「图片 / 录音」标签又依赖附件类型 —— 于是标签永远是空的。
     *   解决办法：列表查询用一条子查询把类型聚合成 `{AUDIO, IMAGE}` 一次带出，
     *   这里把它并进判断，列表与详情得到一致的结果。
     */
    val kinds: Set<AttachmentType> = emptySet(),
) {
    private fun has(type: AttachmentType) =
        kinds.contains(type) || attachments.any { it.type == type }

    val hasAudio get() = has(AttachmentType.AUDIO)
    val hasImage get() = has(AttachmentType.IMAGE)
    val hasFile get() = has(AttachmentType.FILE)
    val hasSheet get() = has(AttachmentType.SHEET)
}

/**
 * 附件只存「相对路径」。绝对路径会随沙箱与备份恢复变化，存绝对路径迟早翻车。
 */
data class Attachment(
    val id: Long = 0,
    val memoId: Long,
    val type: AttachmentType,
    val relativePath: String,
    val displayName: String,
    val sizeBytes: Long,
    val durationMs: Long? = null,
    /** 表格类型：行列与单元格的 JSON */
    val sheetJson: String? = null,
) {
    val humanSize: String
        get() = when {
            sizeBytes < 1024 -> "$sizeBytes B"
            sizeBytes < 1024 * 1024 -> "%.1f KB".format(sizeBytes / 1024.0)
            else -> "%.1f MB".format(sizeBytes / 1024.0 / 1024.0)
        }
}

// ────────────── 天气 ──────────────

enum class SkyCondition { CLEAR, PARTLY_CLOUDY, CLOUDY, RAIN, SHOWER, SNOW, THUNDER, FOG }

data class CurrentWeather(
    val temperature: Double,
    val apparentTemperature: Double,
    val humidity: Int,
    val precipitation: Double,
    val windSpeed: Double,
    val windDirection: Double,
    val isDay: Boolean,
    val condition: SkyCondition,
    val description: String,
)

data class HourWeather(
    val time: LocalDateTime,
    val temperature: Double,
    val precipitationProbability: Int,
    val condition: SkyCondition,
)

data class DayWeather(
    val date: LocalDate,
    val minTemperature: Double,
    val maxTemperature: Double,
    val precipitationProbability: Int,
    val uvIndexMax: Double,
    val condition: SkyCondition,
    val sunrise: LocalTime?,
    val sunset: LocalTime?,
)

data class AirQuality(
    val europeanAqi: Int,
    val pm25: Double,
    val pm10: Double,
    val ozone: Double,
    val nitrogenDioxide: Double,
) {
    /** AQI 分级（欧洲 AQI 口径） */
    val level: String
        get() = when {
            europeanAqi <= 20 -> "优"
            europeanAqi <= 40 -> "良"
            europeanAqi <= 60 -> "轻度污染"
            europeanAqi <= 80 -> "中度污染"
            else -> "重度污染"
        }
}

/**
 * 生活指数：不由接口提供，按 UV / 体感 / 降水概率本地推算。
 * 这样既省一个接口，也避免第三方指数服务停服。
 */
data class LifeIndex(
    val uv: String,
    val dressing: String,
    val sport: String,
    val carWash: String,
    val cold: String,
    val drying: String,
) {
    companion object {
        fun compute(
            uvIndexMax: Double,
            apparentTemp: Double,
            precipProbability: Int,
            humidity: Int,
            dayNightDelta: Double,
            aqi: Int,
        ): LifeIndex = LifeIndex(
            uv = when {
                uvIndexMax <= 2 -> "弱"
                uvIndexMax <= 5 -> "中等"
                uvIndexMax <= 7 -> "强"
                else -> "很强"
            },
            dressing = when {
                apparentTemp < 5 -> "羽绒服"
                apparentTemp < 12 -> "厚外套"
                apparentTemp < 19 -> "长袖"
                apparentTemp < 26 -> "薄长袖"
                else -> "短袖"
            },
            sport = if (precipProbability < 30 && apparentTemp in 15.0..28.0 && aqi <= 100) "适宜"
            else if (precipProbability < 50) "较适宜" else "不宜",
            carWash = if (precipProbability >= 40) "不宜" else "适宜",
            cold = if (dayNightDelta > 10 || apparentTemp < 10) "易发" else "少发",
            drying = if (precipProbability < 20 && humidity < 75) "适宜" else "不宜",
        )
    }
}

data class WeatherSnapshot(
    val cityName: String,
    val latitude: Double,
    val longitude: Double,
    val current: CurrentWeather,
    val hourly: List<HourWeather>,
    val daily: List<DayWeather>,
    val air: AirQuality?,
    val indices: LifeIndex?,
    val fetchedAt: LocalDateTime,
) {
    fun hoursFrom(now: LocalDateTime, count: Int = 24): List<HourWeather> =
        hourly.dropWhile { it.time < now.withMinute(0).withSecond(0) }.take(count)
}

// ────────────── 外观 ──────────────

enum class BackgroundScope { ALL_PAGES, CALENDAR_ONLY, AUTO_BY_TIME }

data class BackgroundTheme(
    val colorKey: String = "warm_white",
    val imageRelativePath: String? = null,
    val blurDp: Int = 0,
    val scrimAlpha: Float = 0f,
    val scope: BackgroundScope = BackgroundScope.ALL_PAGES,
    val autoInvertText: Boolean = true,
)

// ────────────── 天气 × 日程 联动 ──────────────

data class WeatherImpact(
    val event: ScheduleEvent,
    val message: String,
    val affected: Boolean,
)
