package com.summer.journal.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.summer.journal.data.datastore.SettingsRepository
import com.summer.journal.data.repo.CalendarRepository
import com.summer.journal.data.repo.timeslot
import com.summer.journal.domain.holiday.HolidayProvider
import com.summer.journal.domain.holiday.LunarCalendar
import com.summer.journal.domain.model.CourseColor
import com.summer.journal.domain.model.ScheduleEvent
import com.summer.journal.ui.theme.SummerPalette
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

/* ══════════════════════════════════════════════════════════════════════
   ViewModel
   ══════════════════════════════════════════════════════════════════════ */

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val repository: CalendarRepository,
    settings: SettingsRepository,
) : ViewModel() {

    private val _yearMonth = MutableStateFlow(YearMonth.now())
    val yearMonth: StateFlow<YearMonth> = _yearMonth.asStateFlow()

    private val _selectedDate = MutableStateFlow(LocalDate.now())
    val selectedDate: StateFlow<LocalDate> = _selectedDate.asStateFlow()

    /**
     * 「显示农历」开关。
     * ★ 设置页早就有这个开关、DataStore 也存了，但日历从来没读过它 ——
     *   所以用户开了开关也看不到农历。这里把它真正接上。
     */
    val showLunar: StateFlow<Boolean> = settings.showLunar
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** 「显示法定节假日」开关：关掉后格子里的节日小字不再显示 */
    val showHoliday: StateFlow<Boolean> = settings.showHoliday
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** 当前月的全部日程（已展开重复规则），按日期分组 */
    val eventsByDate: StateFlow<Map<LocalDate, List<ScheduleEvent>>> = _yearMonth
        .flatMapLatest { ym ->
            val first = ym.atDay(1)
            val last = ym.atEndOfMonth()
            // 多取前后各 7 天，让月初月末那一行的圆点也准确
            repository.observeExpanded(first.minusDays(7), last.plusDays(7))
        }
        .map { list -> list.groupBy { it.startAt.toLocalDate() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val selectedDayEvents: StateFlow<List<ScheduleEvent>> = _selectedDate
        .flatMapLatest { date -> repository.observeExpanded(date, date) }
        .map { list -> list.sortedBy { it.startAt } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun previousMonth() { _yearMonth.value = _yearMonth.value.minusMonths(1) }
    fun nextMonth() { _yearMonth.value = _yearMonth.value.plusMonths(1) }
    fun goToday() {
        val today = LocalDate.now()
        _yearMonth.value = YearMonth.from(today)
        _selectedDate.value = today
    }

    fun selectDate(date: LocalDate) {
        _selectedDate.value = date
        if (YearMonth.from(date) != _yearMonth.value) _yearMonth.value = YearMonth.from(date)
    }

    fun addEvent(title: String, date: LocalDate, hour: Int, location: String?) {
        viewModelScope.launch {
            repository.upsert(
                ScheduleEvent(
                    title = title.ifBlank { "未命名日程" },
                    startAt = date.atTime(hour, 0),
                    endAt = date.atTime(hour + 1, 0),
                    location = location?.takeIf { it.isNotBlank() },
                    color = CourseColor.VIOLET,
                    // 默认提前 30 分钟提醒 —— 用户第一次用就有反馈，比空着强
                    reminders = listOf(java.time.Duration.ofMinutes(30)),
                )
            )
        }
    }

    fun deleteEvent(id: Long) {
        viewModelScope.launch { repository.delete(id) }
    }
}

/**
 * 生成本月日历格。
 *
 * 刻意做成**顶层纯函数**（而不是 ViewModel 的方法）：
 * 它只依赖入参，可以被单元测试直接覆盖 ——
 * 「2026 年 9 月 1 日是周二，前面要补 2 个上月的格子」这种边界 bug 最好在这层拦住。
 */
fun buildMonthGrid(
    ym: YearMonth,
    events: Map<LocalDate, List<ScheduleEvent>>,
    showLunar: Boolean = false,
): List<DayCell> {
    val firstOfMonth = ym.atDay(1)
    // 日历口径：周日是一周第一天
    val leading = firstOfMonth.dayOfWeek.value % 7
    val start = firstOfMonth.minusDays(leading.toLong())
    val today = LocalDate.now()

    return (0 until 42).map { index ->
        val date = start.plusDays(index.toLong())
        val dayEvents = events[date].orEmpty()
        val lunar = if (showLunar) LunarCalendar.from(date) else null
        DayCell(
            date = date,
            inMonth = YearMonth.from(date) == ym,
            isToday = date == today,
            isWeekend = date.dayOfWeek.value >= 6,
            holidayName = HolidayProvider.holidayName(date),
            eventColors = dayEvents.take(3).map { it.color },
            eventCount = dayEvents.size,
            // 优先显示节日名，其次是农历日/月
            lunarLabel = lunar?.let { l ->
                LunarCalendar.festivalOf(date) ?: l.shortLabel
            },
        )
    }
}

data class DayCell(
    val date: LocalDate,
    val inMonth: Boolean,
    val isToday: Boolean,
    val isWeekend: Boolean,
    val holidayName: String?,
    val eventColors: List<CourseColor>,
    val eventCount: Int,
    /** 农历小字：节日名或「初一 / 廿三」；关闭开关时为 null */
    val lunarLabel: String? = null,
)

/* ══════════════════════════════════════════════════════════════════════
   Screen
   ══════════════════════════════════════════════════════════════════════ */

@Composable
fun MonthCalendarScreen(
    viewModel: CalendarViewModel,
    weatherSlot: @Composable () -> Unit,
    onAddEvent: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ym by viewModel.yearMonth.collectAsStateWithLifecycle()
    val selected by viewModel.selectedDate.collectAsStateWithLifecycle()
    val dayEvents by viewModel.selectedDayEvents.collectAsStateWithLifecycle()
    val eventsByDate by viewModel.eventsByDate.collectAsStateWithLifecycle()
    val showLunar by viewModel.showLunar.collectAsStateWithLifecycle()
    val showHoliday by viewModel.showHoliday.collectAsStateWithLifecycle()
    val grid = remember(ym, eventsByDate, showLunar) { buildMonthGrid(ym, eventsByDate, showLunar) }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        item { MonthHero(yearMonth = ym, onPrev = viewModel::previousMonth, onNext = viewModel::nextMonth, onToday = viewModel::goToday) }
        item { Box(modifier = Modifier.padding(horizontal = 20.dp)) { weatherSlot() } }
        item { MonthGrid(grid = grid, selected = selected, onSelect = viewModel::selectDate, showHoliday = showHoliday) }
        item { SelectedDayHeader(selected = selected, count = dayEvents.size, showLunar = showLunar) }
        items(dayEvents, key = { it.id }) { event ->
            EventRow(event = event, onDelete = viewModel::deleteEvent)
        }
        item { AddEventButton(onClick = { onAddEvent(selected) }) }
        item { Box(modifier = Modifier.height(24.dp)) }
    }
}

@Composable
private fun MonthHero(
    yearMonth: YearMonth,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.linearGradient(
                    listOf(
                        SummerPalette.SkyDeep,
                        SummerPalette.SkyMid,
                        SummerPalette.Dusk,
                        Color(0xFFC88AA6),
                    )
                ),
                RoundedCornerShape(bottomStart = 30.dp, bottomEnd = 30.dp),
            )
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("‹", color = Color.White.copy(alpha = 0.85f), fontSize = 26.sp, modifier = Modifier.clickable { onPrev() })
                    Text(
                        text = "${yearMonth.year}年${yearMonth.monthValue}月",
                        color = Color.White,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text("›", color = Color.White.copy(alpha = 0.85f), fontSize = 26.sp, modifier = Modifier.clickable { onNext() })
                }
                Text(
                    text = "今天",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.White.copy(alpha = 0.18f))
                        .clickable { onToday() }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun MonthGrid(
    grid: List<DayCell>,
    selected: LocalDate,
    onSelect: (LocalDate) -> Unit,
    showHoliday: Boolean,
) {
    Column(
        modifier = Modifier
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(SummerPalette.Card)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            listOf("日", "一", "二", "三", "四", "五", "六").forEachIndexed { i, label ->
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        text = label,
                        fontSize = 11.sp,
                        color = if (i == 0 || i == 6) SummerPalette.SunsetPink else SummerPalette.InkTertiary,
                    )
                }
            }
        }
        (0 until 6).forEach { week ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = if (week == 0) 4.dp else 2.dp),
            ) {
                (0 until 7).forEach { dayIndex ->
                    val cell = grid.getOrNull(week * 7 + dayIndex)
                    Box(modifier = Modifier.weight(1f)) {
                        if (cell != null) {
                            DayCellView(
                                cell = cell,
                                selected = cell.date == selected,
                                showHoliday = showHoliday,
                                onClick = { onSelect(cell.date) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCellView(cell: DayCell, selected: Boolean, showHoliday: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .padding(1.dp)
            .aspectRatio(0.92f)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) SummerPalette.HairlineSoft else Color.Transparent)
            .clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (cell.isToday) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(SummerPalette.Dusk, Color(0xFFC88AA6), SummerPalette.AmberLight))
                        ),
                )
            }
            Text(
                text = cell.date.dayOfMonth.toString(),
                fontSize = 14.sp,
                fontWeight = if (cell.isToday) FontWeight.Bold else FontWeight.Medium,
                color = when {
                    cell.isToday -> Color.White
                    !cell.inMonth -> SummerPalette.InkTertiary.copy(alpha = 0.45f)
                    cell.isWeekend || cell.holidayName != null -> SummerPalette.SunsetPink
                    else -> SummerPalette.Ink
                },
            )
        }
        val holidayLabel = cell.holidayName.takeIf { showHoliday }
        when {
            // 有节假日名（且开关打开）：优先显示节日，信息量最大
            holidayLabel != null -> Text(
                text = holidayLabel,
                fontSize = 8.sp,
                color = SummerPalette.Amber,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            // 否则显示农历小字（「初一 / 廿三 / 八月」），开关关闭时为 null 不占位
            cell.lunarLabel != null -> Text(
                text = cell.lunarLabel,
                fontSize = 8.sp,
                color = if (!cell.inMonth) {
                    SummerPalette.InkTertiary.copy(alpha = 0.4f)
                } else {
                    SummerPalette.InkTertiary
                },
                maxLines = 1,
            )
            else -> Row(
                modifier = Modifier.height(6.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                cell.eventColors.forEach { color ->
                    Box(
                        modifier = Modifier
                            .size(4.5.dp)
                            .clip(CircleShape)
                            .background(color.dotColor()),
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectedDayHeader(selected: LocalDate, count: Int, showLunar: Boolean) {
    val lunar = remember(selected, showLunar) {
        if (showLunar) LunarCalendar.from(selected) else null
    }
    val festival = remember(selected, showLunar) {
        if (showLunar) LunarCalendar.festivalOf(selected) else null
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, end = 20.dp, top = 4.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = "${selected.monthValue}月${selected.dayOfMonth}日 · ${dayName(selected)}",
                fontSize = 15.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = SummerPalette.Ink,
            )
            if (lunar != null) {
                Text(
                    text = listOfNotNull(festival, "农历${lunar.fullText}")
                        .distinct()
                        .joinToString(" · "),
                    fontSize = 11.sp,
                    color = SummerPalette.InkTertiary,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
        if (count > 0) {
            Text(text = "$count 项", fontSize = 12.sp, color = SummerPalette.InkTertiary)
        }
    }
}

@Composable
private fun EventRow(event: ScheduleEvent, onDelete: (Long) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(SummerPalette.Card)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .width(3.5.dp)
                .height(38.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(event.color.dotColor()),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = event.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = SummerPalette.Ink)
            Text(
                text = listOfNotNull(event.startAt.timeslot(), event.location).joinToString(" · "),
                fontSize = 11.5.sp,
                color = SummerPalette.InkTertiary,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        Text(
            text = "删除",
            fontSize = 12.sp,
            color = SummerPalette.InkTertiary,
            modifier = Modifier
                .clickable { onDelete(event.id) }
                .padding(6.dp),
        )
    }
}

@Composable
private fun AddEventButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .height(48.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.linearGradient(
                    listOf(SummerPalette.Dusk, SummerPalette.Lilac, Color(0xFFC88AA6))
                )
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text("＋ 新建日程", color = Color.White, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun dayName(date: LocalDate): String = when (date.dayOfWeek.value) {
    1 -> "星期一"; 2 -> "星期二"; 3 -> "星期三"; 4 -> "星期四"
    5 -> "星期五"; 6 -> "星期六"; else -> "星期日"
}

internal fun CourseColor.dotColor(): Color = when (this) {
    CourseColor.TEAL -> SummerPalette.Teal
    CourseColor.VIOLET -> SummerPalette.Lilac
    CourseColor.PINK -> SummerPalette.SunsetPink
    CourseColor.ORANGE -> SummerPalette.Amber
    CourseColor.BLUE -> Color(0xFF7C8FE0)
}
