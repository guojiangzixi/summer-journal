package com.summer.journal.data.repo

import com.summer.journal.data.local.dao.ScheduleEventDao
import com.summer.journal.data.local.entity.ScheduleEventEntity
import com.summer.journal.domain.model.CourseColor
import com.summer.journal.domain.model.RepeatRule
import com.summer.journal.domain.model.ScheduleEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CalendarRepository @Inject constructor(
    private val dao: ScheduleEventDao,
) {

    /** 观察某个日期区间内的日程（原始记录，未展开重复） */
    fun observeBetween(from: LocalDate, to: LocalDate): Flow<List<ScheduleEvent>> =
        dao.observeBetween(from.toEpochMillis(), to.plusDays(1).toEpochMillis())
            .map { list -> list.map { it.toDomain() } }

    /**
     * 观察区间内的日程并**展开重复规则**。
     * 月视图里要按天分组，展开后的结果才是「某天有哪些事」。
     */
    fun observeExpanded(from: LocalDate, to: LocalDate): Flow<List<ScheduleEvent>> =
        observeBetween(from.minusYears(1), to).map { events ->
            events.flatMap { it.occurrences(from, to) }
        }

    suspend fun upsert(event: ScheduleEvent): Long = dao.upsert(event.toEntity())

    suspend fun delete(id: Long) = dao.deleteById(id)

    suspend fun findById(id: Long): ScheduleEvent? = dao.findById(id)?.toDomain()

    /* ────────────── 映射 ────────────── */

    private fun ScheduleEventEntity.toDomain() = ScheduleEvent(
        id = id,
        title = title,
        note = note,
        startAt = startAt.toLocalDateTime(),
        endAt = endAt?.toLocalDateTime(),
        allDay = allDay,
        location = location,
        repeatRule = parseRepeatRule(repeatRule),
        color = color,
        reminders = reminderOffsets
            .split(',')
            .mapNotNull { it.trim().toLongOrNull() }
            .map { Duration.ofMinutes(it) },
        tags = if (tags.isBlank()) emptySet() else tags.split(',').toSet(),
        isOutdoor = isOutdoor,
    )

    private fun ScheduleEvent.toEntity() = ScheduleEventEntity(
        id = id,
        title = title,
        note = note,
        startAt = startAt.toEpochMillis(),
        endAt = endAt?.toEpochMillis(),
        allDay = allDay,
        location = location,
        repeatRule = repeatRule.toRruleString(),
        repeatUntil = null,
        color = color,
        reminderOffsets = reminders.joinToString(",") { it.toMinutes().toString() },
        tags = tags.joinToString(","),
        isOutdoor = isOutdoor,
        updatedAt = System.currentTimeMillis(),
    )
}

/* ══════════════════════════════════════════════════════════════════════
   重复规则解析与展开
   ══════════════════════════════════════════════════════════════════════ */

/**
 * RRULE 子串 → RepeatRule。
 *
 * 只解析我们自己写出去的格式，不做完整的 RFC 5545 实现 ——
 * 完整的规则引擎（BYSETPOS / BYMONTHDAY=-1 之类）复杂度远超本项目的需要，
 * 真需要的时候再引 lib-recur，别自己造。
 */
fun parseRepeatRule(rrule: String): RepeatRule = runCatching {
    val parts = rrule.split(';')
        .mapNotNull { it.split('=').takeIf { p -> p.size == 2 } }
        .associate { it[0] to it[1] }

    when (parts["FREQ"]) {
        null, "ONCE" -> RepeatRule.Once
        "DAILY" -> RepeatRule.Daily(parts["INTERVAL"]?.toIntOrNull() ?: 1)
        "WEEKLY" -> {
            val days = parts["BYDAY"]
                ?.split(',')
                ?.mapNotNull { code ->
                    DayOfWeek.entries.firstOrNull { it.name.take(2) == code }
                }
                ?.toSet()
                .orEmpty()
            RepeatRule.Weekly(parts["INTERVAL"]?.toIntOrNull() ?: 1, days)
        }
        "MONTHLY" -> RepeatRule.Monthly(parts["BYMONTHDAY"]?.toIntOrNull() ?: 1)
        "YEARLY" -> RepeatRule.Yearly(
            month = parts["BYMONTH"]?.toIntOrNull() ?: 1,
            day = parts["BYMONTHDAY"]?.toIntOrNull() ?: 1,
        )
        else -> RepeatRule.Once
    }
}.getOrDefault(RepeatRule.Once)

/**
 * 把一条日程展开成某区间内的实例。
 * 区间外的不生成 —— 月视图只关心当前这个月，没必要展开一整年。
 */
fun ScheduleEvent.occurrences(from: LocalDate, to: LocalDate): List<ScheduleEvent> {
    if (repeatRule == RepeatRule.Once) {
        val d = startAt.toLocalDate()
        return if (d in from..to) listOf(this) else emptyList()
    }

    val result = mutableListOf<ScheduleEvent>()
    var cursor = startAt
    var guard = 0

    while (cursor.toLocalDate() <= to && guard < 800) {   // guard 防死循环
        guard++
        val date = cursor.toLocalDate()
        val inRange = date >= from

        val matches = when (val r = repeatRule) {
            is RepeatRule.Daily -> true
            is RepeatRule.Weekly -> r.byDays.isEmpty() || date.dayOfWeek in r.byDays
            is RepeatRule.Monthly -> date.dayOfMonth == r.dayOfMonth
            is RepeatRule.Yearly -> date.monthValue == r.month && date.dayOfMonth == r.day
            is RepeatRule.AcademicWeeks -> true
            RepeatRule.Once -> false
        }

        if (inRange && matches) {
            result += copy(id = id, startAt = cursor)
        }

        cursor = when (val r = repeatRule) {
            is RepeatRule.Daily -> cursor.plusDays(r.interval.toLong())
            is RepeatRule.Weekly -> cursor.plusWeeks(r.interval.toLong())
            is RepeatRule.Monthly -> cursor.plusMonths(1)
            is RepeatRule.Yearly -> cursor.plusYears(1)
            is RepeatRule.AcademicWeeks -> cursor.plusWeeks(1)
            RepeatRule.Once -> cursor.plusYears(100)
        }
    }
    return result
}

/** 日程是否「需要提醒」—— 用于 UI 上的铃铛角标 */
val ScheduleEvent.hasReminder: Boolean get() = reminders.isNotEmpty()

/** 把提前量转成人话 */
fun Duration.toHumanOffset(): String {
    val minutes = toMinutes()
    return when {
        minutes <= 0L -> "准时"
        minutes < 60 -> "提前 $minutes 分钟"
        minutes < 60 * 24 -> "提前 ${minutes / 60} 小时"
        else -> "提前 ${minutes / 60 / 24} 天"
    }
}

internal fun CourseColor.asLabel(): String = when (this) {
    CourseColor.TEAL -> "理工"
    CourseColor.VIOLET -> "专业"
    CourseColor.PINK -> "公共"
    CourseColor.ORANGE -> "通识"
    CourseColor.BLUE -> "其他"
}

internal fun LocalDateTime.timeslot(): String = "%02d:%02d".format(hour, minute)
