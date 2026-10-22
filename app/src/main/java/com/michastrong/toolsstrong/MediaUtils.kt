package com.michastrong.toolsstrong

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.ClipData
import android.widget.Toast
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

object MediaUtils {
    enum class OutputKind { VIDEO, AUDIO, IMAGE, DOWNLOAD }

    data class PendingOutput(val uri: Uri, val displayName: String, val mime: String, val kind: OutputKind)

    fun info(context: Context, uri: Uri, includeDuration: Boolean = true): MediaInfo {
        return MediaInfo(
            uri = uri,
            name = displayName(context, uri),
            sizeBytes = fileSize(context, uri),
            durationMs = if (includeDuration) durationMs(context, uri) else 0L,
        )
    }

    fun displayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) return cursor.getString(index)
        }
        return uri.lastPathSegment ?: "Файл"
    }

    fun fileSize(context: Context, uri: Uri): Long {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) return cursor.getLong(index)
        }
        return 0L
    }

    fun durationMs(context: Context, uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Throwable) {
            0L
        } finally {
            retriever.release()
        }
    }

    fun videoDimensions(context: Context, uri: Uri): Pair<Int, Int> {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) {
                val t = w; w = h; h = t
            }
            w to h
        } catch (_: Throwable) {
            0 to 0
        } finally {
            r.release()
        }
    }

    fun hasAudio(context: Context, uri: Uri): Boolean {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            (0 until extractor.trackCount).any { i ->
                extractor.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
        } catch (_: Throwable) {
            true
        } finally {
            extractor.release()
        }
    }

    fun hasAudio(file: File): Boolean {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount).any { i ->
                extractor.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
        } catch (_: Throwable) {
            true
        } finally {
            extractor.release()
        }
    }

    fun hasVideo(file: File): Boolean {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount).any { index ->
                extractor.getTrackFormat(index).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
        } catch (_: Exception) { false }
        finally { extractor.release() }
    }

    fun cleanBaseName(name: String): String {
        val withoutExt = name.substringBeforeLast('.', name)
        return withoutExt.replace(Regex("[^0-9A-Za-zА-Яа-яЁё _.-]"), "_").trim().ifBlank { "file" }.take(90)
    }

    fun strongName(inputName: String, suffix: String, extension: String): String {
        val base = cleanBaseName(inputName)
        return "Strong_${base}_${suffix}.${extension.removePrefix(".")}".replace("__", "_")
    }

    fun createPendingOutput(
        context: Context,
        kind: OutputKind,
        displayName: String,
        mime: String,
    ): PendingOutput? {
        val resolver = context.contentResolver
        val (collection, relativePath) = when (kind) {
            OutputKind.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI to (Environment.DIRECTORY_MOVIES + "/Tools Strong")
            OutputKind.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to (Environment.DIRECTORY_MUSIC + "/Tools Strong")
            OutputKind.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI to (Environment.DIRECTORY_PICTURES + "/Tools Strong")
            OutputKind.DOWNLOAD -> MediaStore.Downloads.EXTERNAL_CONTENT_URI to (Environment.DIRECTORY_DOWNLOADS + "/Tools Strong")
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: return null
        return PendingOutput(uri, displayName, mime, kind)
    }


    fun tempOutputFile(context: Context, extension: String): File {
        val dir = context.externalCacheDir ?: context.cacheDir
        dir.mkdirs()
        return File.createTempFile("strong_", ".${extension.removePrefix(".")}", dir)
    }

    fun persistTempFile(
        context: Context,
        source: File,
        kind: OutputKind,
        displayName: String,
        mime: String,
    ): PendingOutput {
        val output = createPendingOutput(context, kind, displayName, mime)
            ?: error("Не удалось создать итоговый файл")
        try {
            context.contentResolver.openOutputStream(output.uri, "w")!!.use { out ->
                source.inputStream().use { it.copyTo(out, 1024 * 1024) }
            }
            finalizePending(context, output)
            return output
        } catch (t: Throwable) {
            deleteQuietly(context, output.uri)
            throw t
        }
    }

    fun finalizePending(context: Context, output: PendingOutput) {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        context.contentResolver.update(output.uri, values, null, null)
    }

    fun deleteQuietly(context: Context, uri: Uri?) {
        if (uri == null) return
        try { context.contentResolver.delete(uri, null, null) } catch (_: Throwable) { }
    }

    fun open(context: Context, uri: Uri, mime: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Открыть файл"))
        } catch (_: Exception) { Toast.makeText(context, "Нет приложения для открытия этого формата", Toast.LENGTH_LONG).show() }
    }

    fun share(context: Context, uri: Uri, mime: String) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(context.contentResolver, "Tools Strong", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Поделиться"))
        } catch (_: Exception) { Toast.makeText(context, "Не удалось открыть меню отправки", Toast.LENGTH_LONG).show() }
    }

    fun deleteOriginal(context: Context, uri: Uri): Boolean =
        if (DocumentsContract.isDocumentUri(context, uri)) DocumentsContract.deleteDocument(context.contentResolver, uri)
        else context.contentResolver.delete(uri, null, null) > 0

    fun tryDeleteOriginal(context: Context, uri: Uri): Boolean {
        return try {
            deleteOriginal(context, uri)
        } catch (_: Throwable) {
            false
        }
    }

    fun prepareCoverImage(
        context: Context,
        sourceUri: Uri,
        width: Int,
        height: Int,
        title: String,
        author: String,
    ): File {
        require(width in 2..1920 && height in 2..1920) { "Некорректный размер обложки" }
        val sourceBitmap = decodeImage(context, sourceUri, max(width, height) * 2)
        var output: Bitmap? = null
        val file = File.createTempFile("strong_cover_", ".jpg", context.cacheDir)
        try {
        val rendered = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output = rendered
        val canvas = Canvas(rendered)
        canvas.drawColor(Color.BLACK)

        val scale = max(width.toFloat() / sourceBitmap.width, height.toFloat() / sourceBitmap.height)
        val dw = sourceBitmap.width * scale
        val dh = sourceBitmap.height * scale
        val left = (width - dw) / 2f
        val top = (height - dh) / 2f
        canvas.drawBitmap(
            sourceBitmap,
            null,
            Rect(left.roundToInt(), top.roundToInt(), (left + dw).roundToInt(), (top + dh).roundToInt()),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
        )

        val overlayHeight = (height * 0.42f).roundToInt()
        val gradient = Paint().apply {
            shader = LinearGradient(
                0f, (height - overlayHeight).toFloat(), 0f, height.toFloat(),
                Color.TRANSPARENT, Color.argb(235, 0, 0, 0), Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, (height - overlayHeight).toFloat(), width.toFloat(), height.toFloat(), gradient)

        val yellow = Color.rgb(255, 212, 0)
        val pad = width * 0.065f
        val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = (width * 0.028f).coerceAtLeast(22f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val badgeWidth = brandPaint.measureText("TOOLS STRONG") + pad * 0.7f
        val badgeTop = pad * 0.6f
        val badgeBottom = badgeTop + brandPaint.textSize * 1.75f
        val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = yellow }
        canvas.drawRoundRect(pad, badgeTop, pad + badgeWidth, badgeBottom, 18f, 18f, badgePaint)
        canvas.drawText("TOOLS STRONG", pad * 1.35f, badgeTop + brandPaint.textSize * 1.25f, brandPaint)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = (width * if (width > height) 0.055f else 0.072f).coerceAtMost(height * 0.085f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val authorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = yellow
            textSize = titlePaint.textSize * 0.55f
            typeface = Typeface.DEFAULT_BOLD
        }
        val safeTitle = ellipsize(title.ifBlank { "Tools Strong" }, titlePaint, width - pad * 2)
        val safeAuthor = ellipsize(author, authorPaint, width - pad * 2)
        val titleY = height - pad - if (safeAuthor.isBlank()) 0f else authorPaint.textSize * 1.65f
        canvas.drawText(safeTitle, pad, titleY, titlePaint)
        if (safeAuthor.isNotBlank()) canvas.drawText(safeAuthor, pad, height - pad, authorPaint)

        FileOutputStream(file).use { check(rendered.compress(Bitmap.CompressFormat.JPEG, 94, it)) { "Не удалось сохранить обложку" } }
        return file
        } catch (t: Throwable) {
            file.delete()
            throw t
        } finally {
            sourceBitmap.recycle()
            output?.recycle()
        }
    }

    /** Bounds 200 MP camera input before allocation to leave memory for Canvas/encoding. */
    private fun decodeImage(context: Context, uri: Uri, maxSide: Int = Int.MAX_VALUE): Bitmap {
        val maxPixels = minOf(16_000_000.0, Runtime.getRuntime().maxMemory() / 24.0)
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            val factor = minOf(1.0, maxSide.toDouble() / max(info.size.width, info.size.height),
                kotlin.math.sqrt(maxPixels / (info.size.width.toDouble() * info.size.height)))
            decoder.setTargetSize((info.size.width * factor).roundToInt().coerceAtLeast(1),
                (info.size.height * factor).roundToInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }

    fun imageToPdf(context: Context, input: Uri, inputName: String): PendingOutput {
        val output = createPendingOutput(
            context, OutputKind.DOWNLOAD, strongName(inputName, "PDF", "pdf"), "application/pdf"
        ) ?: error("Не удалось создать PDF")
        var bitmap: Bitmap? = null
        try {
            val decoded = decodeImage(context, input, 1600)
            bitmap = decoded
            val doc = PdfDocument()
            try {
                val page = doc.startPage(PdfDocument.PageInfo.Builder(decoded.width, decoded.height, 1).create())
                page.canvas.drawColor(Color.WHITE)
                page.canvas.drawBitmap(decoded, null, Rect(0, 0, decoded.width, decoded.height), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                doc.finishPage(page)
                context.contentResolver.openOutputStream(output.uri, "w")!!.use { doc.writeTo(it) }
            } finally { doc.close() }
            finalizePending(context, output)
            return output
        } catch (t: Throwable) {
            deleteQuietly(context, output.uri)
            throw t
        } finally {
            bitmap?.recycle()
        }
    }

    fun convertImage(context: Context, input: Uri, inputName: String, format: String, quality: Int): PendingOutput {
        val normalized = format.uppercase()
        val (ext, mime, compressFormat) = when (normalized) {
            "PNG" -> Triple("png", "image/png", Bitmap.CompressFormat.PNG)
            "WEBP" -> Triple(
                "webp", "image/webp",
                if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
            )
            else -> Triple("jpg", "image/jpeg", Bitmap.CompressFormat.JPEG)
        }
        val output = createPendingOutput(context, OutputKind.IMAGE, strongName(inputName, normalized, ext), mime)
            ?: error("Не удалось создать изображение")
        var bitmap: Bitmap? = null
        try {
            val decoded = decodeImage(context, input)
            bitmap = decoded
            context.contentResolver.openOutputStream(output.uri, "w")!!.use { stream ->
                if (compressFormat == Bitmap.CompressFormat.JPEG && decoded.hasAlpha()) {
                    val flattened = Bitmap.createBitmap(decoded.width, decoded.height, Bitmap.Config.ARGB_8888)
                    try {
                        Canvas(flattened).apply { drawColor(Color.WHITE); drawBitmap(decoded, 0f, 0f, null) }
                        check(flattened.compress(compressFormat, quality.coerceIn(40, 100), stream)) { "Не удалось записать изображение" }
                    } finally { flattened.recycle() }
                } else check(decoded.compress(compressFormat, quality.coerceIn(40, 100), stream)) { "Не удалось записать изображение" }
            }
            finalizePending(context, output)
            return output
        } catch (t: Throwable) {
            deleteQuietly(context, output.uri)
            throw t
        } finally {
            bitmap?.recycle()
        }
    }

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        val cleaned = text.trim()
        if (cleaned.isBlank() || paint.measureText(cleaned) <= maxWidth) return cleaned
        var value = cleaned
        while (value.length > 1 && paint.measureText("$value…") > maxWidth) value = value.dropLast(1)
        return "$value…"
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "—"
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) "%.2f ГБ".format(mb / 1024.0) else "%.1f МБ".format(mb)
    }

    fun formatDuration(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
