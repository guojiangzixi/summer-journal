package com.summer.journal.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.summer.journal.data.local.dao.AttachmentDao
import com.summer.journal.data.local.dao.CourseDao
import com.summer.journal.data.local.dao.MemoDao
import com.summer.journal.data.local.dao.ScheduleEventDao
import com.summer.journal.data.local.dao.SemesterDao
import com.summer.journal.data.local.dao.WeatherCacheDao
import com.summer.journal.data.local.entity.AttachmentEntity
import com.summer.journal.data.local.entity.Converters
import com.summer.journal.data.local.entity.CourseEntity
import com.summer.journal.data.local.entity.MemoEntity
import com.summer.journal.data.local.entity.ScheduleEventEntity
import com.summer.journal.data.local.entity.SemesterEntity
import com.summer.journal.data.local.entity.WeatherCacheEntity

@Database(
    entities = [
        SemesterEntity::class,
        CourseEntity::class,
        ScheduleEventEntity::class,
        MemoEntity::class,
        AttachmentEntity::class,
        WeatherCacheEntity::class,
    ],
    version = 2,
    exportSchema = true,   // schema 导出到 app/schemas/，Migration 测试要用
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun semesterDao(): SemesterDao
    abstract fun courseDao(): CourseDao
    abstract fun scheduleEventDao(): ScheduleEventDao
    abstract fun memoDao(): MemoDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun weatherCacheDao(): WeatherCacheDao

    companion object {
        const val NAME = "summer_journal.db"

        /**
         * 数据库迁移。
         *
         * ══════════════════════════════════════════════════════════════════
         ⚠️ 这是本项目**最容易出事**的地方，改数据结构前请务必读完这段。
         ══════════════════════════════════════════════════════════════════
         *
         * 规则：
         *   debug 包  → 允许 fallbackToDestructiveMigration（改表直接清库，开发方便）
         *   release 包 → **只认显式迁移**，漏写一条迁移就会在升级时崩，
         *               但崩溃远好过「静默把一学期课表删了」
         *
         * 改表的完整流程：
         *   1. 改 Entity（加字段 / 改字段 / 加表）
         *   2. 把 @Database 的 version 从 1 改成 2
         *   3. 在下面写 MIGRATION_1_2
         *   4. **一定要写测试**：MigrationTestHelper 跑一遍，确认老数据还在
         *   5. versionCode 加一，出包
         *
         * 常见操作的模板（加字段）：
         *
         *   private val MIGRATION_1_2 = object : Migration(1, 2) {
         *       override fun migrate(db: SupportSQLiteDatabase) {
         *           // 加字段必须给默认值，否则老数据这列是 NULL，Kotlin 非空类型会崩
         *           db.execSQL("ALTER TABLE course ADD COLUMN credits INTEGER NOT NULL DEFAULT 0")
         *       }
         *   }
         *
         * 加表：
         *
         *   private val MIGRATION_2_3 = object : Migration(2, 3) {
         *       override fun migrate(db: SupportSQLiteDatabase) {
         *           db.execSQL(
         *               "CREATE TABLE IF NOT EXISTS exam (" +
         *               "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
         *               "course_id INTEGER NOT NULL, " +
         *               "start_at INTEGER NOT NULL)"
         *           )
         *           db.execSQL("CREATE INDEX IF NOT EXISTS index_exam_course_id ON exam(course_id)")
         *       }
         *   }
         *
         * 删字段 / 改类型：SQLite 不支持 DROP COLUMN（3.35 以下），
         * 要「建新表 → 拷数据 → 删旧表 → 改名」四步，别偷懒。
         * 建议先看 Room 导出的 schemas/1.json 和 2.json 对比字段顺序。
         */
        /**
         * v1 → v2：course 表加 week_set 列（离散周次）。
         *
         * 起因：实测教务课表里存在「8,12(周)」这种写法，一门课只上第 8 周和第 12 周。
         * 原来是靠 week_start=8 / week_end=12 表示的，会把第 9/10/11 周也算成有课。
         *
         * ★ 加列必须给 DEFAULT —— 否则老数据这一列是 NULL，
         *   虽然 weekSet 是可空类型不会崩，但保持「加列必带默认值」的习惯能避免踩坑。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE course ADD COLUMN week_set TEXT DEFAULT NULL")
            }
        }

        /**
         * ★ 顺序有讲究：Kotlin 的 companion object 属性初始化是**按书写顺序**执行，
         *   所以 MIGRATION_1_2 必须写在引用它的 MIGRATIONS 之前，
         *   否则报 "Variable 'MIGRATION_1_2' must be initialized"。
         */
        val MIGRATIONS: Array<Migration> = arrayOf(
            MIGRATION_1_2,
        )
    }
}
