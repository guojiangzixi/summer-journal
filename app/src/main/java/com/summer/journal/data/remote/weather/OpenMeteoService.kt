package com.summer.journal.data.remote.weather

import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Open-Meteo 的两个端点。
 *
 * ★ 不需要 API Key、不需要 Authorization header、不需要签名串。
 *   这也是选它的核心理由：APK 里不用塞任何密钥，不存在被反编译盗用的问题。
 */
interface OpenMeteoService {

    /** 天气预报：一次请求拿到 current + hourly + daily */
    @GET("v1/forecast")
    suspend fun forecast(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("current") current: String = CURRENT_FIELDS,
        @Query("hourly") hourly: String = HOURLY_FIELDS,
        @Query("daily") daily: String = DAILY_FIELDS,
        @Query("timezone") timezone: String = "Asia/Shanghai",
        @Query("forecast_days") forecastDays: Int = 7,
        // 免 Key 的代价：要遵守使用条款，非商业用途 + 别做高频轮询
        @Query("wind_speed_unit") windSpeedUnit: String = "ms",
    ): OpenMeteoResponse

    /** 空气质量（独立域名，同样免 Key） */
    @GET("v1/air-quality")
    suspend fun airQuality(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("current") current: String = AIR_FIELDS,
        @Query("timezone") timezone: String = "Asia/Shanghai",
    ): OpenMeteoAirResponse

    companion object {
        const val FORECAST_BASE = "https://api.open-meteo.com/"
        const val AIR_BASE = "https://air-quality-api.open-meteo.com/"

        private const val CURRENT_FIELDS =
            "temperature_2m,relative_humidity_2m,apparent_temperature,is_day," +
                "precipitation,weather_code,wind_speed_10m,wind_direction_10m"

        private const val HOURLY_FIELDS =
            "temperature_2m,precipitation_probability,weather_code,uv_index"

        private const val DAILY_FIELDS =
            "weather_code,temperature_2m_max,temperature_2m_min," +
                "precipitation_probability_max,uv_index_max,sunrise,sunset"

        private const val AIR_FIELDS =
            "pm2_5,pm10,ozone,nitrogen_dioxide,european_aqi"
    }
}
