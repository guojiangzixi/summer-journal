package com.summer.journal.ui.timetable

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.summer.journal.domain.model.Course
import com.summer.journal.ui.theme.SummerPalette

/**
 * 课表图片识别：拍/选一张课表截图 → 识别文字 → 解析成课程 → 确认后导入。
 *
 * 识别完全在本地跑（ML Kit 中文离线模型），不联网、不上传任何图片。
 *
 * ★ 关键设计：**结果一定先经过「可编辑的预览列表」**。
 *   课表图片的排版千差万别，解析器再努力也会错。
 *   把候选课程摆出来让用户扫一眼、删掉明显错的，比「识别完直接写库」安全得多 ——
 *   后者一旦错了，用户会在一整个学期里被错误的课表误导。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseScanSheet(
    state: TimetableViewModel.ScanState,
    onPickImage: () -> Unit,
    onTakePhoto: () -> Unit,
    onImport: (List<Course>) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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

            Text(
                text = "拍摄 / 选择课表图片",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = SummerPalette.Ink,
            )
            Text(
                text = "识别在本机离线完成，图片不会上传到任何服务器。",
                fontSize = 11.sp,
                color = SummerPalette.InkTertiary,
                modifier = Modifier.padding(top = 5.dp),
            )

            when (state) {
                TimetableViewModel.ScanState.Idle -> {
                    Hint(
                        "把教务系统的课表整页截图放进来，识别效果最好。" +
                            "建议包含「星期 + 节次」这两列 —— 它们是解析的锚点。"
                    )
                    PrimaryAction("拍一张课表", onTakePhoto)
                    SecondaryAction("从相册选图", onPickImage)
                }

                TimetableViewModel.ScanState.Recognizing -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 34.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = SummerPalette.Dusk,
                        )
                        Text(
                            text = "正在识别图片里的文字…",
                            fontSize = 13.sp,
                            color = SummerPalette.InkSecondary,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }

                is TimetableViewModel.ScanState.Failed -> {
                    Hint(state.message, tone = Color(0xFFB9636F))
                    PrimaryAction("拍一张课表", onTakePhoto)
                    SecondaryAction("从相册选图", onPickImage)
                }

                is TimetableViewModel.ScanState.Done -> {
                    // 用可增删的本地列表：用户删掉的条目不该再被导入
                    val kept = remember(state) { mutableStateListOf<Course>().also { it.addAll(state.courses) } }
                    var showRaw by remember(state) { mutableStateOf(false) }

                    if (state.warnings.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 14.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(SummerPalette.CategoryAmber)
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            state.warnings.forEach {
                                Text(it, fontSize = 11.5.sp, color = Color(0xFFA9642A), lineHeight = 17.sp)
                            }
                        }
                    }

                    if (kept.isEmpty()) {
                        Hint("这张图里没认出可导入的课程。可以换一张更清晰的，或直接手动添加。")
                        PrimaryAction("拍一张课表", onTakePhoto)
                        SecondaryAction("从相册选图", onPickImage)
                    } else {
                        Text(
                            text = "识别到 ${kept.size} 门课 · 核对后导入",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = SummerPalette.Ink,
                            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                        )

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(SummerPalette.Card),
                        ) {
                            kept.forEachIndexed { index, course ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 11.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(9.dp)
                                            .clip(CircleShape)
                                            .background(timetableDot(course.color)),
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
                                        )
                                        Text(
                                            text = buildString {
                                                append("周${listOf("一", "二", "三", "四", "五", "六", "日")[course.dayOfWeek - 1]}")
                                                append(" 第 ${course.periodStart}-${course.periodEnd} 节")
                                                append(" · ${course.weekRange.first}-${course.weekRange.last} 周")
                                                course.parityLabel?.let { append("($it)") }
                                                course.room?.let { append(" · $it") }
                                                course.teacher?.let { append(" · $it") }
                                            },
                                            fontSize = 10.5.sp,
                                            color = SummerPalette.InkTertiary,
                                            maxLines = 1,
                                        )
                                    }
                                    Text(
                                        text = "移除",
                                        fontSize = 11.5.sp,
                                        color = SummerPalette.InkTertiary,
                                        modifier = Modifier
                                            .clickable { kept.removeAt(index) }
                                            .padding(6.dp),
                                    )
                                }
                            }
                        }

                        // 识别原文：解析错了时，用户能对照看是哪一步出的问题
                        Text(
                            text = if (showRaw) "收起识别原文" else "查看识别原文",
                            fontSize = 11.5.sp,
                            color = Color(0xFF5D51B8),
                            modifier = Modifier
                                .padding(top = 12.dp)
                                .clickable { showRaw = !showRaw },
                        )
                        if (showRaw) {
                            Text(
                                text = state.rawText.take(1200),
                                fontSize = 10.5.sp,
                                color = SummerPalette.InkTertiary,
                                lineHeight = 16.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 200.dp)
                                    .padding(top = 6.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(SummerPalette.Card)
                                    .padding(10.dp),
                            )
                        }

                        PrimaryAction("导入这 ${kept.size} 门课") { onImport(kept.toList()) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String, tone: Color = SummerPalette.InkTertiary) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = tone,
        lineHeight = 18.sp,
        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
    )
}

@Composable
private fun PrimaryAction(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 18.dp)
            .height(50.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.linearGradient(
                    listOf(SummerPalette.Dusk, SummerPalette.Lilac, Color(0xFFC88AA6))
                )
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SecondaryAction(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 9.dp)
            .height(46.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(SummerPalette.HairlineSoft)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = SummerPalette.InkSecondary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}
