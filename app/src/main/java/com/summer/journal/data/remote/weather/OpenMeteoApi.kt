package com.summer.journal.data.remote.weather

import com.summer.journal.domain.model.AirQuality
import com.summer.journal.domain.model.CurrentWeather
import com.summer.journal.domain.model.DayWeather
import com.summer.journal.domain.model.HourWeather
import com.summer.journal.domain.model.LifeIndex
import com.summer.journal.domain.model.SkyCondition
import com.summer.journal.domain.model.WeatherSnapshot
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/* ══════════════════════════════════════════════════════════════════════
   Open-Meteo —— 完全免费、免注册、免 API Key、免签名
   一次 GET 就能拿到 current + hourly + daily 全部字段。
   文档：https://open-meteo.com/en/docs
   ══════════════════════════════════════════════════════════════════════ */

@Serializable
data class OpenMeteoResponse(
    val latitude: Double,
    val longitude: Double,
    val timezone: String = "Asia/Shanghai",
    val current: CurrentDto? = null,
    val hourly: HourlyDto? = null,
    val daily: DailyDto? = null,
) {
    @Serializable
    data class CurrentDto(
        val time: String,
        @SerialName("temperature_2m") val temperature: Double,
        @SerialName("relative_humidity_2m") val humidity: Int,
        @SerialName("apparent_temperature") val apparentTemperature: Double,
        @SerialName("is_day") val isDay: Int = 1,
        val precipitation: Double = 0.0,
        @SerialName("weather_code") val weatherCode: Int,
        @SerialName("wind_speed_10m") val windSpeed: Double = 0.0,
        @SerialName("wind_direction_10m") val windDirection: Double = 0.0,
    )

    @Serializable
    data class HourlyDto(
        val time: List<String> = emptyList(),
        @SerialName("temperature_2m") val temperature: List<Double> = emptyList(),
        @SerialName("precipitation_probability") val precipitationProbability: List<Int> = emptyList(),
        @SerialName("weather_code") val weatherCode: List<Int> = emptyList(),
        @SerialName("uv_index") val uvIndex: List<Double> = emptyList(),
    )

    @Serializable
    data class DailyDto(
        val time: List<String> = emptyList(),
        @SerialName("weather_code") val weatherCode: List<Int> = emptyList(),
        @SerialName("temperature_2m_max") val temperatureMax: List<Double> = emptyList(),
        @SerialName("temperature_2m_min") val temperatureMin: List<Double> = emptyList(),
        @SerialName("precipitation_probability_max") val precipitationProbabilityMax: List<Int> = emptyList(),
        @SerialName("uv_index_max") val uvIndexMax: List<Double> = emptyList(),
        val sunrise: List<String> = emptyList(),
        val sunset: List<String> = emptyList(),
    )
}

@Serializable
data class OpenMeteoAirResponse(
    val latitude: Double,
    val longitude: Double,
    val current: CurrentDto? = null,
) {
    @Serializable
    data class CurrentDto(
        @SerialName("pm2_5") val pm25: Double = 0.0,
        val pm10: Double = 0.0,
        val ozone: Double = 0.0,
        @SerialName("nitrogen_dioxide") val nitrogenDioxide: Double = 0.0,
        @SerialName("european_aqi") val europeanAqi: Int = 0,
    )
}

/* ══════════════════════════════════════════════════════════════════════
   DTO → Domain 映射
   放在这里而不是 Repository，是为了让 Repository 只管「策略」（缓存/降级）
   ══════════════════════════════════════════════════════════════════════ */

object WeatherMapper {

    private val zone: ZoneId get() = ZoneId.of("Asia/Shanghai")

    fun toSnapshot(
        city: City,
        weather: OpenMeteoResponse,
        air: OpenMeteoAirResponse?,
        now: LocalDateTime = LocalDateTime.now(),
    ): WeatherSnapshot {
        val current = weather.current?.let {
            CurrentWeather(
                temperature = it.temperature,
                apparentTemperature = it.apparentTemperature,
                humidity = it.humidity,
                precipitation = it.precipitation,
                windSpeed = it.windSpeed,
                windDirection = it.windDirection,
                isDay = it.isDay == 1,
                condition = WeatherCode.toCondition(it.weatherCode),
                description = WeatherCode.toDescription(it.weatherCode),
            )
        } ?: error("Open-Meteo 返回缺少 current 字段")

        val hourly = weather.hourly?.let { h ->
            h.time.indices.mapNotNull { i ->
                runCatching {
                    HourWeather(
                        time = LocalDateTime.parse(h.time[i]),
                        temperature = h.temperature.getOrElse(i) { 0.0 },
                        precipitationProbability = h.precipitationProbability.getOrElse(i) { 0 },
                        condition = WeatherCode.toCondition(h.weatherCode.getOrElse(i) { 0 }),
                    )
                }.getOrNull()
            }
        } ?: emptyList()

        val daily = weather.daily?.let { d ->
            d.time.indices.mapNotNull { i ->
                runCatching {
                    DayWeather(
                        date = LocalDate.parse(d.time[i]),
                        minTemperature = d.temperatureMin.getOrElse(i) { 0.0 },
                        maxTemperature = d.temperatureMax.getOrElse(i) { 0.0 },
                        precipitationProbability = d.precipitationProbabilityMax.getOrElse(i) { 0 },
                        uvIndexMax = d.uvIndexMax.getOrElse(i) { 0.0 },
                        condition = WeatherCode.toCondition(d.weatherCode.getOrElse(i) { 0 }),
                        sunrise = d.sunrise.getOrNull(i)?.let { LocalDateTime.parse(it).toLocalTime() },
                        sunset = d.sunset.getOrNull(i)?.let { LocalDateTime.parse(it).toLocalTime() },
                    )
                }.getOrNull()
            }
        } ?: emptyList()

        val airQuality = air?.current?.let {
            AirQuality(
                europeanAqi = it.europeanAqi,
                pm25 = it.pm25,
                pm10 = it.pm10,
                ozone = it.ozone,
                nitrogenDioxide = it.nitrogenDioxide,
            )
        }

        // ★ 生活指数本地推算，不依赖任何第三方「生活指数」接口
        val today = daily.firstOrNull()
        val indices = today?.let {
            LifeIndex.compute(
                uvIndexMax = it.uvIndexMax,
                apparentTemp = current.apparentTemperature,
                precipProbability = it.precipitationProbability,
                humidity = current.humidity,
                dayNightDelta = it.maxTemperature - it.minTemperature,
                aqi = airQuality?.europeanAqi ?: 0,
            )
        }

        return WeatherSnapshot(
            cityName = city.name,
            latitude = weather.latitude,
            longitude = weather.longitude,
            current = current,
            hourly = hourly,
            daily = daily,
            air = airQuality,
            indices = indices,
            fetchedAt = now,
        )
    }
}

/**
 * 一个地点。
 *
 * ★ 这里**没有、也不应该有**硬编码的城市表。
 *   城市名由系统定位 + 系统 Geocoder 解析得到（见 CityResolver），
 *   或者由用户在设置里手动搜索选择。App 不预设任何「默认城市」——
 *   预设城市意味着给错地方的天气，比不给更糟。
 */
@Serializable
data class City(
    val name: String,
    val latitude: Double,
    val longitude: Double,
) {
    /** 缓存键：用坐标而不是城市名，避免「长沙」和「长沙市」被当成两个城市 */
    val key: String get() = "%.4f,%.4f".format(latitude, longitude)
}

/**
 * 缓存信封。
 *
 * ★ 刻意缓存「原始 DTO」而不是「映射后的 domain 对象」：
 *   domain 里用了 java.time.LocalDate / LocalDateTime，kotlinx.serialization
 *   默认不支持，要为它们各写一个 Serializer，不值得。
 *   DTO 全是 String / Double / Int，直接可序列化，读出来再映射一次，成本极低。
 */
@Serializable
data class WeatherPayload(
    val city: City,
    val weather: OpenMeteoResponse,
    val air: OpenMeteoAirResponse? = null,
    val fetchedAtMillis: Long,
)
