package com.summer.journal.core.di

import android.content.Context
import androidx.room.Room
import com.summer.journal.data.local.AppDatabase
import com.summer.journal.data.remote.weather.OpenMeteoService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /* ────────────── 协程作用域 ────────────── */

    /**
     * 应用级作用域。
     * 用 SupervisorJob：一个子任务失败不该连带取消其他任务（比如天气刷新失败不该影响附件清理）。
     */
    @Provides
    @Singleton
    @Named("appScope")
    fun provideAppScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /* ────────────── 数据库 ────────────── */

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            .apply {
                if (com.summer.journal.BuildConfig.DEBUG) {
                    // 开发期：改了表结构直接重建库，省得每改一次都写迁移。
                    // 代价是本地测试数据会没 —— 这在开发时是可以接受的。
                    fallbackToDestructiveMigration()
                } else {
                    // 正式包：**只认显式迁移**。
                    // 漏写迁移会直接抛异常崩溃 —— 崩溃远好过静默清空用户一学期的课表。
                    addMigrations(*AppDatabase.MIGRATIONS)
                }
            }
            .build()

    @Provides fun provideSemesterDao(db: AppDatabase) = db.semesterDao()
    @Provides fun provideCourseDao(db: AppDatabase) = db.courseDao()
    @Provides fun provideScheduleEventDao(db: AppDatabase) = db.scheduleEventDao()
    @Provides fun provideMemoDao(db: AppDatabase) = db.memoDao()
    @Provides fun provideAttachmentDao(db: AppDatabase) = db.attachmentDao()
    @Provides fun provideWeatherCacheDao(db: AppDatabase) = db.weatherCacheDao()

    /* ────────────── 网络 ────────────── */

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true          // Open-Meteo 字段可能增减，别因为多一个字段就崩
        explicitNulls = false
        encodeDefaults = true
    }

    @Provides
    @Singleton
    fun provideOkHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        // 天气接口免 Key，不需要任何 auth 拦截器 —— 这也是选它的原因之一
        .addInterceptor(
            HttpLoggingInterceptor().apply {
                level = if (com.summer.journal.BuildConfig.DEBUG) {
                    HttpLoggingInterceptor.Level.BASIC
                } else {
                    HttpLoggingInterceptor.Level.NONE
                }
            },
        )
        .build()

    private fun retrofit(baseUrl: String, client: OkHttpClient, json: Json): Retrofit =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides
    @Singleton
    @Named("forecast")
    fun provideForecastService(client: OkHttpClient, json: Json): OpenMeteoService =
        retrofit(OpenMeteoService.FORECAST_BASE, client, json).create(OpenMeteoService::class.java)

    @Provides
    @Singleton
    @Named("air")
    fun provideAirService(client: OkHttpClient, json: Json): OpenMeteoService =
        retrofit(OpenMeteoService.AIR_BASE, client, json).create(OpenMeteoService::class.java)
}
