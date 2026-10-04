package com.summer.journal.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.summer.journal.data.remote.weather.City
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * 地点解析器。
 *
 * ★ 核心设计：**不内置任何城市表**。
 *   预设城市 = 大概率给错地方的天气，比不给更糟。
 *   所以城市名只有两个来源：
 *     ① 定位 → 反向地理编码（系统 Geocoder，免 Key 免费用）
 *     ② 用户手动搜索 → Open-Meteo Geocoding API（同样免 Key 免费用）
 *   两条都拿不到时，UI 明确提示「请手动选择城市」，绝不用默认值顶替。
 */
@Singleton
class CityResolver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
) {

    private val json = Json { ignoreUnknownKeys = true }

    /* ══════════════════════════════════════════════════════════════
       ① 定位 → 坐标
       ══════════════════════════════════════════════════════════════ */

    /**
     * 取一次「最近一次已知位置」。
     *
     * 为什么不用 requestLocationUpdates / FusedLocationProvider：
     *  - lastKnownLocation 是同步的、零耗电的，对「看一眼天气」这个场景完全够
     *  - FusedLocationProvider 依赖 Google Play 服务，国内部分机型没有 GMS
     *  - 持续定位需要前台服务 + 常驻通知，为天气付这个代价不值得
     */
    @SuppressLint("MissingPermission")
    fun lastKnownCoordinate(): Coordinate? {
        if (!hasLocationPermission()) return null

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
            LocationManager.GPS_PROVIDER,
        )
        return providers
            .mapNotNull { provider -> runCatching { lm.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull(Location::getTime)
            ?.let { Coordinate(it.latitude, it.longitude) }
    }

    /**
     * 主动要一次「当前」位置（异步，6 秒超时）。
     *
     * 为什么需要它：`getLastKnownLocation` 在新机器 / 刚装完 App 时经常是 null
     * （系统还没缓存过任何定位）。此时用户明明授权了，天气却仍显示「打开定位」，
     * 体验上等同于「定位坏了」。
     *
     * 为什么只在 API 30+ 用：`LocationManager.getCurrentLocation` 是 API 30 才有的 API。
     * 低版本继续只用系统缓存位置 —— 不为天气在老机型上引入不稳定代码路径。
     * 全程走系统 LocationManager，**不依赖 GMS**，照顾国产无 Google 服务机型。
     */
    @SuppressLint("MissingPermission")
    suspend fun requestCurrentCoordinate(): Coordinate? {
        if (!hasLocationPermission()) return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null

        val providers = buildList {
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                add(LocationManager.NETWORK_PROVIDER)
            }
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                add(LocationManager.GPS_PROVIDER)
            }
        }
        if (providers.isEmpty()) return null

        return withTimeoutOrNull(CURRENT_LOCATION_TIMEOUT_MS) {
            suspendCancellableCoroutine<Coordinate?> { cont ->
                var resumed = false
                // 先网络后 GPS：网络定位更快，室内也能用；GPS 更准但慢
                val provider = providers.first()
                runCatching {
                    lm.getCurrentLocation(provider, null, context.mainExecutor) { location ->
                        if (!resumed) {
                            resumed = true
                            cont.resume(location?.let { Coordinate(it.latitude, it.longitude) })
                        }
                    }
                }.onFailure {
                    if (!resumed) {
                        resumed = true
                        cont.resume(null)
                    }
                }
            }
        }
    }

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /* ══════════════════════════════════════════════════════════════
       ② 坐标 → 城市名（系统 Geocoder）
       ══════════════════════════════════════════════════════════════ */

    /**
     * 反向地理编码。返回 null 时**不要**编一个城市名出来，
     * 让 UI 显示「当前位置」并提示用户手动选城市。
     */
    suspend fun resolveName(coordinate: Coordinate): String? {
        if (!Geocoder.isPresent()) return null

        val geocoder = Geocoder(context, Locale.CHINA)
        val address = withTimeoutOrNull(GEOCODE_TIMEOUT_MS) {
            runCatching { geocoder.getFromLocationBlocking(coordinate) }.getOrNull()
        } ?: return null

        // 优先「市 + 区」，够精确也不啰嗦：如「长沙市·岳麓区」
        val city = address.locality ?: address.subAdminArea ?: address.adminArea
        val district = address.subLocality ?: address.featureName
        return when {
            city != null && district != null && district != city -> "$city · $district"
            city != null -> city
            else -> address.getAddressLine(0)
        }?.replace("市 · ", " · ")   // 「长沙市 · 岳麓区」→「长沙 · 岳麓区」
    }

    @Suppress("DEPRECATION")
    private suspend fun Geocoder.getFromLocationBlocking(c: Coordinate): Address? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { cont ->
                getFromLocation(c.latitude, c.longitude, 1) { list ->
                    cont.resume(list.firstOrNull())
                }
            }
        } else {
            withContext(Dispatchers.IO) {
                @Suppress("UNCHECKED_CAST")
                (getFromLocation(c.latitude, c.longitude, 1) as? List<Address>)?.firstOrNull()
            }
        }

    /**
     * 定位 → City 的完整链路。
     *
     * 顺序：先读系统缓存位置（零耗电、瞬时）；拿不到再主动请求一次实时定位。
     * 两者都失败才返回 null —— 交给 UI 引导手动选择城市。
     */
    suspend fun resolveCurrentCity(): City? {
        val coordinate = lastKnownCoordinate() ?: requestCurrentCoordinate() ?: return null
        val name = resolveName(coordinate) ?: return null
        return City(name = name, latitude = coordinate.latitude, longitude = coordinate.longitude)
    }

    /* ══════════════════════════════════════════════════════════════
       ③ 手动搜索城市（Open-Meteo Geocoding，免 Key）
       ══════════════════════════════════════════════════════════════ */

    /**
     * GET https://geocoding-api.open-meteo.com/v1/search?name=长沙&count=8&language=zh&format=json
     * 同样免注册、免 API Key。
     */
    suspend fun search(keyword: String): List<City> {
        val query = keyword.trim()
        if (query.length < 1) return emptyList()

        return withContext(Dispatchers.IO) {
            runCatching {
                val url = "$GEOCODING_BASE?name=${query.urlEncoded()}" +
                    "&count=8&language=zh&format=json"
                val request = Request.Builder().url(url).build()
                val body = okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    response.body?.string()
                } ?: return@runCatching emptyList()

                json.decodeFromString(GeocodingResponse.serializer(), body)
                    .results
                    .orEmpty()
                    .map { it.toCity() }
            }.getOrDefault(emptyList())
        }
    }

    @Serializable
    private data class GeocodingResponse(
        val results: List<GeocodingResult>? = null,
    )

    @Serializable
    private data class GeocodingResult(
        val name: String,
        val latitude: Double,
        val longitude: Double,
        val country: String? = null,
        @SerialName("admin1") val admin1: String? = null,
        @SerialName("admin2") val admin2: String? = null,
    ) {
        fun toCity(): City {
            // 只保留「市 · 区」层级，不要太长
            val region = admin2 ?: admin1
            val label = if (region != null && region != name) "$name · $region" else name
            return City(name = label, latitude = latitude, longitude = longitude)
        }
    }

    private fun String.urlEncoded(): String =
        java.net.URLEncoder.encode(this, Charsets.UTF_8.name())

    data class Coordinate(val latitude: Double, val longitude: Double)

    companion object {
        private const val GEOCODING_BASE = "https://geocoding-api.open-meteo.com/v1/search"
        private const val GEOCODE_TIMEOUT_MS = 4_000L
        private const val CURRENT_LOCATION_TIMEOUT_MS = 6_000L
    }
}
