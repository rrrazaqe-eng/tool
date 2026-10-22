package com.michastrong.toolsstrong

import android.content.Context
import android.content.Intent
import android.app.Activity
import android.app.Instrumentation
import android.app.NotificationManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.RandomAccessFile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Exercises real Android decoders, MediaStore, PDF rendering and the packaged native library. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 36)
class AndroidMediaTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val files = mutableListOf<File>()
    private val outputs = mutableListOf<Uri>()

    @Before
    fun clearState() {
        context.getSharedPreferences("tools_strong_job", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("tools_strong_history", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun cleanUp() {
        outputs.forEach { MediaUtils.deleteQuietly(context, it) }
        files.forEach { it.delete() }
        clearState()
    }

    @Test
    fun pngJpgWebpConvertToEachOtherAndJpegFlattensTransparency() {
        for (sourceFormat in listOf("PNG", "JPG", "WEBP")) {
            val source = image(sourceFormat)
            for (target in listOf("PNG", "JPG", "WEBP")) {
                val output = MediaUtils.convertImage(context, Uri.fromFile(source), source.name, target, 92)
                outputs += output.uri
                assertTrue(MediaUtils.fileSize(context, output.uri) > 0)
                assertEquals(output.mime, context.contentResolver.getType(output.uri))
                decode(output.uri).useBitmap { bitmap ->
                    assertEquals(128, bitmap.width)
                    assertEquals(96, bitmap.height)
                    if (target == "JPG" && sourceFormat != "JPG") {
                        val transparentCorner = bitmap.getPixel(2, 2)
                        assertTrue(Color.red(transparentCorner) > 230)
                        assertTrue(Color.green(transparentCorner) > 230)
                        assertTrue(Color.blue(transparentCorner) > 230)
                    }
                }
            }
        }
    }

    @Test
    fun pngJpgWebpBecomeReadableSinglePagePdfs() {
        for (format in listOf("PNG", "JPG", "WEBP")) {
            val source = image(format)
            val output = MediaUtils.imageToPdf(context, Uri.fromFile(source), source.name)
            outputs += output.uri
            assertEquals("application/pdf", context.contentResolver.getType(output.uri))
            assertTrue(MediaUtils.fileSize(context, output.uri) > 100)
            val descriptor = requireNotNull(context.contentResolver.openFileDescriptor(output.uri, "r"))
            PdfRenderer(descriptor).use { pdf ->
                assertEquals(1, pdf.pageCount)
                pdf.openPage(0).use { page ->
                    assertEquals(128, page.width)
                    assertEquals(96, page.height)
                    Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888).useBitmap { rendered ->
                        page.render(rendered, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        assertNotEquals(rendered.getPixel(2, 2), rendered.getPixel(64, 48))
                    }
                }
            }
        }
    }

    @Test
    fun allCoverRatiosRenderRussianTitleAuthorAndBrand() {
        val source = image("PNG")
        for ((width, height) in listOf(1280 to 720, 720 to 1280, 1080 to 1080)) {
            val plain = MediaUtils.prepareCoverImage(context, Uri.fromFile(source), width, height, "Короткое", "")
            files += plain
            val titled = MediaUtils.prepareCoverImage(context, Uri.fromFile(source), width, height,
                "Название песни на русском — длинное название для проверки обрезки", "Автор: Михаил")
            files += titled
            decode(Uri.fromFile(plain)).useBitmap { before ->
                decode(Uri.fromFile(titled)).useBitmap { after ->
                    assertEquals(width, after.width)
                    assertEquals(height, after.height)
                    var changed = 0
                    var yellow = 0
                    for (y in 0 until height step 3) for (x in 0 until width step 3) {
                        val pixel = after.getPixel(x, y)
                        if (Color.red(pixel) > 210 && Color.green(pixel) > 150 && Color.blue(pixel) < 90) yellow++
                        if (y > height * 0.6 && pixel != before.getPixel(x, y)) changed++
                    }
                    assertTrue("Yellow brand/text was not rendered", yellow > 100)
                    assertTrue("Title and author did not change the lower cover", changed > 100)
                }
            }
        }
    }

    @Test
    fun historyRetainsSixLatestAndDeduplicatesUris() {
        for (index in 0 until 9) HistoryStore.add(context,
            HistoryItem("Файл $index", "content://test/$index", "video/mp4", index.toLong(), index.toLong()))
        assertEquals(listOf(8, 7, 6, 5, 4, 3).map { "content://test/$it" }, HistoryStore.load(context).map { it.uri })
        HistoryStore.add(context, HistoryItem("Обновлённый файл", "content://test/5", "audio/mp4", 100, 99))
        val loaded = HistoryStore.load(context)
        assertEquals(6, loaded.size)
        assertEquals("content://test/5", loaded.first().uri)
        assertEquals("Обновлённый файл", loaded.first().name)
        assertEquals(6, loaded.map { it.uri }.distinct().size)
    }

    @Test
    fun jobStateRoundTripsNullableFieldsAndCancellation() {
        val state = JobUiState(running = false, progress = 100, title = "Готово", message = "Файл обработан",
            resultUri = "content://media/result/1", resultMime = "video/mp4", resultName = "Результат.mp4",
            originalUri = "content://original/1", originalName = "Исходник.mp4", originalSize = 500, resultSize = 250,
            canDeleteOriginal = true)
        JobStore.save(context, state)
        assertEquals(state, JobStore.load(context))
        assertTrue(JobStore.load(context).isResult)
        val cancelled = JobUiState(message = "Обработка отменена", cancelled = true)
        JobStore.save(context, cancelled)
        assertEquals(cancelled, JobStore.load(context))
        assertFalse(JobStore.load(context).isResult)
        JobStore.clear(context)
        assertEquals(JobUiState(), JobStore.load(context))
    }

    @Test(timeout = 30_000)
    fun cancellingForegroundPreparationLeavesSourceAndNoResultOrHistory() {
        val source = temp("mp4")
        // A sparse fixture keeps preparation busy without allocating a large bitmap/video.
        RandomAccessFile(source, "rw").use { it.setLength(256L * 1024 * 1024) }
        grantNotifications()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val request = Intent().apply {
                    putExtra(ProcessingService.EXTRA_TYPE, JobType.COMPRESS.name)
                    putExtra(ProcessingService.EXTRA_INPUT, Uri.fromFile(source).toString())
                    putExtra(ProcessingService.EXTRA_NAME, source.name)
                    putExtra(ProcessingService.EXTRA_SIZE, source.length())
                    putExtra(ProcessingService.EXTRA_DURATION, 60_000L)
                }
                ProcessingService.start(activity, request)
            }
            val notifications = context.getSystemService(NotificationManager::class.java)
            waitUntil("processing START and foreground notification") {
                JobStore.load(context).running && ProcessingService.isRunning &&
                    notifications.activeNotifications.any { it.id == 4107 }
            }
            // The real cancel button becomes available only after the START state is visible.
            scenario.onActivity { activity -> ProcessingService.cancel(activity) }
            waitUntil("cancelled processing and finished cleanup") {
                JobStore.load(context).cancelled && !ProcessingService.isRunning
            }
            val state = JobStore.load(context)
            assertFalse(state.running)
            assertFalse(state.isResult)
            assertTrue(source.exists())
            assertTrue(HistoryStore.load(context).isEmpty())
            waitUntil("foreground notification removal") { notifications.activeNotifications.none { it.id == 4107 } }
            SystemClock.sleep(200)
            assertTrue("Late completion overwrote cancellation", JobStore.load(context).cancelled)
            assertFalse(JobStore.load(context).isResult)
        }
    }

    @Test
    fun openAndShareGrantReadAccessToTheResultUri() {
        val input = image("PNG")
        val result = MediaUtils.convertImage(context, Uri.fromFile(input), input.name, "PNG", 92)
        outputs += result.uri
        grantNotifications()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val choosers = mutableListOf<Intent>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_CHOOSER) return null
                choosers += intent
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            instrumentation.addMonitor(monitor)
            try {
                scenario.onActivity { activity ->
                    MediaUtils.open(activity, result.uri, result.mime)
                    MediaUtils.share(activity, result.uri, result.mime)
                }
                assertEquals(2, choosers.size)
                val view = requireNotNull(choosers[0].getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
                assertEquals(Intent.ACTION_VIEW, view.action)
                assertEquals(result.uri, view.data)
                assertEquals(result.mime, view.type)
                assertTrue(view.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                val share = requireNotNull(choosers[1].getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
                assertEquals(Intent.ACTION_SEND, share.action)
                assertEquals(result.mime, share.type)
                assertEquals(result.uri, share.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
                assertEquals(result.uri, share.clipData?.getItemAt(0)?.uri)
                assertTrue(share.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            } finally { instrumentation.removeMonitor(monitor) }
        }
    }

    @Test(timeout = 120_000)
    fun packagedFfmpegHasRequiredEncodersAndWaveFilter() {
        val encoders = FFmpegKit.executeWithArguments(arrayOf("-hide_banner", "-encoders"))
        assertTrue(encoders.allLogsAsString, ReturnCode.isSuccess(encoders.returnCode))
        for (encoder in listOf("h264_mediacodec", "hevc_mediacodec", "libmp3lame", "pcm_s16le", "aac"))
            assertTrue("Missing native encoder $encoder", encoders.allLogsAsString.contains(encoder))
        val filters = FFmpegKit.executeWithArguments(arrayOf("-hide_banner", "-filters"))
        assertTrue(filters.allLogsAsString, ReturnCode.isSuccess(filters.returnCode))
        assertTrue("Missing native showwaves filter", filters.allLogsAsString.contains("showwaves"))
    }

    @Test(timeout = 120_000)
    fun nativeMp3M4aWavConversionMatrixProducesAudio() {
        val original = wav()
        val sources = listOf("MP3", "M4A").map { format ->
            val spec = MediaCommands.build(MediaCommands.Options(JobType.AUDIO_CONVERT,
                original.absolutePath, original.name, 1000, audioFormat = format))
            val output = temp(spec.ext)
            ffmpeg(spec.args(output.absolutePath))
            output
        } + original
        for (source in sources) for (format in listOf("MP3", "M4A", "WAV")) {
            val spec = MediaCommands.build(MediaCommands.Options(JobType.AUDIO_CONVERT,
                source.absolutePath, source.name, 1000, audioFormat = format))
            val output = temp(spec.ext)
            ffmpeg(spec.args(output.absolutePath))
            assertTrue(output.length() > 100)
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(output.absolutePath)
                assertEquals(1, extractor.trackCount)
                assertTrue(extractor.getTrackFormat(0).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/"))
            } finally { extractor.release() }
            assertTrue("Unexpected duration for $format", abs(duration(output) - 1000) < 300)
        }
    }

    @Test(timeout = 120_000)
    fun nativeAudioToVideoEncodesStaticAndWaveCoversWithMediaCodec() {
        val audio = wav()
        val image = image("JPG")
        val cover = MediaUtils.prepareCoverImage(context, Uri.fromFile(image), 320, 180, "Название", "Автор")
        files += cover
        for (wave in listOf(false, true)) {
            val spec = MediaCommands.build(MediaCommands.Options(JobType.AUDIO_TO_VIDEO,
                audio.absolutePath, audio.name, 1000, second = cover.absolutePath, width = 320, height = 180, wave = wave))
            val output = temp("mp4")
            val nativeLog = ffmpeg(spec.args(output.absolutePath))
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(output.absolutePath)
                val formats = (0 until extractor.trackCount).map(extractor::getTrackFormat)
                assertTrue(formats.any { it.getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm" })
                assertTrue("Expected a video/avc track (wave=$wave); actual tracks: $formats\n$nativeLog",
                    formats.any { it.getString(MediaFormat.KEY_MIME) == "video/avc" })
                val video = formats.single { it.getString(MediaFormat.KEY_MIME) == "video/avc" }
                assertEquals(320, video.getInteger(MediaFormat.KEY_WIDTH))
                assertEquals(180, video.getInteger(MediaFormat.KEY_HEIGHT))
            } finally { extractor.release() }
            assertTrue("Unexpected cover duration", abs(duration(output) - 1000) < 500)
            if (wave) for (format in listOf("MP3", "M4A", "WAV")) {
                val extracted = MediaCommands.build(MediaCommands.Options(JobType.VIDEO_TO_AUDIO,
                    output.absolutePath, output.name, 1000, audioFormat = format))
                val audioOutput = temp(extracted.ext)
                ffmpeg(extracted.args(audioOutput.absolutePath))
                assertTrue("Video to $format created no audio", audioOutput.length() > 100)
                assertTrue("Video to $format changed duration", abs(duration(audioOutput) - 1000) < 500)
            }
        }
    }

    private fun temp(ext: String) = File.createTempFile("strong_test_", ".$ext", context.cacheDir).also { files += it }

    private fun image(format: String): File {
        val file = temp(format.lowercase())
        val encoder = when (format) { "PNG" -> Bitmap.CompressFormat.PNG; "WEBP" -> Bitmap.CompressFormat.WEBP_LOSSLESS; else -> Bitmap.CompressFormat.JPEG }
        Bitmap.createBitmap(128, 96, Bitmap.Config.ARGB_8888).useBitmap { bitmap ->
            Canvas(bitmap).apply {
                drawColor(if (format == "JPG") Color.WHITE else Color.TRANSPARENT)
                drawRect(32f, 24f, 96f, 72f, Paint().apply { color = Color.rgb(20, 80, 200) })
            }
            file.outputStream().use { assertTrue(bitmap.compress(encoder, 95, it)) }
        }
        return file
    }

    private fun decode(uri: Uri) = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, _, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }

    private inline fun <T> Bitmap.useBitmap(block: (Bitmap) -> T): T = try { block(this) } finally { recycle() }

    private fun wav(): File {
        val samples = 44_100
        val buffer = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + samples * 2)
        buffer.put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
        buffer.putShort(1.toShort()).putShort(1.toShort()).putInt(samples).putInt(samples * 2)
        buffer.putShort(2.toShort()).putShort(16.toShort())
        buffer.put("data".toByteArray(Charsets.US_ASCII)).putInt(samples * 2)
        repeat(samples) { index -> buffer.putShort((sin(2 * PI * 440 * index / samples) * 12_000).toInt().toShort()) }
        return temp("wav").also { it.writeBytes(buffer.array()) }
    }

    private fun ffmpeg(args: List<String>): String {
        val session = FFmpegKit.executeWithArguments(args.toTypedArray())
        val log = session.allLogsAsString
        assertTrue(log.takeLast(4000), ReturnCode.isSuccess(session.returnCode))
        return log
    }

    private fun duration(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
        } finally { retriever.release() }
    }

    private fun waitUntil(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
        assertTrue("Timed out waiting for $description; isRunning=${ProcessingService.isRunning}; " +
            "state=${JobStore.load(context)}", condition())
    }

    private fun grantNotifications() {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }
}
