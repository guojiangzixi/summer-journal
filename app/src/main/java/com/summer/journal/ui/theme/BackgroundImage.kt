package com.summer.journal.ui.theme

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import java.io.File

/**
 * 背景图的加载。
 *
 * 图片在导入时就已经降采样过了（见 AttachmentStore.importBackground），
 * 所以这里直接 decodeFile 不会 OOM，不需要再算 inSampleSize。
 * 这一点很重要 —— 背景图是每个页面都会画的，解码路径必须尽可能短。
 */
fun loadBackgroundBitmap(filesDir: File, relativePath: String?): ImageBitmap? = runCatching {
    if (relativePath.isNullOrBlank()) return null
    val file = File(File(filesDir, "attachments"), relativePath)
    if (!file.exists()) return null
    BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
}.getOrNull()

@Composable
fun rememberBackgroundImage(relativePath: String?): ImageBitmap? {
    val context = LocalContext.current
    return remember(relativePath) {
        loadBackgroundBitmap(context.filesDir, relativePath)
    }
}
