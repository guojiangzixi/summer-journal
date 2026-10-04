package com.summer.journal.data.importing

import com.summer.journal.data.recognition.ImageTextRecognizer
import com.summer.journal.data.remote.jwxt.CsuJwxtParser

/**
 * 网格型课表截图的结构重建。
 *
 * ══════════════════════════════════════════════════════════════════════
 *  ★ 为什么必须有这个文件
 *
 *  教务课表截图是**二维网格**：星期在表头（横向），节次在最左列（纵向），
 *  课程信息填在交叉的格子里。而 OCR 只会按阅读顺序把文字吐出来，
 *  纯文本长这样：
 *
 *      星期日 星期一 星期二 星期三 …
 *      1 - 2  概率论与数理统计A  大学物理B（二）  概率论与数理统计A
 *      王志忠[教授]  李幼真[副教授]  王志忠[教授]
 *      3-16(周)  3-16(周)  3-16(周)
 *      B座411  A座404  B座411
 *      …
 *
 *  一行里混着好几个星期的内容，「这条文字属于星期几」的信息在纯文本里
 *  **已经丢了**。任何按行解析的写法都不可能还原它 —— 硬猜只会得到一堆错课。
 *
 *  唯一可靠的办法：用文字在图片里的**坐标**把网格重新拼出来。
 *  所以 ImageTextRecognizer 会保留每行的 boundingBox，这里据此外推：
 *
 *      ① 表头行里每个星期名 → 确定「哪一列是星期几」
 *      ② 最左列的文字（"1 - 2"）→ 确定「每一行的节次」
 *      ③ 其余文字按 x 落列、按 y 落行 → 还原出一个个「格子」
 *      ④ 每个格子的文字交给 CsuJwxtParser.parseCellBlock 解析
 *         （和教务导入同一套规则，保证两处结果一致）
 * ══════════════════════════════════════════════════════════════════════
 */
object GridCourseParser {

    /** 列锚点：哪一列是星期几，以及这一列的中心 x */
    private data class Column(val dayOfWeek: Int, val centerX: Int)

    /** 行锚点：哪一行是什么节次，以及这一行的 y 中心 */
    private data class Row(val periodStart: Int, val periodEnd: Int, val centerY: Int)

    /**
     * 尝试按网格解析。
     *
     * @return null 表示「这张图不是网格结构」（比如是纵向列表），
     *         调用方应该退回到 CourseTextParser 的按行策略。
     */
    fun parse(lines: List<ImageTextRecognizer.TextLine>): CourseTextParser.Parsed? {
        if (lines.size < MIN_LINES) return null

        // ── ① 表头：找出所有星期名，按 x 排成列 ──
        // ★ 这里只判断「有没有星期名」，不能要求「有多少个表头行」——
        //   有些 OCR 会把整行「星期日 星期一 星期二 …」识别成一个 block，
        //   那时 headerLines.size == 1，但 buildColumns 能拆出 7 列。
        val headerLines = lines.filter { CsuJwxtParser.isDayName(it.text) }
        if (headerLines.isEmpty()) return null

        val columns = buildColumns(headerLines) ?: return null
        if (columns.size < MIN_DAY_COLUMNS) return null

        val headerBottom = headerLines.maxOf { it.centerY }

        // 节次列与第一个星期列的分界。
        // ★ 用「列宽的一半」而不是「第一个列的中心 x」——
        //   格子里的一段文字可能从列中心偏左的位置开始（长课程名会撑到格线附近），
        //   拿中心 x 当边界会把这类文字误判成节次列的内容。
        val firstColumnLeft = columns.minOf { it.centerX } - columnGap(columns) / 2

        // ── ② 最左列：节次行锚点 ──
        // 节次列在表头下方、且在所有星期列的左边。
        // 用「中心 x 小于第一列的中心 x」判断，比拿绝对像素稳。
        val periodLines = lines
            .filter { it.centerY > headerBottom && it.centerX < firstColumnLeft }
            .mapNotNull { line ->
                val period = parsePeriodLabel(line.text) ?: return@mapNotNull null
                Row(period.first, period.second, line.centerY)
            }
            .sortedBy { it.centerY }
            .distinctBy { it.periodStart }

        if (periodLines.isEmpty()) return null

        // ── ③ 其余文字按 x 落列、按 y 落行 ──
        val bodyLines = lines.filter { it.centerY > headerBottom && it.centerX >= firstColumnLeft }

        val cellBuckets = mutableMapOf<Pair<Int, Int>, MutableList<ImageTextRecognizer.TextLine>>()
        bodyLines.forEach { line ->
            val column = nearestColumn(columns, line.centerX) ?: return@forEach
            val row = rowAt(periodLines, line.centerY) ?: return@forEach
            cellBuckets.getOrPut(column.dayOfWeek to row.periodStart) { mutableListOf() } += line
        }

        // ── ④ 每个格子的多行文字拼起来，交给统一的单元格解析器 ──
        val courses = mutableListOf<com.summer.journal.domain.model.Course>()
        val warnings = mutableListOf<String>()

        periodLines.forEach { row ->
            columns.forEach { column ->
                val bucket = cellBuckets[column.dayOfWeek to row.periodStart].orEmpty()
                if (bucket.isEmpty()) return@forEach

                // 格子内部按 y 排序，还原「课程名 / 教师 / 周次 / 教室」的纵向顺序
                val cellText = bucket.sortedBy { it.centerY }.joinToString("\n") { it.text }

                val course = CsuJwxtParser.parseCellBlock(
                    raw = cellText,
                    dayOfWeek = column.dayOfWeek,
                    periodStart = row.periodStart,
                    periodEnd = row.periodEnd,
                )
                if (course != null) {
                    courses += course
                } else {
                    warnings += "${dayLabel(column.dayOfWeek)} ${row.periodStart}-${row.periodEnd} 节" +
                        "这一格没认出来：${cellText.replace('\n', ' ').take(24)}"
                }
            }
        }

        if (courses.isEmpty()) return null

        return CourseTextParser.Parsed(courses, warnings)
    }

    /* ────────────── 列 ────────────── */

    /**
     * 表头 → 列锚点。
     *
     * 两种常见情况都要处理：
     *   A. 每个星期名是一个独立的 OCR 行（有格线的表格基本都是这样）→ 直接用它的 x
     *   B. 整行「星期日 星期一 星期二 …」被识别成一个 OCR 行 → 按星期名个数
     *      把这一行的 x 范围等分，近似出每一列的位置
     */
    private fun buildColumns(
        headerLines: List<ImageTextRecognizer.TextLine>,
    ): List<Column>? {
        val perLine = headerLines.mapNotNull { line ->
            val day = CsuJwxtParser.dayOfWeekOf(line.text) ?: return@mapNotNull null
            // 一行里只有一个星期名 → 直接可用
            if (dayNameCount(line.text) == 1) Column(day, line.centerX) else null
        }

        if (perLine.size >= MIN_DAY_COLUMNS) {
            return perLine.distinctBy { it.dayOfWeek }.sortedBy { it.centerX }
        }

        // 情况 B：把合并成一行的情况拆开
        val merged = headerLines.firstOrNull { dayNameCount(it.text) >= 2 } ?: return null
        val names = DAY_ORDER.filter { merged.text.contains(it) }
        if (names.size < MIN_DAY_COLUMNS) return null

        val width = (merged.right - merged.left).coerceAtLeast(1)
        val step = width.toFloat() / names.size
        return names.mapIndexedNotNull { index, name ->
            val day = CsuJwxtParser.dayOfWeekOf(name) ?: return@mapIndexedNotNull null
            Column(day, merged.left + (step * (index + 0.5f)).toInt())
        }.sortedBy { it.centerX }
    }

    /** 离哪个列最近（按 x 距离）。超出合理范围就返回 null，避免把页边文字也算进来 */
    private fun nearestColumn(columns: List<Column>, x: Int): Column? {
        val nearest = columns.minByOrNull { kotlin.math.abs(it.centerX - x) } ?: return null
        // 列间距的两倍以内才算「属于这一列」
        val tolerance = columnGap(columns) * 2
        return if (kotlin.math.abs(nearest.centerX - x) <= tolerance) nearest else null
    }

    private fun columnGap(columns: List<Column>): Int {
        if (columns.size < 2) return FALLBACK_GAP
        val xs = columns.map { it.centerX }.sorted()
        return (xs.zipWithNext { a, b -> b - a }).minOrNull()?.coerceIn(1, FALLBACK_GAP)
            ?: FALLBACK_GAP
    }

    /* ────────────── 行 ────────────── */

    /**
     * 这一行文字属于哪个节次。
     *
     * 行边界取相邻节次锚点的中点 —— 因为一个格子的内容（课程名/教师/周次/教室）
     * 会纵向铺满整个格子的高度，不能简单用「离哪个锚点近」判断。
     */
    private fun rowAt(rows: List<Row>, y: Int): Row? {
        if (rows.isEmpty()) return null
        rows.forEachIndexed { index, row ->
            val top = if (index == 0) Int.MIN_VALUE / 4 else (rows[index - 1].centerY + row.centerY) / 2
            val bottom = if (index == rows.lastIndex) {
                Int.MAX_VALUE / 4
            } else {
                (row.centerY + rows[index + 1].centerY) / 2
            }
            if (y in top until bottom) return row
        }
        return null
    }

    /* ────────────── 小工具 ────────────── */

    /** 形如 "1 - 2" / "1-2" / "第1-2节" */
    private val PERIOD_LABEL = Regex("""(\d{1,2})\s*[-–—~～至]\s*(\d{1,2})""")

    private fun parsePeriodLabel(text: String): Pair<Int, Int>? {
        // 「1-2」这种纯数字范围才算节次；带「周」的是周次，不是节次
        if (text.contains('周')) return null
        val match = PERIOD_LABEL.find(text.trim()) ?: return null
        val from = match.groupValues[1].toInt()
        val to = match.groupValues[2].toInt()
        return if (from in 1..20 && to in from..20) from to to else null
    }

    private fun dayNameCount(text: String): Int = DAY_ORDER.count { text.contains(it) }

    private fun dayLabel(dayOfWeek: Int): String = when (dayOfWeek) {
        1 -> "星期一"; 2 -> "星期二"; 3 -> "星期三"; 4 -> "星期四"
        5 -> "星期五"; 6 -> "星期六"; else -> "星期日"
    }

    private val DAY_ORDER = listOf(
        "星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日",
    )

    private const val MIN_LINES = 12
    private const val MIN_DAY_COLUMNS = 3

    /** 列宽兜底值：只用于「只有一列」时的分界计算，避免除零与溢出 */
    private const val FALLBACK_GAP = 200
}
