package com.summer.journal.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.summer.journal.domain.model.BackgroundScope
import com.summer.journal.domain.model.BackgroundTheme
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * 设置持久化。
 *
 * 为什么用 DataStore 而不是 SharedPreferences：
 *  · 异步、不会在主线程阻塞启动
 *  · 有 Flow，改设置能自动驱动 UI 重组（背景色一改，所有页面立刻响应）
 *  · 类型安全，不用到处 getString 再判空
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private object Keys {
        val SHOW_HOLIDAY = booleanPreferencesKey("show_holiday")
        val SHOW_LUNAR = booleanPreferencesKey("show_lunar")
        val WEEK_START_MONDAY = booleanPreferencesKey("week_start_monday")
        val DEFAULT_REMINDER_MINUTES = intPreferencesKey("default_reminder_minutes")
        val CLASS_REMINDER_MINUTES = intPreferencesKey("class_reminder_minutes")
        val TIMETABLE_MODE = stringPreferencesKey("timetable_mode")
        val CURRENT_SEMESTER_ID = stringPreferencesKey("current_semester_id")
        val CITY_NAME = stringPreferencesKey("city_name")
        val CITY_LAT = stringPreferencesKey("city_lat")
        val CITY_LON = stringPreferencesKey("city_lon")

        // 背景主题
        val BG_COLOR_KEY = stringPreferencesKey("bg_color_key")
        val BG_IMAGE_PATH = stringPreferencesKey("bg_image_path")
        val BG_BLUR_DP = intPreferencesKey("bg_blur_dp")
        val BG_SCRIM = intPreferencesKey("bg_scrim_percent")
        val BG_SCOPE = stringPreferencesKey("bg_scope")
        val BG_AUTO_INVERT = booleanPreferencesKey("bg_auto_invert")
    }

    val showHoliday: Flow<Boolean> = context.dataStore.data.map { it[Keys.SHOW_HOLIDAY] ?: true }
    val showLunar: Flow<Boolean> = context.dataStore.data.map { it[Keys.SHOW_LUNAR] ?: true }
    val weekStartMonday: Flow<Boolean> = context.dataStore.data.map { it[Keys.WEEK_START_MONDAY] ?: false }
    val defaultReminderMinutes: Flow<Int> =
        context.dataStore.data.map { it[Keys.DEFAULT_REMINDER_MINUTES] ?: 30 }
    val classReminderMinutes: Flow<Int> =
        context.dataStore.data.map { it[Keys.CLASS_REMINDER_MINUTES] ?: 10 }
    val timetableMode: Flow<String> =
        context.dataStore.data.map { it[Keys.TIMETABLE_MODE] ?: "WEEKDAYS_ONLY" }
    val currentSemesterId: Flow<Long?> =
        context.dataStore.data.map { it[Keys.CURRENT_SEMESTER_ID]?.toLongOrNull() }

    /** 背景主题：一次读完，UI 只需要 collect 这一个 Flow */
    val backgroundTheme: Flow<BackgroundTheme> = context.dataStore.data.map { prefs ->
        BackgroundTheme(
            colorKey = prefs[Keys.BG_COLOR_KEY] ?: "WARM_WHITE",
            imageRelativePath = prefs[Keys.BG_IMAGE_PATH],
            blurDp = prefs[Keys.BG_BLUR_DP] ?: 8,
            scrimAlpha = (prefs[Keys.BG_SCRIM] ?: 28) / 100f,
            scope = runCatching {
                BackgroundScope.valueOf(prefs[Keys.BG_SCOPE] ?: BackgroundScope.ALL_PAGES.name)
            }.getOrDefault(BackgroundScope.ALL_PAGES),
            autoInvertText = prefs[Keys.BG_AUTO_INVERT] ?: true,
        )
    }

    suspend fun setShowHoliday(value: Boolean) =
        context.dataStore.edit { it[Keys.SHOW_HOLIDAY] = value }

    suspend fun setShowLunar(value: Boolean) =
        context.dataStore.edit { it[Keys.SHOW_LUNAR] = value }

    suspend fun setWeekStartMonday(value: Boolean) =
        context.dataStore.edit { it[Keys.WEEK_START_MONDAY] = value }

    suspend fun setDefaultReminderMinutes(minutes: Int) =
        context.dataStore.edit { it[Keys.DEFAULT_REMINDER_MINUTES] = minutes }

    suspend fun setClassReminderMinutes(minutes: Int) =
        context.dataStore.edit { it[Keys.CLASS_REMINDER_MINUTES] = minutes }

    suspend fun setTimetableMode(mode: String) =
        context.dataStore.edit { it[Keys.TIMETABLE_MODE] = mode }

    suspend fun setCurrentSemesterId(id: Long) =
        context.dataStore.edit { it[Keys.CURRENT_SEMESTER_ID] = id.toString() }

    /**
     * 记住用户手选的城市。
     * 注意：**只在用户手动选择时写入**，定位结果不写 ——
     * 否则定位一失败就会用「上次那个城市」，跨城之后就错了。
     */
    suspend fun setManualCity(name: String, lat: Double, lon: Double) =
        context.dataStore.edit {
            it[Keys.CITY_NAME] = name
            it[Keys.CITY_LAT] = lat.toString()
            it[Keys.CITY_LON] = lon.toString()
        }

    val manualCity: Flow<Triple<String, Double, Double>?> = context.dataStore.data.map { prefs ->
        val name = prefs[Keys.CITY_NAME] ?: return@map null
        val lat = prefs[Keys.CITY_LAT]?.toDoubleOrNull() ?: return@map null
        val lon = prefs[Keys.CITY_LON]?.toDoubleOrNull() ?: return@map null
        Triple(name, lat, lon)
    }

    suspend fun clearManualCity() = context.dataStore.edit {
        it.remove(Keys.CITY_NAME)
        it.remove(Keys.CITY_LAT)
        it.remove(Keys.CITY_LON)
    }

    /* ────────────── 背景主题 ────────────── */

    suspend fun setBackgroundColor(key: String) =
        context.dataStore.edit { it[Keys.BG_COLOR_KEY] = key }

    suspend fun setBackgroundImage(relativePath: String?) = context.dataStore.edit { prefs ->
        if (relativePath == null) prefs.remove(Keys.BG_IMAGE_PATH)
        else prefs[Keys.BG_IMAGE_PATH] = relativePath
    }

    suspend fun setBackgroundBlur(dp: Int) =
        context.dataStore.edit { it[Keys.BG_BLUR_DP] = dp.coerceIn(0, 40) }

    suspend fun setBackgroundScrim(percent: Int) =
        context.dataStore.edit { it[Keys.BG_SCRIM] = percent.coerceIn(0, 80) }

    suspend fun setBackgroundScope(scope: BackgroundScope) =
        context.dataStore.edit { it[Keys.BG_SCOPE] = scope.name }

    suspend fun setAutoInvertText(value: Boolean) =
        context.dataStore.edit { it[Keys.BG_AUTO_INVERT] = value }

    suspend fun resetBackground() = context.dataStore.edit { prefs ->
        prefs.remove(Keys.BG_COLOR_KEY)
        prefs.remove(Keys.BG_IMAGE_PATH)
        prefs.remove(Keys.BG_BLUR_DP)
        prefs.remove(Keys.BG_SCRIM)
        prefs.remove(Keys.BG_SCOPE)
        prefs.remove(Keys.BG_AUTO_INVERT)
    }
}
