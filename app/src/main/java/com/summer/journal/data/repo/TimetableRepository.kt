package com.summer.journal.data.repo

import com.summer.journal.data.local.dao.CourseDao
import com.summer.journal.data.local.dao.SemesterDao
import com.summer.journal.data.local.entity.CourseEntity
import com.summer.journal.data.local.entity.SemesterEntity
import com.summer.journal.data.remote.jwxt.CsuJwxtImporter
import com.summer.journal.domain.model.Course
import com.summer.journal.domain.model.CourseColor
import com.summer.journal.domain.model.Semester
import com.summer.journal.domain.model.WeekInput
import com.summer.journal.domain.model.WeekParity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TimetableRepository @Inject constructor(
    private val semesterDao: SemesterDao,
    private val courseDao: CourseDao,
) {

    /* ────────────── 学期 ────────────── */

    fun observeSemesters(): Flow<List<Semester>> =
        semesterDao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observeCurrentSemester(): Flow<Semester?> =
        semesterDao.observeCurrent().map { it?.toDomain() }

    suspend fun createSemester(
        name: String,
        startDate: LocalDate,
        totalWeeks: Int = DEFAULT_TOTAL_WEEKS,
        makeCurrent: Boolean = true,
    ): Long {
        val id = semesterDao.upsert(
            SemesterEntity(
                name = name,
                startDate = startDate.toEpochMillis(),
                totalWeeks = totalWeeks.coerceIn(1, MAX_TOTAL_WEEKS),
                isCurrent = false,
            )
        )
        if (makeCurrent) semesterDao.setCurrent(id)
        return id
    }

    suspend fun setCurrentSemester(id: Long) = semesterDao.setCurrent(id)

    /**
     * 首次启动、库里一个学期都没有时调用：按当前日期推算一个合理的默认学期。
     *
     * 注意这里只是给一个**可编辑的起点**，不是「默认城市」那种会误导用户的预设 ——
     * 学期起止日期用户在设置里能改，而且第一件事就是引导他去确认。
     */
    suspend fun ensureDefaultSemesterExists(): Long {
        semesterDao.firstOrNull()?.let { return it.id }

        val today = LocalDate.now()
        // 9 月之后算秋季学期，2 月之后算春季学期
        val (startYear, name) = when (today.monthValue) {
            in 9..12 -> today.year to "${today.year}–${today.year + 1} 第一学期"
            in 1..2 -> (today.year - 1) to "${today.year - 1}–${today.year} 第一学期"
            else -> (today.year - 1) to "${today.year - 1}–${today.year} 第二学期"
        }
        // 秋季学期大致从 9 月 1 日所在那一周的周一开始
        val start = LocalDate.of(startYear, 9, 1).let {
            it.minusDays((it.dayOfWeek.value - 1).toLong())
        }
        return createSemester(
            name = name,
            startDate = start,
            totalWeeks = DEFAULT_TOTAL_WEEKS,
            makeCurrent = true,
        )
    }

    /* ────────────── 课程 ────────────── */

    fun observeCourses(semesterId: Long): Flow<List<Course>> =
        courseDao.observeBySemester(semesterId).map { list -> list.map { it.toDomain() } }

    suspend fun upsertCourse(course: Course): Long =
        courseDao.upsert(course.toEntity())

    suspend fun deleteCourse(id: Long) = courseDao.deleteById(id)

    /** 清空某个学期的全部课程。示例课表 / 导入失败重来都用它 */
    suspend fun clearCourses(semesterId: Long) = courseDao.deleteBySemester(semesterId)

    /**
     * 把教务导入的结果落库。
     *
     * 注意 course.semesterId 在解析阶段是 0（解析器不知道学期 ID），
     * 到这里才填上 —— 这是刻意的解耦：解析器保持纯函数，可单测。
     */
    suspend fun importParsed(semesterId: Long, parsed: CsuJwxtImporter.ParsedTimetable): ImportOutcome {
        val entities = parsed.courses.map { it.copy(semesterId = semesterId).toEntity() }
        if (entities.isEmpty()) return ImportOutcome(0, 0, 0)

        var added = 0
        var replaced = 0
        entities.forEach { entity ->
            val conflicts = courseDao.findSameSlot(
                semesterId = semesterId,
                dayOfWeek = entity.dayOfWeek,
                periodStart = entity.periodStart,
                periodEnd = entity.periodEnd,
            )
            if (conflicts.isEmpty()) {
                courseDao.insertAll(listOf(entity))
                added++
            } else {
                // 同格子已有课：直接替换而不是叠加。
                // 单双周交错的情况在课表上本来就是两个格子，不会走到这里。
                conflicts.forEach { courseDao.deleteById(it.id) }
                courseDao.insertAll(listOf(entity))
                replaced++
            }
        }
        semesterDao.markSynced(semesterId, System.currentTimeMillis())
        return ImportOutcome(
            added = added,
            replaced = replaced,
            unrecognized = parsed.unrecognizedSlots.size,
        )
    }

    data class ImportOutcome(val added: Int, val replaced: Int, val unrecognized: Int)

    /* ────────────── 映射 ────────────── */

    private fun SemesterEntity.toDomain() = Semester(
        id = id,
        name = name,
        startDate = startDate.toLocalDate(),
        totalWeeks = totalWeeks,
        isCurrent = isCurrent,
        lastSyncedAt = lastSyncedAt?.toLocalDateTime(),
    )

    private fun CourseEntity.toDomain() = Course(
        id = id,
        semesterId = semesterId,
        name = name,
        teacher = teacher,
        room = room,
        dayOfWeek = dayOfWeek,
        periodStart = periodStart,
        periodEnd = periodEnd,
        weekParity = weekParity,
        weekRange = weekStart..weekEnd,
        weekSet = WeekInput.fromStorage(weekSet),
        color = color,
        note = note,
    )

    private fun Course.toEntity() = CourseEntity(
        id = id,
        semesterId = semesterId,
        name = name,
        teacher = teacher,
        room = room,
        dayOfWeek = dayOfWeek,
        periodStart = periodStart,
        periodEnd = periodEnd,
        weekParity = weekParity,
        weekStart = weekRange.first,
        weekEnd = weekRange.last,
        // 离散周次按升序存成 "8,12"，读出来再解析回 Set
        weekSet = weekSet?.sorted()?.joinToString(","),
        color = color,
        note = note,
        rawText = null,
        createdAt = System.currentTimeMillis(),
    )
}

/* ────────────── 学期周数约定（全项目统一）────────────── */

/**
 * 总周数默认值与上限。
 *
 * ★ 为什么默认从 18 提到 20、上限从 30 提到 40：
 *   用户反馈「周数不够用」。国内高校一个学期常见 16–20 教学周，
 *   加上复习/考试周可到 22 周左右；部分学校（含小学期/实践周）能到 30+。
 *   上限给到 40 足够覆盖，且不会让周视图翻页过长而难用。
 */
const val DEFAULT_TOTAL_WEEKS: Int = 20
const val MAX_TOTAL_WEEKS: Int = 40

/* ────────────── 时间转换小工具（全项目统一走这里）────────────── */

internal fun Long.toLocalDate(): LocalDate =
    Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()

internal fun Long.toLocalDateTime(): java.time.LocalDateTime =
    Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDateTime()

internal fun LocalDate.toEpochMillis(): Long =
    atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

internal fun java.time.LocalDateTime.toEpochMillis(): Long =
    atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

/** 兜底的调色：解析器已给颜色，这里只用于手工新增时随机分配 */
internal fun defaultColorFor(index: Int): CourseColor =
    CourseColor.entries[index % CourseColor.entries.size]

internal fun WeekParity.ordinalLabel(): String = when (this) {
    WeekParity.ALL -> "每周"
    WeekParity.ODD -> "单周"
    WeekParity.EVEN -> "双周"
}
