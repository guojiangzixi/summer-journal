package com.summer.journal.data.recognition

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * 图片文字识别（OCR）。
 *
 * 用途：把手札里的图片（板书、PPT 截图、书页、通知）转成可编辑的正文。
 *
 * 三条设计决定：
 *  1. **用 bundled 版模型**（com.google.mlkit:text-recognition-chinese）而不是
 *     GMS 薄壳版 —— 国内荣耀/华为机型大多没有 Google Play 服务，
 *     薄壳版会永远停在「正在下载模型」。bundled 版模型随 APK 分发。
 *  2. **完全离线**。不联网、无 API Key、不申请任何权限 ——
 *     与天气（Open-Meteo）、教务导入（自建会话）保持同一原则。
 *  3. **识别器按需创建、用完即关**。ML Kit 的识别器持有 native 资源，
 *     常驻单例会白占内存；这里每次识别建一个，finally 里 close。
 */
@Singleton
class ImageTextRecognizer @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * 一行文字 + 它在图片里的位置。
     *
     * ★ 坐标不是可有可无的附加信息 —— 课表截图是**网格**，
     *   OCR 只会把文字按阅读顺序吐出来，「这一格属于星期几」这个信息
     *   只能靠 x 坐标判断。没有坐标，网格型课表根本没法重建。
     */
    data class TextLine(
        val text: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) {
        val centerX: Int get() = (left + right) / 2
        val centerY: Int get() = (top + bottom) / 2
    }

    /** 识别结果。block 是「段落」，ML Kit 已经按版面切好，比整块纯文本好读 */
    data class Result(
        val fullText: String,
        val blocks: List<String>,
        val lineCount: Int,
        /** 带坐标的行。用于重建表格结构（网格型课表截图必须靠它） */
        val lines: List<TextLine> = emptyList(),
    ) {
        val isEmpty: Boolean get() = fullText.isBlank()
    }

    /**
     * 识别一张图片。
     *
     * @param uri 图片来源（相册选中的 content:// 或私有目录的 file://）
     * @return 成功返回 Result；失败返回 null，调用方负责提示
     */
    suspend fun recognize(uri: Uri): Result? = runCatching {
        val image = InputImage.fromFilePath(context, uri)
        recognizeImage(image)
    }.getOrNull()

    /** 识别私有目录里的图片（手札附件走这条） */
    suspend fun recognize(file: File): Result? = runCatching {
        val image = InputImage.fromFilePath(context, Uri.fromFile(file))
        recognizeImage(image)
    }.getOrNull()

    private suspend fun recognizeImage(image: InputImage): Result =
        suspendCancellableCoroutine { continuation ->
            val recognizer = TextRecognition.getClient(
                // 中文识别器同时支持中英混排，覆盖「PPT 截图里夹英文术语」的场景
                ChineseTextRecognizerOptions.Builder().build()
            )

            recognizer.process(image)
                .addOnSuccessListener { text ->
                    val blocks = text.textBlocks.map { it.text }
                    val lines = text.textBlocks
                        .flatMap { it.lines }
                        .mapNotNull { line ->
                            // boundingBox 理论上一定存在；万一为空就跳过这一行
                            val box = line.boundingBox ?: return@mapNotNull null
                            TextLine(
                                text = line.text.trim(),
                                left = box.left,
                                top = box.top,
                                right = box.right,
                                bottom = box.bottom,
                            )
                        }
                        .filter { it.text.isNotBlank() }

                    val result = Result(
                        fullText = text.text.trim(),
                        blocks = blocks,
                        lineCount = lines.size,
                        lines = lines,
                    )
                    recognizer.close()
                    if (continuation.isActive) continuation.resume(result)
                }
                .addOnFailureListener {
                    recognizer.close()
                    if (continuation.isActive) {
                        continuation.resume(Result("", emptyList(), 0, emptyList()))
                    }
                }
        }
}
