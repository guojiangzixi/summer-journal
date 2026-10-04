package com.summer.journal.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.summer.journal.data.attachment.AttachmentStore
import com.summer.journal.data.datastore.SettingsRepository
import com.summer.journal.data.remote.weather.City
import com.summer.journal.data.remote.weather.WeatherRepository
import com.summer.journal.domain.model.BackgroundScope
import com.summer.journal.domain.model.BackgroundTheme
import com.summer.journal.domain.model.WeatherSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 应用级 ViewModel：背景主题 + 天气。
 *
 * 这两样东西被多个页面共用（日历头图、天气卡、我的页），
 * 放在 Activity 级作用域里只维护一份，避免每个页面各拉一次天气。
 */
@HiltViewModel
class AppViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val weatherRepository: WeatherRepository,
    private val attachmentStore: AttachmentStore,
) : ViewModel() {

    val backgroundTheme: StateFlow<BackgroundTheme> = settings.backgroundTheme
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BackgroundTheme())

    val weather: StateFlow<WeatherSnapshot?> = weatherRepository.snapshot
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _weatherStatus = MutableStateFlow<WeatherStatus>(WeatherStatus.Loading)
    val weatherStatus: StateFlow<WeatherStatus> = _weatherStatus.asStateFlow()

    private val _cityName = MutableStateFlow<String?>(null)
    val cityName: StateFlow<String?> = _cityName.asStateFlow()

    init {
        refreshWeather()
    }

    /**
     * 天气刷新。
     *
     * 定位策略：
     *  1. 用户在设置里手选过城市 → 用那个（用户意图优先）
     *  2. 否则尝试定位 → 拿不到就进 NeedManualPick 状态，让 UI 引导手动选
     *  ★ 任何情况下都不使用「默认城市」—— 给错地方的天气比不给更糟。
     */
    fun refreshWeather(force: Boolean = false) {
        viewModelScope.launch {
            _weatherStatus.value = WeatherStatus.Loading

            val manual = settings.manualCity.first()
            val city: City? = if (manual != null) {
                City(manual.first, manual.second, manual.third)
            } else {
                weatherRepository.currentCity()
            }

            if (city == null) {
                // 定位不可用 / 没权限 / Geocoder 解析不出来
                _weatherStatus.value = WeatherStatus.NeedManualPick
                return@launch
            }

            _cityName.value = city.name
            // 先吐缓存，界面立刻有内容
            weatherRepository.loadCachedOnly(city)

            val ok = weatherRepository.refresh(city, force = force)
            _weatherStatus.value = if (ok || weatherRepository.snapshot.first() != null) {
                WeatherStatus.Ready
            } else {
                WeatherStatus.Failed
            }
        }
    }

    fun pickCity(city: City) {
        viewModelScope.launch {
            settings.setManualCity(city.name, city.latitude, city.longitude)
            _cityName.value = city.name
            val ok = weatherRepository.refresh(city, force = true)
            _weatherStatus.value = if (ok) WeatherStatus.Ready else WeatherStatus.Failed
        }
    }

    fun searchCities(keyword: String, onResult: (List<City>) -> Unit) {
        viewModelScope.launch {
            onResult(weatherRepository.searchCities(keyword))
        }
    }

    fun clearManualCity() {
        viewModelScope.launch {
            settings.clearManualCity()
            refreshWeather(force = true)
        }
    }

    /**
     * 背景图导入。
     *
     * 两条关键处理，都不是可选项：
     *  1. 先降采样再存 —— 直接把 4K 原图当背景，滑动列表时会明显掉帧甚至 OOM
     *  2. 复制到私有目录再记相对路径 —— 存 content:// URI 的话，
     *     用户撤销相册授权或删掉原图，背景就变成一片白
     */
    fun applyBackgroundImage(uri: android.net.Uri) {
        viewModelScope.launch {
            val relative = attachmentStore.importBackground(uri) ?: return@launch
            settings.setBackgroundImage(relative)
        }
    }

    fun clearBackgroundImage() {
        viewModelScope.launch { settings.setBackgroundImage(null) }
    }

    /** 应用范围：全部页面 / 仅日历页 / 跟随时间自动切换 */
    fun setBackgroundScope(scope: BackgroundScope) {
        viewModelScope.launch { settings.setBackgroundScope(scope) }
    }

    /**
     * 文字自动反色。
     * 开启后，直接画在背景上的文字（页面标题、区块标题）会跟着背景明暗切换深浅。
     */
    fun setAutoInvertText(enabled: Boolean) {
        viewModelScope.launch { settings.setAutoInvertText(enabled) }
    }

    fun setBackgroundBlur(dp: Int) {
        viewModelScope.launch { settings.setBackgroundBlur(dp) }
    }

    fun setBackgroundScrim(percent: Int) {
        viewModelScope.launch { settings.setBackgroundScrim(percent) }
    }

    fun resetBackground() {
        viewModelScope.launch { settings.resetBackground() }
    }

    sealed interface WeatherStatus {
        data object Loading : WeatherStatus

        /** 定位拿不到，需要用户手动选城市 —— 这是明确的状态，不是错误 */
        data object NeedManualPick : WeatherStatus
        data object Ready : WeatherStatus
        data object Failed : WeatherStatus
    }
}
