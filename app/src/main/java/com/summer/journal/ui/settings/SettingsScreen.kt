package com.summer.journal.ui.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.summer.journal.data.datastore.SettingsRepository
import com.summer.journal.data.remote.weather.City
import com.summer.journal.domain.model.BackgroundScope
import com.summer.journal.domain.model.BackgroundTheme
import com.summer.journal.permission.AppPermission
import com.summer.journal.permission.PermissionManager
import com.summer.journal.permission.rationale
import com.summer.journal.ui.AppViewModel
import com.summer.journal.ui.theme.BackgroundColorKey
import com.summer.journal.ui.theme.BackgroundPreviewBox
import com.summer.journal.ui.theme.LocalPageForeground
import com.summer.journal.ui.theme.SummerPalette
import com.summer.journal.ui.theme.brushFor
import com.summer.journal.ui.theme.previewForeground
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalTime
import javax.inject.Inject

/* ══════════════════════════════════════════════════════════════════════
   ViewModel
   ══════════════════════════════════════════════════════════════════════ */

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val permissionManager: PermissionManager,
) : androidx.lifecycle.ViewModel() {

    val showHoliday = settings.showHoliday.stateIn_(viewModelScope, default = true)
    val showLunar = settings.showLunar.stateIn_(viewModelScope, default = true)
    val defaultReminder = settings.defaultReminderMinutes.stateIn_(viewModelScope, default = 30)
    val classReminder = settings.classReminderMinutes.stateIn_(viewModelScope, default = 10)
    val background = settings.backgroundTheme.stateIn_(viewModelScope, default = com.summer.journal.domain.model.BackgroundTheme())

    private val _readiness = MutableStateFlow(permissionManager.reminderReadiness())
    val readiness: StateFlow<PermissionManager.ReminderReadiness> = _readiness.asStateFlow()

    /** 从系统设置页回来时刷新一次 —— 用户可能刚在那边打开了权限 */
    fun refreshPermissions() {
        _readiness.value = permissionManager.reminderReadiness()
    }

    fun isGranted(permission: AppPermission) = permissionManager.isGranted(permission)

    fun settingsIntentFor(permission: AppPermission): Intent? =
        permissionManager.settingsIntentFor(permission)

    fun vendorAutoStartIntent(): Intent? = permissionManager.vendorAutoStartIntent()

    fun setShowHoliday(v: Boolean) = viewModelScope.launch { settings.setShowHoliday(v) }
    fun setShowLunar(v: Boolean) = viewModelScope.launch { settings.setShowLunar(v) }
    fun setBackgroundColor(key: String) = viewModelScope.launch { settings.setBackgroundColor(key) }

    /** 应用范围：全部页面 / 仅日历页 / 跟随时间 */
    fun setBackgroundScope(scope: BackgroundScope) =
        viewModelScope.launch { settings.setBackgroundScope(scope) }
    fun setBackgroundImage(relativePath: String?) =
        viewModelScope.launch { settings.setBackgroundImage(relativePath) }
    fun setBlur(dp: Int) = viewModelScope.launch { settings.setBackgroundBlur(dp) }
    fun setScrim(percent: Int) = viewModelScope.launch { settings.setBackgroundScrim(percent) }
    fun resetBackground() = viewModelScope.launch { settings.resetBackground() }
}

/** 小工具：把 Flow 转成带默认值的 StateFlow，省掉一堆 stateIn 样板 */
private fun <T> Flow<T>.stateIn_(
    scope: CoroutineScope,
    default: T,
): StateFlow<T> = stateIn(
    scope = scope,
    started = SharingStarted.WhileSubscribed(5_000),
    initialValue = default,
)

/* ══════════════════════════════════════════════════════════════════════
   Screen
   ══════════════════════════════════════════════════════════════════════ */

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    appViewModel: AppViewModel,
    onImportBackground: (android.net.Uri) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val readiness by viewModel.readiness.collectAsStateWithLifecycle()
    val background by viewModel.background.collectAsStateWithLifecycle()
    val cityName by appViewModel.cityName.collectAsStateWithLifecycle()

    var showCityPicker by remember { mutableStateOf(false) }

    // 从系统设置页返回时，重新读一次权限状态
    LifecycleResumeEffect(Unit) {
        viewModel.refreshPermissions()
        onPauseOrDispose { }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            // 页面标题直接画在背景上，所以要走 LocalPageForeground：
            // 背景换成深色照片时它会自动变白，否则就看不见了
            Text(
                text = "我的",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = LocalPageForeground.current,
            )
        }

        // ── 提醒权限引导（核心） ──
        item {
            PermissionSection(
                readiness = readiness,
                isGranted = viewModel::isGranted,
                onOpenSettings = { permission ->
                    viewModel.settingsIntentFor(permission)?.let {
                        runCatching { context.startActivity(it) }
                    }
                },
                onOpenVendorPage = {
                    viewModel.vendorAutoStartIntent()?.let {
                        runCatching { context.startActivity(it) }
                    }
                },
            )
        }

        // ── 天气 ──
        item {
            SettingsGroup("天气") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showCityPicker = true }
                        .padding(horizontal = 16.dp, vertical = 15.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("城市", fontSize = 13.sp, color = SummerPalette.InkSecondary)
                    Text(
                        text = cityName ?: "当前定位（自动）",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = SummerPalette.Ink,
                    )
                }
                InfoHint("定位只在打开天气时取一次，不后台追踪；取不到定位时会请你手动选择城市，不会用任何默认城市顶替。")
            }
        }

        // ── 日历 ──
        item {
            SettingsGroup("日历") {
                SwitchRow("显示法定节假日", viewModel.showHoliday.collectAsStateWithLifecycle().value) {
                    viewModel.setShowHoliday(it)
                }
                SwitchRow("显示农历与节气", viewModel.showLunar.collectAsStateWithLifecycle().value) {
                    viewModel.setShowLunar(it)
                }
            }
        }

        // ── 背景 ──
        item {
            SettingsGroup("背景") {
                // ① 实时预览。和真实背景走同一套渲染逻辑，
                //    避免「预览好看、进去发现不一样」这种经典问题。
                BackgroundPreview(theme = background)

                // ② 背景颜色
                GroupLabel("背景颜色")
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    BackgroundColorKey.entries.take(4).forEach { key ->
                        ColorSwatch(
                            key = key,
                            selected = background.colorKey == key.name,
                            onClick = { viewModel.setBackgroundColor(key.name) },
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .padding(top = 10.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    BackgroundColorKey.entries.drop(4).forEach { key ->
                        ColorSwatch(
                            key = key,
                            selected = background.colorKey == key.name,
                            onClick = { viewModel.setBackgroundColor(key.name) },
                        )
                    }
                }

                // ③ 背景图片
                val pickBackground = rememberLauncherForActivityResult(
                    ActivityResultContracts.PickVisualMedia(),
                ) { uri -> uri?.let(onImportBackground) }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            pickBackground.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                        .padding(horizontal = 16.dp, vertical = 15.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("背景图片", fontSize = 13.sp, color = SummerPalette.InkSecondary)
                    Text(
                        text = if (background.imageRelativePath != null) "已设置 · 点此更换" else "从相册导入自己的照片",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = SummerPalette.Dusk,
                    )
                }

                if (background.imageRelativePath != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.setBackgroundImage(null) }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("移除图片", fontSize = 13.sp, color = SummerPalette.InkSecondary)
                        Text("移除", fontSize = 13.sp, color = Color(0xFFB9636F))
                    }
                }

                // ④ 模糊与遮罩 —— 这两项是「照片再花也不影响看日程」的关键
                SliderRow(label = "模糊", value = background.blurDp, range = 0..40) {
                    viewModel.setBlur(it)
                }
                SliderRow(
                    label = "遮罩浓度",
                    value = (background.scrimAlpha * 100).toInt(),
                    range = 0..80,
                ) {
                    viewModel.setScrim(it)
                }

                // 遮罩太低 + 有图 → 页面标题可能看不清，直接提示而不是偷偷改用户的数值
                if (background.imageRelativePath != null && background.scrimAlpha < 0.2f) {
                    HintText("遮罩偏低。如果照片本身很亮，日历页的标题可能会看不清 —— 往右拖一点就行。")
                }

                // ⑤ 应用范围
                GroupLabel("应用范围")
                val systemDark = isSystemInDarkTheme()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(SummerPalette.HairlineSoft),
                ) {
                    ScopeRow(
                        scope = BackgroundScope.ALL_PAGES,
                        current = background.scope,
                        title = "全部页面",
                        desc = "日历、课表、手札统一用这套背景",
                        onSelect = viewModel::setBackgroundScope,
                    )
                    ScopeRow(
                        scope = BackgroundScope.CALENDAR_ONLY,
                        current = background.scope,
                        title = "仅日历页",
                        desc = "其他页面保持纯色底，翻页时视觉更安静",
                        onSelect = viewModel::setBackgroundScope,
                    )
                    ScopeRow(
                        scope = BackgroundScope.AUTO_BY_TIME,
                        current = background.scope,
                        title = "跟随时间自动切换",
                        desc = "白天用浅色方案，日落后自动切到深色（与系统深色模式无关）",
                        onSelect = viewModel::setBackgroundScope,
                    )
                }

                // ⑥ 文字自动反色
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "文字自动反色",
                            fontSize = 13.sp,
                            color = SummerPalette.InkSecondary,
                        )
                        Text(
                            text = "背景变深时，页面标题自动换成浅色（白卡片里的文字不受影响）",
                            fontSize = 11.sp,
                            color = SummerPalette.InkTertiary,
                            lineHeight = 16.sp,
                            modifier = Modifier.padding(top = 3.dp, end = 8.dp),
                        )
                    }
                    Switch(
                        checked = background.autoInvertText,
                        onCheckedChange = appViewModel::setAutoInvertText,
                    )
                }

                // 当前实际生效的是什么，写清楚，免得以为设置没生效
                HintText(
                    when {
                        background.scope == BackgroundScope.CALENDAR_ONLY ->
                            "当前：只有日历页用这套背景，其余页面是纯色底。"
                        background.scope == BackgroundScope.AUTO_BY_TIME ->
                            "当前：按时间自动切换（现在应当是" +
                                (if (LocalTime.now().hour in 6..17) "浅色" else "深色") + "方案）。"
                        background.imageRelativePath != null ->
                            "当前：${background.blurDp}px 模糊 · ${(background.scrimAlpha * 100).toInt()}% 遮罩 · 文字" +
                                (if (background.autoInvertText) "自动反色" else "固定深色")
                        else ->
                            "当前：${BackgroundColorKey.fromKey(background.colorKey).label}" +
                                "（${if (systemDark) "深色模式" else "浅色模式"}下）· 文字" +
                                (if (background.autoInvertText) "自动反色" else "固定深色")
                    }
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.resetBackground() }
                        .padding(horizontal = 16.dp, vertical = 15.dp),
                ) {
                    Text("恢复默认背景", fontSize = 13.sp, color = SummerPalette.Dusk)
                }
            }
        }
    }

    if (showCityPicker) {
        CityPickerDialog(
            onDismiss = { showCityPicker = false },
            onSearch = { keyword, onResult -> appViewModel.searchCities(keyword, onResult) },
            onPick = { city ->
                appViewModel.pickCity(city)
                showCityPicker = false
            },
            onUseCurrentLocation = {
                appViewModel.clearManualCity()
                showCityPicker = false
            },
        )
    }
}

/* ────────────── 权限区 ────────────── */

@Composable
private fun PermissionSection(
    readiness: PermissionManager.ReminderReadiness,
    isGranted: (AppPermission) -> Boolean,
    onOpenSettings: (AppPermission) -> Unit,
    onOpenVendorPage: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(SummerPalette.Card)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("让提醒准点响", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = SummerPalette.Ink)
                Text(
                    text = "已完成 ${readiness.readyCount}/3 项",
                    fontSize = 11.5.sp,
                    color = SummerPalette.InkTertiary,
                )
            }
            if (readiness.allReady) {
                Text("已就绪", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = SummerPalette.Teal)
            }
        }

        PermissionRow(
            title = "通知权限",
            granted = readiness.notification,
            fallback = AppPermission.NOTIFICATION.rationale.fallback,
            onOpen = { onOpenSettings(AppPermission.NOTIFICATION) },
        )
        PermissionRow(
            title = "精确闹钟",
            granted = readiness.exactAlarm,
            fallback = AppPermission.EXACT_ALARM.rationale.fallback,
            onOpen = { onOpenSettings(AppPermission.EXACT_ALARM) },
        )
        PermissionRow(
            title = "后台运行不被清理",
            granted = readiness.batteryOptimization,
            fallback = AppPermission.IGNORE_BATTERY_OPTIMIZATION.rationale.fallback,
            onOpen = { onOpenSettings(AppPermission.IGNORE_BATTERY_OPTIMIZATION) },
        )

        if (readiness.vendorGuideNeeded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(SummerPalette.HairlineSoft)
                    .padding(12.dp),
            ) {
                Text(
                    text = "${readiness.vendorLabel}手机请再手动确认一步",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = SummerPalette.Ink,
                )
                Text(
                    text = "设置 → 应用 → 应用启动管理 → 夏日手札 → 关闭「自动管理」，" +
                        "并勾选「自启动 / 关联启动 / 后台活动」。否则提醒在夜间可能不响。",
                    fontSize = 11.5.sp,
                    color = SummerPalette.InkSecondary,
                    modifier = Modifier.padding(top = 5.dp),
                    lineHeight = 17.sp,
                )
                Text(
                    text = "尝试打开发动管理页 ›",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = SummerPalette.Dusk,
                    modifier = Modifier
                        .padding(top = 9.dp)
                        .clickable { onOpenVendorPage() },
                )
            }
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    granted: Boolean,
    fallback: String,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .padding(top = 3.dp)
                .size(18.dp)
                .clip(CircleShape)
                .background(if (granted) SummerPalette.CategoryTeal else SummerPalette.HairlineSoft),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (granted) "✓" else "·",
                fontSize = 11.sp,
                color = if (granted) Color(0xFF2E8E86) else SummerPalette.InkTertiary,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = SummerPalette.Ink)
            if (!granted) {
                Text(fallback, fontSize = 11.sp, color = SummerPalette.InkTertiary, lineHeight = 16.sp)
            }
        }
        if (!granted) {
            Text(
                text = "去设置",
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = SummerPalette.Dusk,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .clickable { onOpen() }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}

/* ────────────── 通用小组件 ────────────── */

@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            text = title,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = SummerPalette.Ink,
            modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(SummerPalette.Card),
        ) { content() }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 13.sp, color = SummerPalette.InkSecondary)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderRow(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, fontSize = 12.5.sp, color = SummerPalette.InkSecondary)
            Text("$value", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = SummerPalette.Ink)
        }
        androidx.compose.material3.Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun ColorSwatch(key: BackgroundColorKey, selected: Boolean, onClick: () -> Unit) {
    // ★ 色卡必须画**真实渐变色**（和背景同一套 brushFor），
    //   否则用户点进来的预览和实际效果对不上，等于没预览。
    //   选中态用「描边 + 右上角对勾」表达，比一层淡蒙版清楚得多。
    Box(
        modifier = Modifier
            .height(52.dp)
            .fillMaxWidth(0.22f)
            .clip(RoundedCornerShape(14.dp))
            .background(brushFor(key.name, dark = false))
            .then(
                if (selected) {
                    Modifier.border(2.5.dp, SummerPalette.Dusk, RoundedCornerShape(14.dp))
                } else {
                    Modifier.border(1.dp, SummerPalette.Hairline, RoundedCornerShape(14.dp))
                }
            )
            .clickable { onClick() },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Text(
            text = key.label,
            fontSize = 9.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = SummerPalette.InkSecondary,
            modifier = Modifier
                .padding(bottom = 6.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(Color.White.copy(alpha = 0.78f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
        if (selected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(SummerPalette.Dusk),
                contentAlignment = Alignment.Center,
            ) {
                Text("✓", fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun InfoHint(text: String) {
    Text(
        text = text,
        fontSize = 11.5.sp,
        color = SummerPalette.InkTertiary,
        lineHeight = 17.sp,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).padding(bottom = 12.dp),
    )
}

/** 紧凑版说明文字，用在背景设置里（那里每段之间已经很挤了） */
@Composable
private fun HintText(text: String) {
    Text(
        text = text,
        fontSize = 11.5.sp,
        color = SummerPalette.InkTertiary,
        lineHeight = 17.sp,
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 4.dp),
    )
}

/** 小节标题（区块内部的，比如「背景颜色」「应用范围」） */
@Composable
private fun GroupLabel(text: String) {
    Text(
        text = text,
        fontSize = 12.5.sp,
        color = SummerPalette.InkTertiary,
        modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 10.dp),
    )
}

/**
 * 背景实时预览。
 *
 * 刻意画成一个「迷你日历页」而不是单纯一块背景色 ——
 * 用户真正想知道的是「换成这张照片之后，我还能不能看清日程和标题」，
 * 所以预览里必须有：白卡片（内容载体）+ 直接画在背景上的标题。
 * 这两样正好是遮罩浓度和文字反色影响的全部对象。
 */
@Composable
private fun BackgroundPreview(theme: BackgroundTheme) {
    val systemDark = isSystemInDarkTheme()
    val hasImage = theme.imageRelativePath != null
    val fg = previewForeground(theme, systemDark, hasImage)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 14.dp, bottom = 2.dp),
    ) {
        Text(
            text = "预览",
            fontSize = 12.5.sp,
            color = SummerPalette.InkTertiary,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        BackgroundPreviewBox(
            theme = theme,
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .clip(RoundedCornerShape(16.dp)),
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                Text(
                    text = "2026年9月",
                    color = fg,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "农历八月初九 · 星期日",
                    color = fg.copy(alpha = 0.72f),
                    fontSize = 9.sp,
                    modifier = Modifier.padding(top = 3.dp),
                )

                // 一排日期格
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    repeat(7) { index ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(20.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(
                                    if (index == 3) Color.White.copy(alpha = 0.95f)
                                    else Color.White.copy(alpha = 0.55f)
                                ),
                        )
                    }
                }

                // 两张日程卡片：白卡片是内容载体，它不受背景影响，这是「照片再花也能看日程」的底气
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(30.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.94f)),
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(30.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.94f)),
                    )
                }
            }
        }
    }
}

/** 应用范围的单选项 */
@Composable
private fun ScopeRow(
    scope: BackgroundScope,
    current: BackgroundScope,
    title: String,
    desc: String,
    onSelect: (BackgroundScope) -> Unit,
) {
    val selected = scope == current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(scope) }
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .size(18.dp)
                .clip(CircleShape)
                .background(if (selected) SummerPalette.Dusk else Color.White),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Text("✓", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = SummerPalette.Ink,
            )
            Text(
                text = desc,
                fontSize = 11.sp,
                color = SummerPalette.InkTertiary,
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/* ────────────── 城市选择 ────────────── */

@Composable
private fun CityPickerDialog(
    onDismiss: () -> Unit,
    onSearch: (String, (List<City>) -> Unit) -> Unit,
    onPick: (City) -> Unit,
    onUseCurrentLocation: () -> Unit,
) {
    var keyword by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<City>>(emptyList()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择城市") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = keyword,
                    onValueChange = { input ->
                        keyword = input
                        if (input.length >= 1) onSearch(input) { results = it }
                    },
                    label = { Text("搜索城市名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = onUseCurrentLocation) {
                    Text("改用当前定位（清除手选城市）")
                }
                results.take(6).forEach { city ->
                    Text(
                        text = city.name,
                        fontSize = 14.sp,
                        color = SummerPalette.Ink,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(city) }
                            .padding(vertical = 9.dp),
                    )
                }
                if (keyword.isNotBlank() && results.isEmpty()) {
                    Text(
                        text = "没找到这个城市。注意本机缺 GMS 时系统地理编码可能不可用，可尝试换关键词。",
                        fontSize = 11.5.sp,
                        color = SummerPalette.InkTertiary,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
