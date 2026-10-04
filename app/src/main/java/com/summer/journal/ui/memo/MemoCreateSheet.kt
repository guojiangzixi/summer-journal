package com.summer.journal.ui.memo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.summer.journal.ui.theme.SummerPalette

/** 新建手札时先选的载体类型 */
enum class MemoKind(
    val label: String,
    val hint: String,
) {
    BLANK("空白", "先建一条，想起来再写"),
    AUDIO("音频", "边走边录，事后回听"),
    IMAGE("图片", "拍照或选图，可识别文字"),
    FILE("文件", "文档、压缩包、作业附件"),
    SHEET("表格", "随手记一组数据"),
}

/**
 * 新建手札。
 *
 * ★ 先选载体再建 —— 因为「记一个想法」和「录一段课」需要的信息完全不同：
 *   前者要一块空白，后者应该建完就立刻开始录音。
 *   统一的「输入标题 → 确定」会逼用户多做一步无用功。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoCreateSheet(
    onDismiss: () -> Unit,
    onCreate: (title: String, kind: MemoKind) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var title by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(MemoKind.BLANK) }

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

            Text(
                text = "新建手札",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = SummerPalette.Ink,
            )
            Text(
                text = "先挑一个载体，之后再补别的附件也可以。",
                fontSize = 11.5.sp,
                color = SummerPalette.InkTertiary,
                modifier = Modifier.padding(top = 5.dp),
            )

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                placeholder = { Text("标题，如「周三 · 数据结构笔记」", fontSize = 14.sp) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
            )

            Text(
                text = "选择类型",
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = SummerPalette.InkSecondary,
                modifier = Modifier.padding(top = 18.dp, bottom = 9.dp),
            )

            // 五个类型：前两个一行，后三个一行 —— 比纯 3 列网格更省纵向空间
            KindGrid(
                kind = kind,
                onSelect = { kind = it },
            )

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
                    .clickable { onCreate(title.trim(), kind) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = when (kind) {
                        MemoKind.BLANK -> "创建手札"
                        MemoKind.AUDIO -> "创建并开始录音"
                        MemoKind.IMAGE -> "创建并选择图片"
                        MemoKind.FILE -> "创建并选择文件"
                        MemoKind.SHEET -> "创建并新建表格"
                    },
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun KindGrid(kind: MemoKind, onSelect: (MemoKind) -> Unit) {
    val entries = MemoKind.entries
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            entries.take(2).forEach { item ->
                KindCard(item, item == kind, { onSelect(item) }, Modifier.weight(1f))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            entries.drop(2).forEach { item ->
                KindCard(item, item == kind, { onSelect(item) }, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun KindCard(
    kind: MemoKind,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) SummerPalette.CategoryViolet else SummerPalette.Card)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 13.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = kind.label,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) Color(0xFF5346AE) else SummerPalette.Ink,
            )
            if (selected) {
                Text(
                    text = " ✓",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF5346AE),
                )
            }
        }
        Text(
            text = kind.hint,
            fontSize = 10.sp,
            color = if (selected) Color(0xFF7A70C4) else SummerPalette.InkTertiary,
            lineHeight = 14.sp,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}
