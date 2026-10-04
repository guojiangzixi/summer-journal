package com.summer.journal

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import com.summer.journal.reminder.ReminderScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class SummerJournalApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()

        // 通知渠道必须在发第一条通知之前建好。
        // 渠道一旦以某个重要性创建，之后只能由用户改 —— 所以重要性设 HIGH，别设 DEFAULT。
        ReminderScheduler.ensureChannels(this)

        // ★ 按需初始化 WorkManager（配套 AndroidManifest 里移除 WorkManagerInitializer 的那段）。
        //   这样 HiltWorkerFactory 才会生效 —— 否则 Worker 里的 @Inject 全是 null，
        //   表现为「开机后不重排提醒」「附件清理不执行」这类静默失效。
        WorkManager.initialize(this, workManagerConfiguration)
    }

    /**
     * WorkManager 的配置：注入 HiltWorkerFactory，让 Worker 支持 @Inject。
     * 用途：天气定时刷新、孤儿附件清理、开机后重排提醒。
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(
                if (BuildConfig.DEBUG) android.util.Log.DEBUG else android.util.Log.INFO
            )
            .build()
}
