package com.summer.journal.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.summer.journal.domain.model.CourseColor
import com.summer.journal.domain.model.RepeatRule
import com.summer.journal.domain.model.ScheduleEvent
import com.summer.journal.ui.theme.SummerPalette
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/* ══════════════════════════════════════════════════════════════════════
   选项表（UI 文案 ↔ 领域模型 的唯一映射点）
   ══════════════════════════════════════════════════════════════════════ */

private enum class RepeatChoice(val label: String) {
    ONCE("不重复"),
    DAILY("每天"),
    WEEKLY("每周"),
    MONTHLY("每月"),
    YEARLY("每年"),
}

/** 提醒提前量。0 = 准时提醒 */
private val REMINDER_CHOICES: List<Pair<String, Long>> = listOf(
    "准时" to 0L,
    "提前 5 分钟" to 5L,
    "提前 15 分钟" to 15L,
    "提前 30 分钟" to 30L,
    "提前 1 小时" to 60L,
    "提前 1 天" to 60L * 24,
)

/** 常用标签。用户也能自己输入 —— 这些只是「一键填」的快捷方式 */
private val TAG_PRESETS = listOf("课程", "考试", "作业", "会议", "运动", "复习", "生活")

/* ══════════════════════════════════════════════════════════════════════
   新建 / 编辑日程
   ══════════════════════════════════════════════════════════════════════ */

/**
 * 日程编辑器（底部弹层）。
 *
 * @param initial     null = 新建；非 null = 编辑已有日程
 * @param defaultDate 新建时预填的日期（来自日历上选中的那天）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventEditorSheet(
    initial: ScheduleEvent?,
    defaultDate: LocalDate,
    onDismiss: () -> Unit,
    onSave: (ScheduleEvent) -> Unit,
    onDelete: ((Long) -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val baseStart = initial?.startAt
        ?: LocalDateTime.of(defaultDate, LocalTime.of(9, 0))
    val baseEnd = initial?.endAt
        ?: baseStart.plusHours(1)

    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var date by remember { mutableStateOf(baseStart.toLocalDate()) }
    var startTime by remember { mutableStateOf(baseStart.toLocalTime()) }
    var endTime by remember { mutableStateOf(baseEnd.toLocalTime()) }
    var location by remember { mutableStateOf(initial?.location.orEmpty()) }
    var color by remember { mutableStateOf(initial?.color ?: CourseColor.VIOLET) }
    var repeat by remember {
        mutableStateOf(
            when (initial?.repeatRule) {
                is RepeatRule.Daily -> RepeatChoice.DAILY
                is RepeatRule.Weekly -> RepeatChoice.WEEKLY
                is RepeatRule.Monthly -> RepeatChoice.MONTHLY
                is RepeatRule.Yearly -> RepeatChoice.YEARLY
                else -> RepeatChoice.ONCE
            }
        )
    }
    var reminderMinutes by remember {
        // 默认「提前 30 分钟」——用户第一次用就有反馈，比空着强
        mutableStateOf(initial?.reminders?.map { it.toMinutes() } ?: listOf(30L))
    }
    var tags by remember { mutableStateOf(initial?.tags ?: emptySet<String>()) }
    var tagInput by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SummerPalette.Background,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
        ) {
            // ── 抓手 + 标题栏 ──
            Box(
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 14.dp)
                    .size(width = 40.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(SummerPalette.Hairline)
                    .align(Alignment.CenterHorizontally),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (initial == null) "新建日程" else "编辑日程",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = SummerPalette.Ink,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (initial != null && onDelete != null) {
                        TextButton(onClick = { onDelete(initial.id) }) {
                            Text("删除", color = Color(0xFFB9636F), fontSize = 13.sp)
                        }
                    }
                }
            }

            // ── 标题 ──
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                placeholder = { Text("准备做什么？", fontSize = 14.sp) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            )

            // ── 时间：日期步进 + 起止时间步进 ──
            SectionLabel("时间")
            StepperRow(
                label = "日期",
                value = "${date.monthValue}月${date.dayOfMonth}日 ${weekdayShort(date)}",
                onMinus = { date = date.minusDays(1) },
                onPlus = { date = date.plusDays(1) },
            )
            StepperRow(
                label = "开始",
                value = "%02d:%02d".format(startTime.hour, startTime.minute),
                onMinus = { startTime = startTime.minusMinutes(30) },
                onPlus = { startTime = startTime.plusMinutes(30) },
            )
            StepperRow(
                label = "结束",
                value = "%02d:%02d".format(endTime.hour, endTime.minute),
                onMinus = { endTime = endTime.minusMinutes(30) },
                onPlus = { endTime = endTime.plusMinutes(30) },
            )

            // ── 重复规则 ──
            SectionLabel("重复")
            ChipRow(
                options = RepeatChoice.entries.map { it.label },
                isSelected = { index -> RepeatChoice.entries[index] == repeat },
                onSelect = { index -> repeat = RepeatChoice.entries[index] },
            )

            // ── 提前提醒 ──
            SectionLabel("提前提醒")
            ChipRow(
                options = REMINDER_CHOICES.map { it.first },
                isSelected = { index -> REMINDER_CHOICES[index].second in reminderMinutes },
                onSelect = { index ->
                    val value = REMINDER_CHOICES[index].second
                    reminderMinutes = if (value in reminderMinutes) {
                        reminderMinutes - value
                    } else {
                        reminderMinutes + value
                    }
                },
                multi = true,
            )

            // ── 标签 / 所属课程 ──
            SectionLabel("所属课程 / 标签")
            ChipRow(
                options = TAG_PRESETS,
                isSelected = { index -> TAG_PRESETS[index] in tags },
                onSelect = { index ->
                    val tag = TAG_PRESETS[index]
                    tags = if (tag in tags) tags - tag else tags + tag
                },
                multi = true,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 6.dp),
            ) {
                OutlinedTextField(
                    value = tagInput,
                    onValueChange = { tagInput = it },
                    placeholder = { Text("自定义标签，回车添加", fontSize = 13.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    val t = tagInput.trim()
                    if (t.isNotEmpty()) {
                        tags = tags + t
                        tagInput = ""
                    }
                }) { Text("添加", fontSize = 13.sp) }
            }

            // ── 颜色分组 ──
            SectionLabel("颜色分组")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                CourseColor.entries.forEach { item ->
                    val selected = item == color
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(item.containerColor())
                            .border(
                                width = if (selected) 2.dp else 0.dp,
                                color = if (selected) item.dotColor() else Color.Transparent,
                                shape = CircleShape,
                            )
                            .clickable { color = item }
                            .padding(9.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(item.dotColor()),
                        )
                    }
                }
            }

            // ── 保存 ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 22.dp)
                    .height(50.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(SummerPalette.Dusk, SummerPalette.Lilac, Color(0xFFC88AA6))
                        )
                    )
                    .clickable {
                        onSave(
                            buildEvent(
                                initial = initial,
                                title = title,
                                date = date,
                                startTime = startTime,
                                endTime = endTime,
                                location = location,
                                color = color,
                                repeat = repeat,
                                reminderMinutes = reminderMinutes,
                                tags = tags,
                            )
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (initial == null) "保存日程" else "保存修改",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Text(
                text = "提醒会在保存后自动排入系统闹钟；" +
                    "荣耀机型需要在「我的 → 提醒权限」里放行后台运行，否则到点不会响。",
                fontSize = 10.5.sp,
                color = SummerPalette.InkTertiary,
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }

    /** 开始时间变了，结束时间至少晚 30 分钟 —— 否则会出现「15:00 到 14:00」这种反的区间 */
    fun syncEnd() {
        if (!endTime.isAfter(startTime)) endTime = startTime.plusMinutes(30)
    }
}

/* ══════════════════════════════════════════════════════════════════════
   组装 ScheduleEvent
   ══════════════════════════════════════════════════════════════════════ */

private fun buildEvent(
    initial: ScheduleEvent?,
    title: String,
    date: LocalDate,
    startTime: LocalTime,
    endTime: LocalTime,
    location: String,
    color: CourseColor,
    repeat: RepeatChoice,
    reminderMinutes: List<Long>,
    tags: Set<String>,
): ScheduleEvent {
    val start = LocalDateTime.of(date, startTime)
    // 结束早于等于开始时兜底成 1 小时 —— 避免把「15:00 到 14:00」这种反区间写进库
    val rawEnd = LocalDateTime.of(date, endTime)
    val end = if (rawEnd.isAfter(start)) rawEnd else start.plusHours(1)

    val rule: RepeatRule = when (repeat) {
        RepeatChoice.ONCE -> RepeatRule.Once
        RepeatChoice.DAILY -> RepeatRule.Daily(1)
        RepeatChoice.WEEKLY -> RepeatRule.Weekly(1, setOf(date.dayOfWeek))
        RepeatChoice.MONTHLY -> RepeatRule.Monthly(date.dayOfMonth)
        RepeatChoice.YEARLY -> RepeatRule.Yearly(date.monthValue, date.dayOfMonth)
        // 学期周重复在课表里用，日程编辑器不暴露 —— 避免两条入口写同一份数据打架
    }

    return ScheduleEvent(
        id = initial?.id ?: 0L,
        title = title.ifBlank { "未命名日程" },
        note = initial?.note,
        startAt = start,
        endAt = end,
        allDay = false,
        location = location.takeIf { it.isNotBlank() },
        repeatRule = rule,
        color = color,
        reminders = reminderMinutes.sorted().map { Duration.ofMinutes(it) },
        tags = tags,
        isOutdoor = initial?.isOutdoor ?: false,
    )
}

/* ══════════════════════════════════════════════════════════════════════
   小件
   ══════════════════════════════════════════════════════════════════════ */

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 12.5.sp,
        fontWeight = FontWeight.SemiBold,
        color = SummerPalette.InkSecondary,
        modifier = Modifier.padding(top = 18.dp, bottom = 8.dp),
    )
}

@Composable
private fun StepperRow(
    label: String,
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(SummerPalette.Card)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 12.5.sp,
            color = SummerPalette.InkTertiary,
            modifier = Modifier.width(44.dp),
        )
        Spacer(modifier = Modifier.weight(1f))
        StepButton("−", onMinus)
        Text(
            text = value,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = SummerPalette.Ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(96.dp),
        )
        StepButton("＋", onPlus)
    }
}

@Composable
private fun StepButton(symbol: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(SummerPalette.HairlineSoft)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, fontSize = 15.sp, color = SummerPalette.InkSecondary)
    }
}

@Composable
private fun ChipRow(
    options: List<String>,
    isSelected: (Int) -> Boolean,
    onSelect: (Int) -> Unit,
    multi: Boolean = false,
) {
    // 选项少于一屏宽时排一行；多了自动换行（FlowRow 需要 experimental，这里手动分行更可控）
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        options.chunked(3).forEachIndexed { rowIndex, rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                rowItems.forEachIndexed { colIndex, label ->
                    val index = rowIndex * 3 + colIndex
                    val selected = isSelected(index)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(
                                if (selected) SummerPalette.CategoryViolet else SummerPalette.Card
                            )
                            .clickable { onSelect(index) }
                            .padding(horizontal = 13.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = if (multi && selected) "✓ $label" else label,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) Color(0xFF5346AE) else SummerPalette.InkSecondary,
                        )
                    }
                }
            }
        }
    }
}
