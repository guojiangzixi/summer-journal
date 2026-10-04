package com.summer.journal.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.summer.journal.data.repo.toHumanOffset
import com.summer.journal.domain.holiday.HolidayProvider
import com.summer.journal.domain.model.CourseColor
import com.summer.journal.domain.model.ScheduleEvent
import com.summer.journal.ui.theme.SummerPalette
import java.time.LocalDate
import java.time.YearMonth

/* ══════════════════════════════════════════════════════════════════════
   视图模式：年 / 月 / 周 / 日
   ══════════════════════════════════════════════════════════════════════ */

enum class CalendarViewMode(val label: String) {
    YEAR("年"),
    MONTH("月"),
    WEEK("周"),
    DAY("日"),
    ;

    companion object {
        /** 从 DataStore 里存的字符串解析；非法值回落月视图 */
        fun fromKey(key: String?): CalendarViewMode =
            entries.firstOrNull { it.name.equals(key, ignoreCase = true) } ?: MONTH
    }
}

/** hero 上的「年/月/周/日」分段切换 */
@Composable
fun ViewModeSegmented(
    mode: CalendarViewMode,
    onModeChange: (CalendarViewMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(999.dp))
            .background(Color.White.copy(alpha = 0.15f))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        CalendarViewMode.entries.forEach { item ->
            val active = item == mode
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(999.dp))
                    .then(
                        if (active) Modifier.background(Color.White) else Modifier
                    )
                    .clickable { onModeChange(item) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = item.label,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (active) SummerPalette.Ink else Color.White.copy(alpha = 0.78f),
                )
            }
        }
    }
}

/* ══════════════════════════════════════════════════════════════════════
   周视图：一周七天横条
   ══════════════════════════════════════════════════════════════════════ */

/**
 * 七天的横条。今天用渐变实心高亮，选中的那天用浅底。
 *
 * 注意「今天」和「选中」是两件事：翻到别的周时今天不在这周里，
 * 而选中日可能在另一天 —— 两者必须能同时表达，否则用户会迷失。
 */
@Composable
fun WeekBar(
    weekStart: LocalDate,
    selected: LocalDate,
    eventCounts: Map<LocalDate, Int>,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val today = LocalDate.now()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        (0..6).forEach { offset ->
            val date = weekStart.plusDays(offset.toLong())
            val isToday = date == today
            val isSelected = date == selected
            val hasEvents = (eventCounts[date] ?: 0) > 0

            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .then(
                        when {
                            isToday -> Modifier.background(
                                Brush.linearGradient(
                                    listOf(SummerPalette.Dusk, Color(0xFFC88AA6), SummerPalette.AmberLight)
                                )
                            )
                            isSelected -> Modifier.background(SummerPalette.HairlineSoft)
                            else -> Modifier
                        }
                    )
                    .clickable { onSelect(date) }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = weekdayShort(date),
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = when {
                        isToday -> Color.White.copy(alpha = 0.9f)
                        date.dayOfWeek.value >= 6 -> SummerPalette.SunsetPink
                        else -> SummerPalette.InkTertiary
                    },
                )
                Text(
                    text = date.dayOfMonth.toString(),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isToday) Color.White else SummerPalette.Ink,
                    modifier = Modifier.padding(top = 3.dp),
                )
                // 有日程的点：今天不显示（实心圆已经够显眼，再叠点会脏）
                Box(
                    modifier = Modifier
                        .padding(top = 3.dp)
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                isToday -> Color.Transparent
                                hasEvents -> SummerPalette.Lilac
                                else -> Color.Transparent
                            }
                        ),
                )
            }
        }
    }
}

/* ══════════════════════════════════════════════════════════════════════
   时间轴（周视图 / 日视图共用）
   ══════════════════════════════════════════════════════════════════════ */

/** 时间轴每小时的高度。56dp 是「一个 30 分钟会议能放下两行字」的下限 */
private val TimelineHourHeight: Dp = 56.dp

/**
 * 单日时间轴。
 *
 * 事件块用 offset 绝对定位 —— 这是日历类 UI 的标准做法：
 * 用 Column 顺序排列的话，「14:20 开始的 50 分钟会议」根本没法对齐到 20 分刻度上。
 *
 * @param showReminder 日视图显示「铃铛 + 提前时长」；周视图空间窄，不显示
 */
@Composable
fun DayTimeline(
    events: List<ScheduleEvent>,
    startHour: Int = 8,
    endHour: Int = 22,
    showReminder: Boolean = true,
    onEventClick: (ScheduleEvent) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val totalHours = (endHour - startHour).coerceAtLeast(1)
    val labelWidth = 42.dp
    val maxY = TimelineHourHeight * totalHours

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(maxY)
            .padding(horizontal = 14.dp),
    ) {
        // ── 底层：小时刻度线 ──
        Column(modifier = Modifier.fillMaxWidth()) {
            (startHour until endHour).forEach { hour ->
                Box(modifier = Modifier.height(TimelineHourHeight)) {
                    Text(
                        text = "%02d:00".format(hour),
                        fontSize = 10.5.sp,
                        color = SummerPalette.InkTertiary,
                        modifier = Modifier
                            .width(labelWidth)
                            .offset(y = (-6).dp),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = labelWidth)
                            .offset(y = (-0.5).dp)
                            .height(1.dp)
                            .background(SummerPalette.HairlineSoft),
                    )
                }
            }
        }

        // ── 上层：事件块 ──
        events.forEach { event ->
            val startMinutes = event.startAt.hour * 60 + event.startAt.minute
            val baseMinutes = startHour * 60
            val rawTop = (startMinutes - baseMinutes) / 60f
            // 早于 startHour 的事件贴顶显示，而不是被裁掉 —— 用户宁可看到也不愿「消失」
            val topHours = rawTop.coerceIn(0f, totalHours.toFloat() - 0.4f)

            val end = event.endAt
            val durationMinutes = if (end != null) {
                java.time.Duration.between(event.startAt, end).toMinutes()
            } else {
                // 没填结束时间就按 1 小时画，比画成一条线可点得多
                60L
            }
            val heightDp = (durationMinutes / 60f * TimelineHourHeight.value)
                .coerceIn(34f, (totalHours - topHours) * TimelineHourHeight.value)

            Box(
                modifier = Modifier
                    .offset(
                        x = labelWidth + 6.dp,
                        y = (topHours * TimelineHourHeight.value).dp,
                    )
                    .fillMaxWidth()
                    .height(heightDp.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(event.color.containerColor())
                    .clickable { onEventClick(event) }
                    .padding(horizontal = 9.dp, vertical = 6.dp),
            ) {
                Column {
                    Text(
                        text = event.title,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = event.color.onContainerColor(),
                        maxLines = 1,
                    )
                    val meta = buildList {
                        add(
                            "%02d:%02d".format(event.startAt.hour, event.startAt.minute) +
                                if (end != null) "–%02d:%02d".format(end.hour, end.minute) else ""
                        )
                        event.location?.takeIf { it.isNotBlank() }?.let { add(it) }
                    }.joinToString(" · ")

                    if (heightDp >= 46f) {
                        Text(
                            text = meta,
                            fontSize = 10.sp,
                            color = event.color.onContainerColor().copy(alpha = 0.78f),
                            maxLines = 1,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }

                    // 带提醒的事件：铃铛 + 提前时长（日视图专有）
                    if (showReminder && event.reminders.isNotEmpty() && heightDp >= 58f) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            modifier = Modifier.padding(top = 3.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Notifications,
                                contentDescription = "已设提醒",
                                tint = event.color.onContainerColor().copy(alpha = 0.85f),
                                modifier = Modifier.size(11.dp),
                            )
                            Text(
                                text = event.reminders.min().toHumanOffset(),
                                fontSize = 9.5.sp,
                                color = event.color.onContainerColor().copy(alpha = 0.85f),
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

/* ══════════════════════════════════════════════════════════════════════
   年视图：12 个月迷你月历
   ══════════════════════════════════════════════════════════════════════ */

/**
 * 一整年的迷你月历总览。
 *
 * 每个日期压缩成一个 6dp 的圆点：**粉点 = 有记录**，**橙点 = 节假日**。
 * 点任意月份标题或网格 → 跳到该月的月视图（点月份这个动作是整个年视图的出口，
 * 所以整张卡片都是热区，而不是只有标题一行）。
 */
@Composable
fun YearMiniGrid(
    year: Int,
    hasRecord: (LocalDate) -> Boolean,
    onMonthClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        (1..12).step(3).forEach { rowStart ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                (rowStart until rowStart + 3).forEach { month ->
                    MonthMiniCard(
                        year = year,
                        month = month,
                        hasRecord = hasRecord,
                        onClick = { onMonthClick(month) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun MonthMiniCard(
    year: Int,
    month: Int,
    hasRecord: (LocalDate) -> Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ym = YearMonth.of(year, month)
    val isCurrentMonth = YearMonth.now() == ym
    // 和月视图同一套口径：周日是一周第一天
    val leading = ym.atDay(1).dayOfWeek.value % 7
    val start = ym.atDay(1).minusDays(leading.toLong())

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (isCurrentMonth) SummerPalette.Card else Color(0xFFFCFAF9))
            .clickable { onClick() }
            .padding(horizontal = 7.dp, vertical = 9.dp),
    ) {
        Text(
            text = "${month}月",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = if (isCurrentMonth) Color(0xFF5D51B8) else SummerPalette.InkSecondary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        (0 until 6).forEach { week ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                (0 until 7).forEach { dayIndex ->
                    val date = start.plusDays((week * 7 + dayIndex).toLong())
                    val inMonth = date.monthValue == month
                    val dotColor = when {
                        !inMonth -> Color.Transparent
                        HolidayProvider.isHoliday(date) -> SummerPalette.Amber
                        hasRecord(date) -> SummerPalette.SunsetPink
                        else -> Color(0xFFDCD6E4)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(6.dp)
                            .clip(CircleShape)
                            .background(dotColor),
                    )
                }
            }
        }
    }
}

/* ══════════════════════════════════════════════════════════════════════
   共用小件
   ══════════════════════════════════════════════════════════════════════ */

fun weekdayShort(date: LocalDate): String =
    listOf("日", "一", "二", "三", "四", "五", "六")[date.dayOfWeek.value % 7]

fun weekdayFull(date: LocalDate): String =
    listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")[
        date.dayOfWeek.value - 1
    ]

/** 事件块底色（浅色容器） */
internal fun CourseColor.containerColor(): Color = when (this) {
    CourseColor.TEAL -> SummerPalette.CategoryTeal
    CourseColor.VIOLET -> SummerPalette.CategoryViolet
    CourseColor.PINK -> SummerPalette.CategoryPink
    CourseColor.ORANGE -> SummerPalette.CategoryAmber
    CourseColor.BLUE -> Color(0xFFE8EDFB)
}

/** 事件块文字色（在浅底上保证对比度） */
internal fun CourseColor.onContainerColor(): Color = when (this) {
    CourseColor.TEAL -> Color(0xFF2A7C75)
    CourseColor.VIOLET -> Color(0xFF5346AE)
    CourseColor.PINK -> Color(0xFFAE5C69)
    CourseColor.ORANGE -> Color(0xFFA9642A)
    CourseColor.BLUE -> Color(0xFF3E56A8)
}
