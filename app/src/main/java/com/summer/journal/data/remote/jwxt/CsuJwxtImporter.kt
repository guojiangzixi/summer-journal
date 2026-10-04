package com.summer.journal.data.remote.jwxt

import android.content.Context
import android.webkit.CookieManager
import com.summer.journal.domain.model.Course
import com.summer.journal.domain.model.CourseColor
import com.summer.journal.domain.model.WeekInput
import com.summer.journal.domain.model.WeekParity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.absoluteValue

/* ══════════════════════════════════════════════════════════════════════
   教务系统课表导入
   ══════════════════════════════════════════════════════════════════════

   ★ 核心决策：**不逆向登录接口**。

   教务系统的登录有加密参数和 CSRF token，逆向成本高，而且学校一升级就失效，
   还会带来合规风险。正确做法是：

       用 WebView 让用户自己在教务系统页面上正常登录
                ↓
       登录成功后从 CookieManager 读出会话 Cookie
                ↓
       带同一个 Cookie 用 OkHttp 请求课表页
                ↓
       Jsoup 解析 → 归一化 → 落库

   这样：密码永远不经过我们的代码；解析逻辑只有一处；学校改版只改 Parser。
   ══════════════════════════════════════════════════════════════════════ */

@Singleton
class CsuJwxtImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
) {

    /**
     * 用 WebView 里已有的登录态抓课表。
     * @return 解析结果；失败时返回 Failure 并带上原因，UI 据此给降级入口。
     */
    suspend fun importFromWebViewSession(): ImportResult = withContext(Dispatchers.IO) {
        val cookie = CookieManager.getInstance().getCookie(BASE_URL)
        if (cookie.isNullOrBlank()) {
            return@withContext ImportResult.Failure(Reason.NO_SESSION)
        }

        val html = runCatching { fetchScheduleHtml(cookie) }.getOrNull()
            ?: return@withContext ImportResult.Failure(Reason.NETWORK)

        // 登录态失效时教务会跳回登录页
        if (html.contains("login") && html.contains("用户名")) {
            return@withContext ImportResult.Failure(Reason.SESSION_EXPIRED)
        }

        val parsed = CsuJwxtParser.parse(html)
        when {
            parsed.courses.isEmpty() -> ImportResult.Failure(Reason.PARSE_EMPTY)
            else -> ImportResult.Success(parsed)
        }
    }

    /** 从纯文本粘贴导入：教务改版导致解析失败时的第一降级路径 */
    fun importFromPastedText(text: String): ImportResult {
        val parsed = CsuJwxtParser.parsePlainText(text)
        return if (parsed.courses.isEmpty()) ImportResult.Failure(Reason.PARSE_EMPTY)
        else ImportResult.Success(parsed)
    }

    private fun fetchScheduleHtml(cookie: String): String {
        val request = Request.Builder()
            .url(SCHEDULE_URL)
            .header("Cookie", cookie)
            .header("Referer", BASE_URL)
            .header("User-Agent", UA)
            .build()
        return okHttpClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            response.body?.string().orEmpty()
        }
    }

    /** 导入结束后清理，不长期保留教务会话 */
    fun clearSession() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }

    /* ────────────── 结果类型 ────────────── */

    enum class Reason { NO_SESSION, SESSION_EXPIRED, NETWORK, PARSE_EMPTY }

    sealed interface ImportResult {
        data class Success(val parsed: ParsedTimetable) : ImportResult
        data class Failure(val reason: Reason) : ImportResult
    }

    data class ParsedTimetable(
        val semesterName: String?,
        val courses: List<Course>,
        /** 解析质量，用于 UI 上提示「有 3 个格子没能识别，请手动补录」 */
        val unrecognizedSlots: List<String> = emptyList(),
    )

    companion object {
        const val BASE_URL = "http://csujwc.its.csu.edu.cn/"
        const val SCHEDULE_URL =
            "http://csujwc.its.csu.edu.cn/jsxsd/xskb/xskb_list.do?Ves632DSdyV=NEW_XSD_WDKB"
        private const val UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
    }
}

/* ══════════════════════════════════════════════════════════════════════
   解析器 —— 全项目最需要「改版时只改这一处」的地方
   ══════════════════════════════════════════════════════════════════════ */

object CsuJwxtParser {

    /**
     * 教务课表页结构（以中南大学 jwxsd 为例）：
     *
     *   <table id="kbtable">
     *     <tr><th>节次</th><th>星期一</th><th>星期二</th>…<th>星期日</th></tr>
     *     <tr>
     *       <td>1-2</td>
     *       <td>
     *         <div class="kbcontent1">高等数学A(二)<br>张明<br>科南201<br>1-16周</div>
     *       </td>
     *       …
     *     </tr>
     *     …
     *   </table>
     *
     * 单元格文本的字段顺序在不同学期/不同校区会变，所以解析策略是
     * **按内容特征识别**，而不是按行号取 —— 这是抗改版的关键。
     */
    fun parse(html: String): CsuJwxtImporter.ParsedTimetable {
        val doc = Jsoup.parse(html)

        val table = doc.selectFirst("table#kbtable")
            ?: doc.select("table").firstOrNull { it.text().contains("星期一") }
            ?: return CsuJwxtImporter.ParsedTimetable(null, emptyList())

        val rows = table.select("tr")
        if (rows.isEmpty()) return CsuJwxtImporter.ParsedTimetable(null, emptyList())

        // 表头：确定「第几列 = 星期几」
        val headerCells = rows.first().select("th, td")
        val dayOfWeekByColumn = mutableMapOf<Int, Int>()
        headerCells.forEachIndexed { index, cell ->
            DAY_NAMES.entries.firstOrNull { cell.text().contains(it.key) }
                ?.let { dayOfWeekByColumn[index] = it.value.dayOfWeek }
        }

        val semesterName = doc.selectFirst(".Nsb_r_list_thb, .h1, title")
            ?.text()
            ?.takeIf { it.contains("学期") }

        val courses = mutableListOf<Course>()
        val unrecognized = mutableListOf<String>()

        rows.drop(1).forEach { row ->
            val cells = row.select("td")
            val period = parsePeriodLabel(cells.firstOrNull()?.text()) ?: return@forEach

            cells.forEachIndexed { index, cell ->
                // 第 0 列一定是「节次」列，永远不是星期列。
                // 有些教务版本表头不写「节次」两个字，靠这个判断兜住列错位。
                if (index == 0) return@forEachIndexed
                val dayOfWeek = dayOfWeekByColumn[index] ?: return@forEachIndexed

                // 一个格子里可能并列多门课（单双周交错）——教务会放多个 kbcontent div。
                // ★ 但也有的教务版本把文字直接写在 td 里，没有任何 div 包裹。
                //   只认 kbcontent 的话会「一节课都解析不出来」，所以这里必须兜底。
                val blocks = cell.select("div[class^=kbcontent], div.kbcontent1, div.kbcontent")
                val sources = if (blocks.isNotEmpty()) blocks.map { it.html() } else listOf(cell.html())

                sources.forEach { blockHtml ->
                    val raw = blockHtml
                        .replace(Regex("(?i)<br\\s*/?>"), "\n")
                        .replace(Regex("<[^>]+>"), "")
                    val parsed = parseCellText(raw, dayOfWeek, period)
                    if (parsed == null) {
                        if (raw.isNotBlank()) {
                            val dayName = DAY_NAMES.entries
                                .firstOrNull { it.value.dayOfWeek == dayOfWeek }?.key.orEmpty()
                            unrecognized += "$dayName ${period.first}-${period.second}: " +
                                raw.replace('\n', ' ').take(28)
                        }
                    } else {
                        courses += parsed
                    }
                }
            }
        }

        return CsuJwxtImporter.ParsedTimetable(semesterName, courses, unrecognized)
    }

    /** 纯文本粘贴导入：一行一门课，用 Tab / 逗号 分隔 */
    fun parsePlainText(text: String): CsuJwxtImporter.ParsedTimetable {
        val courses = text.lines().mapNotNull { line ->
            val parts = line.split('\t', ',', '，').map(String::trim).filter(String::isNotEmpty)
            if (parts.size < 3) return@mapNotNull null
            runCatching {
                Course(
                    semesterId = 0,                        // 由调用方填充
                    name = parts[0],
                    teacher = parts.getOrNull(1),
                    room = parts.getOrNull(2),
                    dayOfWeek = parseDayName(parts.getOrNull(3)) ?: 1,
                    periodStart = parsePeriodLabel(parts.getOrNull(4))?.first ?: 1,
                    periodEnd = parsePeriodLabel(parts.getOrNull(4))?.second ?: 2,
                )
            }.getOrNull()
        }
        return CsuJwxtImporter.ParsedTimetable(null, courses)
    }

    /* ────────────── 单元格解析 ────────────── */

    /**
     * 这一行是不是「周次行」。
     *
     * ★ 必须同时满足「含周」+「含数字」。
     *   只看「含周」会把《周易概论》《周礼导读》这类课程名当成周次行，
     *   结果是课程名丢失、周次被填成乱值 —— 这种错最难被发现。
     */
    private fun isWeekLine(line: String): Boolean =
        line.contains('周') && line.any(Char::isDigit)

    /**
     * 周次解析**委托给 [WeekInput.parse]**，不在这里自己写一套。
     *
     * 原因：「手动编辑课程时间」的输入也要用同一套规则。
     * 两边各写一份的话，迟早出现「导进来的课对了、手改的课错了」
     * 这种最难查的 bug —— 因为两处的正则只差一个字符。
     *
     * 认得的写法见 WeekInput.parse 的注释（3-16周 / 3-16(周) / 3-18(单周) / 8,12(周) …）
     */
    private fun parseWeeks(line: String): WeekInput? =
        if (isWeekLine(line)) WeekInput.parse(line) else null

    /**
     * 教师职称后缀：`王志忠[教授]`、`王煜[特聘副教授]`、`胡云宾(副教授)`
     * 方括号和括号（含全角）里的内容一律剥掉。
     */
    private val TITLE_BRACKET = Regex("""[\[【(（][^\]】)）]*[\]】)）]""")

    /**
     * 教师字段清洗。
     *
     * 做两件事：
     *  1. 剥掉职称 —— `王志忠[教授]` → `王志忠`
     *  2. 多个教师连起来 —— `奎晓燕[教授],康松林[副教授]` → `奎晓燕、康松林`
     *     （用顿号而不是逗号，避免跟「逗号 = 字段分隔」的语义混淆）
     */
    private fun cleanTeacher(raw: String): String? =
        raw.split(',', '，', '、', ';', '；')
            .map { it.replace(TITLE_BRACKET, "").trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString("、")
            .takeIf { it.isNotEmpty() }

    /**
     * 像不像教室：必须带数字，且带位置特征词（座/楼/室…）或「字母+数字」。
     *
     * 之前只用「字母」判断，结果 `大学物理B（二）` 这种含 B 的教师行/课名
     * 会被误判成教室。加上「必须带数字」就稳了。
     */
    private val ROOM_HINT = Regex("""(楼|室|馆|区|院|教|阶|号|栋|座|校区)|[A-Za-z]\s*\d""")

    private fun looksLikeRoom(line: String): Boolean =
        line.any(Char::isDigit) && ROOM_HINT.containsMatchIn(line)

    /**
     * 单元格文本一般长这样：
     *   概率论与数理统计A
     *   王志忠[教授]
     *   3-16(周)
     *   B座411
     *
     * 顺序、有没有某一行，在不同学校/不同学期都会变，所以**按特征识别**：
     *   含「周」+数字 → 周次；剩下的第一行 → 课程名；
     *   再剩下的行按「几行」来分流教师与教室（见下）。
     */
    private fun parseCellText(raw: String, dayOfWeek: Int, period: Pair<Int, Int>): Course? {
        val lines = raw.lines().map(String::trim).filter(String::isNotEmpty)
        if (lines.isEmpty()) return null

        var weekSpec: WeekInput? = null
        val middle = mutableListOf<String>()

        for (line in lines) {
            if (weekSpec == null && isWeekLine(line)) {
                val parsed = parseWeeks(line)
                if (parsed != null) {
                    weekSpec = parsed
                    continue
                }
            }
            middle += line
        }

        if (middle.isEmpty()) return null

        val name = middle.first()
        val rest = middle.drop(1)

        // 剩下的行只可能是 [教师?, 教室?]。
        // ★ 不去猜字面，而是用「剩下几行」来决定 —— 比正则猜稳得多：
        //     两行以上 → 带数字的是教室，另一行是教师
        //     只剩一行 → 像教室就当教室（没有教师名的课），否则当教师（没有教室的课，比如体育）
        val room: String?
        val teacherLine: String?
        if (rest.size >= 2) {
            val roomIndex = rest.indexOfFirst { looksLikeRoom(it) }
                .takeIf { it >= 0 }
                ?: rest.lastIndex
            room = rest[roomIndex]
            teacherLine = rest.firstOrNull { it != room }
        } else {
            val only = rest.firstOrNull()
            if (only != null && looksLikeRoom(only)) {
                room = only
                teacherLine = null
            } else {
                room = null
                teacherLine = only
            }
        }

        return Course(
            semesterId = 0,                                    // 由调用方填充
            name = name,
            teacher = teacherLine?.let { cleanTeacher(it) },
            room = room,
            dayOfWeek = dayOfWeek,
            periodStart = period.first,
            periodEnd = period.second,
            weekParity = weekSpec?.parity ?: WeekParity.ALL,
            weekRange = weekSpec?.weekRange ?: 1..16,
            weekSet = weekSpec?.weekSet,
            color = colorFor(name),
        )
    }

    /** 同一门课每次导入颜色保持一致 —— 避免每次导入颜色都在跳 */
    private fun colorFor(name: String): CourseColor {
        val palette = CourseColor.entries
        return palette[(name.hashCode().absoluteValue) % palette.size]
    }

    private fun parsePeriodLabel(text: String?): Pair<Int, Int>? {
        val t = text?.trim() ?: return null
        val m = Regex("""(\d+)\s*[-–~]\s*(\d+)""").find(t) ?: return null
        return m.groupValues[1].toInt() to m.groupValues[2].toInt()
    }

    private fun parseDayName(text: String?): Int? = DAY_NAMES
        .entries
        .firstOrNull { it.key == text?.trim() }
        ?.value?.dayOfWeek

    /* ────────────── 供「课表截图识别」复用的入口 ────────────── */

    /** 这段文本里是不是出现了星期名 */
    internal fun isDayName(text: String): Boolean =
        DAY_NAMES.keys.any { text.contains(it) }

    /** 从含星期名的文本里取出 dayOfWeek（1..7）；取不到返回 null */
    internal fun dayOfWeekOf(text: String): Int? =
        DAY_NAMES.entries.firstOrNull { text.trim() == it.key }?.value?.dayOfWeek
            ?: DAY_NAMES.entries.firstOrNull { text.contains(it.key) }?.value?.dayOfWeek

    /**
     * 解析一个「格子的文本」。
     *
     * ★ 做成 internal 并对外开放，是为了让**课表截图识别**复用同一套规则。
     *   两边各写一套的话，迟早出现「教务导入的课对了、拍照识别的课错位」
     *   这种最难查的 bug —— 因为两处的解析逻辑只差一两个字符。
     */
    internal fun parseCellBlock(
        raw: String,
        dayOfWeek: Int,
        periodStart: Int,
        periodEnd: Int,
    ): Course? = parseCellText(raw, dayOfWeek, periodStart to periodEnd)

    /**
     * ★ 教务系统的口径是「星期日」在第一列，而 java.time 里 Sunday = 7。
     *   这个映射只在这里写一次，别的地方一律用 1..7（周一..周日）。
     */
    private val DAY_NAMES: Map<String, DayInfo> = mapOf(
        "星期一" to DayInfo(1), "周一" to DayInfo(1),
        "星期二" to DayInfo(2), "周二" to DayInfo(2),
        "星期三" to DayInfo(3), "周三" to DayInfo(3),
        "星期四" to DayInfo(4), "周四" to DayInfo(4),
        "星期五" to DayInfo(5), "周五" to DayInfo(5),
        "星期六" to DayInfo(6), "周六" to DayInfo(6),
        "星期日" to DayInfo(7), "星期天" to DayInfo(7), "周日" to DayInfo(7),
    )

    private data class DayInfo(val dayOfWeek: Int)
}
