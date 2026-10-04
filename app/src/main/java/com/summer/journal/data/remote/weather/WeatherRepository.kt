package com.summer.journal.data.remote.weather

import com.summer.journal.data.local.dao.WeatherCacheDao
import com.summer.journal.data.local.entity.WeatherCacheEntity
import com.summer.journal.data.location.CityResolver
import com.summer.journal.domain.model.ScheduleEvent
import com.summer.journal.domain.model.WeatherImpact
import com.summer.journal.domain.model.WeatherSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * 天气仓储。
 *
 * 设计要点：
 * 1. **stale-while-revalidate**：先吐缓存（界面秒开），后台再刷新
 * 2. **任何失败都不抛给 UI**：失败 → 隐藏天气卡，日历页照常工作
 * 3. **不造数据**：拿不到就返回 null，绝不返回假的 26°
 * 4. **缓存 30 分钟**：Open-Meteo 免费，但也没必要高频轮询（遵守使用条款）
 */
@Singleton
class WeatherRepository @Inject constructor(
    private val cityResolver: CityResolver,
    @Named("forecast") private val forecastService: OpenMeteoService,
    @Named("air") private val airQualityService: OpenMeteoService,
    private val dao: WeatherCacheDao,
    @Named("appScope") private val appScope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()

    private val _snapshot = MutableStateFlow<WeatherSnapshot?>(null)

    /** UI 直接 collect 这个；初始为 null 表示「还没有任何数据」 */
    val snapshot: Flow<WeatherSnapshot?> = _snapshot.asStateFlow()

    companion object {
        private val CACHE_TTL: Duration = Duration.ofMinutes(30)
        private const val NETWORK_TIMEOUT_MS = 8_000L
    }

    /**
     * 拉取天气。多次调用会被 mutex 串行化，避免同一时刻发 3 组请求。
     * @param force true 时忽略缓存 TTL（下拉刷新用）
     * @return true 表示拿到了新数据
     */
    suspend fun refresh(city: City, force: Boolean = false): Boolean = mutex.withLock {
        val cached = readCache(city.key)

        if (!force && cached != null && isFresh(cached.fetchedAt)) {
            _snapshot.value = deserialize(cached.payloadJson)
            return@withLock true
        }

        // 先把旧数据推给 UI —— 用户立刻看到内容，而不是转圈
        cached?.let { _snapshot.value = deserialize(it.payloadJson) }

        val payload = withTimeoutOrNull(NETWORK_TIMEOUT_MS) { fetch(city) }
        return@withLock if (payload != null) {
            val snapshot = runCatching {
                WeatherMapper.toSnapshot(payload.city, payload.weather, payload.air)
            }.getOrNull()
            if (snapshot == null) {
                false
            } else {
                _snapshot.value = snapshot
                writeCache(city.key, payload)
                true
            }
        } else {
            // 失败：保留旧快照（可能为 null），不弹错，不崩溃
            false
        }
    }

    /** 冷启动用：只有缓存可用就先显示缓存 */
    suspend fun loadCachedOnly(city: City) {
        val cached = readCache(city.key) ?: return
        _snapshot.value = deserialize(cached.payloadJson)
    }

    /**
     * 并发请求两个域名。
     * 空气质量失败不应该拖垮主预报 —— 所以 air 是 nullable，weather 才是必需项。
     */
    private suspend fun fetch(city: City): WeatherPayload? = coroutineScope {
        val weatherDeferred = async { runCatching { forecastService.forecast(city.latitude, city.longitude) } }
        val airDeferred = async { runCatching { airQualityService.airQuality(city.latitude, city.longitude) } }

        val weather = weatherDeferred.await().getOrNull() ?: return@coroutineScope null
        val air = airDeferred.await().getOrNull()

        WeatherPayload(
            city = city,
            weather = weather,
            air = air,
            fetchedAtMillis = System.currentTimeMillis(),
        )
    }

    /* ────────────── 定位 ────────────── */

    /**
     * 定位 → 城市。**不返回任何默认城市**：
     * 取不到坐标或解析不出地名时返回 null，由 UI 引导用户手动选择。
     *
     * 为什么不给默认值：预设一个城市意味着可能给用户看错地方的天气，
     * 这比「暂时没有天气」糟糕得多。
     */
    suspend fun currentCity(): City? = cityResolver.resolveCurrentCity()

    /** 手动搜索城市：Open-Meteo Geocoding，免 Key。定位不可用时的唯一出路 */
    suspend fun searchCities(keyword: String): List<City> = cityResolver.search(keyword)

    /* ────────────── 天气 × 日程 联动 ────────────── */

    /**
     * 「受天气影响的日程」——纯计算，放在 Domain 逻辑里，Repository 只做转发。
     * 这是天气模块真正的价值点：不是让你看天气，而是告诉你日程要不要改。
     */
    fun impactsFor(events: List<ScheduleEvent>, snapshot: WeatherSnapshot?): List<WeatherImpact> {
        val weather = snapshot ?: return emptyList()
        return events.mapNotNull { event ->
            val day = weather.daily.firstOrNull { it.date == event.startAt.toLocalDate() }
                ?: return@mapNotNull null

            val probability = day.precipitationProbability
            when {
                probability >= 50 && event.isOutdoor -> WeatherImpact(
                    event = event,
                    message = "${day.date.monthValue}日降水 $probability% · 建议改室内",
                    affected = true,
                )
                probability >= 50 -> WeatherImpact(
                    event = event,
                    message = "当天有雨，记得带伞",
                    affected = false,
                )
                day.maxTemperature >= 35 && event.isOutdoor -> WeatherImpact(
                    event = event,
                    message = "高温 ${day.maxTemperature.toInt()}° · 建议避开正午",
                    affected = true,
                )
                else -> null
            }
        }
    }

    /* ────────────── 缓存 ────────────── */

    private suspend fun readCache(key: String): WeatherCacheEntity? =
        runCatching { dao.find(key) }.getOrNull()

    private suspend fun writeCache(key: String, payload: WeatherPayload) {
        runCatching {
            dao.upsert(
                WeatherCacheEntity(
                    cityKey = key,
                    payloadJson = json.encodeToString(WeatherPayload.serializer(), payload),
                    fetchedAt = payload.fetchedAtMillis,
                )
            )
        }
    }

    private fun isFresh(fetchedAt: Long): Boolean =
        System.currentTimeMillis() - fetchedAt < CACHE_TTL.toMillis()

    /** 缓存里是原始 DTO，读出来再映射一次 —— 避免为 java.time 写自定义 Serializer */
    private fun deserialize(raw: String): WeatherSnapshot? = runCatching {
        val payload = json.decodeFromString(WeatherPayload.serializer(), raw)
        WeatherMapper.toSnapshot(payload.city, payload.weather, payload.air)
    }.getOrNull()
}
