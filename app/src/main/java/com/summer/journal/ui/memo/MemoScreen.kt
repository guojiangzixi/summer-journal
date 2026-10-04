package com.summer.journal.ui.memo

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.summer.journal.data.attachment.AttachmentStore
import com.summer.journal.data.attachment.RecordingService
import com.summer.journal.data.recognition.ImageTextRecognizer
import com.summer.journal.data.repo.MemoRepository
import com.summer.journal.domain.model.Attachment
import com.summer.journal.domain.model.AttachmentType
import com.summer.journal.domain.model.Memo
import com.summer.journal.ui.theme.LocalPageForeground
import com.summer.journal.ui.theme.SummerPalette
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/* ══════════════════════════════════════════════════════════════════════
   ViewModel
   ══════════════════════════════════════════════════════════════════════ */

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class MemoViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MemoRepository,
    private val attachmentStore: AttachmentStore,
    private val recognizer: ImageTextRecognizer,
) : ViewModel() {

    private val _filter = MutableStateFlow<AttachmentType?>(null)
    val filter: StateFlow<AttachmentType?> = _filter.asStateFlow()

    /** 搜索关键词。空串表示不搜索（走筛选分支，省一次查询）。 */
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /**
     * 列表数据 = 筛选 × 搜索。
     *
     * 两者叠加：有搜索词时优先走搜索（在结果里仍可按类型筛），没搜索词时走类型筛选。
     * 用 flatMapLatest 保证快速输入时只有最后一次查询的结果落地，避免乱序。
     */
    val memos: StateFlow<List<Memo>> = combine(_filter, _query) { type, q -> type to q }
        .flatMapLatest { (type, q) ->
            if (q.isBlank()) repository.observeFiltered(type)
            else repository.search(q).map { list ->
                if (type == null) list else list.filter { it.kinds.contains(type) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setFilter(type: AttachmentType?) { _filter.value = type }

    fun setQuery(text: String) { _query.value = text }

    fun create(title: String) {
        viewModelScope.launch { repository.create(title = title) }
    }

    /**
     * 按「类型」新建手札，落库后回调 id。
     *
     * ★ 为什么回调放在协程里而不是立刻返回 id：
     *   建壳是异步写库，调用方必须拿到**真实 id** 才能去挂附件 / 跳详情 ——
     *   拿假 id（比如 0）会导致选图回来找不到目标手札，这正是「图片手札建不出来」的旧病根。
     */
    fun createWithKind(title: String, kind: MemoKind, onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            val id = repository.create(title = title.ifBlank { kind.label })
            when (kind) {
                // 表格：建壳的同时就把空表格附件挂上，进去即可编辑
                MemoKind.SHEET -> repository.addAttachment(id, attachmentStore.newSheet())
                else -> Unit
            }
            onCreated(id)
        }
    }

    fun delete(memoId: Long) {
        viewModelScope.launch { repository.deleteSoft(memoId) }
    }

    /**
     * 换封面。
     * 图片走 AttachmentStore 落盘到私有目录，库里只记相对路径 ——
     * 存 content:// 的话用户一撤销授权封面就全白了。
     */
    fun changeCover(memoId: Long, uri: Uri) {
        viewModelScope.launch {
            val relative = attachmentStore.importBackground(uri) ?: return@launch
            repository.updateCover(memoId, relative)
        }
    }

    /** 封面图在磁盘上的绝对路径，给 Coil 用 */
    fun coverFile(memo: Memo) = memo.coverUri?.let { attachmentStore.fileOf(it) }

    fun addImage(memoId: Long, uri: Uri) {
        viewModelScope.launch {
            val attachment = attachmentStore.importImage(uri) ?: return@launch
            repository.addAttachment(memoId, attachment)
        }
    }

    /* ══════════════════════════════════════════════════════════════
       详情页
       ══════════════════════════════════════════════════════════════ */

    fun observeDetail(memoId: Long): Flow<Memo?> = repository.observeDetail(memoId)

    /** 附件在磁盘上的真实文件，给图片预览和音频播放用 */
    fun fileOf(attachment: Attachment): File = attachmentStore.fileOf(attachment.relativePath)

    fun removeAttachment(memoId: Long, attachment: Attachment) {
        viewModelScope.launch { repository.removeAttachment(attachment.id) }
    }

    /**
     * 自动保存草稿。
     *
     * 手札是「边想边写」的场景，不该逼用户记得按保存。
     * 这里用防抖（300ms）合并连续输入，避免每敲一个字就写一次库。
     */
    private var autoSaveJob: Job? = null

    fun updateText(memoId: Long, title: String, body: String) {
        autoSaveJob?.cancel()
        autoSaveJob = viewModelScope.launch {
            delay(AUTO_SAVE_DEBOUNCE_MS)
            repository.updateText(memoId, title, body)
        }
    }

    /**
     * 用户按了保存按钮：立刻落盘，写完再回调。
     *
     * ★ 回调放在**写库之后**，不是在点击时就触发 ——
     *   否则写失败了界面还显示「已保存」，那是更糟的体验。
     */
    fun saveNow(memoId: Long, title: String, body: String, onSaved: () -> Unit = {}) {
        autoSaveJob?.cancel()
        viewModelScope.launch {
            repository.updateText(memoId, title, body)
            onSaved()
        }
    }

    /** OCR 结果追加到正文（用户确认后） */
    fun appendToBody(memoId: Long, text: String) {
        viewModelScope.launch { repository.appendToBody(memoId, text) }
    }

    fun addFile(memoId: Long, uri: Uri) {
        viewModelScope.launch {
            val attachment = attachmentStore.importFile(uri) ?: return@launch
            repository.addAttachment(memoId, attachment)
        }
    }

    /** 新建一张空表格附件 */
    fun addSheet(memoId: Long) {
        viewModelScope.launch {
            repository.addAttachment(memoId, attachmentStore.newSheet())
        }
    }

    /* ────────────── 录音 ────────────── */

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    private var recordingMemoId: Long? = null
    private var recordingFile: File? = null

    fun startRecording(memoId: Long) {
        if (_recording.value) return
        val (file, _) = attachmentStore.newRecordingFile()
        recordingFile = file
        recordingMemoId = memoId
        RecordingService.start(context, file.absolutePath)
        _recording.value = true
    }

    fun stopRecording() {
        if (!_recording.value) return
        RecordingService.stop(context)
        _recording.value = false

        // 录音文件要等 Service 真正停止后才写完，所以延迟一点再落库。
        // 更稳的做法是监听 ACTION_RECORD_DONE 广播，这里先用延迟 + 文件存在性检查兜住。
        val memoId = recordingMemoId ?: return
        val file = recordingFile ?: return
        viewModelScope.launch {
            delay(600)
            if (!file.exists() || file.length() == 0L) return@launch
            val relative = file.absolutePath
                .substringAfter(attachmentStore.rootPath + File.separator, "")
                .ifEmpty { file.name }
            repository.addAttachment(
                memoId,
                Attachment(
                    memoId = memoId,
                    type = AttachmentType.AUDIO,
                    relativePath = relative,
                    displayName = "录音 " + java.time.LocalDateTime.now()
                        .format(java.time.format.DateTimeFormatter.ofPattern("M月d日 HH:mm")),
                    sizeBytes = file.length(),
                ),
            )
        }
    }

    /* ────────────── 图片转文字（OCR） ────────────── */

    sealed interface OcrState {
        data object Idle : OcrState
        data object Running : OcrState
        data class Failed(val message: String) : OcrState
        data class Done(val text: String) : OcrState
    }

    private val _ocrState = MutableStateFlow<OcrState>(OcrState.Idle)
    val ocrState: StateFlow<OcrState> = _ocrState.asStateFlow()

    /**
     * 把一张图片附件转成文字。
     * 识别在本机离线完成（ML Kit 中文模型），不联网、不上传。
     */
    fun recognizeImage(attachment: Attachment) {
        viewModelScope.launch {
            _ocrState.value = OcrState.Running
            val result = recognizer.recognize(attachmentStore.fileOf(attachment.relativePath))
            _ocrState.value = if (result == null || result.isEmpty) {
                OcrState.Failed("这张图里没认出文字。换一张更清晰的，或者手动输入。")
            } else {
                OcrState.Done(result.fullText)
            }
        }
    }

    fun dismissOcr() { _ocrState.value = OcrState.Idle }

    private companion object {
        const val AUTO_SAVE_DEBOUNCE_MS = 300L
    }
}

/* ══════════════════════════════════════════════════════════════════════
   Screen
   ══════════════════════════════════════════════════════════════════════ */

@Composable
fun MemoScreen(
    viewModel: MemoViewModel,
    onOpen: (Long) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val memos by viewModel.memos.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    var showCreate by remember { mutableStateOf(false) }
    var coverTarget by remember { mutableStateOf<Long?>(null) }

    /**
     * ★ 图片 / 文件手札的「先选内容、再跳详情」时序。
     *
     * 旧做法是「建壳 → 立刻 onOpen(id) 跳详情 → 在详情页里选图」，
     * 结果列表页被销毁、选图器随之反注册，选图回调永远收不到 ——
     * 表现就是「点了选图，图片手札却是空的 / 根本建不出来」。
     *
     * 现在改成：建壳后**留在列表页**，等选图回来、附件落库，**再**跳详情。
     */
    var pendingAttachMemoId by remember { mutableStateOf<Long?>(null) }

    // 建壳后要发起哪种选择器（图片 / 文件）。null 表示不发起。
    var pendingPick by remember { mutableStateOf<AttachmentType?>(null) }

    val contentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        val target = pendingAttachMemoId
        when {
            target == null -> Unit
            uri == null -> {
                // 用户取消了选图：不留一条空手札
                viewModel.delete(target)
            }
            else -> {
                viewModel.addImage(target, uri)
                onOpen(target)
            }
        }
        pendingAttachMemoId = null
        pendingPick = null
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val target = pendingAttachMemoId
        when {
            target == null -> Unit
            uri == null -> viewModel.delete(target)
            else -> {
                viewModel.addFile(target, uri)
                onOpen(target)
            }
        }
        pendingAttachMemoId = null
        pendingPick = null
    }

    val imagePicker = rememberLauncherForActivityResult(
        // ★ PickVisualMedia：Android 13+ 走系统 PhotoPicker，**不需要任何读取权限**
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        val target = coverTarget
        if (uri != null && target != null) viewModel.changeCover(target, uri)
        coverTarget = null
    }

    // 新建完成后按类型决定去向：
    //   空白/表格 → 直接进详情；图片/文件 → 先选内容再进；音频 → 进详情后手动开录
    fun handleCreated(memoId: Long, kind: MemoKind) {
        when (kind) {
            MemoKind.IMAGE -> {
                pendingAttachMemoId = memoId
                pendingPick = AttachmentType.IMAGE
                contentPicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            }
            MemoKind.FILE -> {
                pendingAttachMemoId = memoId
                pendingPick = AttachmentType.FILE
                filePicker.launch(arrayOf("*/*"))
            }
            else -> onOpen(memoId)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // 头部
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "手札",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                // 页面标题直接画在背景上，跟随背景明暗自动变深浅
                color = LocalPageForeground.current,
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(SummerPalette.Dusk, SummerPalette.Lilac, Color(0xFFC88AA6))
                        )
                    )
                    .clickable { showCreate = true }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            ) {
                Text("＋ 新建", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        // 搜索框
        SearchField(
            value = query,
            onValueChange = viewModel::setQuery,
            onClear = { viewModel.setQuery("") },
        )

        // 类型筛选
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip("全部", filter == null) { viewModel.setFilter(null) }
            FilterChip("音频", filter == AttachmentType.AUDIO) { viewModel.setFilter(AttachmentType.AUDIO) }
            FilterChip("图片", filter == AttachmentType.IMAGE) { viewModel.setFilter(AttachmentType.IMAGE) }
            FilterChip("文件", filter == AttachmentType.FILE) { viewModel.setFilter(AttachmentType.FILE) }
            FilterChip("表格", filter == AttachmentType.SHEET) { viewModel.setFilter(AttachmentType.SHEET) }
        }

        if (memos.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (query.isNotBlank()) "没有匹配「$query」的手札"
                    else "还没有记录，从今天的天色开始吧",
                    fontSize = 13.sp,
                    color = SummerPalette.InkTertiary,
                )
            }
        } else {
            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 20.dp, end = 20.dp, bottom = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(memos, key = { it.id }) { memo ->
                    MemoCard(
                        memo = memo,
                        coverFile = viewModel.coverFile(memo),
                        onOpen = { onOpen(memo.id) },
                        onChangeCover = {
                            coverTarget = memo.id
                            imagePicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onDelete = { viewModel.delete(memo.id) },
                    )
                }
            }
        }
    }

    if (showCreate) {
        MemoCreateSheet(
            onDismiss = { showCreate = false },
            onCreate = { title, kind ->
                showCreate = false
                // ★ 建壳与「跳详情 / 选内容」分开：先落库拿到 id，再决定去向。
                viewModel.createWithKind(title, kind, onCreated = { id -> handleCreated(id, kind) })
            },
        )
    }
}

/** 手札搜索框。放列表顶部，按标题/正文模糊匹配。 */
@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onClear: () -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text("搜索标题或正文", fontSize = 13.5.sp) },
        leadingIcon = { Text("🔍", fontSize = 14.sp) },
        trailingIcon = {
            if (value.isNotEmpty()) {
                Text(
                    text = "✕",
                    fontSize = 14.sp,
                    color = SummerPalette.InkTertiary,
                    modifier = Modifier
                        .clickable { onClear() }
                        .padding(10.dp),
                )
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        textStyle = TextStyle(fontSize = 14.sp, color = SummerPalette.Ink),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 12.dp),
    )
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) SummerPalette.CategoryViolet else SummerPalette.HairlineSoft)
            .clickable { onClick() }
            .padding(horizontal = 13.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            fontSize = 12.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) Color(0xFF5346AE) else SummerPalette.InkSecondary,
        )
    }
}

@Composable
private fun MemoCard(
    memo: Memo,
    coverFile: java.io.File?,
    onOpen: () -> Unit,
    onChangeCover: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(SummerPalette.Card)
            // 整张卡片可点：进详情。比只让标题可点更符合直觉
            .clickable { onOpen() },
    ) {
        // 封面：有自定义图就显示图，没有就用品牌渐变兜底
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(118.dp),
        ) {
            if (coverFile != null && coverFile.exists()) {
                AsyncImage(
                    model = coverFile,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    SummerPalette.SkyMid,
                                    SummerPalette.Dusk,
                                    SummerPalette.SunsetPink,
                                    SummerPalette.AmberLight,
                                )
                            )
                        ),
                )
            }

            Text(
                text = "换封面",
                color = Color.White,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color.White.copy(alpha = 0.22f))
                    .clickable { onChangeCover() }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )

            Column(modifier = Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                Text(
                    text = memo.title.ifBlank { "未命名手札" },
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = memo.updatedAt.format(DateTimeFormatter.ofPattern("M月d日 HH:mm")),
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 10.5.sp,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = memo.tags.joinToString(" ") { "#$it" }.ifBlank { memo.body.take(24) },
                fontSize = 11.5.sp,
                color = SummerPalette.InkTertiary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            Text(
                text = "删除",
                fontSize = 12.sp,
                color = SummerPalette.InkTertiary,
                modifier = Modifier.clickable { onDelete() }.padding(4.dp),
            )
        }
    }
}
