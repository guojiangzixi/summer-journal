package com.summer.journal.ui.common

import android.content.Context
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.FileProvider
import java.io.File

/**
 * 启动系统相机。
 *
 * ★ 为什么不能直接把 `File` 交给 TakePicture：
 *   系统相机的 `EXTRA_OUTPUT` 只接受 `content://` URI。
 *   传 `file://` 在 Android 7+ 会直接抛 `FileUriExposedException`。
 *   所以要先经 FileProvider 把私有文件包装成 content:// URI。
 *
 * 落点是 `cache/share/`，对应 `res/xml/file_paths.xml` 里的 `cache_share` 声明。
 * 用 cache 而不是 files：这些原图只用于「拍完立刻导入」，不需要长期保留。
 */
fun launchCameraCapture(
    context: Context,
    launcher: ActivityResultLauncher<Uri>,
    fileNamePrefix: String = "shot",
    onUriReady: (Uri) -> Unit,
) {
    val file = File(context.cacheDir, "share/${fileNamePrefix}_${System.currentTimeMillis()}.jpg")
        .apply { parentFile?.mkdirs() }

    val uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull() ?: return

    onUriReady(uri)
    runCatching { launcher.launch(uri) }
}
