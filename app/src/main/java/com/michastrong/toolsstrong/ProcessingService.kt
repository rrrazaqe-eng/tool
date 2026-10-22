package com.michastrong.toolsstrong

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

class ProcessingService : Service() {
    companion object {
        const val ACTION_START = "com.michastrong.toolsstrong.START"
        const val ACTION_CANCEL = "com.michastrong.toolsstrong.CANCEL"
        const val ACTION_STATE = "com.michastrong.toolsstrong.STATE"

        const val EXTRA_TYPE = "type"
        const val EXTRA_INPUT = "input"
        const val EXTRA_SECOND_INPUT = "secondInput"
        const val EXTRA_NAME = "name"
        const val EXTRA_DURATION = "duration"
        const val EXTRA_SIZE = "size"
        const val EXTRA_START_MS = "startMs"
        const val EXTRA_END_MS = "endMs"
        const val EXTRA_PRESET = "preset"
        const val EXTRA_TARGET_MB = "targetMb"
        const val EXTRA_CODEC = "codec"
        const val EXTRA_RESOLUTION = "resolution"
        const val EXTRA_AUDIO_FORMAT = "audioFormat"
        const val EXTRA_AUDIO_BITRATE = "audioBitrate"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_TITLE = "title"
        const val EXTRA_AUTHOR = "author"
        const val EXTRA_WAVE = "wave"
        const val EXTRA_ROTATE = "rotate"
        const val EXTRA_CROP = "crop"
        const val EXTRA_IMAGE_FORMAT = "imageFormat"
        const val EXTRA_IMAGE_QUALITY = "imageQuality"

        @Volatile var isRunning: Boolean = false
            private set

        private const val CHANNEL = "tools_strong_processing"
        private const val NOTIFICATION_ID = 4107

        fun start(context: android.content.Context, intent: Intent) {
            intent.setClass(context, ProcessingService::class.java).action = ACTION_START
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        fun cancel(context: android.content.Context) {
            val i = Intent(context, ProcessingService::class.java).setAction(ACTION_CANCEL)
            context.startService(i)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateLock = Any()
    private var work: Job? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    @Volatile private var ffmpegSession: FFmpegSession? = null
    private val cancelled = AtomicBoolean(false)
    private val terminal = AtomicBoolean(false)
    private var lastProgressAt = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (BuildConfig.DEBUG) android.util.Log.d("ToolsStrong", "Service action=${intent?.action}, running=$isRunning, terminal=${terminal.get()}")
        when (intent?.action) {
            ACTION_CANCEL -> cancelProcessing("Обработка отменена")
            ACTION_START -> if (!isRunning && !terminal.get() && work?.isCompleted != false) runJob(intent)
                else {
                    android.widget.Toast.makeText(this, "Предыдущая операция ещё завершается", android.widget.Toast.LENGTH_SHORT).show()
                    if (terminal.get()) stopSelf(startId)
                }
        }
        return START_NOT_STICKY
    }

    private fun cancelProcessing(message: String) {
        synchronized(stateLock) {
            if (terminal.getAndSet(true)) { stopSelf(); return }
            cancelled.set(true)
            publish(JobStore.load(this).copy(running = false, cancelled = true, message = message))
        }
        ffmpegSession?.let { FFmpegKit.cancel(it.sessionId) }
        work?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        cancelProcessing("Достигнут системный лимит фоновой обработки. Попробуйте более короткий файл")
    }

    private fun runJob(intent: Intent) {
        cancelled.set(false)
        val type = runCatching { JobType.valueOf(intent.getStringExtra(EXTRA_TYPE) ?: "") }.getOrNull()
        val inputUri = intent.getStringExtra(EXTRA_INPUT)?.let(Uri::parse)
        if (type == null || inputUri == null) { stopSelf(); return }
        val name = intent.getStringExtra(EXTRA_NAME) ?: "file"
        val durationMs = intent.getLongExtra(EXTRA_DURATION, 0L)
        val originalSize = intent.getLongExtra(EXTRA_SIZE, 0L)
        val initial = JobUiState(
            running = true, title = titleFor(type), message = "Подготовка файла…",
            originalUri = inputUri.toString(), originalName = name, originalSize = originalSize,
        )
        isRunning = true
        publish(initial)
        // mediaProcessing was introduced in API 35; earlier devices use dataSync.
        try {
            // minSdk 29 supports the platform overload; ServiceCompat masks out mediaProcessing.
            startForeground(NOTIFICATION_ID, makeNotification(initial),
                if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (error: Exception) {
            android.util.Log.e("ToolsStrong", "Foreground service start failed", error)
            terminal.set(true)
            isRunning = false
            publish(initial.copy(running = false, message = "Не удалось запустить фоновую обработку"))
            stopSelf()
            return
        }

        wakeLock = getSystemService(android.os.PowerManager::class.java)
            .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "ToolsStrong:processing").apply {
                setReferenceCounted(false)
                acquire(6 * 60 * 60 * 1000L)
            }
        work = scope.launch {
            var localInput: File? = null
            var localSecond: File? = null
            var localOutput: File? = null
            try {
                if (type == JobType.IMAGE_TO_PDF || type == JobType.IMAGE_CONVERT) {
                    progress(15, "Обработка изображения…")
                    val output = if (type == JobType.IMAGE_TO_PDF) MediaUtils.imageToPdf(this@ProcessingService, inputUri, name)
                    else MediaUtils.convertImage(this@ProcessingService, inputUri, name,
                        intent.getStringExtra(EXTRA_IMAGE_FORMAT) ?: "JPG", intent.getIntExtra(EXTRA_IMAGE_QUALITY, 92))
                    complete(output, originalSize, inputUri, name)
                    return@launch
                }
                val inputFile = copyUriToTemp(inputUri, extensionFromName(name, type))
                localInput = inputFile
                coroutineContext.ensureActive()
                val options = MediaCommands.Options(
                    type = type, input = inputFile.absolutePath, originalName = name,
                    durationMs = durationMs, hasAudio = MediaUtils.hasAudio(inputFile),
                    codec = intent.getStringExtra(EXTRA_CODEC) ?: "H.264",
                    preset = intent.getStringExtra(EXTRA_PRESET) ?: "Оптимальное",
                    targetMb = intent.getDoubleExtra(EXTRA_TARGET_MB, 0.0),
                    resolution = intent.getStringExtra(EXTRA_RESOLUTION) ?: "720p",
                    startMs = intent.getLongExtra(EXTRA_START_MS, 0), endMs = intent.getLongExtra(EXTRA_END_MS, durationMs),
                    audioFormat = intent.getStringExtra(EXTRA_AUDIO_FORMAT) ?: "MP3",
                    audioBitrate = intent.getIntExtra(EXTRA_AUDIO_BITRATE, 192),
                    width = intent.getIntExtra(EXTRA_WIDTH, 1280), height = intent.getIntExtra(EXTRA_HEIGHT, 720),
                    wave = intent.getBooleanExtra(EXTRA_WAVE, true), rotate = intent.getIntExtra(EXTRA_ROTATE, 90),
                    crop = intent.getStringExtra(EXTRA_CROP) ?: "1:1",
                )
                val second = intent.getStringExtra(EXTRA_SECOND_INPUT)?.let(Uri::parse)
                if (type == JobType.AUDIO_TO_VIDEO) {
                    requireNotNull(second) { "Не выбрана обложка" }
                    localSecond = MediaUtils.prepareCoverImage(this@ProcessingService, second, options.width, options.height,
                        intent.getStringExtra(EXTRA_TITLE).orEmpty(), intent.getStringExtra(EXTRA_AUTHOR).orEmpty())
                }
                val readyOptions = options.copy(second = localSecond?.absolutePath)
                var spec = MediaCommands.build(readyOptions)
                val outputFile = MediaUtils.tempOutputFile(this@ProcessingService, spec.ext)
                localOutput = outputFile
                var bitrateScale = 1.0
                var attempt = 0
                while (true) {
                    coroutineContext.ensureActive()
                    progress(8, if (attempt == 0) "Обработка началась" else "Уточняю размер результата…")
                    val session = execute(spec.args(outputFile.absolutePath), spec.durationMs)
                    coroutineContext.ensureActive()
                    if (!ReturnCode.isSuccess(session.returnCode)) {
                        error("Не удалось обработать файл. Проверьте формат и поддержку выбранного кодека устройством")
                    }
                    require(outputFile.length() > 0) { "Обработчик создал пустой файл" }
                    if (spec.kind == MediaUtils.OutputKind.VIDEO) require(MediaUtils.hasVideo(outputFile)) {
                        "Кодек не создал видеодорожку. Попробуйте H.264 или другое разрешение"
                    }
                    val target = spec.targetBytes
                    if (target == null || outputFile.length() <= target) break
                    require(attempt++ < 2) { "Не удалось уложиться в размер. Увеличьте число МБ или уменьшите разрешение" }
                    bitrateScale *= target.toDouble() / outputFile.length() * .9
                    spec = MediaCommands.build(readyOptions, bitrateScale)
                    outputFile.delete()
                }
                progress(96, "Сохраняю результат…")
                coroutineContext.ensureActive()
                val output = MediaUtils.persistTempFile(this@ProcessingService, outputFile, spec.kind, spec.displayName, spec.mime)
                complete(output, originalSize, inputUri, name)
            } catch (_: CancellationException) {
                // Cancellation state has already been published by cancelProcessing/onDestroy.
            } catch (t: Exception) {
                fail(t.message ?: "Не удалось выполнить операцию")
            } finally {
                ffmpegSession = null
                cleanup(localInput, localSecond, localOutput)
            }
        }.also { job -> job.invokeOnCompletion { releaseWakeLock(); isRunning = false } }
    }

    private suspend fun execute(args: List<String>, durationMs: Long): FFmpegSession = suspendCoroutine { continuation ->
        val session = FFmpegKit.executeWithArgumentsAsync(args.toTypedArray(),
            { done -> continuation.resume(done) }, { }, { stats ->
                if (durationMs > 0 && SystemClock.elapsedRealtime() - lastProgressAt >= 500) {
                    lastProgressAt = SystemClock.elapsedRealtime()
                    val p = (8 + stats.time.toDouble() / durationMs * 86).roundToInt().coerceIn(8, 94)
                    progress(p, "Обработка… $p%")
                }
            })
        ffmpegSession = session
        if (cancelled.get()) FFmpegKit.cancel(session.sessionId)
    }

    private fun copyUriToTemp(uri: Uri, ext: String): File {
        val out = MediaUtils.tempOutputFile(this, ext)
        try {
            contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Не удалось открыть файл" }
                out.outputStream().use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        if (cancelled.get()) throw CancellationException()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                }
            }
            return out
        } catch (t: Exception) { out.delete(); throw t }
    }

    private fun extensionFromName(name: String, type: JobType): String {
        val ext = name.substringAfterLast('.', "").lowercase().takeIf { it.length in 2..5 }
        return ext ?: when (type) {
            JobType.VIDEO_TO_AUDIO, JobType.COMPRESS, JobType.TRIM_KEEP, JobType.TRIM_REMOVE, JobType.TRIM_COMPRESS_KEEP, JobType.TRIM_COMPRESS_REMOVE, JobType.ROTATE, JobType.CROP, JobType.RESIZE, JobType.QUICK -> "mp4"
            else -> "dat"
        }
    }

    private fun complete(output: MediaUtils.PendingOutput, originalSize: Long, originalUri: Uri, originalName: String) {
        synchronized(stateLock) {
        if (cancelled.get() || terminal.get()) { MediaUtils.deleteQuietly(this, output.uri); return }
        val resultSize = MediaUtils.fileSize(this, output.uri)
        val state = JobUiState(
            running = false,
            progress = 100,
            title = "Готово",
            message = "Файл обработан",
            resultUri = output.uri.toString(),
            resultMime = output.mime,
            resultName = output.displayName,
            originalUri = originalUri.toString(),
            originalName = originalName,
            originalSize = originalSize,
            resultSize = resultSize,
            canDeleteOriginal = true,
        )
        HistoryStore.add(this, HistoryItem(output.displayName, output.uri.toString(), output.mime, resultSize, System.currentTimeMillis()))
        terminal.set(true)
        publish(state)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        }
    }

    private fun fail(message: String) {
        synchronized(stateLock) {
        if (cancelled.get() || terminal.getAndSet(true)) return
        val old = JobStore.load(this)
        publish(old.copy(running = false, message = message, progress = 0))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        }
    }

    private fun progress(value: Int, message: String) {
        synchronized(stateLock) {
        if (cancelled.get() || terminal.get()) return
        val old = JobStore.load(this)
        val state = old.copy(running = true, progress = value.coerceIn(0, 99), message = message)
        publish(state)
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT < 33 || androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED)
            nm.notify(NOTIFICATION_ID, makeNotification(state))
        }
    }

    private fun publish(state: JobUiState) {
        if (BuildConfig.DEBUG) android.util.Log.d("ToolsStrong", "Job running=${state.running}, progress=${state.progress}, cancelled=${state.cancelled}, result=${state.isResult}")
        JobStore.save(this, state)
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName))
    }

    private fun makeNotification(state: JobUiState) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle("Tools Strong")
        .setContentText(state.message.ifBlank { state.title })
        .setOnlyAlertOnce(true)
        .setOngoing(state.running)
        .setProgress(100, state.progress, state.progress <= 0)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Отменить", PendingIntent.getService(this, 1, Intent(this, ProcessingService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Обработка файлов", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun cleanup(vararg files: File?) = files.forEach { runCatching { it?.delete() } }
    private fun titleFor(type: JobType) = when (type) {
        JobType.QUICK -> "Быстро для Telegram / MAX"
        JobType.COMPRESS -> "Сжатие видео"
        JobType.TRIM_KEEP, JobType.TRIM_REMOVE -> "Обрезка видео"
        JobType.TRIM_COMPRESS_KEEP, JobType.TRIM_COMPRESS_REMOVE -> "Обрезка + сжатие"
        JobType.VIDEO_TO_AUDIO -> "Видео → аудио"
        JobType.AUDIO_TO_VIDEO -> "Аудио → видео"
        JobType.ROTATE -> "Поворот видео"
        JobType.CROP -> "Обрезка кадра"
        JobType.RESIZE -> "Разрешение видео"
        JobType.IMAGE_TO_PDF -> "Изображение → PDF"
        JobType.IMAGE_CONVERT -> "Конвертация изображения"
        JobType.AUDIO_CONVERT -> "Конвертация аудио"
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isRunning && !terminal.get()) cancelProcessing("Обработка прервана системой. Запустите её снова")
        scope.cancel()
        releaseWakeLock()
    }
}
