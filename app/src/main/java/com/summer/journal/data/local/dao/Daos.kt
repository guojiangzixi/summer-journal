package com.summer.journal.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.summer.journal.data.local.entity.AttachmentEntity
import com.summer.journal.data.local.entity.CourseEntity
import com.summer.journal.data.local.entity.MemoEntity
import com.summer.journal.data.local.entity.MemoListRow
import com.summer.journal.data.local.entity.ScheduleEventEntity
import com.summer.journal.data.local.entity.SemesterEntity
import com.summer.journal.data.local.entity.WeatherCacheEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SemesterDao {
    @Query("SELECT * FROM semester ORDER BY is_current DESC, sort_order DESC")
    fun observeAll(): Flow<List<SemesterEntity>>

    @Query("SELECT * FROM semester WHERE is_current = 1 LIMIT 1")
    fun observeCurrent(): Flow<SemesterEntity?>

    @Query("SELECT * FROM semester WHERE id = :id")
    suspend fun findById(id: Long): SemesterEntity?

    @Query("SELECT COUNT(*) FROM semester")
    suspend fun count(): Int

    @Query("SELECT * FROM semester ORDER BY is_current DESC, sort_order DESC LIMIT 1")
    suspend fun firstOrNull(): SemesterEntity?

    @Upsert suspend fun upsert(item: SemesterEntity): Long

    /** 切换当前学期：先全部取消，再标记目标 —— 必须在一个事务里 */
    @Transaction
    suspend fun setCurrent(id: Long) {
        clearCurrent()
        markCurrent(id)
    }

    @Query("UPDATE semester SET is_current = 0")
    suspend fun clearCurrent()

    @Query("UPDATE semester SET is_current = 1 WHERE id = :id")
    suspend fun markCurrent(id: Long)

    @Query("UPDATE semester SET last_synced_at = :at WHERE id = :id")
    suspend fun markSynced(id: Long, at: Long)

    @Delete suspend fun delete(item: SemesterEntity)
}

@Dao
interface CourseDao {
    @Query("SELECT * FROM course WHERE semester_id = :semesterId ORDER BY day_of_week, period_start")
    fun observeBySemester(semesterId: Long): Flow<List<CourseEntity>>

    /** 一次性读取：Worker / 重排提醒这类场景不需要 Flow */
    @Query("SELECT * FROM course WHERE semester_id = :semesterId ORDER BY day_of_week, period_start")
    suspend fun listBySemester(semesterId: Long): List<CourseEntity>

    @Query("SELECT * FROM course WHERE id = :id")
    suspend fun findById(id: Long): CourseEntity?

    @Upsert suspend fun upsert(item: CourseEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<CourseEntity>)

    @Update suspend fun update(item: CourseEntity)

    @Query("DELETE FROM course WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM course WHERE semester_id = :semesterId")
    suspend fun deleteBySemester(semesterId: Long)

    /**
     * 导入时用来做 diff：同一格子已有课程则视为「冲突」，交给用户决定覆盖还是保留。
     * 注意 —— 这里不设数据库唯一约束，因为单双周会合法地让两门课落在同一格。
     */
    @Query(
        """SELECT * FROM course
           WHERE semester_id = :semesterId
             AND day_of_week = :dayOfWeek
             AND period_start = :periodStart
             AND period_end = :periodEnd"""
    )
    suspend fun findSameSlot(
        semesterId: Long,
        dayOfWeek: Int,
        periodStart: Int,
        periodEnd: Int,
    ): List<CourseEntity>
}

@Dao
interface ScheduleEventDao {
    @Query("SELECT * FROM schedule_event WHERE start_at BETWEEN :from AND :to ORDER BY start_at")
    fun observeBetween(from: Long, to: Long): Flow<List<ScheduleEventEntity>>

    @Query("SELECT * FROM schedule_event WHERE start_at >= :from AND start_at <= :to ORDER BY start_at")
    suspend fun listBetween(from: Long, to: Long): List<ScheduleEventEntity>

    @Query("SELECT * FROM schedule_event WHERE id = :id")
    suspend fun findById(id: Long): ScheduleEventEntity?

    @Upsert suspend fun upsert(item: ScheduleEventEntity): Long

    @Query("DELETE FROM schedule_event WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 到期未提醒的记录，用于「错过的提醒」补偿 */
    @Query("SELECT * FROM schedule_event WHERE start_at < :now AND start_at > :since ORDER BY start_at DESC")
    suspend fun listRecentlyPast(now: Long, since: Long): List<ScheduleEventEntity>
}

@Dao
interface MemoDao {
    @Query("SELECT * FROM memo ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<MemoEntity>>

    /**
     * 列表查询（可按附件类型筛选）。
     *
     * ★ 为什么不用 LEFT JOIN 附件：
     *   一篇手札有 N 个附件就会 JOIN 出 N 行 —— 列表里同一篇手札重复出现 N 次，
     *   而且卡片上的类型标签还拿不到正确值。
     *
     * 现在的做法：
     *   · 用 `EXISTS` 子查询做筛选 —— 不产生重复行，索引 `(memo_id, type)` 直接命中；
     *   · 用一条 `GROUP_CONCAT(DISTINCT type)` 子查询把附件类型聚合成
     *     `"AUDIO,IMAGE"` 一次带出，供卡片标签使用（解析见 MemoRepository）。
     */
    @Query(
        """SELECT memo.*,
                  (SELECT GROUP_CONCAT(DISTINCT attachment.type)
                     FROM attachment
                    WHERE attachment.memo_id = memo.id) AS kinds
             FROM memo
            WHERE (:type IS NULL OR EXISTS(
                      SELECT 1 FROM attachment
                       WHERE attachment.memo_id = memo.id
                         AND attachment.type = :type))
            ORDER BY memo.updated_at DESC"""
    )
    fun observeFilteredByAttachmentType(type: String?): Flow<List<MemoListRow>>

    /** 按标题 / 正文模糊搜索。同样带出 kinds，搜索结果卡片也要能显示类型标签。 */
    @Query(
        """SELECT memo.*,
                  (SELECT GROUP_CONCAT(DISTINCT attachment.type)
                     FROM attachment
                    WHERE attachment.memo_id = memo.id) AS kinds
             FROM memo
            WHERE memo.title LIKE '%' || :q || '%'
               OR memo.body LIKE '%' || :q || '%'
            ORDER BY memo.updated_at DESC"""
    )
    fun search(q: String): Flow<List<MemoListRow>>

    @Query("SELECT * FROM memo WHERE id = :id")
    suspend fun findById(id: Long): MemoEntity?

    @Upsert suspend fun upsert(item: MemoEntity): Long

    @Query("UPDATE memo SET cover_path = :coverPath, updated_at = :at WHERE id = :id")
    suspend fun updateCover(id: Long, coverPath: String?, at: Long)

    /** 正文自动保存。只改 title/body/updated_at，不碰封面和创建时间 */
    @Query("UPDATE memo SET title = :title, body = :body, updated_at = :at WHERE id = :id")
    suspend fun updateText(id: Long, title: String, body: String, at: Long)

    /** OCR 结果追加到正文末尾。用 SQL 的 || 拼接，省掉一次「读-改-写」 */
    @Query("UPDATE memo SET body = body || :addition, updated_at = :at WHERE id = :id")
    suspend fun appendToBody(id: Long, addition: String, at: Long)

    @Query("DELETE FROM memo WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface AttachmentDao {
    @Query("SELECT * FROM attachment WHERE memo_id = :memoId ORDER BY created_at")
    fun observeByMemo(memoId: Long): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachment WHERE memo_id = :memoId ORDER BY created_at")
    suspend fun listByMemo(memoId: Long): List<AttachmentEntity>

    @Upsert suspend fun upsert(item: AttachmentEntity): Long

    @Query("DELETE FROM attachment WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 标记孤儿：删手札时先把文件标脏，24 小时后再真删，防误删 */
    @Query("UPDATE attachment SET orphan_since = :at WHERE memo_id = :memoId")
    suspend fun markOrphanByMemo(memoId: Long, at: Long)

    @Query("SELECT * FROM attachment WHERE orphan_since IS NOT NULL AND orphan_since < :before")
    suspend fun listOrphansBefore(before: Long): List<AttachmentEntity>

    @Query("SELECT COALESCE(SUM(size_bytes), 0) FROM attachment WHERE orphan_since IS NULL")
    suspend fun totalUsedBytes(): Long
}

@Dao
interface WeatherCacheDao {
    @Query("SELECT * FROM weather_cache WHERE city_key = :cityKey LIMIT 1")
    suspend fun find(cityKey: String): WeatherCacheEntity?

    @Upsert suspend fun upsert(item: WeatherCacheEntity): Long

    @Query("DELETE FROM weather_cache WHERE fetched_at < :before")
    suspend fun purgeOlderThan(before: Long)
}
