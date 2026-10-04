package com.summer.journal.permission

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 权限总入口。
 *
 * 所有「判断 / 申请 / 跳系统页」都必须走这里，业务代码不要直接调
 * ContextCompat.checkSelfPermission 或 Settings.ACTION_*。
 */
@Singleton
class PermissionManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * 是否已授权。
     * 注意三种权限的判定方式完全不同 —— 这是最容易写错的地方：
     *  - 运行时权限      → checkSelfPermission
     *  - 精确闹钟        → AlarmManager.canScheduleExactAlarms()
     *  - 电池优化白名单  → PowerManager.isIgnoringBatteryOptimizations()
     */
    fun isGranted(permission: AppPermission): Boolean = when (permission) {
        AppPermission.EXACT_ALARM ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
            } else true

        AppPermission.IGNORE_BATTERY_OPTIMIZATION ->
            context.getSystemService(PowerManager::class.java)
                ?.isIgnoringBatteryOptimizations(context.packageName) == true

        else ->
            if (!permission.isApplicable()) true   // 系统没有这个权限，视为已满足
            else ContextCompat.checkSelfPermission(context, permission.manifestName) ==
                PackageManager.PERMISSION_GRANTED
    }

    fun allGranted(permissions: List<AppPermission>): Boolean = permissions.all { isGranted(it) }

    /**
     * 需要跳系统设置页的特殊权限 → 对应的 Intent。
     * 返回 null 表示这是普通运行时权限，走 ActivityResultContracts.RequestPermission 即可。
     */
    fun settingsIntentFor(permission: AppPermission): Intent? = when (permission) {
        AppPermission.EXACT_ALARM ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    .setData("package:${context.packageName}".toUri())
            } else null

        AppPermission.IGNORE_BATTERY_OPTIMIZATION ->
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData("package:${context.packageName}".toUri())

        AppPermission.NOTIFICATION ->
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

        else -> null
    }

    /**
     * 用户已永久拒绝普通运行时权限时，退而求其次跳「应用详情页」，
     * 比跳通知设置页更通用。
     */
    fun appDetailsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData("package:${context.packageName}".toUri())

    /* ══════════════════════════════════════════════════════════════
       国内 ROM 专项：厂商「自启动 / 后台管理」页
       ══════════════════════════════════════════════════════════════ */

    /**
     * 这些 Intent 都是**非公开**的，跳转失败很正常。
     * 所以设计上：能跳就跳，跳不过就展示截图指路（见 BatteryOptGuideScreen），
     * 千万不要以为一定能跳过去。
     */
    private val vendorAutoStartIntents: List<Intent> = listOf(
        // 荣耀 / 华为
        Intent().setClassName(
            "com.huawei.systemmanager",
            "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        ),
        Intent().setClassName(
            "com.huawei.systemmanager",
            "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
        ),
        // 小米
        Intent().setClassName(
            "com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity",
        ),
        // OPPO / 一加
        Intent().setClassName(
            "com.coloros.safecenter",
            "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        ),
        // vivo
        Intent().setClassName(
            "com.vivo.permissionmanager",
            "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        ),
    )

    /**
     * 返回第一个「能被系统解析」的厂商自启动页。
     * 找不到就返回 null —— 调用方必须处理 null（展示图文指路）。
     */
    fun vendorAutoStartIntent(): Intent? = vendorAutoStartIntents.firstOrNull { intent ->
        intent.resolveActivity(context.packageManager) != null
    }

    /** 厂商名，用于把引导文案写得更具体（「荣耀手机请再手动确认…」） */
    fun manufacturerLabel(): String = when {
        Build.MANUFACTURER.equals("HONOR", ignoreCase = true) -> "荣耀"
        Build.MANUFACTURER.equals("HUAWEI", ignoreCase = true) -> "华为"
        Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) -> "小米"
        Build.MANUFACTURER.equals("OPPO", ignoreCase = true) -> "OPPO"
        Build.MANUFACTURER.equals("vivo", ignoreCase = true) -> "vivo"
        else -> Build.MANUFACTURER
    }

    /** 是否需要展示「后台保活」引导：非原生 Android 一律展示 */
    fun needsVendorGuide(): Boolean = manufacturerLabel() !in listOf("Google", "Android")

    /* ══════════════════════════════════════════════════════════════
       组合状态：提醒相关权限的整体就绪情况
       ══════════════════════════════════════════════════════════════ */

    data class ReminderReadiness(
        val notification: Boolean,
        val exactAlarm: Boolean,
        val batteryOptimization: Boolean,
        val vendorGuideNeeded: Boolean,
        val vendorLabel: String,
    ) {
        val allReady: Boolean get() = notification && exactAlarm && batteryOptimization
        val readyCount: Int get() = listOf(notification, exactAlarm, batteryOptimization).count { it }
    }

    fun reminderReadiness(): ReminderReadiness = ReminderReadiness(
        notification = isGranted(AppPermission.NOTIFICATION),
        exactAlarm = isGranted(AppPermission.EXACT_ALARM),
        batteryOptimization = isGranted(AppPermission.IGNORE_BATTERY_OPTIMIZATION),
        vendorGuideNeeded = needsVendorGuide(),
        vendorLabel = manufacturerLabel(),
    )

    /* ══════════════════════════════════════════════════════════════
       权限使用策略
       ══════════════════════════════════════════════════════════════ */

    /**
     * Android 13+ 上「选图片」优先走 PhotoPicker（PickVisualMedia），
     * 系统相册代选，App 只拿到用户选中的那几张 —— **完全不需要读媒体权限**。
     *
     * 少一个权限 = 少一半审核问题 + 少一半卸载。
     */
    fun shouldUsePhotoPicker(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** 12 及以下才需要传统的读存储权限 */
    fun legacyReadStoragePermission(): AppPermission = AppPermission.READ_EXTERNAL_STORAGE
}
