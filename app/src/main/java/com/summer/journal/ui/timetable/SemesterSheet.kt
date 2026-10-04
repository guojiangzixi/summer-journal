package com.summer.journal.ui.timetable

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.summer.journal.domain.model.Semester
import com.summer.journal.ui.theme.SummerPalette
import java.time.LocalDate

/**
 * 学期管理。
 *
 * 「按学期归档」是这个页面的核心：课表按学期分开存，
 * 切换学期后课表只显示那一个学期的课，可以随时往**任意**学期继续添加。
 * 这样「上学期的课」不会和新学期混在一张表里。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SemesterSheet(
    semesters: List<Semester>,
    currentId: Long?,
    courseCountOf: (Long) -> Int,
    onSelect: (Long) -> Unit,
    onCreate: (name: String, startDate: LocalDate, totalWeeks: Int) -> Unit,
    onDelete: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showForm by remember { mutableStateOf(false) }

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
                    text = "学期管理",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = SummerPalette.Ink,
                )
                Text(
                    text = if (showForm) "取消" else "＋ 新建学期",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF5D51B8),
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .clickable { showForm = !showForm }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            Text(
                text = "每个学期的课程分开归档。切到哪个学期，课表就显示哪个学期，随时可以继续添加。",
                fontSize = 11.sp,
                color = SummerPalette.InkTertiary,
                lineHeight = 17.sp,
                modifier = Modifier.padding(top = 5.dp, bottom = 14.dp),
            )

            if (showForm) {
                NewSemesterForm(
                    onCreate = { name, start, weeks ->
                        onCreate(name, start, weeks)
                        showForm = false
                    }
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(SummerPalette.Card),
            ) {
                if (semesters.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 26.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("还没有学期", fontSize = 13.sp, color = SummerPalette.InkTertiary)
                    }
                }
                semesters.forEach { semester ->
                    val isCurrent = semester.id == currentId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(semester.id) }
                            .padding(horizontal = 14.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isCurrent) SummerPalette.Lilac else SummerPalette.Hairline
                                ),
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 11.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = semester.name,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = SummerPalette.Ink,
                                )
                                if (isCurrent) {
                                    Text(
                                        text = "当前",
                                        fontSize = 9.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF2E8E86),
                                        modifier = Modifier
                                            .padding(start = 7.dp)
                                            .clip(RoundedCornerShape(99.dp))
                                            .background(SummerPalette.CategoryTeal)
                                            .padding(horizontal = 7.dp, vertical = 2.dp),
                                    )
                                }
                            }
                            Text(
                                text = "开学 ${semester.startDate.monthValue}月${semester.startDate.dayOfMonth}日" +
                                    " · ${semester.totalWeeks} 周 · ${courseCountOf(semester.id)} 门课",
                                fontSize = 10.5.sp,
                                color = SummerPalette.InkTertiary,
                                modifier = Modifier.padding(top = 3.dp),
                            )
                        }
                        // 当前学期不给删 —— 删了课表页会直接空掉，属于误操作高发点
                        if (!isCurrent) {
                            Text(
                                text = "删除",
                                fontSize = 11.5.sp,
                                color = SummerPalette.InkTertiary,
                                modifier = Modifier
                                    .clickable { onDelete(semester.id) }
                                    .padding(6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NewSemesterForm(onCreate: (String, LocalDate, Int) -> Unit) {
    // 默认给一个合理的起点：9 月 1 日所在那一周的周一
    val defaultStart = remember {
        val sep = LocalDate.now().withMonth(9).withDayOfMonth(1)
        sep.minusDays((sep.dayOfWeek.value - 1).toLong())
    }
    var name by remember { mutableStateOf(defaultSemesterName()) }
    var start by remember { mutableStateOf(defaultStart) }
    // 默认 20 周；上限放到 40 周，覆盖「加长学期 / 小学期」等更长排期
    var weeks by remember { mutableStateOf(20) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(SummerPalette.Card)
            .padding(14.dp),
    ) {
        Text(
            text = "新建学期",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = SummerPalette.Ink,
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            placeholder = { Text("学期名称", fontSize = 13.sp) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
        )
        FormStepper(
            label = "开学日",
            value = "${start.year}年${start.monthValue}月${start.dayOfMonth}日",
            onMinus = { start = start.minusDays(7) },
            onPlus = { start = start.plusDays(7) },
        )
        FormStepper(
            label = "总周数",
            value = "$weeks 周",
            onMinus = { weeks = (weeks - 1).coerceAtLeast(1) },
            onPlus = { weeks = (weeks + 1).coerceAtMost(40) },
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .height(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    Brush.linearGradient(
                        listOf(SummerPalette.Dusk, SummerPalette.Lilac, Color(0xFFC88AA6))
                    )
                )
                .clickable { onCreate(name.ifBlank { "未命名学期" }, start, weeks) },
            contentAlignment = Alignment.Center,
        ) {
            Text("创建并设为当前学期", color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun FormStepper(
    label: String,
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SummerPalette.HairlineSoft)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 12.sp, color = SummerPalette.InkTertiary, modifier = Modifier.width(52.dp))
        Spacer(modifier = Modifier.weight(1f))
        StepBtn("−", onMinus)
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = SummerPalette.Ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(124.dp),
        )
        StepBtn("＋", onPlus)
    }
}

@Composable
private fun StepBtn(symbol: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(SummerPalette.Card)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, fontSize = 14.sp, color = SummerPalette.InkSecondary)
    }
}

/** 按当前日期猜一个学期名，用户可改 —— 只是个起点，不是「默认值」 */
private fun defaultSemesterName(): String {
    val today = LocalDate.now()
    val year = today.year
    return when (today.monthValue) {
        in 9..12 -> "$year–${year + 1} 第一学期"
        in 1..2 -> "${year - 1}–$year 第一学期"
        else -> "${year - 1}–$year 第二学期"
    }
}
