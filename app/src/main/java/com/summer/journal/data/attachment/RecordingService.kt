package com.summer.journal.data.attachment

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.File

/**
 * 录音前台服务。
 *
 * ★ 为什么必须是前台服务：
 *   录音属于「用户可感知的后台运行」，Android 10+ 起如果没有前台服务，
 *   一旦应用切后台，麦克风会被系统直接掐掉 —— 用户录到一半切去回消息，
 *   回来发现录音断了，这是最典型的差评来源。
 *
 * ★ 为什么用 MediaRecorder 而不是 Media3 Recorder：
 *   Media3 的 Recorder 更现代，但在部分国产 ROM 上存在编码器兼容问题；
 *   MediaRecorder 是平台 API，兼容性最稳。音频录制对延迟不敏感，稳比新重要。
 */
class RecordingService : Service() {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(
                path = intent.getStringExtra(EXTRA_OUTPUT_PATH).orEmpty(),
            )
            ACTION_STOP -> stopRecording()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startRecording(path: String) {
        if (recorder != null) return
        if (path.isBlank()) {
            stopSelf(); return
        }

        ensureChannel()

        // ★ 关键保护：Android 14+ 若未授予 RECORD_AUDIO 就调 startForeground(麦克风类型)，
        //   系统会抛 SecurityException。这个异常若直通主线程，进程会被直接杀掉 →
        //   用户看到的现象就是「一点录音 App 立刻退出」。
        //   所以这里必须整体兜住：起不来就干净退出，绝不崩。
        val foregroundOk = runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else 0,
            )
        }.isSuccess

        if (!foregroundOk) {
            stopSelf()
            return
        }

        runCatching {
            val file = File(path).apply { parentFile?.mkdirs() }
            outputFile = file

            recorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION") MediaRecorder()
            }).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                // 64kbps 单声道：语音日记够用，1 分钟约 480KB
                setAudioEncodingBitRate(64_000)
                setAudioSamplingRate(44_100)
                setAudioChannels(1)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
        }.onFailure {
            recorder?.release()
            recorder = null
            stopSelf()
        }
    }

    private fun stopRecording() {
        runCatching {
            recorder?.stop()
            recorder?.release()
        }
        recorder = null

        // 结果通过广播回传，调用方（ViewModel）负责落库
        val file = outputFile
        outputFile = null
        if (file != null && file.exists() && file.length() > 0) {
            sendBroadcast(
                Intent(ACTION_RECORD_DONE)
                    .setPackage(packageName)
                    .putExtra(EXTRA_OUTPUT_PATH, file.absolutePath)
                    .putExtra(EXTRA_DURATION_MS, durationMsHint(file)),
            )
        }

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** 时长为估算值；精确时长由播放器读元数据时校正 */
    private fun durationMsHint(file: File): Long = file.length() / 8_000L * 1_000L

    override fun onDestroy() {
        if (recorder != null) {
            runCatching { recorder?.stop() }
            recorder?.release()
            recorder = null
        }
        super.onDestroy()
    }

    private fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "正在录音",
            // 录音属于「正在进行的用户可感知任务」，LOW 就够，别用 HIGH 打扰用户
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "手札录音期间常驻"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("正在录音")
            .setContentText("返回 App 可停止并保存到手札")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    companion object {
        const val ACTION_START = "com.summer.journal.action.RECORD_START"
        const val ACTION_STOP = "com.summer.journal.action.RECORD_STOP"
        const val ACTION_RECORD_DONE = "com.summer.journal.action.RECORD_DONE"
        const val EXTRA_OUTPUT_PATH = "output_path"
        const val EXTRA_DURATION_MS = "duration_ms"

        private const val CHANNEL_ID = "channel_recording"
        private const val NOTIFICATION_ID = 2001

        fun start(context: Context, absolutePath: String) {
            context.startForegroundService(
                Intent(context, RecordingService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_OUTPUT_PATH, absolutePath),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, RecordingService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
