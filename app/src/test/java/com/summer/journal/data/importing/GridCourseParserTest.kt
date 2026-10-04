package com.summer.journal.data.importing

import com.summer.journal.data.recognition.ImageTextRecognizer
import com.summer.journal.domain.model.WeekParity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网格型课表截图的结构重建测试。
 *
 * ══════════════════════════════════════════════════════════════════════
 *  测试数据来源：用户提供的真实课表截图（2026–2027 第一学期）。
 *
 *  每个格子的课程名、教师职称、周次写法、教室都逐字来自截图；
 *  坐标是按截图的表格布局**推算出来的**（表头一行、最左列节次、
 *  其余是格子内容），因为测试里拿不到真实图片的像素坐标，
 *  但只要能正确模拟「文字落在哪个行列」，就能验证重建算法本身。
 * ══════════════════════════════════════════════════════════════════════
 */
class GridCourseParserTest {

    /* ══════════════════════════════════════════════════════════════
       主用例：整张表 18 个格子
       ══════════════════════════════════════════════════════════════ */

    @Test
    fun `能把截图里的 17 个格子全部重建出来`() {
        val parsed = GridCourseParser.parse(screenshotLines())

        requireNotNull(parsed) { "应当识别为网格结构" }
        // 逐格数过截图：1-2 节 3 格、3-4 节 3 格、5-6 节 4 格、7-8 节 4 格、9-10 节 3 格 = 17
        assertEquals("课程格子数", 17, parsed.courses.size)
    }

    @Test
    fun `每个格子都能落到正确的星期与节次`() {
        val parsed = requireNotNull(GridCourseParser.parse(screenshotLines()))
        val byName = parsed.courses.groupBy { it.name }

        // 星期一 1-2 节
        val mon12 = parsed.courses.first {
            it.name == "概率论与数理统计A" && it.dayOfWeek == 1 && it.periodStart == 1
        }
        assertEquals(2, mon12.periodEnd)
        assertEquals("B座411", mon12.room)
        assertEquals("王志忠", mon12.teacher)

        // 星期三 3-4 节 → 体育（三），没有教室
        val wed34 = parsed.courses.first { it.name == "体育（三）" }
        assertEquals(3, wed34.dayOfWeek)
        assertEquals(3, wed34.periodStart)
        assertEquals(4, wed34.periodEnd)
        assertNull("体育在截图里没有教室", wed34.room)

        // 星期四 9-10 节 → 英美文学欣赏
        val thu910 = parsed.courses.first { it.name == "英美文学欣赏" }
        assertEquals(4, thu910.dayOfWeek)
        assertEquals(9, thu910.periodStart)
        assertEquals(10, thu910.periodEnd)
        assertEquals("B座117", thu910.room)

        // 电工学A 在周一 5-6（单周）和周五 7-8 各一次
        val dianGong = byName.getValue("电工学A")
        assertEquals(2, dianGong.size)
        assertTrue(dianGong.any { it.dayOfWeek == 1 && it.periodStart == 5 })
        assertTrue(dianGong.any { it.dayOfWeek == 5 && it.periodStart == 7 })
    }

    @Test
    fun `教师职称在网格路径里同样会被剥离`() {
        val parsed = requireNotNull(GridCourseParser.parse(screenshotLines()))

        assertEquals(
            "王煜",
            parsed.courses.first { it.name == "工程力学C" && it.dayOfWeek == 4 }.teacher,
        )
        assertEquals(
            "胡云宾",
            parsed.courses.first {
                it.name == "工科大学化学—有机化学基础B" && it.dayOfWeek == 5
            }.teacher,
        )
    }

    @Test
    fun `多教师字段完整保留`() {
        val parsed = requireNotNull(GridCourseParser.parse(screenshotLines()))
        val ai = parsed.courses.first { it.name == "人工智能基础及应用（通识）A" }

        assertEquals("奎晓燕、康松林", ai.teacher)
        assertEquals("C座507", ai.room)
    }

    @Test
    fun `带括号的周次格式能识别`() {
        val parsed = requireNotNull(GridCourseParser.parse(screenshotLines()))
        val course = parsed.courses.first { it.name == "概率论与数理统计A" }

        assertEquals(3, course.weekRange.first)
        assertEquals(16, course.weekRange.last)
        assertEquals(WeekParity.ALL, course.weekParity)
    }

    @Test
    fun `单双周能识别`() {
        val parsed = requireNotNull(GridCourseParser.parse(screenshotLines()))

        assertEquals(
            WeekParity.ODD,
            parsed.courses.first { it.name == "电工学A" && it.periodStart == 5 }.weekParity,
        )
        assertEquals(
            WeekParity.EVEN,
            parsed.courses.first { it.name == "工程力学C" && it.dayOfWeek == 4 }.weekParity,
        )
    }

    @Test
    fun `离散周次 8,12 周被正确还原`() {
        val parsed = requireNotNull(GridCourseParser.parse(screenshotLines()))
        val course = parsed.courses.first { it.name == "形势与政策" }

        assertEquals(setOf(8, 12), course.weekSet)
        assertTrue("第 8 周上课", course.occursInWeek(8))
        assertTrue("第 12 周上课", course.occursInWeek(12))
        assertTrue("第 10 周不上课", !course.occursInWeek(10))
    }

    @Test
    fun `课程名里的全角括号和破折号不会被切断`() {
        val parsed = requireNotNull(GridCourseParser.parse(screenshotLines()))
        val names = parsed.courses.map { it.name }.toSet()

        assertTrue("大学物理B（二）" in names)
        assertTrue("工科大学化学—有机化学基础B" in names)
        assertTrue("工科大学化学—物理化学D" in names)
    }

    /* ══════════════════════════════════════════════════════════════
       工程健壮性
       ══════════════════════════════════════════════════════════════ */

    @Test
    fun `不是网格的图会返回 null 让调用方退回按行解析`() {
        // 纵向列表型（一门课一段），没有表头行
        val lines = listOf(
            line("高等数学A(二)", 100, 100),
            line("张明", 100, 130),
            line("周一 1-2节", 100, 160),
            line("1-16周", 100, 190),
        )

        assertNull(GridCourseParser.parse(lines))
    }

    @Test
    fun `只有一两个星期列的图不算网格`() {
        val lines = listOf(
            line("星期一", 100, 20),
            line("星期二", 300, 20),
            line("1 - 2", 40, 100),
            line("课程甲", 100, 90),
            line("课程乙", 300, 90),
        )

        assertNull("少于 3 个星期列就不该走网格路径", GridCourseParser.parse(lines))
    }

    @Test
    fun `合并成一行的表头也能还原列`() {
        // 有些 OCR 会把整行「星期日 星期一 星期二 星期三」识别成一个 block
        val lines = mutableListOf(
            ImageTextRecognizer.TextLine("星期日 星期一 星期二 星期三", 40, 20, 760, 40),
            line("1 - 2", 30, 150),
            line("课程甲", 100, 120),
            line("老师甲", 100, 150),
            line("1-16周", 100, 180),
            line("课程乙", 320, 120),
            line("老师乙", 320, 150),
            line("1-16周", 320, 180),
            line("课程丙", 560, 120),
            line("老师丙", 560, 150),
            line("1-16周", 560, 180),
            line("课程丁", 740, 120),
            line("老师丁", 740, 150),
            line("1-16周", 740, 180),
        )

        val parsed = requireNotNull(GridCourseParser.parse(lines))
        assertEquals(4, parsed.courses.size)
        assertEquals(1, parsed.courses.first { it.name == "课程甲" }.dayOfWeek)
        assertEquals(2, parsed.courses.first { it.name == "课程乙" }.dayOfWeek)
    }

    @Test
    fun `空行与噪声文字不会造出假课`() {
        val parsed = GridCourseParser.parse(lines = emptyList())
        assertNull(parsed)
    }

    /* ══════════════════════════════════════════════════════════════
       测试数据与坐标模拟
       ══════════════════════════════════════════════════════════════ */

    private fun line(text: String, centerX: Int, centerY: Int) =
        ImageTextRecognizer.TextLine(text, centerX - 40, centerY - 10, centerX + 40, centerY + 10)

    /** 一个格子：[星期(1..7), 节次, 课程名, 教师, 周次, 教室?] */
    private data class Slot(
        val day: Int,
        val period: String,
        val name: String,
        val teacher: String,
        val weeks: String,
        val room: String? = null,
    )

    private val slots = listOf(
        // ── 1 - 2 节 ──
        Slot(1, "1 - 2", "概率论与数理统计A", "王志忠[教授]", "3-16(周)", "B座411"),
        Slot(3, "1 - 2", "大学物理B（二）", "李幼真[副教授]", "3-16(周)", "A座404"),
        Slot(5, "1 - 2", "概率论与数理统计A", "王志忠[教授]", "3-16(周)", "B座411"),
        // ── 3 - 4 节 ──
        Slot(1, "3 - 4", "大学物理B（二）", "李幼真[副教授]", "3-16(周)", "A座401"),
        Slot(3, "3 - 4", "体育（三）", "罗薇[讲师]", "3-18(周)"),
        Slot(5, "3 - 4", "工科大学化学—有机化学基础B", "胡云宾[副教授]", "3-10(周)", "A座113"),
        // ── 5 - 6 节 ──
        Slot(1, "5 - 6", "电工学A", "李飞[教授]", "3-18(单周)", "C座304"),
        Slot(2, "5 - 6", "人工智能基础及应用（通识）A", "奎晓燕[教授],康松林[副教授]", "3-18(周)", "C座507"),
        Slot(4, "5 - 6", "工程力学C", "王煜[特聘副教授]", "3-18(双周)", "A座104"),
        Slot(5, "5 - 6", "工科大学化学—物理化学D", "邹国强[教授]", "3-14(周)", "C座205"),
        // ── 7 - 8 节 ──
        Slot(1, "7 - 8", "中国近现代史纲要", "井园园", "3-18(周)", "C座310"),
        Slot(2, "7 - 8", "工程力学C", "王煜[特聘副教授]", "3-18(周)", "B座411"),
        Slot(4, "7 - 8", "形势与政策", "罗昊贤[讲师]", "8,12(周)", "C座501"),
        Slot(5, "7 - 8", "电工学A", "李飞[教授]", "3-18(周)", "C座304"),
        // ── 9 - 10 节 ──
        Slot(1, "9 - 10", "工科大学化学—物理化学D", "邹国强[教授]", "3-14(周)", "C座112"),
        Slot(3, "9 - 10", "工科大学化学—有机化学基础B", "胡云宾[副教授]", "3-10(周)", "A座313"),
        Slot(4, "9 - 10", "英美文学欣赏", "吴玲英[教授]", "3-18(周)", "B座117"),
    )

    /** 表头 y 与各列的 x 中心（按截图里「星期日」在最左的顺序） */
    private val headerY = 40
    private val columnX = mapOf(
        7 to 120, 1 to 260, 2 to 400, 3 to 540, 4 to 680, 5 to 820, 6 to 960,
    )

    /** 节次列：x 在最左边，y 是每一行的中心 */
    private val periodX = 45
    private val periodY = linkedMapOf(
        "1 - 2" to 150,
        "3 - 4" to 330,
        "5 - 6" to 510,
        "7 - 8" to 690,
        "9 - 10" to 870,
        "11 - 12" to 1050,
    )

    /**
     * 还原这张截图的 OCR 结果：
     * 表头一行（每格一个 TextLine）+ 最左列节次 + 每个格子里纵向排列的 4 行内容。
     */
    private fun screenshotLines(): List<ImageTextRecognizer.TextLine> {
        val out = mutableListOf<ImageTextRecognizer.TextLine>()

        // 表头：「星期日 星期一 … 星期六」，每格独立成行
        listOf("星期日" to 7, "星期一" to 1, "星期二" to 2, "星期三" to 3,
            "星期四" to 4, "星期五" to 5, "星期六" to 6)
            .forEach { (label, day) ->
                out += line(label, columnX.getValue(day), headerY)
            }

        // 最左列：节次
        periodY.forEach { (label, y) -> out += line(label, periodX, y) }

        // 格子内容：课程名 / 教师 / 周次 / 教室 纵向铺开
        slots.forEach { slot ->
            val rowY = periodY.getValue(slot.period)
            val x = columnX.getValue(slot.day)
            val fields = listOfNotNull(slot.name, slot.teacher, slot.weeks, slot.room)
            fields.forEachIndexed { index, text ->
                // 4 行内容围绕行中心上下分布，间距 30px
                val offset = (index - (fields.size - 1) / 2f) * 30f
                out += line(text, x, (rowY + offset).toInt())
            }
        }
        return out
    }
}
