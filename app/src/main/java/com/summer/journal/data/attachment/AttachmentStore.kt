package com.summer.journal.data.attachment

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.summer.journal.domain.model.Attachment
import com.summer.journal.domain.model.AttachmentType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * 附件落盘。
 *
 * 三条铁律：
 *  1. **只存相对路径**。绝对路径会随沙箱重建 / 备份恢复 / 换机迁移而失效。
 *  2. **一律复制到私有目录**。不能存 content:// URI —— 用户删了原文件，
 *     或者撤销了授权，手札里的附件就全变成空白，这是最容易被投诉的一类 bug。
 *  3. **导入即压缩**。照片随手一拍 50MP，直接存进 App 会瞬间吃掉几百 MB；
 *     但压缩前保留原图（originals/），需要时还能还原。
 */
@Singleton
class AttachmentStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val root: File get() = File(context.filesDir, ROOT_DIR).apply { mkdirs() }

    /**
     * attachments 根目录的绝对路径。
     *
     * 录音是先由 Service 写文件、再由 ViewModel 落库的，
     * 落库时要把绝对路径换算成相对路径，所以需要这个。
     */
    val rootPath: String get() = root.absolutePath

    /* ────────────── 音频（录音）────────────── */

    fun newRecordingFile(): Pair<File, String> {
        val relative = "$DIR_AUDIO/${UUID.randomUUID()}.m4a"
        val file = File(root, relative).apply { parentFile?.mkdirs() }
        return file to relative
    }

    /* ────────────── 图片 ─────────────── */

    /** 图片附件：长边压到 2048，JPEG 85；同时留一份原图 */
    suspend fun importImage(source: Uri): Attachment? = withContext(Dispatchers.IO) {
        runCatching {
            val uuid = UUID.randomUUID().toString()
            val relative = "$DIR_IMAGE/$uuid.jpg"
            val target = File(root, relative).apply { parentFile?.mkdirs() }

            val bitmap = decodeDownsampled(source, MAX_IMAGE_EDGE)
            target.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            bitmap.recycle()

            // 原图也留一份，用户想导出时还在
            runCatching {
                val originalRelative = "$DIR_ORIGINALS/$uuid.original"
                val original = File(root, originalRelative).apply { parentFile?.mkdirs() }
                context.contentResolver.openInputStream(source)?.use { input ->
                    original.outputStream().use { input.copyTo(it) }
                }
            }

            Attachment(
                memoId = 0,
                type = AttachmentType.IMAGE,
                relativePath = relative,
                displayName = displayNameOf(source) ?: "照片.jpg",
                sizeBytes = target.length(),
            )
        }.getOrNull()
    }

    /** 背景图：压到屏幕分辨率就够，不必按附件规格存 */
    suspend fun importBackground(source: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val relative = "$DIR_BACKGROUND/${UUID.randomUUID()}.jpg"
            val target = File(root, relative).apply { parentFile?.mkdirs() }

            val bitmap = decodeDownsampled(source, MAX_BACKGROUND_EDGE)
            target.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
            }
            bitmap.recycle()
            relative
        }.getOrNull()
    }

    /* ────────────── 文件 ─────────────── */

    suspend fun importFile(source: Uri): Attachment? = withContext(Dispatchers.IO) {
        runCatching {
            val name = displayNameOf(source) ?: "未命名文件"
            val safeName = sanitize(name)
            val relative = "$DIR_FILE/${UUID.randomUUID()}_$safeName"
            val target = File(root, relative).apply { parentFile?.mkdirs() }

            context.contentResolver.openInputStream(source)?.use { input ->
                target.outputStream().use { output ->
                    // 单文件上限 100MB —— 超了直接拒绝，别把用户存储撑爆
                    val copied = input.copyTo(output)
                    require(copied <= MAX_FILE_BYTES) { "文件超过 100MB" }
                }
            } ?: return@runCatching null

            Attachment(
                memoId = 0,
                type = AttachmentType.FILE,
                relativePath = relative,
                displayName = name,
                sizeBytes = target.length(),
            )
        }.getOrNull()
    }

    /* ────────────── 表格 ─────────────── */

    /** 表格不落文件，只把行列 + 单元格存成 JSON */
    fun newSheet(): Attachment = Attachment(
        memoId = 0,
        type = AttachmentType.SHEET,
        relativePath = "",
        displayName = "新建表格",
        sizeBytes = 0,
        sheetJson = DEFAULT_SHEET_JSON,
    )

    /* ────────────── 读取与清理 ─────────────── */

    fun fileOf(relativePath: String): File = File(root, relativePath)

    fun exists(relativePath: String): Boolean =
        relativePath.isNotBlank() && fileOf(relativePath).exists()

    /** 给 Image 组件用的 Uri（内部文件要用 FileProvider，不能直接给 File） */
    fun shareableUri(relativePath: String): Uri =
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            fileOf(relativePath),
        )

    fun deletePhysical(relativePath: String) {
        if (relativePath.isBlank()) return
        runCatching { fileOf(relativePath).delete() }
    }

    suspend fun totalUsedBytes(): Long = withContext(Dispatchers.IO) {
        root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /* ────────────── 内部工具 ─────────────── */

    /**
     * 降采样解码 —— 这一步是「不 OOM」的关键。
     * 直接 BitmapFactory.decodeStream 一张 50MP 的图会立刻 OOM，
     * 必须先 inJustDecodeBounds 读尺寸，算出 inSampleSize，再真解码。
     */
    private fun decodeDownsampled(uri: Uri, maxEdge: Int): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val (w, h) = info.size.width to info.size.height
                val sample = sampleSize(max(w, h), maxEdge)
                if (sample > 1) {
                    decoder.setTargetSampleSize(sample)
                }
            }
        }

        // API 28 以下走老路径
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(max(bounds.outWidth, bounds.outHeight), maxEdge)
        }
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: error("无法解码图片")
    }

    private fun sampleSize(edge: Int, maxEdge: Int): Int {
        var sample = 1
        while (edge / sample > maxEdge * 2) sample *= 2
        return sample
    }

    private fun displayNameOf(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80)

    companion object {
        private const val ROOT_DIR = "attachments"
        private const val DIR_AUDIO = "audio"
        private const val DIR_IMAGE = "image"
        private const val DIR_FILE = "file"
        private const val DIR_ORIGINALS = "originals"
        private const val DIR_BACKGROUND = "background"

        private const val MAX_IMAGE_EDGE = 2048
        private const val MAX_BACKGROUND_EDGE = 1440
        private const val MAX_FILE_BYTES = 100L * 1024 * 1024

        private const val DEFAULT_SHEET_JSON =
            """{"cols":4,"rows":6,"cells":{"0,0":"项目","0,1":"数值","0,2":"备注","0,3":"日期"}}"""
    }
}
