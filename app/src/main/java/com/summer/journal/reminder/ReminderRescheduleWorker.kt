package com.summer.journal.reminder

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.summer.journal.data.attachment.AttachmentStore
import com.summer.journal.data.datastore.SettingsRepository
import com.summer.journal.data.local.dao.CourseDao
import com.summer.journal.data.local.dao.ScheduleEventDao
import com.summer.journal.data.local.dao.SemesterDao
import com.summer.journal.data.repo.toLocalDate
import com.summer.journal.data.repo.occurrences
import com.summer.journal.data.repo.parseRepeatRule
import com.summer.journal.domain.model.ScheduleEvent
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * 重排所有提醒。
 *
 * 什么时候会用到它：
 *  · 手机重启 —— 系统会清空所有 AlarmManager 闹钟
 *  · 应用升级 —— 同上
 *  · 用户改了系统时间 / 时区
 *  · 用户改了「默认提醒提前量」设置
 *  · 教务导入完成（多了几十节课要提醒）
 *
 * ★ 为什么是 Worker 而不是在 Receiver 里直接做：
 *   BroadcastReceiver 只有 10 秒，查库 + 排几十个闹钟很可能超时被杀。
 *   Receiver 只负责「喊一声」，实际活儿交给 WorkManager。
 */
@HiltWorker
class ReminderRescheduleWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val eventDao: ScheduleEventDao,
    private val courseDao: CourseDao,
    private val semesterDao: SemesterDao,
    private val scheduler: ReminderScheduler,
    private val settings: SettingsRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = runCatching {
        val today = LocalDate.now()
        val horizon = today.plusDays(LOOK_AHEAD_DAYS)

        scheduleEvents(today, horizon)
        scheduleClasses(today, horizon)

        Result.success()
    }.getOrElse { Result.retry() }

    /** 日历日程：把未来 N 天里带提醒的日程都排上 */
    private suspend fun scheduleEvents(from: LocalDate, to: LocalDate) {
        val raw = eventDao.listBetween(
            from.minusYears(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
            to.plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
        )

        raw.forEach { entity ->
            val event = ScheduleEvent(
                id = entity.id,
                title = entity.title,
                note = entity.note,
                startAt = entity.startAt.let {
                    java.time.Instant.ofEpochMilli(it)
                        .atZone(java.time.ZoneId.systemDefault())
                        .toLocalDateTime()
                },
                endAt = null,
                allDay = entity.allDay,
                location = entity.location,
                repeatRule = parseRepeatRule(entity.repeatRule),
                reminders = entity.reminderOffsets
                    .split(',')
                    .mapNotNull { it.trim().toLongOrNull() }
                    .map { Duration.ofMinutes(it) },
                isOutdoor = entity.isOutdoor,
            )

            if (event.reminders.isEmpty()) {
                scheduler.cancelForEvent(event.id, offsetCount = 0)
                return@forEach
            }

            event.occurrences(from, to).forEach { instance ->
                scheduler.scheduleForEvent(
                    eventId = instance.id,
                    title = instance.title,
                    location = instance.location,
                    startAt = instance.startAt,
                    offsets = instance.reminders,
                )
            }
        }
    }

    /**
     * 上课提醒：从「课程规则」这一次性展开成未来 N 天的具体时刻。
     *
     * 之所以在这里展开而不是让 Repository 常驻一个展开后的表：
     * 课表存的是规则（第 1-16 周、单双周），展开是纯计算，
     * 每次重排现算比维护一份冗余表 + 同步逻辑简单得多。
     */
    private suspend fun scheduleClasses(from: LocalDate, to: LocalDate) {
        val semester = semesterDao.firstOrNull() ?: return
        val semesterStart = semester.startDate.toLocalDate()
        val leadMinutes = settings.classReminderMinutes.first().toLong()

        val courses = courseDao.listBySemester(semester.id)

        courses.forEach { entity ->
            val course = com.summer.journal.domain.model.Course(
                id = entity.id,
                semesterId = entity.semesterId,
                name = entity.name,
                teacher = entity.teacher,
                room = entity.room,
                dayOfWeek = entity.dayOfWeek,
                periodStart = entity.periodStart,
                periodEnd = entity.periodEnd,
                weekParity = entity.weekParity,
                weekRange = entity.weekStart..entity.weekEnd,
                color = entity.color,
            )

            course.occurrences(semesterStart)
                .filter { it in from..to }
                .forEach { date ->
                    val startTime = startTimeOf(course.periodStart) ?: return@forEach
                    val trigger = date.atTime(startTime).minusMinutes(leadMinutes)
                    val location = listOfNotNull(course.room, course.teacher).joinToString(" · ")
                    scheduler.schedule(
                        requestCode = CLASS_REQUEST_BASE + (course.id * 10 + course.periodStart / 2).toInt(),
                        title = course.name,
                        body = if (location.isBlank()) {
                            "还有 ${leadMinutes} 分钟上课"
                        } else {
                            "还有 ${leadMinutes} 分钟上课 · $location"
                        },
                        triggerAt = trigger,
                        eventId = -course.id,   // 负数区分「课程提醒」与「日程提醒」
                    )
                }
        }
    }

    private fun startTimeOf(periodStart: Int): LocalTime? = when (periodStart) {
        1 -> LocalTime.of(8, 0)
        3 -> LocalTime.of(10, 0)
        5 -> LocalTime.of(14, 0)
        7 -> LocalTime.of(16, 0)
        9 -> LocalTime.of(19, 0)
        11 -> LocalTime.of(20, 50)
        else -> null
    }

    companion object {
        private const val LOOK_AHEAD_DAYS = 7L
        private const val CLASS_REQUEST_BASE = 500_000

        private const val UNIQUE_RESCHEDULE = "reminder_reschedule"
        private const val UNIQUE_MAINTENANCE = "daily_maintenance"

        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_RESCHEDULE,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<ReminderRescheduleWorker>()
                    .setInitialDelay(3, TimeUnit.SECONDS)   // 让开机广播先喘口气
                    .build(),
            )
        }

        /** 每天一次的维护：清孤儿附件 + 清过期天气缓存 */
        fun enqueueDailyMaintenance(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_MAINTENANCE,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<MaintenanceWorker>(1, TimeUnit.DAYS)
                    .setInitialDelay(1, TimeUnit.HOURS)
                    .build(),
            )
        }
    }
}

/**
 * 日常维护：
 *  · 清理超过 24 小时的孤儿附件（删手札时只标记不删文件，防手滑）
 *  · 清理过期的天气缓存
 */
@HiltWorker
class MaintenanceWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val attachmentStore: AttachmentStore,
    private val memoRepository: com.summer.journal.data.repo.MemoRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = runCatching {
        val cutoff = System.currentTimeMillis() - ORPHAN_GRACE_MS

        memoRepository.orphansBefore(cutoff).forEach { attachment ->
            attachmentStore.deletePhysical(attachment.relativePath)
            memoRepository.purgeAttachment(attachment.id)
        }
        Result.success()
    }.getOrElse { Result.retry() }

    companion object {
        /** 24 小时宽限期：用户删完手札反悔了还能找回来 */
        private const val ORPHAN_GRACE_MS = 24L * 60 * 60 * 1000
    }
}
