package com.summer.journal.data.importing

import com.summer.journal.domain.model.Course
import com.summer.journal.domain.model.CourseColor
import com.summer.journal.domain.model.WeekParity
import kotlin.math.absoluteValue

/**
 * 把「课表图片识别出来的文字」解析成课程。
 *
 * ★ 诚实说明：OCR 出来的文本排版千奇百怪，**没有任何解析器能保证 100% 正确**。
 *   所以这里的目标不是「全自动」，而是「尽量把脏活干完，剩下让人补两笔」：
 *     · 能解析出结构化字段的，自动填好
 *     · 解析不出的，标成 warnings 交给用户
 *     · 结果一律进「可编辑的预览列表」，用户确认后才落库
 *   绝不把猜出来的课程直接写进课表 —— 那会污染用户一整个学期的数据。
 *
 * 三步策略，从结构化到宽松：
 *   1. 整行式：一行就是一门课（含星期 + 节次）
 *   2. 锚点式：找到「星期X 第N-M节」这类锚点行，把它的相邻行当作同一门课的其他字段
 *   3. 单门式：整段只有一门课
 */
object CourseTextParser {

    data class Parsed(
        val courses: List<Course>,
        val warnings: List<String>,
    ) {
        val isEmpty: Boolean get() = courses.isEmpty()
    }

    /* ────────────── 正则 ────────────── */

    private val DAY_REGEX = Regex("(?:星期|周)([一二三四五六日天])")
    private val PERIOD_REGEX = Regex("第?\\s*(\\d{1,2})\\s*[-–~至]\\s*(\\d{1,2})\\s*节")
    private val SINGLE_PERIOD_REGEX = Regex("第?\\s*(\\d{1,2})\\s*节")
    private val WEEK_REGEX = Regex("第?\\s*(\\d{1,2})\\s*[-–~至]\\s*(\\d{1,2})\\s*周")
    private val SINGLE_WEEK_REGEX = Regex("第?\\s*(\\d{1,2})\\s*周")

    /** 像「教室」的东西：含有楼/室/馆/场/区/座 等字样 */
    private val ROOM_HINT = Regex("[\\u4e00-\\u9fa5A-Za-z0-9（）()·\\-\\s]{0,14}(?:教学楼|楼|教室|馆|场|区|座|栋|室)")
    /** 单双周 */
    private val ODD_EVEN = Regex("(单周|双周|单数周|双数周|周单|周双)")

    /* ══════════════════════════════════════════════════════════
       入口
       ══════════════════════════════════════════════════════════ */

    fun parse(text: String): Parsed {
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return Parsed(emptyList(), listOf("没有识别到任何文字"))

        // 策略 1：一行一门课
        val perLine = lines.mapNotNull { parseFromLine(it, lines) }
        if (perLine.size >= 2) {
            return Parsed(perLine, emptyList())
        }

        // 策略 2：锚点式（纵向堆叠的课表截图）
        val anchored = parseByAnchor(lines)
        if (anchored.courses.isNotEmpty()) return anchored

        // 策略 3：只识别出一门课
        if (perLine.size == 1) return Parsed(perLine, emptyList())

        return Parsed(
            emptyList(),
            listOf(
                "没能从图里认出课程结构。这张图可能是课表的一部分，或者字体太小。",
                "建议换成更清晰、完整的课表截图；也可以手动添加课程。",
            ),
        )
    }

    /* ══════════════════════════════════════════════════════════
       策略 1：一行一门课
       ══════════════════════════════════════════════════════════ */

    private fun parseFromLine(line: String, allLines: List<String>): Course? {
        val day = parseDay(line) ?: return null
        val period = parsePeriod(line) ?: return null

        // 把「星期/节次/周次」这些元信息从行里剥掉，剩下的才是课程名/教师/教室
        var residue = line
        DAY_REGEX.findAll(line).forEach { residue = residue.replace(it.value, " ") }
        residue = residue
            .replace(PERIOD_REGEX, " ")
            .replace(SINGLE_PERIOD_REGEX, " ")
            .replace(WEEK_REGEX, " ")
            .replace(SINGLE_WEEK_REGEX, " ")
            .replace(ODD_EVEN, " ")
            .replace(Regex("[\\(（]?\\d+\\s*[-–~至]\\s*\\d+\\s*[周节][\\)）]?"), " ")

        val fragments = splitFragments(residue)
        if (fragments.isEmpty()) return null

        val room = fragments.firstOrNull { ROOM_HINT.containsMatchIn(it) }
            ?.takeIf { it.length in 2..20 }
        val name = fragments
            .firstOrNull { it != room && it.length >= 2 && it.any { c -> c.isChinese() } }
            ?: fragments.firstOrNull { it.length >= 2 }
            ?: return null

        // 教师：剩下片段里最像人名的那个（2–4 个汉字、不含教室特征）
        val teacher = fragments.firstOrNull {
            it != room && it != name && it.length in 2..4 &&
                it.all { c -> c.isChinese() } && !ROOM_HINT.containsMatchIn(it)
        }

        return buildCourse(
            name = name,
            teacher = teacher,
            room = room,
            day = day,
            period = period,
            weekRange = parseWeekRange(line) ?: (1..18),
            parity = parseParity(line),
        )
    }

    /* ══════════════════════════════════════════════════════════
       策略 2：锚点式
       ══════════════════════════════════════════════════════════ */

    /**
     * 纵向堆叠的课表截图，OCR 后往往长这样：
     *
     *     高等数学A(二)
     *     张明
     *     科教南楼201
     *     周一 1-2节
     *     1-16周
     *
     * 这里用「含星期 + 节次」的行当锚点，把锚点上方最多 3 行当作同一门课的字段。
     */
    private fun parseByAnchor(lines: List<String>): Parsed {
        val courses = mutableListOf<Course>()
        val warnings = mutableListOf<String>()
        val consumed = BooleanArray(lines.size)

        lines.forEachIndexed { index, line ->
            if (consumed[index]) return@forEachIndexed
            val day = parseDay(line) ?: return@forEachIndexed
            val period = parsePeriod(line) ?: return@forEachIndexed

            // 往上找最多 3 行没用过的内容，作为课程名 / 教师 / 教室
            val contextIndices = (index - 3..index - 1)
                .filter { it in lines.indices && !consumed[it] }
                .filter { parseDay(lines[it]) == null }

            val fragments = (contextIndices.map { lines[it] } + line)
                .flatMap { splitFragments(it) }
                .filter { it.length >= 2 }

            if (fragments.isEmpty()) return@forEachIndexed

            val room = fragments.firstOrNull { ROOM_HINT.containsMatchIn(it) && it.length in 2..20 }
            val name = fragments.firstOrNull {
                it != room && it.length >= 2 && it.any { c -> c.isChinese() }
            } ?: return@forEachIndexed
            val teacher = fragments.firstOrNull {
                it != room && it != name && it.length in 2..4 && it.all { c -> c.isChinese() }
            }

            if (name.contains(Regex("课表|星期|节次|时间"))) return@forEachIndexed

            courses += buildCourse(
                name = name,
                teacher = teacher,
                room = room,
                day = day,
                period = period,
                weekRange = parseWeekRange(line)
                    ?: contextIndices.firstNotNullOfOrNull { parseWeekRange(lines[it]) }
                    ?: (1..18),
                parity = parseParity(
                    (contextIndices.map { lines[it] } + line).joinToString(" ")
                ),
            )

            consumed[index] = true
            contextIndices.forEach { consumed[it] = true }
        }

        if (courses.isEmpty()) return Parsed(emptyList(), emptyList())
        if (courses.size < 2) {
            warnings += "只认出了 1 门课，请核对一遍再导入。"
        }
        return Parsed(courses, warnings)
    }

    /* ══════════════════════════════════════════════════════════
       字段解析
       ══════════════════════════════════════════════════════════ */

    fun parseDay(text: String): Int? {
        DAY_REGEX.find(text)?.let { match ->
            return when (match.groupValues[1]) {
                "一" -> 1; "二" -> 2; "三" -> 3; "四" -> 4
                "五" -> 5; "六" -> 6; else -> 7
            }
        }
        return null
    }

    fun parsePeriod(text: String): IntRange? {
        PERIOD_REGEX.find(text)?.let { match ->
            val start = match.groupValues[1].toIntOrNull() ?: return@let
            val end = match.groupValues[2].toIntOrNull() ?: return@let
            if (start in 1..14 && end in start..14) return start..end
        }
        SINGLE_PERIOD_REGEX.find(text)?.let { match ->
            val single = match.groupValues[1].toIntOrNull() ?: return@let
            if (single in 1..14) return single..single
        }
        return null
    }

    fun parseWeekRange(text: String): IntRange? {
        WEEK_REGEX.find(text)?.let { match ->
            val start = match.groupValues[1].toIntOrNull() ?: return@let
            val end = match.groupValues[2].toIntOrNull() ?: return@let
            if (start in 1..30 && end in start..30) return start..end
        }
        SINGLE_WEEK_REGEX.find(text)?.let { match ->
            val single = match.groupValues[1].toIntOrNull() ?: return@let
            if (single in 1..30) return single..single
        }
        return null
    }

    fun parseParity(text: String): WeekParity {
        val hit = ODD_EVEN.find(text)?.value ?: return WeekParity.ALL
        return if (hit.contains("单")) WeekParity.ODD else WeekParity.EVEN
    }

    /* ══════════════════════════════════════════════════════════
       小工具
       ══════════════════════════════════════════════════════════ */

    private fun buildCourse(
        name: String,
        teacher: String?,
        room: String?,
        day: Int,
        period: IntRange,
        weekRange: IntRange,
        parity: WeekParity,
    ) = Course(
        id = 0,
        semesterId = 0,
        name = name.trim().take(24),
        teacher = teacher?.trim(),
        room = room?.trim(),
        dayOfWeek = day,
        periodStart = period.first,
        periodEnd = period.last,
        weekParity = parity,
        weekRange = weekRange.first..weekRange.last.coerceAtLeast(weekRange.first),
        // 颜色按课程名哈希 —— 同一门课每次识别都是同一个色
        color = CourseColor.entries[name.hashCode().absoluteValue % CourseColor.entries.size],
    )

    /** 把一段文字切成候选片段（按常见分隔符） */
    private fun splitFragments(text: String): List<String> =
        text.split(Regex("[\\s,，、|/\\\\·]+"))
            .map { it.trim().trim('(', ')', '（', '）', '-', '·') }
            .filter { it.isNotBlank() }

    private fun Char.isChinese(): Boolean = this in '\u4e00'..'\u9fff'
}
