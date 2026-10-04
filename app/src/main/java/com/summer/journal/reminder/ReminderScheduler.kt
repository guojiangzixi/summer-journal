package com.summer.journal.reminder

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.summer.journal.permission.AppPermission
import com.summer.journal.permission.PermissionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 提醒调度。
 *
 * ★ 为什么不用 WorkManager 做提醒：
 *   WorkManager 的最小延迟是「分钟级」，用来做 09:00 的课前提醒会迟到，
 *   而且系统会把任务批量合并、延迟执行。提醒必须走 AlarmManager。
 *
 * ★ 为什么要三档降级：
 *   Android 12+ 的精确闹钟是「特殊权限」，用户不给就只能降级；
 *   国内 ROM（荣耀 MagicOS 等）还会冻结后台应用。
 *   所以这里把「能不能准点」当成运行时状态处理，而不是假设一定能准。
 */
@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissionManager: PermissionManager,
) {

    private val alarmManager: AlarmManager
        get() = context.getSystemService(AlarmManager::class.java)

    /**
     * 调度能力：决定用哪个 API，以及要不要在 UI 上提示「提醒可能延迟」。
     *
     * @param windowMinutes 仅 APPROXIMATE 用：允许系统推迟的最大分钟数
     */
    enum class Capability(val windowMinutes: Long = 10) {
        /** 精确闹钟已授权 —— setExactAndAllowWhileIdle，误差秒级 */
        EXACT,

        /** 未授权 —— setWindow，最多晚 windowMinutes 分钟，UI 需提示 */
        APPROXIMATE,

        /** 通知权限都没有 —— 只能在应用内提示 */
        IN_APP_ONLY,
    }

    fun capabilityNotice(): String? = when (capability()) {
        Capability.EXACT -> null
        Capability.APPROXIMATE -> "精确闹钟未开启，提醒可能会有几分钟延迟"
        Capability.IN_APP_ONLY -> "未开启通知，提醒只会显示在 App 内"
    }

    fun capability(): Capability = when {
        !permissionManager.isGranted(AppPermission.NOTIFICATION) -> Capability.IN_APP_ONLY
        !permissionManager.isGranted(AppPermission.EXACT_ALARM) -> Capability.APPROXIMATE
        else -> Capability.EXACT
    }

    /* ══════════════════════════════════════════════════════════════
       排期
       ══════════════════════════════════════════════════════════════ */

    /**
     * 排一条提醒。
     *
     * @param requestCode 稳定且唯一，否则取消时找不回来。用 (eventId * 100 + offsetIndex) 生成。
     */
    fun schedule(
        requestCode: Int,
        title: String,
        body: String,
        triggerAt: LocalDateTime,
        eventId: Long,
    ) {
        val triggerAtMillis = triggerAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        // 已经过去的时间点直接跳过，不要排一个立刻会响的闹钟
        if (triggerAtMillis <= System.currentTimeMillis()) return

        val pendingIntent = buildPendingIntent(requestCode, title, body, eventId)

        when (capability()) {
            Capability.EXACT -> {
                // ★ 关键：setExactAndAllowWhileIdle 能在 Doze 模式下唤醒
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                } else {
                    alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                }
            }
            Capability.APPROXIMATE -> {
                // 降级：允许系统推迟，但给一个窗口上限，别无限期
                val window = capability().windowMinutes * 60_000L
                alarmManager.setWindow(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    window,
                    pendingIntent,
                )
            }
            Capability.IN_APP_ONLY -> {
                // 什么都不排。UI 会显示「提醒只在应用内」的提示条。
            }
        }
    }

    fun cancel(requestCode: Int, eventId: Long) {
        alarmManager.cancel(buildPendingIntent(requestCode, "", "", eventId))
    }

    /**
     * 给一个日程排所有提醒。
     * 注意：一个日程可以有多个提前量（准时 / 提前 30 分 / 提前 1 天）。
     */
    fun scheduleForEvent(
        eventId: Long,
        title: String,
        location: String?,
        startAt: LocalDateTime,
        offsets: List<Duration>,
    ) {
        cancelForEvent(eventId, offsets.size)
        offsets.forEachIndexed { index, offset ->
            schedule(
                requestCode = requestCodeOf(eventId, index),
                title = title,
                body = buildBody(startAt, location),
                triggerAt = startAt.minus(offset),
                eventId = eventId,
            )
        }
    }

    fun cancelForEvent(eventId: Long, offsetCount: Int) {
        repeat(offsetCount) { index ->
            cancel(requestCodeOf(eventId, index), eventId)
        }
    }

    /**
     * requestCode 用「递增 ID」而不是 hashCode —— hashCode 会撞，
     * 撞了就意味着取消 A 的提醒会把 B 的也取消掉。
     */
    private var nextRequestBase = 1_000
    private val requestCodes = mutableMapOf<Long, Int>()

    private fun requestCodeOf(eventId: Long, offsetIndex: Int): Int {
        val base = requestCodes.getOrPut(eventId) { nextRequestBase++ }
        return base * 10 + offsetIndex
    }

    /* ══════════════════════════════════════════════════════════════
       PendingIntent / 通知
       ══════════════════════════════════════════════════════════════ */

    private fun buildPendingIntent(
        requestCode: Int,
        title: String,
        body: String,
        eventId: Long,
    ): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_REMIND
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_BODY, body)
            putExtra(EXTRA_EVENT_ID, eventId)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun buildBody(startAt: LocalDateTime, location: String?): String {
        val time = "%02d:%02d".format(startAt.hour, startAt.minute)
        return if (location.isNullOrBlank()) time else "$time · $location"
    }

    companion object {
        const val ACTION_REMIND = "com.summer.journal.action.REMIND"
        const val EXTRA_TITLE = "title"
        const val EXTRA_BODY = "body"
        const val EXTRA_EVENT_ID = "event_id"

        /** 高优先级渠道：荣耀/华为会把低优先级通知折叠成静默，提醒就失效了 */
        const val CHANNEL_REMINDER = "channel_reminder"
        const val CHANNEL_CLASS = "channel_class"

        fun ensureChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            listOf(
                Triple(CHANNEL_REMINDER, "日程提醒", "你设置的日程提醒"),
                Triple(CHANNEL_CLASS, "上课提醒", "课前提醒"),
            ).forEach { (id, name, desc) ->
                val channel = NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH).apply {
                    description = desc
                    enableVibration(true)
                    setShowBadge(true)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }
}

/**
 * 闹钟到点后的接收器。
 * 职责单一：发通知。不要在这里查数据库、算逻辑 —— 广播接收器随时可能被系统杀掉。
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderScheduler.ACTION_REMIND) return

        val title = intent.getStringExtra(ReminderScheduler.EXTRA_TITLE).orEmpty()
        val body = intent.getStringExtra(ReminderScheduler.EXTRA_BODY).orEmpty()
        val eventId = intent.getLongExtra(ReminderScheduler.EXTRA_EVENT_ID, -1L)

        val openApp = PendingIntent.getActivity(
            context,
            eventId.toInt(),
            Intent().setClassName(context.packageName, "com.summer.journal.MainActivity")
                .putExtra("open_event_id", eventId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, ReminderScheduler.CHANNEL_REMINDER)
            .setSmallIcon(android.R.drawable.ic_dialog_info)   // TODO 换成自己的图标
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(eventId.toInt(), notification)
        } // 权限被撤销时 notify 会抛 SecurityException，静默吞掉即可
    }
}

/**
 * 开机 / 应用升级 / 时间被改 → 所有闹钟都会被系统清空，必须重排。
 *
 * 注意：这里只做「触发重排」，具体重排逻辑放在 WorkManager 里异步做，
 * 因为 BroadcastReceiver 只有 10 秒，查库 + 排几十个闹钟可能超时。
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            -> ReminderRescheduleWorker.enqueue(context)
        }
    }
}
