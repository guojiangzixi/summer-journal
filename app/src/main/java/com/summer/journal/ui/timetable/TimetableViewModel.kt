package com.summer.journal.ui.timetable

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.summer.journal.data.datastore.SettingsRepository
import com.summer.journal.data.importing.CourseTextParser
import com.summer.journal.data.importing.GridCourseParser
import com.summer.journal.data.recognition.ImageTextRecognizer
import com.summer.journal.data.remote.jwxt.CsuJwxtImporter
import com.summer.journal.data.repo.TimetableRepository
import com.summer.journal.domain.model.Course
import com.summer.journal.domain.model.CourseColor
import com.summer.journal.domain.model.Semester
import com.summer.journal.domain.model.WeekParity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.absoluteValue

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TimetableViewModel @Inject constructor(
    private val repository: TimetableRepository,
    private val importer: CsuJwxtImporter,
    private val settings: SettingsRepository,
    private val recognizer: ImageTextRecognizer,
) : ViewModel() {

    val semesters: StateFlow<List<Semester>> = repository.observeSemesters()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val currentSemester: StateFlow<Semester?> = repository.observeCurrentSemester()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _semesterId = MutableStateFlow<Long?>(null)

    val courses: StateFlow<List<Course>> = _semesterId
        .flatMapLatest { id ->
            if (id == null) kotlinx.coroutines.flow.flowOf(emptyList())
            else repository.observeCourses(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val mode: StateFlow<TimetableMode> = settings.timetableMode
        .map { stored ->
            runCatching { TimetableMode.valueOf(stored) }.getOrDefault(TimetableMode.WEEKDAYS_ONLY)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TimetableMode.WEEKDAYS_ONLY)

    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState.asStateFlow()

    init {
        viewModelScope.launch {
            // 首次启动时保证至少有一个学期，否则课表页是空的
            val id = repository.ensureDefaultSemesterExists()
            _semesterId.value = id
        }
    }

    fun selectSemester(id: Long) {
        viewModelScope.launch {
            repository.setCurrentSemester(id)
            _semesterId.value = id
        }
    }

    fun setMode(newMode: TimetableMode) {
        viewModelScope.launch { settings.setTimetableMode(newMode.name) }
    }

    fun addCourse(
        name: String,
        teacher: String?,
        room: String?,
        dayOfWeek: Int,
        periodStart: Int,
        periodEnd: Int,
        weekStart: Int,
        weekEnd: Int,
        parity: WeekParity,
    ) {
        val semesterId = _semesterId.value ?: return
        viewModelScope.launch {
            repository.upsertCourse(
                Course(
                    semesterId = semesterId,
                    name = name.ifBlank { "未命名课程" },
                    teacher = teacher?.takeIf { it.isNotBlank() },
                    room = room?.takeIf { it.isNotBlank() },
                    dayOfWeek = dayOfWeek,
                    periodStart = periodStart,
                    periodEnd = periodEnd,
                    weekParity = parity,
                    weekRange = weekStart..weekEnd,
                    // 颜色按课程名哈希，保证同一门课每次都是同一个色
                    color = CourseColor.entries[
                        (name.hashCode().absoluteValue) % CourseColor.entries.size
                    ],
                )
            )
        }
    }

    fun deleteCourse(id: Long) {
        viewModelScope.launch { repository.deleteCourse(id) }
    }

    fun clearAllCourses() {
        val semesterId = _semesterId.value ?: return
        viewModelScope.launch { repository.clearCourses(semesterId) }
    }

    /**
     * 载入示例课表。
     *
     * ★ 只在用户**主动点击**时调用，且按钮文案明确写了「示例 / 看效果」。
     *   这和「偷偷塞一份假数据冒充用户自己的课表」是两回事 ——
     *   参考之前天气那次的原则：绝不把示例数据伪装成真实数据。
     *   页面底部随时可以「清空课表」。
     */
    fun seedSample() {
        val semesterId = _semesterId.value ?: return
        viewModelScope.launch {
            repository.clearCourses(semesterId)
            SAMPLE_COURSES.forEach { template ->
                repository.upsertCourse(
                    Course(
                        semesterId = semesterId,
                        name = template.name,
                        teacher = template.teacher,
                        room = template.room,
                        dayOfWeek = template.dayOfWeek,
                        periodStart = template.periodStart,
                        periodEnd = template.periodEnd,
                        weekParity = template.parity,
                        weekRange = 1..16,
                        color = template.color,
                    )
                )
            }
            _importState.value = ImportState.Done(
                added = SAMPLE_COURSES.size,
                replaced = 0,
                unrecognized = 0,
                isSample = true,
            )
        }
    }

    /** 教务导入：登录由 JwxtLoginActivity 完成，这里只负责拿结果落库 */
    fun importFromJwxt() {
        val semesterId = _semesterId.value ?: return
        viewModelScope.launch {
            _importState.value = ImportState.Running
            when (val result = importer.importFromWebViewSession()) {
                is CsuJwxtImporter.ImportResult.Success -> {
                    val outcome = repository.importParsed(semesterId, result.parsed)
                    importer.clearSession()   // 导入完立即清 Cookie，不留会话
                    _importState.value = ImportState.Done(
                        added = outcome.added,
                        replaced = outcome.replaced,
                        unrecognized = outcome.unrecognized,
                    )
                }
                is CsuJwxtImporter.ImportResult.Failure -> {
                    _importState.value = ImportState.Failed(result.reason)
                }
            }
        }
    }

    fun dismissImportState() { _importState.value = ImportState.Idle }

    /* ══════════════════════════════════════════════════════════════
       课表图片识别（拍照 / 相册选图 → OCR → 解析成课程）
       ══════════════════════════════════════════════════════════════ */

    /**
     * 识别流程的状态。
     *
     * ★ 关键：Done 携带的是**候选列表**，不是直接落库的结果。
     *   课表截图的排版千差万别，解析器再努力也会错。把候选课程摆出来让用户
     *   扫一眼、删掉明显错的，比「识别完直接写库」安全得多 ——
     *   后者一旦错了，用户会在一整个学期里被错误的课表误导。
     */
    sealed interface ScanState {
        data object Idle : ScanState
        data object Recognizing : ScanState
        data class Failed(val message: String) : ScanState
        data class Done(
            val courses: List<Course>,
            val warnings: List<String>,
            /** 识别出的原始文本，供用户对照，也方便排查解析错误 */
            val rawText: String,
        ) : ScanState
    }

    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    /** 从相册 / 相机拿到的图片 URI 走这条 */
    fun scanFromUri(uri: android.net.Uri) {
        viewModelScope.launch {
            _scanState.value = ScanState.Recognizing

            val recognized = recognizer.recognize(uri)
            if (recognized == null || recognized.isEmpty) {
                _scanState.value = ScanState.Failed(
                    "没能从这张图里认出文字。试试把课表整页截图放进来，" +
                        "或者换成更清晰、没有倾斜的照片。"
                )
                return@launch
            }

            // ★ 先试「网格重建」：教务课表截图是二维表格，
            //   纯文本已经丢了「这条文字属于星期几」的信息，只有靠坐标才能还原。
            val grid = GridCourseParser.parse(recognized.lines)
            if (grid != null && grid.courses.isNotEmpty()) {
                _scanState.value = ScanState.Done(
                    courses = grid.courses,
                    warnings = grid.warnings,
                    rawText = recognized.fullText,
                )
                return@launch
            }

            // 退回按行解析：适合「一门课一段」的纵向列表截图
            parseRecognizedText(recognized.fullText)
        }
    }

    /**
     * 直接给文本（手输 / 粘贴 / 别的来源）。
     * 和 OCR 走同一个解析器，避免两条路各有一套规则。
     */
    fun scanFromText(text: String) {
        viewModelScope.launch {
            _scanState.value = ScanState.Recognizing
            parseRecognizedText(text)
        }
    }

    private fun parseRecognizedText(text: String) {
        val parsed = CourseTextParser.parse(text)
        _scanState.value = if (parsed.courses.isEmpty()) {
            ScanState.Failed(
                parsed.warnings.firstOrNull()
                    ?: "文字认出来了，但没能拼成课程。可以展开原始文字核对一下格式。"
            )
        } else {
            ScanState.Done(
                courses = parsed.courses,
                warnings = parsed.warnings,
                rawText = text,
            )
        }
    }

    fun resetScan() { _scanState.value = ScanState.Idle }

    /** 用户确认后批量导入识别结果 */
    fun importScannedCourses(courses: List<Course>) {
        val semesterId = _semesterId.value ?: return
        viewModelScope.launch {
            courses.forEach { repository.upsertCourse(it.copy(semesterId = semesterId)) }
            _scanState.value = ScanState.Idle
            _importState.value = ImportState.Done(added = courses.size, replaced = 0, unrecognized = 0)
        }
    }

    /* ══════════════════════════════════════════════════════════════
       单独修改某一门课程（时间 / 教室 / 教师 / 周次）
       ══════════════════════════════════════════════════════════════ */

    /**
     * 保存课程编辑结果。
     *
     * 走 upsert：id 存在就是更新，不存在就是新增。
     * 用 id 判定的好处是同一个方法既能「改课」也能「新建」，UI 不用分两套调用。
     */
    fun saveCourse(course: Course) {
        val semesterId = _semesterId.value
        viewModelScope.launch {
            repository.upsertCourse(
                if (course.semesterId == 0L && semesterId != null) {
                    course.copy(semesterId = semesterId)
                } else {
                    course
                }
            )
        }
    }

    sealed interface ImportState {
        data object Idle : ImportState
        data object Running : ImportState

        data class Done(
            val added: Int,
            val replaced: Int,
            val unrecognized: Int,
            val isSample: Boolean = false,
        ) : ImportState

        data class Failed(val reason: CsuJwxtImporter.Reason) : ImportState
    }
}

/**
 * 示例课表 —— 只在用户主动点「载入示例课表」时使用。
 *
 * ★ 为什么这里可以有硬编码数据，而天气那边不行：
 *   天气的「默认城市」会被误当成用户真实所在位置，给错地方的天气比不给更糟；
 *   而这里的数据带着明确的「示例」标签，是用来验证排版的，
 *   点一下就能清空，不存在「被当成真实数据」的风险。
 */
internal data class SampleCourse(
    val name: String,
    val teacher: String?,
    val room: String,
    val dayOfWeek: Int,
    val periodStart: Int,
    val periodEnd: Int,
    val parity: WeekParity = WeekParity.ALL,
    val color: CourseColor,
)

internal val SAMPLE_COURSES: List<SampleCourse> = listOf(
    SampleCourse("高等数学A(二)", "张明", "科教南楼 201", 1, 1, 2, color = CourseColor.TEAL),
    SampleCourse("数据结构", "李涛", "新校区 A302", 1, 3, 4, color = CourseColor.VIOLET),
    SampleCourse("大学英语(三)", "王芳", "外语楼 206", 1, 7, 8, color = CourseColor.PINK),
    SampleCourse("大学物理", "陈刚", "科教南楼 105", 2, 1, 2, color = CourseColor.ORANGE),
    SampleCourse("概率论与数理统计", "刘敏", "科教南楼 301", 2, 3, 4, color = CourseColor.TEAL),
    SampleCourse("计算机组成原理", "赵鹏", "新校区 A405", 3, 1, 2, color = CourseColor.VIOLET),
    SampleCourse("线性代数", "周琳", "科教南楼 202", 3, 5, 6, color = CourseColor.ORANGE),
    SampleCourse("体育(篮球)", "孙教练", "新校区体艺馆", 3, 9, 10, color = CourseColor.PINK),
    SampleCourse("高等数学A(二)", "张明", "科教南楼 201", 4, 3, 4, parity = WeekParity.ODD, color = CourseColor.TEAL),
    SampleCourse("离散数学", "吴迪", "新校区 A210", 4, 5, 6, color = CourseColor.VIOLET),
    SampleCourse("思想道德与法治", "何静", "文科楼 B 座", 4, 7, 8, color = CourseColor.ORANGE),
    SampleCourse("数据结构", "李涛", "新校区 A302", 5, 1, 2, color = CourseColor.VIOLET),
    SampleCourse("大学物理实验", "陈刚", "物理实验楼", 5, 3, 4, color = CourseColor.ORANGE),
    SampleCourse("大学英语(三)", "王芳", "外语楼 206", 5, 7, 8, color = CourseColor.PINK),
    SampleCourse("创新创业实践", "罗老师", "创客空间", 6, 5, 6, color = CourseColor.TEAL),
)
