package com.summer.journal.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.summer.journal.data.remote.jwxt.CsuJwxtImporter
import com.summer.journal.data.remote.jwxt.JwxtLoginActivity
import com.summer.journal.domain.model.Course
import com.summer.journal.ui.calendar.CalendarViewModel
import com.summer.journal.ui.calendar.MonthCalendarScreen
import com.summer.journal.ui.memo.MemoDetailScreen
import com.summer.journal.ui.memo.MemoScreen
import com.summer.journal.ui.memo.MemoViewModel
import com.summer.journal.ui.settings.SettingsScreen
import com.summer.journal.ui.settings.SettingsViewModel
import com.summer.journal.ui.theme.AppBackground
import com.summer.journal.ui.theme.SummerPalette
import com.summer.journal.ui.common.launchCameraCapture
import com.summer.journal.ui.timetable.CourseEditorSheet
import com.summer.journal.ui.timetable.CourseScanSheet
import com.summer.journal.ui.timetable.ImportBanner
import com.summer.journal.ui.timetable.TimetableMode
import com.summer.journal.ui.timetable.TimetableScreen
import com.summer.journal.ui.timetable.TimetableViewModel
import com.summer.journal.ui.weather.WeatherCard
import java.time.LocalDate

/**
 * ViewModel 的导入状态 → UI 提示条。
 *
 * 放在 UI 层做这个映射，是为了让 ViewModel 不依赖任何 UI 类型
 * （否则 VM 里就得到处 import Compose 的东西，测试也难写）。
 */
private fun TimetableViewModel.ImportState.toBanner(): ImportBanner? = when (this) {
    TimetableViewModel.ImportState.Idle -> null
    TimetableViewModel.ImportState.Running -> ImportBanner.Running
    is TimetableViewModel.ImportState.Done ->
        if (isSample) ImportBanner.Done(added, 0, 0) else ImportBanner.Done(added, replaced, unrecognized)
    is TimetableViewModel.ImportState.Failed -> ImportBanner.Failed(
        when (reason) {
            CsuJwxtImporter.Reason.NO_SESSION ->
                "没拿到登录状态。请重新点「导入」，在打开的页面里完成登录。"
            CsuJwxtImporter.Reason.SESSION_EXPIRED ->
                "登录已过期，请重新登录一次。"
            CsuJwxtImporter.Reason.NETWORK ->
                "网络请求失败。检查一下网络，或确认手机能打开教务系统网页。"
            CsuJwxtImporter.Reason.PARSE_EMPTY ->
                "页面拿到了，但没解析出课程 —— 教务系统可能改版了。" +
                    "先用「手动添加课程」把课录进来，再把页面反馈给我们修解析。"
        }
    )
}

enum class MainTab(val label: String) {
    CALENDAR("日历"),
    TIMETABLE("课表"),
    MEMO("手札"),
    PROFILE("我的"),
}

@Composable
fun MainScaffold(
    appViewModel: AppViewModel,
    modifier: Modifier = Modifier,
) {
    var tab by remember { mutableStateOf(MainTab.CALENDAR) }
    var showAddEvent by remember { mutableStateOf(false) }
    var addEventDate by remember { mutableStateOf(LocalDate.now()) }

    // 日历的 VM 提到这一层：新建日程的对话框在 Composable 作用域之外也要用它
    val calendarViewModel: CalendarViewModel = hiltViewModel()

    // 课表的 VM 也一样（课程详情 / 添加课程弹窗、教务导入回调都要用）
    val timetableViewModel: TimetableViewModel = hiltViewModel()

    /**
     * 教务导入的第一步：拉起登录页。
     *
     * WebView 登录由 JwxtLoginActivity 负责（用户自己输账号密码，App 不读取、不保存），
     * 登录成功后它返回 RESULT_OK，我们再调 importFromJwxt() 抓课表。
     */
    val jwxtLoginLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            timetableViewModel.importFromJwxt()
        }
    }

    val weather by appViewModel.weather.collectAsStateWithLifecycle()
    val weatherStatus by appViewModel.weatherStatus.collectAsStateWithLifecycle()
    val cityName by appViewModel.cityName.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // 当前学期提到这一层：课表分支和「添加课程」弹窗都要用
    val semester by timetableViewModel.currentSemester.collectAsStateWithLifecycle()
    // 背景主题：交给 AppBackground 渲染
    val backgroundTheme by appViewModel.backgroundTheme.collectAsStateWithLifecycle()

    /* ── 课程编辑面板 ──
       editorInitial == null 表示「新建」；非 null 表示「改这一门的时间」。
       两者共用同一个面板，避免两份几乎一样的表单各自演化。 */
    var editorOpen by remember { mutableStateOf(false) }
    var editorInitial by remember { mutableStateOf<Course?>(null) }
    var editorDefaultDay by remember { mutableIntStateOf(1) }
    var editorDefaultPeriod by remember { mutableStateOf(1..2) }

    /* ── 课表图片识别 ── */
    var showScan by remember { mutableStateOf(false) }
    var scanCameraUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val scanCameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        val uri = scanCameraUri
        scanCameraUri = null
        if (success && uri != null) timetableViewModel.scanFromUri(uri)
    }
    val scanAlbumLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let { timetableViewModel.scanFromUri(it) } }
    // 和手札那边同样的坑：清单里声明了 CAMERA 但没授权时，
    // 直接 startActivity 会抛 SecurityException（表现为「点了没反应」）
    val scanCameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            launchCameraCapture(context, scanCameraLauncher, "timetable") { scanCameraUri = it }
        }
    }
    fun openScanCamera() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            launchCameraCapture(context, scanCameraLauncher, "timetable") { scanCameraUri = it }
        } else {
            scanCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    /* ── 天气定位 ── */
    // 天气卡上的「开启定位」：
    //   过去定位权限从来没被申请过（清单声明了但没运行时请求），
    //   于是天气一直停在「打开定位或手动选城市」，用户以为定位功能坏了。
    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        // 粗略定位拿到就够了（天气只需要到城市级）；拿到任一即刷新
        if (result.values.any { it }) appViewModel.refreshWeather(force = true)
    }
    fun requestLocation() {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (fine || coarse) {
            appViewModel.refreshWeather(force = true)
        } else {
            locationPermission.launch(
                arrayOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                )
            )
        }
    }

    /* ── 手札详情 ── */
    var openMemoId by remember { mutableStateOf<Long?>(null) }
    val memoViewModel: MemoViewModel = hiltViewModel()

    // 课表识别的状态（放在这里，因为面板和主界面都要读）
    val scanState by timetableViewModel.scanState.collectAsStateWithLifecycle()

    val statusPadding = WindowInsets.statusBars.asPaddingValues()
    val navPadding = WindowInsets.navigationBars.asPaddingValues()

    // 背景引擎包在最外层：它需要知道当前 Tab，才能实现「仅日历页」这个应用范围
    AppBackground(
        theme = backgroundTheme,
        isCalendarPage = tab == MainTab.CALENDAR,
    ) {
    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 状态栏留白：用系统 inset 而不是写死 62dp，换机型才不会错位
            Box(modifier = Modifier.height(statusPadding.calculateTopPadding().coerceAtLeast(24.dp)))

            Box(modifier = Modifier.weight(1f)) {
                when (tab) {
                    MainTab.CALENDAR -> {
                        MonthCalendarScreen(
                            viewModel = calendarViewModel,
                            weatherSlot = {
                                WeatherCard(
                                    snapshot = weather,
                                    status = weatherStatus,
                                    cityName = cityName,
                                    onPickCity = { tab = MainTab.PROFILE },
                                    onRetry = { appViewModel.refreshWeather(force = true) },
                                    onEnableLocation = ::requestLocation,
                                )
                            },
                            onAddEvent = { date ->
                                addEventDate = date
                                showAddEvent = true
                            },
                        )
                    }

                    MainTab.TIMETABLE -> {
                        val courses by timetableViewModel.courses.collectAsStateWithLifecycle()
                        val mode by timetableViewModel.mode.collectAsStateWithLifecycle()
                        val importState by timetableViewModel.importState.collectAsStateWithLifecycle()

                        TimetableScreen(
                            semesterName = semester?.name ?: "未设置学期",
                            courses = courses,
                            mode = mode,
                            importBanner = importState.toBanner(),
                            // 「按周查看」需要知道开学日和总周数，才能算出每周的日期区间
                            semesterStart = semester?.startDate,
                            totalWeeks = semester?.totalWeeks ?: 20,
                            onModeChange = timetableViewModel::setMode,
                            // 点已有课程 → 打开面板改时间（原先是只读详情，改不了）
                            onCourseClick = { course ->
                                editorInitial = course
                                editorOpen = true
                            },
                            // 点空格子 → 打开面板新建，星期/节次已预填好
                            onEmptySlotClick = { day, slot ->
                                editorInitial = null
                                editorDefaultDay = day
                                editorDefaultPeriod = slot.start..slot.end
                                editorOpen = true
                            },
                            onAddCourse = {
                                editorInitial = null
                                editorDefaultDay = 1
                                editorDefaultPeriod = 1..2
                                editorOpen = true
                            },
                            onImportFromJwxt = {
                                jwxtLoginLauncher.launch(
                                    android.content.Intent(context, JwxtLoginActivity::class.java)
                                )
                            },
                            onScanImage = { showScan = true },
                            onSeedSample = timetableViewModel::seedSample,
                            onClearAll = timetableViewModel::clearAllCourses,
                            onDismissBanner = timetableViewModel::dismissImportState,
                        )
                    }

                    MainTab.MEMO -> {
                        MemoScreen(
                            viewModel = memoViewModel,
                            onOpen = { openMemoId = it },
                        )
                    }

                    MainTab.PROFILE -> {
                        val viewModel: SettingsViewModel = hiltViewModel()
                        SettingsScreen(
                            viewModel = viewModel,
                            appViewModel = appViewModel,
                            onImportBackground = { uri ->
                                appViewModel.applyBackgroundImage(uri)
                            },
                        )
                    }
                }
            }

            PillTabBar(
                selected = tab,
                onSelect = { tab = it },
                bottomInset = navPadding.calculateBottomPadding(),
            )
            }
        }
    }

    if (showAddEvent) {
        AddEventDialog(
            date = addEventDate,
            onDismiss = { showAddEvent = false },
            onConfirm = { title, hour, location ->
                calendarViewModel.addEvent(title, addEventDate, hour, location)
                showAddEvent = false
            },
        )
    }

    /* ────────────── 课程编辑面板 ──────────────
       一个面板同时承担「新建课程」和「单独修改某一门课程的时间」。
       editorInitial 为 null 就是新建。 */
    if (editorOpen) {
        CourseEditorSheet(
            initial = editorInitial,
            semesterId = semester?.id ?: 0L,
            totalWeeks = semester?.totalWeeks ?: 18,
            defaultDay = editorDefaultDay,
            defaultPeriod = editorDefaultPeriod,
            onDismiss = { editorOpen = false },
            onSave = { course ->
                timetableViewModel.saveCourse(course)
                editorOpen = false
            },
            onDelete = { id ->
                timetableViewModel.deleteCourse(id)
                editorOpen = false
            },
        )
    }

    /* ────────────── 课表截图识别 ────────────── */
    if (showScan) {
        CourseScanSheet(
            state = scanState,
            onPickImage = {
                scanAlbumLauncher.launch(
                    androidx.activity.result.PickVisualMediaRequest(
                        ActivityResultContracts.PickVisualMedia.ImageOnly
                    )
                )
            },
            onTakePhoto = ::openScanCamera,
            onImport = { courses ->
                timetableViewModel.importScannedCourses(courses)
                showScan = false
            },
            onDismiss = {
                timetableViewModel.resetScan()
                showScan = false
            },
        )
    }

    /* ────────────── 手札详情 ────────────── */
    openMemoId?.let { memoId ->
        MemoDetailScreen(
            memoId = memoId,
            viewModel = memoViewModel,
            onBack = { openMemoId = null },
            // 录音由 Service 负责，ViewModel 只管状态与落库
            onStartRecording = { id -> memoViewModel.startRecording(id) },
        )
    }
}

/**
 * 底部胶囊 Tab Bar。
 * 高度 62dp、圆角 36dp、激活项实心渐变 —— 与视觉稿一致。
 */
@Composable
private fun PillTabBar(
    selected: MainTab,
    onSelect: (MainTab) -> Unit,
    bottomInset: androidx.compose.ui.unit.Dp,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = 21.dp,
                end = 21.dp,
                top = 12.dp,
                bottom = bottomInset.coerceAtLeast(12.dp),
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(62.dp)
                .clip(RoundedCornerShape(36.dp))
                .background(Color.White.copy(alpha = 0.92f))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            MainTab.entries.forEach { item ->
                val active = item == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .clip(RoundedCornerShape(26.dp))
                        .then(
                            if (active) {
                                Modifier.background(
                                    Brush.linearGradient(
                                        listOf(SummerPalette.Dusk, SummerPalette.Lilac, Color(0xFFC88AA6))
                                    )
                                )
                            } else Modifier
                        )
                        .clickable { onSelect(item) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = item.label,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.4.sp,
                        color = if (active) Color.White else SummerPalette.InkTertiary,
                    )
                }
            }
        }
    }
}

@Composable
private fun AddEventDialog(
    date: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (title: String, hour: Int, location: String?) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    var hour by remember { mutableStateOf(9) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${date.monthValue}月${date.dayOfMonth}日 · 新建日程") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("准备做什么？") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    label = { Text("地点（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = hour.toString(),
                    onValueChange = { input -> hour = input.toIntOrNull()?.coerceIn(0, 23) ?: 9 },
                    label = { Text("时间（0–23 点）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "默认会提前 30 分钟提醒，可在日程详情里改。",
                    fontSize = 11.sp,
                    color = SummerPalette.InkTertiary,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(title, hour, location) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
