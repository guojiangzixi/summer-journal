package com.summer.journal.data.remote.jwxt

import com.summer.journal.domain.model.WeekParity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 教务课表解析器的测试。
 *
 * ══════════════════════════════════════════════════════════════════════
 *  测试数据来源：用户提供的真实课表截图（2026–2027 第一学期）。
 *  这不是编出来的样例 —— 每一个格子的课程名、教师职称、周次、教室
 *  都逐字来自截图，所以它能真实反映解析器的识别能力。
 * ══════════════════════════════════════════════════════════════════════
 *
 * 截图里的真实格式特征（也是本次要验证的难点）：
 *  1. 周次写成 `3-16(周)` —— **带括号**，不是 `3-16周`
 *  2. 单双周写成 `3-18(单周)` / `3-18(双周)`
 *  3. 离散周次写成 `8,12(周)` —— 一门课只上第 8 周和第 12 周
 *  4. 教师带职称后缀：`王志忠[教授]`、`王煜[特聘副教授]`
 *  5. 多教师用逗号连接：`奎晓燕[教授],康松林[副教授]`
 *  6. 课程名含全角括号与破折号：`大学物理B（二）`、`工科大学化学—有机化学基础B`
 *  7. 表头顺序：星期日 在第一列
 *  8. 体育课没有教室
 */
class CsuJwxtParserTest {

    /* ══════════════════════════════════════════════════════════════
       主用例：用截图里的全部 18 个格子验证识别率
       ══════════════════════════════════════════════════════════════ */

    @Test
    fun `能识别截图里全部 17 个格子的课程`() {
        val result = CsuJwxtParser.parse(realScheduleHtml())

        // 逐格数过截图：3+3+4+4+3 = 17
        assertEquals("应识别出 17 个课程格子", 17, result.courses.size)
        assertTrue("不应该有识别失败的格子，实际: ${result.unrecognizedSlots}", result.unrecognizedSlots.isEmpty())
    }

    @Test
    fun `周次能识别带括号的 3-16 周格式`() {
        val result = CsuJwxtParser.parse(realScheduleHtml())
        val course = result.courses.first { it.name == "概率论与数理统计A" && it.dayOfWeek == 1 }

        assertEquals("起始周", 3, course.weekRange.first)
        assertEquals("结束周", 16, course.weekRange.last)
        assertEquals("不该被识别成单双周", WeekParity.ALL, course.weekParity)
    }

    @Test
    fun `单双周能识别`() {
        val result = CsuJwxtParser.parse(realScheduleHtml())

        val odd = result.courses.first { it.name == "电工学A" && it.periodStart == 5 }
        assertEquals("电工学A(单周)", WeekParity.ODD, odd.weekParity)

        val even = result.courses.first { it.name == "工程力学C" && it.dayOfWeek == 4 }
        assertEquals("工程力学C(双周)", WeekParity.EVEN, even.weekParity)
    }

    @Test
    fun `离散周次 8,12 周不会被稀释成 8-12 周`() {
        val result = CsuJwxtParser.parse(realScheduleHtml())
        val course = result.courses.first { it.name == "形势与政策" }

        assertEquals("离散周次应存成集合", setOf(8, 12), course.weekSet)
        assertTrue("第 8 周要上课", course.occursInWeek(8))
        assertTrue("第 12 周要上课", course.occursInWeek(12))
        assertTrue("第 10 周不该上课（这正是离散周次必须单独存的原因）", !course.occursInWeek(10))
    }

    @Test
    fun `教师职称后缀会被剥离`() {
        val result = CsuJwxtParser.parse(realScheduleHtml())

        val a = result.courses.first { it.name == "概率论与数理统计A" }
        assertEquals("王志忠", a.teacher)

        val b = result.courses.first { it.name == "工程力学C" && it.dayOfWeek == 4 }
        assertEquals("王煜", b.teacher)
    }

    @Test
    fun `多教师会用顿号连起来且都去掉职称`() {
        val result = CsuJwxtParser.parse(realScheduleHtml())
        val course = result.courses.first { it.name == "人工智能基础及应用（通识）A" }

        assertEquals("奎晓燕、康松林", course.teacher)
    }

    @Test
    fun `教师名字较长时不会被判成教室而丢掉`() {
        val result = CsuJwxtParser.parse(realScheduleHtml())
        val course = result.courses.first { it.name == "人工智能基础及应用（通识）A" }

        assertNotNull("多教师字段不能被丢掉", course.teacher)
        assertEquals("C座507", course.room)
    }

    @Test
    fun `教室能识别各种座位号写法`() {
        val result = CsuJwxtParser.parse(realScheduleHtml())

        assertEquals("B座411", result.courses.first { it.name == "概率论与数理统计A" }.room)
        assertEquals("C座501", result.courses.first { it.name == "形势与政策" }.room)
        assertEquals("A座113", result.courses.first { it.name == "工科大学化学—有机化学基础B" && it.dayOfWeek == 5 }.room)
    }

    @Test
    fun `没有教室的课不会把教室字段填成垃圾值`() {
        val result = CsuJwxtParser.parse(realScheduleHtml())
        val pe = result.courses.first { it.name == "体育（三）" }

        assertNull("体育课在截图里没有教室，应该是 null", pe.room)
        assertEquals("罗薇", pe.teacher)
    }

    @Test
    fun `课程名里的全角括号和破折号不会被切断`() {
        val result = CsuJwxtParser.parse(realScheduleHtml())
        val names = result.courses.map { it.name }.toSet()

        assertTrue("大学物理B（二）", "大学物理B（二）" in names)
        assertTrue("工科大学化学—有机化学基础B", "工科大学化学—有机化学基础B" in names)
        assertTrue("工科大学化学—物理化学D", "工科大学化学—物理化学D" in names)
        assertTrue("人工智能基础及应用（通识）A", "人工智能基础及应用（通识）A" in names)
    }

    @Test
    fun `星期和节次映射正确`() {
        val result = CsuJwxtParser.parse(realScheduleHtml())

        val mon12 = result.courses.first { it.name == "概率论与数理统计A" && it.periodStart == 1 }
        assertEquals("星期一", 1, mon12.dayOfWeek)
        assertEquals(1, mon12.periodStart)
        assertEquals(2, mon12.periodEnd)

        val wed34 = result.courses.first { it.name == "体育（三）" }
        assertEquals("星期三", 3, wed34.dayOfWeek)
        assertEquals(3, wed34.periodStart)

        val thu910 = result.courses.first { it.name == "英美文学欣赏" }
        assertEquals("星期四", 4, thu910.dayOfWeek)
        assertEquals(9, thu910.periodStart)
        assertEquals(10, thu910.periodEnd)
    }

    @Test
    fun `同一门课出现在多个时段时各生成一条`() {
        val result = CsuJwxtParser.parse(realScheduleHtml())

        val dianGong = result.courses.filter { it.name == "电工学A" }
        assertEquals("电工学A 在截图里出现 2 次（周一5-6 单周、周五7-8）", 2, dianGong.size)

        val gaiLun = result.courses.filter { it.name == "概率论与数理统计A" }
        assertEquals("概率论与数理统计A 周一1-2 + 周五1-2", 2, gaiLun.size)
    }

    /* ══════════════════════════════════════════════════════════════
       健壮性：教务改版 / 结构差异时不能崩
       ══════════════════════════════════════════════════════════════ */

    @Test
    fun `单元格没有 kbcontent div 时也能解析`() {
        // 部分教务系统版本把文字直接写在 td 里，没有 kbcontent 包裹。
        // 如果只认 kbcontent，会一节课都解析不出来。
        val html = """
            <table>
              <tr><th></th><th>星期一</th><th>星期二</th></tr>
              <tr>
                <td>1-2</td>
                <td>高等数学A<br>张明<br>1-16周<br>科教南楼201</td>
                <td></td>
              </tr>
            </table>
        """.trimIndent()

        val result = CsuJwxtParser.parse(html)

        assertEquals(1, result.courses.size)
        assertEquals("高等数学A", result.courses[0].name)
        assertEquals("张明", result.courses[0].teacher)
        assertEquals("科教南楼201", result.courses[0].room)
    }

    @Test
    fun `表头顺序变了也能正确映射星期`() {
        // 有的学校是「周一…周日」，有的是「周日…周六」
        val html = """
            <table>
              <tr><th>节次</th><th>星期一</th><th>星期二</th><th>星期日</th></tr>
              <tr>
                <td>1-2</td>
                <td><div class="kbcontent1">课程A<br>老师甲<br>1-16周<br>A101</div></td>
                <td></td>
                <td><div class="kbcontent1">课程B<br>老师乙<br>1-16周<br>B202</div></td>
              </tr>
            </table>
        """.trimIndent()

        val result = CsuJwxtParser.parse(html)

        assertEquals(2, result.courses.size)
        assertEquals(1, result.courses.first { it.name == "课程A" }.dayOfWeek)
        assertEquals("星期日=7", 7, result.courses.first { it.name == "课程B" }.dayOfWeek)
    }

    @Test
    fun `空表不会崩、不会造出假课`() {
        val result = CsuJwxtParser.parse("<html><body><p>没有课表</p></body></html>")

        assertTrue(result.courses.isEmpty())
        assertTrue(result.unrecognizedSlots.isEmpty())
    }

    @Test
    fun `完全空的表格不会崩、也不会造出假课`() {
        // 表头有「节次」列，格子里什么都没有
        val result = CsuJwxtParser.parse(
            "<table><tr><th>节次</th><th>星期一</th></tr><tr><td>1 - 2</td><td></td></tr></table>"
        )

        assertTrue("空格子不该生成课程：${result.courses}", result.courses.isEmpty())
        assertTrue("空格子也不该算识别失败", result.unrecognizedSlots.isEmpty())
    }

    @Test
    fun `表头漏写节次两个字也不会把节次当成课程`() {
        // 有些教务版本表头第一格是空的或没有「节次」字样。
        // 如果按表头文字来对齐列，节次「1 - 2」会被当成星期一的一门课。
        val result = CsuJwxtParser.parse(
            "<table><tr><th></th><th>星期一</th></tr><tr><td>1 - 2</td><td></td></tr></table>"
        )

        assertTrue("列 0 永远是节次列，不该被当成星期列：${result.courses}", result.courses.isEmpty())
    }

    @Test
    fun `一个格子里并列两门课时两门都要识别出来`() {
        // 单双周交错时，教务会把两门课塞进同一个 td
        val html = """
            <table>
              <tr><th></th><th>星期一</th></tr>
              <tr>
                <td>1-2</td>
                <td>
                  <div class="kbcontent1">课程甲<br>老师A<br>1-16周(单周)<br>A101</div>
                  <div class="kbcontent2">课程乙<br>老师B<br>1-16周(双周)<br>A102</div>
                </td>
              </tr>
            </table>
        """.trimIndent()

        val result = CsuJwxtParser.parse(html)

        assertEquals(2, result.courses.size)
        assertEquals(WeekParity.ODD, result.courses.first { it.name == "课程甲" }.weekParity)
        assertEquals(WeekParity.EVEN, result.courses.first { it.name == "课程乙" }.weekParity)
    }

    @Test
    fun `同一门课多次导入颜色保持一致`() {
        val a = CsuJwxtParser.parse(realScheduleHtml())
        val b = CsuJwxtParser.parse(realScheduleHtml())

        val colorA = a.courses.first { it.name == "电工学A" }.color
        val colorB = b.courses.first { it.name == "电工学A" }.color
        assertEquals("颜色按课程名哈希，两次导入必须一致", colorA, colorB)
    }

    /* ══════════════════════════════════════════════════════════════
       测试数据：逐字来自用户提供的真实课表截图
       ══════════════════════════════════════════════════════════════ */

    /** 一个格子的内容：[课程名, 教师, 周次, 教室?] */
    private data class Slot(
        val day: Int,          // 1=周一 … 7=周日
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
        Slot(3, "3 - 4", "体育（三）", "罗薇[讲师]", "3-18(周)", null),
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

    private val periodRows = listOf("1 - 2", "3 - 4", "5 - 6", "7 - 8", "9 - 10", "11 - 12")

    /** 还原教务课表页的 HTML 结构（表头周日在第一列，格子用 kbcontent 包裹） */
    private fun realScheduleHtml(): String {
        val sb = StringBuilder()
        sb.append("<html><head><title>2026-2027学年第一学期理论课表</title></head><body>")
        sb.append("<table id=\"kbtable\" class=\"table\">")

        // 表头：周日 在最前（教务口径）
        sb.append("<tr><th></th><th>星期日</th><th>星期一</th><th>星期二</th><th>星期三</th>")
        sb.append("<th>星期四</th><th>星期五</th><th>星期六</th></tr>")

        periodRows.forEach { period ->
            sb.append("<tr>")
            sb.append("<td>").append(period).append("</td>")
            // 列顺序：周日, 周一 … 周六 —— 与表头一一对应
            listOf(7, 1, 2, 3, 4, 5, 6).forEach { day ->
                val cellSlots = slots.filter { it.day == day && it.period == period }
                sb.append("<td>")
                cellSlots.forEachIndexed { index, slot ->
                    sb.append("<div class=\"kbcontent").append(index + 1).append("\">")
                    sb.append(slot.name).append("<br>")
                    sb.append(slot.teacher).append("<br>")
                    sb.append(slot.weeks).append("<br>")
                    slot.room?.let { sb.append(it) }
                    sb.append("</div>")
                }
                sb.append("</td>")
            }
            sb.append("</tr>")
        }

        sb.append("</table></body></html>")
        return sb.toString()
    }
}
