package com.summer.journal.ui.timetable

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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.summer.journal.domain.model.Course
import com.summer.journal.domain.model.CourseColor
import com.summer.journal.domain.model.WeekParity
import com.summer.journal.ui.theme.SummerPalette

/**
 * 添加 / 编辑课程（底部弹层）。
 *
 * 点课表格子直接进到这里改 —— 课程名、教师、教室、周次、节次、单双周、颜色，
 * 也可以整块删除。这是课表页最高频的操作，所以字段全部平铺，不做二级页。
 *
 * @param initial null = 新建（点空格子或「＋ 添加课程」）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseEditorSheet(
    initial: Course?,
    semesterId: Long,
    totalWeeks: Int,
    defaultDay: Int = 1,
    defaultPeriod: IntRange = 1..2,
    onDismiss: () -> Unit,
    onSave: (Course) -> Unit,
    onDelete: ((Long) -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var teacher by remember { mutableStateOf(initial?.teacher.orEmpty()) }
    var room by remember { mutableStateOf(initial?.room.orEmpty()) }
    var day by remember { mutableStateOf(initial?.dayOfWeek ?: defaultDay) }
    var periodStart by remember { mutableStateOf(initial?.periodStart ?: defaultPeriod.first) }
    var periodEnd by remember { mutableStateOf(initial?.periodEnd ?: defaultPeriod.last) }
    var weekStart by remember { mutableStateOf(initial?.weekRange?.first ?: 1) }
    var weekEnd by remember { mutableStateOf(initial?.weekRange?.last ?: totalWeeks) }
    var parity by remember { mutableStateOf(initial?.weekParity ?: WeekParity.ALL) }
    var color by remember { mutableStateOf(initial?.color ?: CourseColor.TEAL) }

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
                    text = if (initial == null) "添加课程" else "编辑课程",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = SummerPalette.Ink,
                )
                if (initial != null && onDelete != null) {
                    TextButton(onClick = { onDelete(initial.id) }) {
                        Text("删除课程", color = Color(0xFFB9636F), fontSize = 13.sp)
                    }
                }
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("课程名，如「高等数学A(二)」", fontSize = 15.sp) },
                singleLine = true,
                // 输入框正文放大加粗一点 —— 用户反馈「添加课表时字看不清」
                textStyle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = SummerPalette.Ink),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            )

            Row(
                modifier = Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = teacher,
                    onValueChange = { teacher = it },
                    placeholder = { Text("教师", fontSize = 14.sp) },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 15.sp, color = SummerPalette.Ink),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = room,
                    onValueChange = { room = it },
                    placeholder = { Text("教室", fontSize = 14.sp) },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 15.sp, color = SummerPalette.Ink),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f),
                )
            }

            Label("星期")
            ChipRow(
                options = listOf("一", "二", "三", "四", "五", "六", "日"),
                selectedIndex = day - 1,
                onSelect = { day = it + 1 },
            )

            Label("节次")
            StepperRow(
                label = "从",
                value = "第 $periodStart 节",
                onMinus = { periodStart = (periodStart - 1).coerceAtLeast(1) },
                onPlus = { periodStart = (periodStart + 1).coerceAtMost(periodEnd) },
            )
            StepperRow(
                label = "到",
                value = "第 $periodEnd 节",
                onMinus = { periodEnd = (periodEnd - 1).coerceAtLeast(periodStart) },
                onPlus = { periodEnd = (periodEnd + 1).coerceAtMost(14) },
            )

            Label("周次（总 $totalWeeks 周）")
            StepperRow(
                label = "从",
                value = "第 $weekStart 周",
                onMinus = { weekStart = (weekStart - 1).coerceAtLeast(1) },
                onPlus = { weekStart = (weekStart + 1).coerceAtMost(weekEnd) },
            )
            StepperRow(
                label = "到",
                value = "第 $weekEnd 周",
                onMinus = { weekEnd = (weekEnd - 1).coerceAtLeast(weekStart) },
                onPlus = { weekEnd = (weekEnd + 1).coerceAtMost(totalWeeks) },
            )

            Label("单双周")
            ChipRow(
                options = listOf("每周", "单周", "双周"),
                selectedIndex = when (parity) {
                    WeekParity.ALL -> 0
                    WeekParity.ODD -> 1
                    WeekParity.EVEN -> 2
                },
                onSelect = {
                    parity = when (it) {
                        1 -> WeekParity.ODD
                        2 -> WeekParity.EVEN
                        else -> WeekParity.ALL
                    }
                },
            )

            Label("颜色")
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
                            .background(timetableContainer(item))
                            .border(
                                width = if (selected) 2.dp else 0.dp,
                                color = if (selected) timetableDot(item) else Color.Transparent,
                                shape = CircleShape,
                            )
                            .clickable { color = item }
                            .padding(9.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(timetableDot(item)),
                        )
                    }
                }
            }

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
                            Course(
                                id = initial?.id ?: 0L,
                                semesterId = semesterId,
                                name = name.ifBlank { "未命名课程" },
                                teacher = teacher.takeIf { it.isNotBlank() },
                                room = room.takeIf { it.isNotBlank() },
                                dayOfWeek = day,
                                periodStart = periodStart,
                                periodEnd = periodEnd,
                                weekParity = parity,
                                weekRange = weekStart..weekEnd,
                                color = color,
                                note = initial?.note,
                            )
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (initial == null) "添加课程" else "保存修改",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            if (initial != null) {
                Text(
                    text = "删除后这节课会从课表和上课提醒里一起移除。",
                    fontSize = 10.5.sp,
                    color = SummerPalette.InkTertiary,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
    }
}

/* ────────────── 小件（与日程编辑器同款，但独立一份避免跨包暴露）────────────── */

@Composable
private fun Label(text: String) {
    Text(
        text = text,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        color = SummerPalette.Ink,
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
            fontSize = 13.5.sp,
            color = SummerPalette.InkSecondary,
            modifier = Modifier.width(36.dp),
        )
        Spacer(modifier = Modifier.weight(1f))
        StepButton("−", onMinus)
        Text(
            text = value,
            fontSize = 15.5.sp,
            fontWeight = FontWeight.Bold,
            color = SummerPalette.Ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(104.dp),
        )
        StepButton("＋", onPlus)
    }
}

@Composable
private fun StepButton(symbol: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(SummerPalette.HairlineSoft)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = SummerPalette.Ink)
    }
}

@Composable
private fun ChipRow(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(
                        if (selected) SummerPalette.CategoryViolet else SummerPalette.Card
                    )
                    .clickable { onSelect(index) }
                    .padding(horizontal = 15.dp, vertical = 9.dp),
            ) {
                Text(
                    text = label,
                    fontSize = 13.5.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = if (selected) Color(0xFF5346AE) else SummerPalette.InkSecondary,
                )
            }
        }
    }
}

internal fun timetableDot(color: CourseColor): Color = when (color) {
    CourseColor.TEAL -> SummerPalette.Teal
    CourseColor.VIOLET -> SummerPalette.Lilac
    CourseColor.PINK -> SummerPalette.SunsetPink
    CourseColor.ORANGE -> SummerPalette.Amber
    CourseColor.BLUE -> Color(0xFF7C8FE0)
}

internal fun timetableContainer(color: CourseColor): Color = when (color) {
    CourseColor.TEAL -> SummerPalette.CategoryTeal
    CourseColor.VIOLET -> SummerPalette.CategoryViolet
    CourseColor.PINK -> SummerPalette.CategoryPink
    CourseColor.ORANGE -> SummerPalette.CategoryAmber
    CourseColor.BLUE -> Color(0xFFE8EDFB)
}
