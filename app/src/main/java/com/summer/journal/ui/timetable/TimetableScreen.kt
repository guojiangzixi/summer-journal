package com.summer.journal.ui.timetable

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.summer.journal.domain.model.Course
import com.summer.journal.domain.model.CourseColor
import com.summer.journal.domain.model.WeekParity
import com.summer.journal.ui.theme.SummerPalette
import java.time.LocalDate

/* ══════════════════════════════════════════════════════════════════════
   课表的三种读法
   ══════════════════════════════════════════════════════════════════════

   390px 宽的手机上，7 列 × 每格约 40px，课程名只能排两行、挤得看不清。
   所以给三种模式，由用户按需切：

     WEEKDAYS_ONLY       5 列，每格约 57px —— 上课看这个，信息最全
     FULL_WEEK           7 列，每格约 40px —— 一眼看全周
     HORIZONTAL_SCROLL   7 列，每格 74px，左右滑动 —— 要看周末又要看清字
   ══════════════════════════════════════════════════════════════════════ */

enum class TimetableMode(val label: String) {
    WEEKDAYS_ONLY("只看工作日"),
    FULL_WEEK("显示周末"),
    HORIZONTAL_SCROLL("横滑"),
    ;

    val visibleDays: List<Int>
        get() = when (this) {
            WEEKDAYS_ONLY -> listOf(1, 2, 3, 4, 5)
            FULL_WEEK, HORIZONTAL_SCROLL -> listOf(7, 1, 2, 3, 4, 5, 6)   // 教务口径：周日在前
        }

    val cellWidth: Dp
        get() = if (this == HORIZONTAL_SCROLL) 74.dp else 0.dp
}

/** 中南大学标准作息 —— 放在这里而不是硬编码进 UI，设置页也要引用 */
val PERIOD_SLOTS: List<PeriodSlot> = listOf(
    PeriodSlot(1, 2, "08:00", "09:40"),
    PeriodSlot(3, 4, "10:00", "11:40"),
    PeriodSlot(5, 6, "14:00", "15:40"),
    PeriodSlot(7, 8, "16:00", "17:40"),
    PeriodSlot(9, 10, "19:00", "20:40"),
    PeriodSlot(11, 12, "20:50", "22:30"),
)

data class PeriodSlot(val start: Int, val end: Int, val from: String, val to: String) {
    val label: String get() = "$start-$end"
}

/** 导入结果 / 进行中的提示条 */
sealed interface ImportBanner {
    data object Running : ImportBanner
    data class Done(val added: Int, val replaced: Int, val unrecognized: Int) : ImportBanner
    data class Failed(val message: String) : ImportBanner
}

/* ══════════════════════════════════════════════════════════════════════
   Screen
   ══════════════════════════════════════════════════════════════════════ */

@Composable
fun TimetableScreen(
    semesterName: String,
    courses: List<Course>,
    mode: TimetableMode,
    importBanner: ImportBanner?,
    semesterStart: LocalDate?,
    totalWeeks: Int,
    onModeChange: (TimetableMode) -> Unit,
    onCourseClick: (Course) -> Unit,
    onEmptySlotClick: (dayOfWeek: Int, slot: PeriodSlot) -> Unit,
    onAddCourse: () -> Unit,
    onImportFromJwxt: () -> Unit,
    onScanImage: () -> Unit,
    onSeedSample: () -> Unit,
    onClearAll: () -> Unit,
    onDismissBanner: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 「按周查看」的当前周次。默认停在今天所在的周；学期没开始或已结束则停在第一周。
    var selectedWeek by remember(semesterStart, totalWeeks) {
        mutableIntStateOf(
            semesterStart?.let { weekOfToday(it, totalWeeks) } ?: 1
        )
    }
    val safeTotalWeeks = totalWeeks.coerceAtLeast(1)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        // 表头：学期名 + 两个入口。
        // ★ 没有这两个按钮，用户根本没办法把课加进来 —— 之前课表空白就是这个原因。
        item {
            TimetableHeader(
                semesterName = semesterName,
                courseCount = courses.size,
                onImportFromJwxt = onImportFromJwxt,
                onAddCourse = onAddCourse,
            )
        }

        importBanner?.let { banner ->
            item { ImportBannerRow(banner = banner, onDismiss = onDismissBanner) }
        }

        if (courses.isEmpty()) {
            item {
                TimetableEmptyState(
                    onImportFromJwxt = onImportFromJwxt,
                    onAddCourse = onAddCourse,
                    onScanImage = onScanImage,
                    onSeedSample = onSeedSample,
                )
            }
        } else {
            // ★ 「按周查看」：每周单独看，显示这一周真实要上的课 + 具体日期。
            //   解决「一学期 20 周挤在一张网格里、看不出这周到底上什么」的问题。
            item {
                WeekPickerCard(
                    week = selectedWeek,
                    totalWeeks = safeTotalWeeks,
                    semesterStart = semesterStart,
                    weekCourses = coursesInWeek(courses, selectedWeek),
                    onPrev = { selectedWeek = (selectedWeek - 1).coerceAtLeast(1) },
                    onNext = { selectedWeek = (selectedWeek + 1).coerceAtMost(safeTotalWeeks) },
                )
            }
            item { TimetableModeSwitch(mode = mode, onModeChange = onModeChange) }
            item {
                TimetableGrid(
                    courses = courses,
                    mode = mode,
                    onCourseClick = onCourseClick,
                    onEmptySlotClick = onEmptySlotClick,
                )
            }
            item { TimetableLegend() }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SecondaryButton(text = "＋ 添加", onClick = onAddCourse, modifier = Modifier.weight(1f))
                    SecondaryButton(text = "拍照识别", onClick = onScanImage, modifier = Modifier.weight(1f))
                    SecondaryButton(text = "清空", onClick = onClearAll, modifier = Modifier.weight(0.7f))
                }
            }
        }
    }
}

/* ══════════════════════════════════════════════════════════════════════
   按周查看
   ══════════════════════════════════════════════════════════════════════ */

/**
 * 今天落在第几教学周（1-based）。
 * 学期还没开学（今天早于开学日）返回 1；已超过总周数则返回总周数。
 */
internal fun weekOfToday(semesterStart: LocalDate, totalWeeks: Int): Int {
    val days = java.time.temporal.ChronoUnit.DAYS.between(semesterStart, LocalDate.now())
    if (days < 0) return 1
    return (days / 7).toInt().plus(1).coerceIn(1, totalWeeks.coerceAtLeast(1))
}

/**
 * 某一天是否属于某教学周。教学周以「开学日所在那一周的周一开始」计，
 * 与 [weekOfToday] 保持同一口径，否则翻周和显示的日期会对不上。
 */
private fun dateOfWeekDay(semesterStart: LocalDate, week: Int, dayOfWeek: Int): LocalDate {
    val firstMonday = semesterStart.minusDays((semesterStart.dayOfWeek.value - 1).toLong())
    return firstMonday.plusWeeks((week - 1).toLong()).plusDays((dayOfWeek - 1).toLong())
}

/**
 * 某一周真实要上的课。
 *
 * 判定逻辑必须与课程本身的「周次」口径一致：
 *  - 有 weekSet（如「8,12周」）→ 只看 weekSet 是否含该周，**不**再叠加 weekRange，否则会漏课
 *  - 否则用 weekRange + weekParity（单/双周）
 */
internal fun coursesInWeek(courses: List<Course>, week: Int): List<Course> =
    courses.filter { course ->
        if (week < 1) return@filter false
        val weekSet = course.weekSet
        if (weekSet != null) {
            weekSet.contains(week)
        } else {
            week in course.weekRange && when (course.weekParity) {
                WeekParity.ALL -> true
                WeekParity.ODD -> week % 2 == 1
                WeekParity.EVEN -> week % 2 == 0
            }
        }
    }

@Composable
private fun WeekPickerCard(
    week: Int,
    totalWeeks: Int,
    semesterStart: LocalDate?,
    weekCourses: List<Course>,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(SummerPalette.Card)
            .padding(16.dp),
    ) {
        // 行 1：‹ 第 N 周 ›
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WeekNavButton("‹", enabled = week > 1, onClick = onPrev)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "第 $week 周",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = SummerPalette.Ink,
                )
                if (semesterStart != null) {
                    val monday = dateOfWeekDay(semesterStart, week, 1)
                    val sunday = dateOfWeekDay(semesterStart, week, 7)
                    Text(
                        text = "${monday.monthValue}/${monday.dayOfMonth} – " +
                            "${sunday.monthValue}/${sunday.dayOfMonth} · 共 $totalWeeks 周",
                        fontSize = 10.5.sp,
                        color = SummerPalette.InkTertiary,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                } else {
                    Text(
                        text = "共 $totalWeeks 周",
                        fontSize = 10.5.sp,
                        color = SummerPalette.InkTertiary,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
            WeekNavButton("›", enabled = week < totalWeeks, onClick = onNext)
        }

        // 行 2：该周的课
        if (weekCourses.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(SummerPalette.HairlineSoft)
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("这一周没有课", fontSize = 12.5.sp, color = SummerPalette.InkTertiary)
            }
        } else {
            Column(
                modifier = Modifier.padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                weekCourses
                    .sortedWith(compareBy({ it.dayOfWeek }, { it.periodStart }))
                    .forEach { course ->
                        WeekCourseRow(course = course, semesterStart = semesterStart, week = week)
                    }
            }
        }
    }
}

@Composable
private fun WeekCourseRow(course: Course, semesterStart: LocalDate?, week: Int) {
    val accent = course.color.palette().second
    val dateText = semesterStart?.let {
        val d = dateOfWeekDay(it, week, course.dayOfWeek)
        "${d.monthValue}/${d.dayOfMonth}"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(accent.copy(alpha = 0.10f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(32.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(accent),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp),
        ) {
            Text(
                text = course.name,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = SummerPalette.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append(dayLabel(course.dayOfWeek))
                    append(" · ${course.periodStart}-${course.periodEnd} 节")
                    course.room?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                },
                fontSize = 11.sp,
                color = SummerPalette.InkSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (dateText != null) {
            Text(
                text = dateText,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = accent,
            )
        }
    }
}

@Composable
private fun WeekNavButton(symbol: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(
                if (enabled) SummerPalette.HairlineSoft else SummerPalette.HairlineSoft.copy(alpha = 0.4f)
            )
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = symbol,
            fontSize = 18.sp,
            color = if (enabled) SummerPalette.InkSecondary else SummerPalette.InkTertiary.copy(alpha = 0.5f),
        )
    }
}

@Composable
private fun TimetableHeader(
    semesterName: String,
    courseCount: Int,
    onImportFromJwxt: () -> Unit,
    onAddCourse: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.linearGradient(
                    listOf(SummerPalette.SkyDeep, SummerPalette.SkyMid, SummerPalette.Dusk, Color(0xFFC88AA6))
                ),
                RoundedCornerShape(bottomStart = 30.dp, bottomEnd = 30.dp),
            )
            .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 22.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = semesterName,
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (courseCount == 0) "还没有课程" else "共 $courseCount 门课",
                    color = Color.White.copy(alpha = 0.78f),
                    fontSize = 11.5.sp,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HeaderButton(text = "导入", onClick = onImportFromJwxt)
                HeaderButton(text = "＋ 添加", onClick = onAddCourse, primary = true)
            }
        }
    }
}

@Composable
private fun HeaderButton(text: String, onClick: () -> Unit, primary: Boolean = false) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(
                if (primary) Color.White.copy(alpha = 0.95f) else Color.White.copy(alpha = 0.18f)
            )
            .clickable { onClick() }
            .padding(horizontal = 13.dp, vertical = 8.dp),
    ) {
        Text(
            text = text,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (primary) SummerPalette.Dusk else Color.White,
        )
    }
}

/**
 * 空态。
 *
 * 必须解释清楚「为什么是空的」和「怎么填满」——
 * 只给一个空白网格，用户会以为 App 坏了（这正是之前的表现）。
 */
@Composable
private fun TimetableEmptyState(
    onImportFromJwxt: () -> Unit,
    onAddCourse: () -> Unit,
    onScanImage: () -> Unit,
    onSeedSample: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 18.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(SummerPalette.Card)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .height(44.dp)
                .width(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(SummerPalette.CategoryViolet),
            contentAlignment = Alignment.Center,
        ) {
            Text("课", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF5346AE))
        }

        Text(
            text = "这个学期还没有课程",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = SummerPalette.Ink,
        )
        Text(
            text = "课表是空的，所以网格里什么都没显示。\n用下面任意一种方式把课上进来：",
            fontSize = 12.5.sp,
            color = SummerPalette.InkTertiary,
            lineHeight = 19.sp,
            textAlign = TextAlign.Center,
        )

        PrimaryButton(text = "从教务系统一键导入", onClick = onImportFromJwxt)
        SecondaryButton(text = "拍/选一张课表截图识别", onClick = onScanImage, modifier = Modifier.fillMaxWidth())
        SecondaryButton(text = "手动添加课程", onClick = onAddCourse, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = onSeedSample) {
            Text("先载入示例课表看看效果", fontSize = 12.5.sp, color = SummerPalette.InkTertiary)
        }
        Text(
            text = "示例数据只是用来看排版，随时可以在课表页底部一键清空。",
            fontSize = 11.sp,
            color = SummerPalette.InkTertiary.copy(alpha = 0.8f),
            lineHeight = 16.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ImportBannerRow(banner: ImportBanner, onDismiss: () -> Unit) {
    val (bg, fg) = when (banner) {
        ImportBanner.Running -> SummerPalette.CategoryViolet to Color(0xFF5346AE)
        is ImportBanner.Done -> SummerPalette.CategoryTeal to Color(0xFF2A7C75)
        is ImportBanner.Failed -> SummerPalette.CategoryPink to Color(0xFFAE5C69)
    }
    val message = when (banner) {
        ImportBanner.Running -> "正在解析教务课表…"
        is ImportBanner.Done -> buildString {
            append("导入完成：新增 ${banner.added} 门")
            if (banner.replaced > 0) append("，覆盖 ${banner.replaced} 门")
            if (banner.unrecognized > 0) append("，${banner.unrecognized} 个格子没认出来")
        }
        is ImportBanner.Failed -> banner.message
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 14.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = message, fontSize = 12.5.sp, color = fg, modifier = Modifier.weight(1f), lineHeight = 18.sp)
        if (banner != ImportBanner.Running) {
            Text(
                text = "知道了",
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = fg,
                modifier = Modifier
                    .clickable { onDismiss() }
                    .padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(
                Brush.linearGradient(listOf(SummerPalette.Dusk, SummerPalette.Lilac, Color(0xFFC88AA6)))
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(46.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(SummerPalette.HairlineSoft)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = SummerPalette.InkSecondary, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
    }
}

/** 三态切换 */
@Composable
private fun TimetableModeSwitch(
    mode: TimetableMode,
    onModeChange: (TimetableMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(SummerPalette.HairlineSoft)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TimetableMode.entries.forEach { entry ->
            val selected = entry == mode
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(999.dp))
                    .then(if (selected) Modifier.background(Color.White) else Modifier)
                    .clickable { onModeChange(entry) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = entry.label,
                    fontSize = 12.5.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) SummerPalette.Ink else SummerPalette.InkTertiary,
                )
            }
        }
    }
}

@Composable
private fun TimetableGrid(
    courses: List<Course>,
    mode: TimetableMode,
    onCourseClick: (Course) -> Unit,
    onEmptySlotClick: (Int, PeriodSlot) -> Unit,
) {
    val days = mode.visibleDays
    val horizontal = mode == TimetableMode.HORIZONTAL_SCROLL

    // 注意顺序：horizontalScroll 必须在 fillMaxWidth 之外，两个一起用会互相抵消
    val scrollState = rememberScrollState()
    val gridModifier = Modifier
        .padding(horizontal = if (horizontal) 14.dp else 10.dp)
        .then(
            if (horizontal) Modifier.horizontalScroll(scrollState)
            else Modifier.fillMaxWidth()
        )

    Column(modifier = gridModifier) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = if (horizontal) Modifier else Modifier.fillMaxWidth(),
        ) {
            Box(modifier = Modifier.width(if (horizontal) 30.dp else 32.dp))
            days.forEach { day ->
                Box(
                    modifier = if (horizontal) Modifier.width(mode.cellWidth) else Modifier.weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = dayLabel(day),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = SummerPalette.InkTertiary,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
        }

        PERIOD_SLOTS.forEach { slot ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = (if (horizontal) Modifier else Modifier.fillMaxWidth())
                    .padding(bottom = 4.dp),
            ) {
                Box(
                    modifier = Modifier
                        .width(if (horizontal) 30.dp else 32.dp)
                        .height(if (horizontal) 62.dp else 78.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(SummerPalette.HairlineSoft),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = slot.label,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = SummerPalette.InkTertiary,
                    )
                }

                days.forEach { day ->
                    val course = courses.firstOrNull {
                        it.dayOfWeek == day &&
                            it.periodStart == slot.start &&
                            it.periodEnd == slot.end
                    }
                    Box(
                        modifier = (if (horizontal) Modifier.width(mode.cellWidth) else Modifier.weight(1f))
                            .height(if (horizontal) 62.dp else 78.dp)
                            .clip(RoundedCornerShape(if (horizontal) 9.dp else 10.dp))
                            .then(
                                if (course == null) {
                                    Modifier
                                        .background(SummerPalette.HairlineSoft.copy(alpha = 0.45f))
                                        .clickable { onEmptySlotClick(day, slot) }
                                } else {
                                    Modifier.clickable { onCourseClick(course) }
                                }
                            ),
                    ) {
                        course?.let { CourseBlock(it, compact = horizontal) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CourseBlock(course: Course, compact: Boolean) {
    val (bg, fg) = course.color.palette()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bg)
            .padding(horizontal = 5.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = course.name,
            fontSize = if (compact) 10.sp else 11.sp,
            lineHeight = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = fg,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        course.room?.let {
            Text(
                text = it,
                fontSize = if (compact) 8.5.sp else 9.sp,
                lineHeight = 11.sp,
                color = fg.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 单双周角标：最容易被忽略、又最影响「今天到底上不上课」的信息
        course.parityLabel?.let {
            Text(
                text = "$it · ${course.weekRange.first}-${course.weekRange.last}周",
                fontSize = 8.sp,
                color = fg.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

internal fun CourseColor.palette(): Pair<Color, Color> = when (this) {
    CourseColor.TEAL -> SummerPalette.CategoryTeal to Color(0xFF2A7C75)
    CourseColor.VIOLET -> SummerPalette.CategoryViolet to Color(0xFF5346AE)
    CourseColor.PINK -> SummerPalette.CategoryPink to Color(0xFFAE5C69)
    CourseColor.ORANGE -> SummerPalette.CategoryAmber to Color(0xFFA9642A)
    CourseColor.BLUE -> Color(0xFFE8EEFB) to Color(0xFF3C5CA8)
}

@Composable
private fun TimetableLegend() {
    val items = listOf(
        SummerPalette.Teal to "理工基础",
        SummerPalette.Lilac to "专业核心",
        SummerPalette.SunsetPink to "公共必修",
        SummerPalette.Amber to "实验 / 通识",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items.forEach { (color, label) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Box(
                    modifier = Modifier
                        .width(9.dp)
                        .height(9.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(color),
                )
                Text(text = label, fontSize = 10.5.sp, color = SummerPalette.InkTertiary)
            }
        }
    }
}

/* ══════════════════════════════════════════════════════════════════════
   添加课程弹窗
   ══════════════════════════════════════════════════════════════════════ */

@Composable
fun AddCourseDialog(
    totalWeeks: Int,
    onDismiss: () -> Unit,
    onConfirm: (
        name: String,
        teacher: String?,
        room: String?,
        dayOfWeek: Int,
        periodStart: Int,
        periodEnd: Int,
        weekStart: Int,
        weekEnd: Int,
        parity: WeekParity,
    ) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var teacher by remember { mutableStateOf("") }
    var room by remember { mutableStateOf("") }
    var day by remember { mutableIntStateOf(1) }
    var slotIndex by remember { mutableIntStateOf(0) }
    var weekStart by remember { mutableIntStateOf(1) }
    var weekEnd by remember { mutableIntStateOf(totalWeeks.coerceAtLeast(1)) }
    var parity by remember { mutableStateOf(WeekParity.ALL) }

    val slot = PERIOD_SLOTS[slotIndex]

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加课程") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("课程名（必填）", fontSize = 14.sp, fontWeight = FontWeight.SemiBold) },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = SummerPalette.Ink),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = teacher,
                    onValueChange = { teacher = it },
                    label = { Text("教师（可选）", fontSize = 14.sp, fontWeight = FontWeight.SemiBold) },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 15.sp, color = SummerPalette.Ink),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = room,
                    onValueChange = { room = it },
                    label = { Text("教室（可选）", fontSize = 14.sp, fontWeight = FontWeight.SemiBold) },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 15.sp, color = SummerPalette.Ink),
                    modifier = Modifier.fillMaxWidth(),
                )

                FieldLabel("星期")
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    (1..7).forEach { d ->
                        SelectableChip(
                            text = dayLabel(d).removePrefix("星期"),
                            selected = d == day,
                            onClick = { day = d },
                        )
                    }
                }

                FieldLabel("节次")
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PERIOD_SLOTS.forEachIndexed { index, s ->
                        SelectableChip(
                            text = s.label,
                            selected = index == slotIndex,
                            onClick = { slotIndex = index },
                        )
                    }
                }

                FieldLabel("周次")
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = weekStart.toString(),
                        onValueChange = { input ->
                            weekStart = input.toIntOrNull()?.coerceIn(1, totalWeeks) ?: 1
                        },
                        label = { Text("从") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(88.dp),
                    )
                    Text("—", color = SummerPalette.InkTertiary)
                    OutlinedTextField(
                        value = weekEnd.toString(),
                        onValueChange = { input ->
                            weekEnd = input.toIntOrNull()?.coerceIn(1, totalWeeks) ?: totalWeeks
                        },
                        label = { Text("到") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(88.dp),
                    )
                    Text("周", color = SummerPalette.InkTertiary, fontSize = 13.sp)
                }

                FieldLabel("单双周")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    WeekParity.entries.forEach { p ->
                        SelectableChip(
                            text = parityLabel(p),
                            selected = p == parity,
                            onClick = { parity = p },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onConfirm(
                        name.trim(),
                        teacher.trim().ifBlank { null },
                        room.trim().ifBlank { null },
                        day,
                        slot.start,
                        slot.end,
                        weekStart.coerceAtMost(weekEnd),
                        weekEnd.coerceAtLeast(weekStart),
                        parity,
                    )
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = SummerPalette.InkSecondary)
}

@Composable
private fun SelectableChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) SummerPalette.CategoryViolet else SummerPalette.HairlineSoft)
            .clickable { onClick() }
            .padding(horizontal = 11.dp, vertical = 7.dp),
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) Color(0xFF5346AE) else SummerPalette.InkSecondary,
        )
    }
}

internal fun parityLabel(parity: WeekParity): String = when (parity) {
    WeekParity.ALL -> "每周"
    WeekParity.ODD -> "单周"
    WeekParity.EVEN -> "双周"
}

/**
 * ★ 教务口径是「星期日」在最前，java.time 里 Sunday = 7。
 *   这个映射只写一次（和 CsuJwxtParser 里的 DAY_NAMES 保持一致）。
 */
private fun dayLabel(dayOfWeek: Int): String = when (dayOfWeek) {
    1 -> "星期一"; 2 -> "星期二"; 3 -> "星期三"; 4 -> "星期四"
    5 -> "星期五"; 6 -> "星期六"; 7 -> "星期日"
    else -> ""
}
