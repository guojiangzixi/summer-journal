package com.summer.journal.permission

import android.Manifest
import android.os.Build

/**
 * 权限定义。
 *
 * 分三类，因为「申请方式」和「降级方式」完全不同：
 *  - RUNTIME  ：普通运行时权限，一个系统弹框搞定
 *  - SPECIAL  ：特殊权限，必须跳系统设置页（Android 12+ 的精确闹钟、电池优化白名单）
 *  - AUTO     ：安装即得，只需在 Manifest 声明（开机广播等）
 */
enum class PermissionKind { RUNTIME, SPECIAL, AUTO }

/**
 * 应用用到的全部权限。
 * 加权限时**必须先在这里登记**，然后才允许在业务代码里引用 —— 避免权限散落各处。
 */
enum class AppPermission(
    val manifestName: String,
    val kind: PermissionKind,
    val minSdk: Int = 0,
) {
    /** Android 13+ 必须动态申请；拒绝后提醒只在应用内可见 */
    NOTIFICATION(
        manifestName = Manifest.permission.POST_NOTIFICATIONS,
        kind = PermissionKind.RUNTIME,
        minSdk = Build.VERSION_CODES.TIRAMISU,
    ),

    /** Android 12+ 特殊权限：不授权就只能「大致准点」 */
    EXACT_ALARM(
        manifestName = Manifest.permission.SCHEDULE_EXACT_ALARM,
        kind = PermissionKind.SPECIAL,
        minSdk = Build.VERSION_CODES.S,
    ),

    /** 国内 ROM（荣耀 MagicOS / 华为 EMUI / 小米 MIUI）必做，否则后台提醒可能不触发 */
    IGNORE_BATTERY_OPTIMIZATION(
        manifestName = Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        kind = PermissionKind.SPECIAL,
    ),

    /** 只申请粗定位。精确定位不主动要 —— 天气不需要米级精度 */
    COARSE_LOCATION(
        manifestName = Manifest.permission.ACCESS_COARSE_LOCATION,
        kind = PermissionKind.RUNTIME,
    ),

    /** 手札录音；拒绝后可改为「上传已有音频」，其他功能不受影响 */
    RECORD_AUDIO(
        manifestName = Manifest.permission.RECORD_AUDIO,
        kind = PermissionKind.RUNTIME,
    ),

    /** 手札拍照；**不申请也能用相册**，所以这是纯可选权限 */
    CAMERA(
        manifestName = Manifest.permission.CAMERA,
        kind = PermissionKind.RUNTIME,
    ),

    /** 读取媒体（Android 13+）。13+ 上优先走 PhotoPicker，通常根本用不到 */
    READ_MEDIA_IMAGES(
        manifestName = "android.permission.READ_MEDIA_IMAGES",
        kind = PermissionKind.RUNTIME,
        minSdk = Build.VERSION_CODES.TIRAMISU,
    ),
    READ_MEDIA_AUDIO(
        manifestName = "android.permission.READ_MEDIA_AUDIO",
        kind = PermissionKind.RUNTIME,
        minSdk = Build.VERSION_CODES.TIRAMISU,
    ),
    /** Android 12 及以下的兼容路径 */
    READ_EXTERNAL_STORAGE(
        manifestName = Manifest.permission.READ_EXTERNAL_STORAGE,
        kind = PermissionKind.RUNTIME,
    ),
    ;

    /** 当前系统版本下这个权限是否「存在」—— 低版本没有的权限不该出现在引导页里 */
    fun isApplicable(sdkInt: Int = Build.VERSION.SDK_INT): Boolean = sdkInt >= minSdk
}

/**
 * 申请结果 —— 三种，而不是「给没给」两种。
 * 区分「还能再问」和「永久拒绝」是体验的关键：
 * 前者该出引导卡，后者只能跳系统设置。
 */
sealed interface PermissionResult {
    data object Granted : PermissionResult
    data class Denied(val canAskAgain: Boolean) : PermissionResult
    data object PermanentlyDenied : PermissionResult

    val isGranted: Boolean get() = this is Granted
}

/**
 * 权限被拒后，功能怎么降级、话术怎么说。
 * 集中放在这里，避免每个页面自己编文案（最后一定会不一致）。
 */
data class PermissionRationale(
    val title: String,
    /** 为什么需要 —— 一句话，别写长段落 */
    val why: String,
    /** 拒绝了会怎样 —— 必须诚实说明，不要吓唬用户 */
    val fallback: String,
)

val AppPermission.rationale: PermissionRationale
    get() = when (this) {
        AppPermission.NOTIFICATION -> PermissionRationale(
            title = "开启通知才能收到提醒",
            why = "日程和上课提醒需要通过系统通知送达。",
            fallback = "不开也没关系，提醒会在你下次打开 App 时以横幅形式出现。",
        )
        AppPermission.EXACT_ALARM -> PermissionRationale(
            title = "开启精确闹钟才能准点响",
            why = "系统默认会为了省电推迟闹钟，最长可能晚 10 分钟。",
            fallback = "不开启的话，提醒仍会响，只是可能晚几分钟。",
        )
        AppPermission.IGNORE_BATTERY_OPTIMIZATION -> PermissionRationale(
            title = "让提醒在后台不被清理",
            why = "部分手机（含荣耀）会在后台冻结应用，导致提醒不触发。",
            fallback = "不设置的话，夜间和长时间未打开 App 时提醒可能不准。",
        )
        AppPermission.COARSE_LOCATION -> PermissionRationale(
            title = "用一次定位匹配校区天气",
            why = "读取当前位置，自动显示你所在校区的天气。",
            fallback = "也可以手动选择城市，天气功能完全可用。",
        )
        AppPermission.RECORD_AUDIO -> PermissionRationale(
            title = "录音权限只用于手札",
            why = "在手札里直接录一段声音日记。",
            fallback = "不授权的话，可以改为上传已有的音频文件。",
        )
        AppPermission.CAMERA -> PermissionRationale(
            title = "拍张照存进手札",
            why = "直接拍摄照片作为手札附件或封面。",
            fallback = "不授权也能从相册选图。",
        )
        AppPermission.READ_MEDIA_IMAGES,
        AppPermission.READ_MEDIA_AUDIO,
        AppPermission.READ_EXTERNAL_STORAGE -> PermissionRationale(
            title = "选择要放进手札的文件",
            why = "从你的相册 / 音频里挑内容。",
            fallback = "系统会让你逐张选择，App 只拿到你选中的那些。",
        )
    }