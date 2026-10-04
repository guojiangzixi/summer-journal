package com.summer.journal.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import com.summer.journal.domain.model.AttachmentType
import com.summer.journal.domain.model.CourseColor
import com.summer.journal.domain.model.WeekParity

/* ══════════════════════════════════════════════════════════
   Room 实体
   设计约定：
   1. 时间统一存 epochMillis（Long），不存字符串 —— 排序与范围查询才走得动索引
   2. 枚举用 TypeConverter 存 name，方便 DBA 手工排查
   3. 附件存相对路径
   ══════════════════════════════════════════════════════════ */

@Entity(
    tableName = "semester",
    indices = [Index(value = ["name"], unique = true)]
)
data class SemesterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "start_date") val startDate: Long,
    @ColumnInfo(name = "total_weeks") val totalWeeks: Int = 18,
    @ColumnInfo(name = "is_current") val isCurrent: Boolean = false,
    @ColumnInfo(name = "last_synced_at") val lastSyncedAt: Long? = null,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
)

@Entity(
    tableName = "course",
    foreignKeys = [
        ForeignKey(
            entity = SemesterEntity::class,
            parentColumns = ["id"],
            childColumns = ["semester_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [
        Index(value = ["semester_id", "day_of_week", "period_start"]),
        Index(value = ["semester_id"]),
    ]
)
data class CourseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "semester_id") val semesterId: Long,
    val name: String,
    val teacher: String? = null,
    val room: String? = null,
    /** 1=周一 … 7=周日 */
    @ColumnInfo(name = "day_of_week") val dayOfWeek: Int,
    @ColumnInfo(name = "period_start") val periodStart: Int,
    @ColumnInfo(name = "period_end") val periodEnd: Int,
    @ColumnInfo(name = "week_parity") val weekParity: WeekParity = WeekParity.ALL,
    @ColumnInfo(name = "week_start") val weekStart: Int = 1,
    @ColumnInfo(name = "week_end") val weekEnd: Int = 16,
    /**
     * 离散周次，逗号分隔（如 `"8,12"`）。
     *
     * 教务课表里真的会写「8,12(周)」—— 一门课一学期只上第 8 周和第 12 周。
     * 存成 week_start=8 / week_end=12 会把第 9/10/11 周也算成有课，
     * 于是「明明不上课却弹了提醒」。null 表示按 week_start..week_end 推算。
     */
    @ColumnInfo(name = "week_set") val weekSet: String? = null,
    @ColumnInfo(name = "color") val color: CourseColor = CourseColor.TEAL,
    val note: String? = null,
    /** 从教务导入的原始文本，改版排查时非常有用 */
    @ColumnInfo(name = "raw_text") val rawText: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "schedule_event",
    indices = [Index(value = ["start_at"]), Index(value = ["updated_at"])]
)
data class ScheduleEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val note: String? = null,
    @ColumnInfo(name = "start_at") val startAt: Long,
    @ColumnInfo(name = "end_at") val endAt: Long? = null,
    @ColumnInfo(name = "all_day") val allDay: Boolean = false,
    val location: String? = null,
    /** RFC 5545 RRULE 子串，如 FREQ=WEEKLY;BYDAY=MO,TH */
    @ColumnInfo(name = "repeat_rule") val repeatRule: String = "FREQ=ONCE",
    @ColumnInfo(name = "repeat_until") val repeatUntil: Long? = null,
    val color: CourseColor = CourseColor.VIOLET,
    /** 逗号分隔的提前分钟数，如 "0,30,1440"；0 表示准时 */
    @ColumnInfo(name = "reminder_offsets") val reminderOffsets: String = "",
    /** 逗号分隔标签 */
    val tags: String = "",
    @ColumnInfo(name = "is_outdoor") val isOutdoor: Boolean = false,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "memo",
    indices = [Index(value = ["updated_at"])]
)
data class MemoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    /** 自定义封面（应用私有目录下的相对路径） */
    @ColumnInfo(name = "cover_path") val coverPath: String? = null,
    val body: String = "",
    val tags: String = "",
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/**
 * 手札**列表**专用的读投影。
 *
 * ★ 为什么单独建一个投影，而不是直接用 `@Relation` 加载 attachments：
 *   1. `@Relation` 会给每条手札再发一次子查询（N+1），列表卡顿；
 *   2. 更关键的是：用 `LEFT JOIN` 手动连接附件，一条手札有 N 个附件就会
 *      出来 N 行重复 —— 列表里同一篇手札出现好几次。
 *
 * 这里用「手札实体 + 一条聚合子查询出的 kinds 字符串」解决：
 *   kinds 形如 `"AUDIO,IMAGE"`，由 `GROUP_CONCAT(DISTINCT type)` 得到，
 *   天然去重，也不会产生重复行。解析后喂给 [com.summer.journal.domain.model.Memo.kinds]。
 */
data class MemoListRow(
    @Embedded val memo: MemoEntity,
    @ColumnInfo(name = "kinds") val kinds: String? = null,
)

@Entity(
    tableName = "attachment",
    foreignKeys = [
        ForeignKey(
            entity = MemoEntity::class,
            parentColumns = ["id"],
            childColumns = ["memo_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index(value = ["memo_id", "type"]), Index(value = ["memo_id"])]
)
data class AttachmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "memo_id") val memoId: Long,
    val type: AttachmentType,
    /** 相对 app 私有目录的路径，如 attachments/audio/xxx.m4a */
    @ColumnInfo(name = "relative_path") val relativePath: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "duration_ms") val durationMs: Long? = null,
    @ColumnInfo(name = "sheet_json") val sheetJson: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    /** 删除手札时不立刻删文件，先标孤儿，24h 后由 WorkManager 清理 */
    @ColumnInfo(name = "orphan_since") val orphanSince: Long? = null,
)

@Entity(
    tableName = "weather_cache",
    indices = [Index(value = ["city_key"], unique = true)]
)
data class WeatherCacheEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "city_key") val cityKey: String,
    /** 整个 WeatherSnapshot 的 JSON，天气是「整体使用」的，拆表没收益 */
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long,
)

/* ══════════════════════════════════════════════════════════
   TypeConverter
   ══════════════════════════════════════════════════════════ */

class Converters {

    @TypeConverter fun weekParityToString(v: WeekParity): String = v.name
    @TypeConverter fun stringToWeekParity(v: String): WeekParity =
        runCatching { WeekParity.valueOf(v) }.getOrDefault(WeekParity.ALL)

    @TypeConverter fun colorToString(v: CourseColor): String = v.name
    @TypeConverter fun stringToColor(v: String): CourseColor =
        runCatching { CourseColor.valueOf(v) }.getOrDefault(CourseColor.TEAL)

    @TypeConverter fun attachTypeToString(v: AttachmentType): String = v.name
    @TypeConverter fun stringToAttachType(v: String): AttachmentType =
        runCatching { AttachmentType.valueOf(v) }.getOrDefault(AttachmentType.FILE)

    /** "0,30,1440" ⇄ List<Duration>（这里用 Long 毫秒数承载） */
    @TypeConverter fun offsetsToString(v: List<Long>): String = v.joinToString(",")
    @TypeConverter fun stringToOffsets(v: String): List<Long> =
        v.split(',').mapNotNull { it.trim().toLongOrNull() }

    /** 标签用 \u001F 分隔，避免与标签内容里的逗号冲突 */
    @TypeConverter fun tagsToString(v: Set<String>): String = v.joinToString("\u001F")
    @TypeConverter fun stringToTags(v: String): Set<String> =
        if (v.isBlank()) emptySet() else v.split('\u001F').toSet()
}
