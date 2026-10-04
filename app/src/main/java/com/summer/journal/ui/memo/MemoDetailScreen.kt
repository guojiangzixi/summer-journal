package com.summer.journal.ui.memo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import coil3.compose.AsyncImage
import com.summer.journal.domain.model.Attachment
import com.summer.journal.domain.model.AttachmentType
import com.summer.journal.domain.model.Memo
import com.summer.journal.ui.theme.SummerPalette
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.File

/**
 * 手札详情：正文 + 附件区 + 底部四类上传入口。
 *
 * 音频用 Media3 ExoPlayer 在应用内播放（依赖已在工程里，之前一直没接 UI）。
 */
@Composable
fun MemoDetailScreen(
    memoId: Long,
    viewModel: MemoViewModel,
    onBack: () -> Unit,
    onStartRecording: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val memoFlow = remember(memoId) { viewModel.observeDetail(memoId) }
    val memo by memoFlow.collectAsStateWithLifecycle(initialValue = null)

    val context = LocalContext.current
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var previewImage by remember { mutableStateOf<Attachment?>(null) }
    var linkDialog by remember { mutableStateOf(false) }
    // 「已保存」提示：点保存后短暂出现，给用户一个明确的「存好了」信号
    var savedFlash by remember { mutableStateOf(false) }

    val ocrState by viewModel.ocrState.collectAsStateWithLifecycle()
    val recording by viewModel.recording.collectAsStateWithLifecycle()

    // ── 直接拍照 ──
    // 相机只接受 content:// URI，先落 cache/share 再交给系统相机（file_paths.xml 已声明）
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        val uri = pendingCameraUri
        pendingCameraUri = null
        if (success && uri != null) viewModel.addImage(memoId, uri)
    }
    // 通过系统相机 Intent 拍照：若清单里声明了 CAMERA 却没授权，会抛 SecurityException，
    // 所以必须先申请权限再启动（这也是之前「拍照直接崩/没反应」的根因之一）。
    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) launchCamera(context, cameraLauncher) { pendingCameraUri = it } }
    fun openCamera() {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) launchCamera(context, cameraLauncher) { pendingCameraUri = it }
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    // ── 录音权限 ──
    // ★ 录音崩溃的另一半根因：过去录音前从不申请 RECORD_AUDIO。
    //   Android 14+ 未授权就起麦克风前台服务 → SecurityException → 进程被杀，
    //   用户看到的就是「一点录音 App 直接退出」。这里先申请、授权后再起服务。
    val recordPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) onStartRecording(memoId) }
    fun toggleRecording() {
        if (recording) {
            viewModel.stopRecording()
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) onStartRecording(memoId)
        else recordPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    // 首次拿到数据时灌进本地状态；之后不再覆盖，否则会打断用户正在打的字
    LaunchedEffect(memo?.id) {
        val m = memo
        if (m != null && !loaded) {
            title = m.title
            body = m.body
            loaded = true
        }
    }

    // 防抖保存：输入停顿 600ms 才落库
    LaunchedEffect(title, body, loaded) {
        if (!loaded) return@LaunchedEffect
        delay(600)
        viewModel.updateText(memoId, title, body)
    }

    // ── 上传入口 ──
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let { viewModel.addImage(memoId, it) } }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.addFile(memoId, it) } }

    Column(modifier = modifier.fillMaxSize()) {
        DetailTopBar(
            onBack = onBack,
            onDelete = { showDelete = true },
            recording = recording,
            saved = savedFlash,
            onSave = {
                viewModel.saveNow(memoId, title, body) { savedFlash = true }
            },
        )

        // 「已保存」轻提示：1.6 秒后自动消失
        LaunchedEffect(savedFlash) {
            if (savedFlash) {
                delay(1600)
                savedFlash = false
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            BasicTextField(
                value = title,
                onValueChange = { title = it },
                textStyle = TextStyle(
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = SummerPalette.Ink,
                ),
                cursorBrush = SolidColor(SummerPalette.Dusk),
                decorationBox = { inner ->
                    if (title.isEmpty()) {
                        Text(
                            "标题",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = SummerPalette.InkTertiary.copy(alpha = 0.6f),
                        )
                    }
                    inner()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 10.dp),
            )

            BasicTextField(
                value = body,
                onValueChange = { body = it },
                textStyle = TextStyle(
                    fontSize = 14.sp,
                    lineHeight = 23.sp,
                    color = SummerPalette.InkSecondary,
                ),
                cursorBrush = SolidColor(SummerPalette.Dusk),
                decorationBox = { inner ->
                    if (body.isEmpty()) {
                        Text(
                            "写点什么…",
                            fontSize = 14.sp,
                            color = SummerPalette.InkTertiary.copy(alpha = 0.7f),
                        )
                    }
                    inner()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(SummerPalette.Card)
                    .padding(14.dp)
                    .height(150.dp),
            )

            val attachments = memo?.attachments.orEmpty()
            if (attachments.isNotEmpty()) {
                SectionTitle("附件 · ${attachments.size} 个")

                AudioBlocks(
                    attachments = attachments.filter { it.type == AttachmentType.AUDIO },
                    fileOf = viewModel::fileOf,
                    onRemove = { viewModel.removeAttachment(memoId, it) },
                )
                ImageGrid(
                    attachments = attachments.filter { it.type == AttachmentType.IMAGE },
                    fileOf = viewModel::fileOf,
                    onClick = { previewImage = it },
                )
                FileRows(
                    attachments = attachments.filter { it.type == AttachmentType.FILE },
                    onRemove = { viewModel.removeAttachment(memoId, it) },
                )
                SheetBlocks(
                    attachments = attachments.filter { it.type == AttachmentType.SHEET },
                    onRemove = { viewModel.removeAttachment(memoId, it) },
                )
            }

            Box(modifier = Modifier.height(16.dp))
        }

        // ── 底部上传入口，随时补附件 ──
        UploadBar(
            recording = recording,
            onRecord = { toggleRecording() },
            onCamera = { openCamera() },
            onImage = {
                imagePicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onFile = { filePicker.launch(arrayOf("*/*")) },
            onSheet = { viewModel.addSheet(memoId) },
            onLink = { linkDialog = true },
        )
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("删除这条手札？") },
            text = {
                Text(
                    "手札会立刻从列表里消失；里面的附件文件会先保留 24 小时再清理，误删了还有机会找回。",
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(memoId)
                    showDelete = false
                    onBack()
                }) { Text("删除", color = Color(0xFFB9636F)) }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) { Text("取消") }
            },
        )
    }

    previewImage?.let { image ->
        ImageActionSheet(
            onDismiss = { previewImage = null },
            onRecognize = {
                viewModel.recognizeImage(image)
                previewImage = null
            },
            onRemove = {
                viewModel.removeAttachment(memoId, image)
                previewImage = null
            },
        )
    }

    if (linkDialog) {
        LinkDialog(
            onDismiss = { linkDialog = false },
            onConfirm = { url ->
                viewModel.appendToBody(memoId, url)
                body = if (body.isBlank()) url else "$body\n$url"
                linkDialog = false
            },
        )
    }

    when (val state = ocrState) {
        MemoViewModel.OcrState.Idle -> Unit

        MemoViewModel.OcrState.Running -> OcrSheet(
            title = "正在识别图片里的文字…",
            text = null,
            onInsert = null,
            onDismiss = viewModel::dismissOcr,
        )

        is MemoViewModel.OcrState.Failed -> OcrSheet(
            title = "没能识别出文字",
            text = state.message,
            onInsert = null,
            onDismiss = viewModel::dismissOcr,
        )

        is MemoViewModel.OcrState.Done -> OcrSheet(
            title = "识别到 ${state.text.length} 个字",
            text = state.text,
            onInsert = {
                viewModel.appendToBody(memoId, state.text)
                body = if (body.isBlank()) state.text else "$body\n${state.text}"
                viewModel.dismissOcr()
            },
            onDismiss = viewModel::dismissOcr,
        )
    }
}

/* ══════════════════════════════════════════════════════════════════════
   顶栏
   ══════════════════════════════════════════════════════════════════════ */

@Composable
private fun DetailTopBar(
    onBack: () -> Unit,
    onDelete: () -> Unit,
    recording: Boolean,
    saved: Boolean,
    onSave: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "‹ 手札",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = SummerPalette.InkSecondary,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable { onBack() }
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
        Box(modifier = Modifier.weight(1f))
        if (recording) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(end = 10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFE05A6B)),
                )
                Text("录音中", fontSize = 11.5.sp, color = Color(0xFFE05A6B), fontWeight = FontWeight.SemiBold)
            }
        }
        // 「已保存」的短暂回执
        if (saved) {
            Text(
                text = "已保存 ✓",
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF2E8E86),
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        // 显式保存按钮：把「存好了」这件事变得看得见
        Text(
            text = "保存",
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = SummerPalette.Dusk,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(SummerPalette.CategoryViolet)
                .clickable { onSave() }
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
        Text(
            text = "删除",
            fontSize = 12.5.sp,
            color = SummerPalette.InkTertiary,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable { onDelete() }
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
}

/**
 * 用系统相机拍一张照，交给 [launcher]。
 *
 * 相机 Intent 只接受 content:// URI（File 直传在 Android 7+ 会崩），
 * 所以先落到 cache/share/ 再经 FileProvider 转成 URI。
 * 全程 runCatching：即使 FileProvider 配置缺失或系统无相机 App，也只是「没拍成」，绝不崩。
 */
private fun launchCamera(
    context: android.content.Context,
    launcher: androidx.activity.result.ActivityResultLauncher<Uri>,
    onUriReady: (Uri) -> Unit,
) {
    val file = File(context.cacheDir, "share/memo_${System.currentTimeMillis()}.jpg")
        .apply { parentFile?.mkdirs() }
    val uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull() ?: return
    onUriReady(uri)
    runCatching { launcher.launch(uri) }
}

/* ══════════════════════════════════════════════════════════════════════
   音频：波形 + 播放
   ══════════════════════════════════════════════════════════════════════ */

@Composable
private fun AudioBlocks(
    attachments: List<Attachment>,
    fileOf: (Attachment) -> java.io.File,
    onRemove: (Attachment) -> Unit,
) {
    if (attachments.isEmpty()) return
    val context = LocalContext.current
    var playingId by remember { mutableStateOf<Long?>(null) }

    val player = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) playingId = null
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    attachments.forEach { audio ->
        val playing = playingId == audio.id
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 9.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(SummerPalette.Card)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(if (playing) SummerPalette.Dusk else SummerPalette.CategoryViolet)
                    .clickable {
                        if (playing) {
                            player.pause()
                            playingId = null
                        } else {
                            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(fileOf(audio))))
                            player.prepare()
                            player.play()
                            playingId = audio.id
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (playing) "❚❚" else "▶",
                    fontSize = 13.sp,
                    color = if (playing) Color.White else Color(0xFF5346AE),
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 11.dp),
            ) {
                Text(
                    text = audio.displayName,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = SummerPalette.Ink,
                    maxLines = 1,
                )
                Waveform(
                    seed = audio.id,
                    modifier = Modifier.padding(top = 5.dp),
                    tint = if (playing) SummerPalette.Lilac else SummerPalette.Hairline,
                )
                Text(
                    text = "${formatDuration(audio.durationMs)} · ${audio.humanSize}",
                    fontSize = 10.sp,
                    color = SummerPalette.InkTertiary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Text(
                text = "移除",
                fontSize = 11.sp,
                color = SummerPalette.InkTertiary,
                modifier = Modifier
                    .clickable { onRemove(audio) }
                    .padding(6.dp),
            )
        }
    }
}

/**
 * 音频波形。
 *
 * ★ 说明：这是**装饰性波形**（按附件 id 生成的稳定伪随机柱），不是真实音频包络。
 *   画真实波形需要解码音频取振幅，成本远高于它在 UI 上的价值。
 *   柱高固定不变 —— 至少不会随播放「乱跳」而误导用户。
 */
@Composable
private fun Waveform(seed: Long, modifier: Modifier = Modifier, tint: Color) {
    val bars = remember(seed) {
        val random = java.util.Random(seed)
        List(34) { 0.22f + random.nextFloat() * 0.78f }
    }
    Row(
        modifier = modifier.height(18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        bars.forEach { ratio ->
            Box(
                modifier = Modifier
                    .size(width = 2.dp, height = (18 * ratio).dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(tint),
            )
        }
    }
}

private fun formatDuration(ms: Long?): String {
    if (ms == null || ms <= 0) return "时长未知"
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

/* ══════════════════════════════════════════════════════════════════════
   图片九宫格
   ══════════════════════════════════════════════════════════════════════ */

@Composable
private fun ImageGrid(
    attachments: List<Attachment>,
    fileOf: (Attachment) -> java.io.File,
    onClick: (Attachment) -> Unit,
) {
    if (attachments.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        attachments.chunked(3).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                rowItems.forEach { image ->
                    AsyncImage(
                        model = fileOf(image),
                        contentDescription = image.displayName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onClick(image) },
                    )
                }
                // 最后一行不足 3 张时补空位，避免图片被拉宽
                repeat(3 - rowItems.size) {
                    Box(modifier = Modifier.weight(1f))
                }
            }
        }
        Text(
            text = "点任意图片可识别其中的文字",
            fontSize = 10.sp,
            color = SummerPalette.InkTertiary,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

/* ══════════════════════════════════════════════════════════════════════
   文件 / 表格
   ══════════════════════════════════════════════════════════════════════ */

@Composable
private fun FileRows(attachments: List<Attachment>, onRemove: (Attachment) -> Unit) {
    if (attachments.isEmpty()) return
    attachments.forEach { file ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 7.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(SummerPalette.Card)
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(SummerPalette.CategoryAmber),
                contentAlignment = Alignment.Center,
            ) {
                Text("文", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFFA9642A))
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 11.dp),
            ) {
                Text(
                    text = file.displayName,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = SummerPalette.Ink,
                    maxLines = 1,
                )
                Text(
                    text = file.humanSize,
                    fontSize = 10.sp,
                    color = SummerPalette.InkTertiary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Text(
                text = "移除",
                fontSize = 11.sp,
                color = SummerPalette.InkTertiary,
                modifier = Modifier
                    .clickable { onRemove(file) }
                    .padding(6.dp),
            )
        }
    }
}

@Composable
private fun SheetBlocks(attachments: List<Attachment>, onRemove: (Attachment) -> Unit) {
    attachments.forEach { sheet ->
        var json by remember(sheet.id) { mutableStateOf(sheet.sheetJson ?: EMPTY_SHEET) }
        var editing by remember(sheet.id) { mutableStateOf(false) }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 9.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(SummerPalette.Card)
                .padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = sheet.displayName,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = SummerPalette.Ink,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (editing) "收起" else "编辑",
                    fontSize = 11.sp,
                    color = Color(0xFF5D51B8),
                    modifier = Modifier
                        .clickable { editing = !editing }
                        .padding(4.dp),
                )
                Text(
                    text = "移除",
                    fontSize = 11.sp,
                    color = SummerPalette.InkTertiary,
                    modifier = Modifier
                        .clickable { onRemove(sheet) }
                        .padding(4.dp),
                )
            }

            SheetPreview(json, editable = editing) { updated ->
                json = updated
            }
        }
    }
}

/** 表格渲染。editable 时每个单元格都是可直接输入的小输入框 */
@Composable
private fun SheetPreview(json: String, editable: Boolean, onChange: (String) -> Unit) {
    val parsed = remember(json) { runCatching { JSONObject(json) }.getOrNull() } ?: return
    val cols = parsed.optInt("cols", 4).coerceIn(1, 6)
    val rows = parsed.optInt("rows", 6).coerceIn(1, 20)
    val cells = parsed.optJSONObject("cells") ?: JSONObject()

    fun valueAt(r: Int, c: Int) = cells.optString("$r,$c", "")

    fun update(r: Int, c: Int, text: String) {
        val next = JSONObject(json)
        val nextCells = next.optJSONObject("cells") ?: JSONObject()
        nextCells.put("$r,$c", text)
        next.put("cells", nextCells)
        onChange(next.toString())
    }

    Column(
        modifier = Modifier.padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        (0 until rows).forEach { r ->
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                (0 until cols).forEach { c ->
                    val cellValue = valueAt(r, c)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(30.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(
                                if (r == 0) SummerPalette.CategoryViolet else SummerPalette.HairlineSoft
                            )
                            .then(
                                if (editable) Modifier.border(
                                    0.5.dp, SummerPalette.Hairline, RoundedCornerShape(7.dp)
                                ) else Modifier
                            )
                            .padding(horizontal = 5.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (editable) {
                            BasicTextField(
                                value = cellValue,
                                onValueChange = { update(r, c, it) },
                                singleLine = true,
                                textStyle = TextStyle(
                                    fontSize = 10.sp,
                                    color = SummerPalette.Ink,
                                    fontWeight = if (r == 0) FontWeight.SemiBold else FontWeight.Normal,
                                ),
                                cursorBrush = SolidColor(SummerPalette.Dusk),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            Text(
                                text = cellValue,
                                fontSize = 10.sp,
                                fontWeight = if (r == 0) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (r == 0) Color(0xFF5346AE) else SummerPalette.InkSecondary,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val EMPTY_SHEET = """{"cols":4,"rows":6,"cells":{}}"""

/* ══════════════════════════════════════════════════════════════════════
   底部上传条 + 各种 sheet
   ══════════════════════════════════════════════════════════════════════ */

@Composable
private fun UploadBar(
    recording: Boolean,
    onRecord: () -> Unit,
    onCamera: () -> Unit,
    onImage: () -> Unit,
    onFile: () -> Unit,
    onSheet: () -> Unit,
    onLink: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SummerPalette.Card)
            .navigationBarsPadding()
            .padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        UploadAction(
            label = if (recording) "停止" else "录音",
            symbol = if (recording) "■" else "◉",
            active = recording,
            onClick = onRecord,
            modifier = Modifier.weight(1f),
        )
        UploadAction("拍照", "📷", false, onCamera, Modifier.weight(1f))
        UploadAction("相册", "▣", false, onImage, Modifier.weight(1f))
        UploadAction("文件", "▤", false, onFile, Modifier.weight(1f))
        UploadAction("表格", "▦", false, onSheet, Modifier.weight(1f))
        UploadAction("链接", "🔗", false, onLink, Modifier.weight(1f))
    }
}

@Composable
private fun UploadAction(
    label: String,
    symbol: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (active) SummerPalette.CategoryPink else SummerPalette.HairlineSoft)
            .clickable { onClick() }
            .padding(vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(symbol, fontSize = 15.sp, color = if (active) Color(0xFFAE5C69) else SummerPalette.InkSecondary)
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (active) Color(0xFFAE5C69) else SummerPalette.InkSecondary,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImageActionSheet(
    onDismiss: () -> Unit,
    onRecognize: () -> Unit,
    onRemove: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
            ActionRow("🔍  识别图片里的文字", "离线识别，不联网", Color(0xFF5D51B8), onRecognize)
            ActionRow("🗑  移除这张图片", null, Color(0xFFB9636F), onRemove)
            Box(modifier = Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OcrSheet(
    title: String,
    text: String?,
    onInsert: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SummerPalette.Background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = SummerPalette.Ink)
            if (text != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .height(190.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(SummerPalette.Card)
                        .padding(12.dp),
                ) {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        Text(text, fontSize = 12.5.sp, color = SummerPalette.InkSecondary, lineHeight = 20.sp)
                    }
                }
            }
            if (onInsert != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                            .clip(RoundedCornerShape(15.dp))
                            .background(SummerPalette.HairlineSoft)
                            .clickable { onDismiss() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("放弃", fontSize = 14.sp, color = SummerPalette.InkSecondary)
                    }
                    Box(
                        modifier = Modifier
                            .weight(2f)
                            .height(46.dp)
                            .clip(RoundedCornerShape(15.dp))
                            .background(SummerPalette.Dusk)
                            .clickable { onInsert() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("插入到正文", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionRow(
    label: String,
    hint: String?,
    color: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = color)
            if (hint != null) {
                Text(hint, fontSize = 10.5.sp, color = SummerPalette.InkTertiary, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@Composable
private fun LinkDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("插入链接") },
        text = {
            Column {
                Text(
                    "链接会作为一行文字追加到正文末尾。",
                    fontSize = 12.sp,
                    color = SummerPalette.InkTertiary,
                )
                BasicTextField(
                    value = url,
                    onValueChange = { url = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    textStyle = TextStyle(fontSize = 14.sp, color = SummerPalette.Ink),
                    cursorBrush = SolidColor(SummerPalette.Dusk),
                    decorationBox = { inner ->
                        if (url.isEmpty()) {
                            Text("https://…", fontSize = 14.sp, color = SummerPalette.InkTertiary)
                        }
                        inner()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(SummerPalette.HairlineSoft)
                        .padding(12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (url.isNotBlank()) onConfirm(url.trim()) }) { Text("插入") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = SummerPalette.InkSecondary,
        modifier = Modifier.padding(top = 18.dp, bottom = 9.dp),
    )
}
