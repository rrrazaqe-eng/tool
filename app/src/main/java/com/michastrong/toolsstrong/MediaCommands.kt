package com.michastrong.toolsstrong

import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/** Command construction is independent of Android and covered by JVM/FFmpeg tests. */
object MediaCommands {
    data class Options(
        val type: JobType,
        val input: String,
        val originalName: String,
        val durationMs: Long,
        val hasAudio: Boolean = true,
        val second: String? = null,
        val codec: String = "H.264",
        val preset: String = "Оптимальное",
        val targetMb: Double = 0.0,
        val resolution: String = "720p",
        val startMs: Long = 0,
        val endMs: Long = durationMs,
        val audioFormat: String = "MP3",
        val audioBitrate: Int = 192,
        val width: Int = 1280,
        val height: Int = 720,
        val wave: Boolean = true,
        val rotate: Int = 90,
        val crop: String = "1:1",
    )

    data class Spec(
        val displayName: String,
        val ext: String,
        val mime: String,
        val kind: MediaUtils.OutputKind,
        val durationMs: Long,
        val targetBytes: Long?,
        val args: (String) -> List<String>,
    )

    fun scaleFilter(height: Int): String {
        val longSide = height * 16 / 9 / 2 * 2
        return "scale=w='if(gte(iw,ih),min(iw,$longSide),min(iw,$height))':" +
            "h='if(gte(iw,ih),min(ih,$height),min(ih,$longSide))':" +
            "force_original_aspect_ratio=decrease:force_divisible_by=2,setsar=1"
    }

    fun build(o: Options, bitrateScale: Double = 1.0): Spec {
        val trimKeep = o.type in setOf(JobType.TRIM_KEEP, JobType.TRIM_COMPRESS_KEEP)
        val trimRemove = o.type in setOf(JobType.TRIM_REMOVE, JobType.TRIM_COMPRESS_REMOVE)
        val compress = o.type in setOf(JobType.COMPRESS, JobType.TRIM_COMPRESS_KEEP, JobType.TRIM_COMPRESS_REMOVE)
        val start = o.startMs.coerceIn(0, o.durationMs.coerceAtLeast(0))
        val end = o.endMs.coerceIn(start, o.durationMs.coerceAtLeast(start))
        if (trimKeep || trimRemove) require(end > start) { "Выберите непустой фрагмент" }
        val duration = when {
            trimKeep -> end - start
            trimRemove -> o.durationMs - (end - start)
            else -> o.durationMs
        }
        if (trimRemove) require(duration > 0) { "Нельзя удалить всё видео: оставьте часть ролика" }
        val target = o.targetMb.takeIf { compress && it.isFinite() && it > 0 }?.let { (it * 1024 * 1024).toLong() }
        if (target != null) require(duration > 0) { "Не удалось определить длительность для сжатия до размера" }
        val audioK = if (target != null && target * 8.0 / (duration / 1000.0) < 500_000) 64 else 128
        val videoK = if (target != null) {
            val available = target * 8.0 * 0.94 / (duration / 1000.0) / 1000.0 - if (o.hasAudio) audioK else 0
            require(available >= 64) { "Размер слишком мал для этого ролика. Увеличьте число МБ или обрежьте видео" }
            (available * bitrateScale).roundToInt().coerceAtLeast(64)
        } else when (o.preset) {
            "Высокое" -> 6000
            "Максимальное" -> 1200
            else -> 3200
        }.let { if (o.codec.contains("265")) (it * .72).roundToInt() else it }
        val encoder = if (o.codec.contains("265")) "hevc_mediacodec" else "h264_mediacodec"
        val height = when (o.resolution) { "1080p" -> 1080; "720p" -> 720; "480p" -> 480; else -> 0 }
        val privacy = listOf("-map_metadata", "-1", "-map_chapters", "-1")
        fun encoded(codec: String = encoder, rate: Int = videoK) = listOf(
            // NDK async avoids MediaCodec's undefined encoder flush during MP4 header probing.
            "-c:v", codec, "-b:v", "${rate}k", "-pix_fmt", "yuv420p",
            "-ndk_codec:v", "1", "-ndk_async:v", "1", "-bsf:v", "extract_extradata",
            "-c:a", "aac", "-b:a", "${audioK}k", "-movflags", "+faststart",
        ) + if (codec == "hevc_mediacodec") listOf("-tag:v", "hvc1") else emptyList()
        fun spec(suffix: String, ext: String = "mp4", mime: String = "video/mp4", kind: MediaUtils.OutputKind = MediaUtils.OutputKind.VIDEO, command: (String) -> List<String>) =
            Spec(MediaUtils.strongName(o.originalName, suffix, ext), ext, mime, kind, duration, target, command)
        fun video(suffix: String, filter: String? = null, rate: Int = videoK, codec: String = encoder) = spec(suffix) { out ->
            listOf("-y", "-i", o.input, "-map", "0:v:0", "-map", "0:a:0?") +
                (filter?.let { listOf("-vf", it) } ?: emptyList()) + privacy + encoded(codec, rate) + out
        }
        return when (o.type) {
            JobType.QUICK -> video("quick", scaleFilter(720), 2500, "h264_mediacodec")
            JobType.COMPRESS -> video("compressed", if (height > 0) scaleFilter(height) else "scale=trunc(iw/2)*2:trunc(ih/2)*2,setsar=1")
            JobType.TRIM_KEEP, JobType.TRIM_COMPRESS_KEEP -> spec("trim") { out ->
                listOf("-y", "-ss", seconds(start), "-i", o.input, "-t", seconds(duration), "-map", "0:v:0", "-map", "0:a:0?") +
                    listOf("-vf", if (compress && height > 0) scaleFilter(height) else "scale=trunc(iw/2)*2:trunc(ih/2)*2,setsar=1") +
                    privacy + encoded(rate = if (compress) videoK else 5000) + out
            }
            JobType.TRIM_REMOVE, JobType.TRIM_COMPRESS_REMOVE -> spec("cut") { out ->
                val segments = buildList {
                    if (start > 0) add(0L to start)
                    if (end < o.durationMs) add(end to o.durationMs)
                }
                val graph = buildList {
                    segments.forEachIndexed { index, (from, to) ->
                        add("[0:v:0]trim=start=${seconds(from)}:end=${seconds(to)},setpts=PTS-STARTPTS[v$index]")
                        if (o.hasAudio) add("[0:a:0]atrim=start=${seconds(from)}:end=${seconds(to)},asetpts=PTS-STARTPTS[a$index]")
                    }
                    val inputs = segments.indices.joinToString("") { "[v$it]" + if (o.hasAudio) "[a$it]" else "" }
                    add("${inputs}concat=n=${segments.size}:v=1:a=${if (o.hasAudio) 1 else 0}[joined]${if (o.hasAudio) "[a]" else ""}")
                    add("[joined]${if (compress && height > 0) scaleFilter(height) else "scale=trunc(iw/2)*2:trunc(ih/2)*2,setsar=1"}[v]")
                }.joinToString(";")
                listOf("-y", "-i", o.input, "-filter_complex", graph, "-map", "[v]") +
                    (if (o.hasAudio) listOf("-map", "[a]") else emptyList()) + privacy +
                    encoded(rate = if (compress) videoK else 5000) + out
            }
            JobType.VIDEO_TO_AUDIO, JobType.AUDIO_CONVERT -> {
                require(o.hasAudio) { "В выбранном файле нет аудиодорожки" }
                val kbps = o.audioBitrate.coerceIn(96, 320)
                val (ext, mime, args) = when (o.audioFormat.uppercase(Locale.ROOT)) {
                    "WAV" -> Triple("wav", "audio/wav", listOf("-c:a", "pcm_s16le"))
                    "M4A" -> Triple("m4a", "audio/mp4", listOf("-c:a", "aac", "-b:a", "${kbps}k"))
                    else -> Triple("mp3", "audio/mpeg", listOf("-c:a", "libmp3lame", "-b:a", "${kbps}k"))
                }
                spec("audio", ext, mime, MediaUtils.OutputKind.AUDIO) { out ->
                    listOf("-y", "-i", o.input, "-map", "0:a:0", "-vn") + privacy + args + out
                }
            }
            JobType.AUDIO_TO_VIDEO -> {
                requireNotNull(o.second) { "Не выбрана обложка" }
                require(o.durationMs > 0) { "Не удалось определить длительность аудио. Выберите MP3, M4A или WAV" }
                require(o.hasAudio) { "В выбранном файле нет аудиодорожки" }
                require(o.width in 2..1920 && o.height in 2..1920 && o.width % 2 == 0 && o.height % 2 == 0)
                spec("cover") { out ->
                    val waveH = max(120, o.height / 5) / 2 * 2
                    val graph = "[1:a:0]showwaves=s=${o.width}x$waveH:mode=line:rate=30:colors=0xFFD400@0.95,format=rgba[sw];" +
                        "[0:v][sw]overlay=0:(H-h)/2:shortest=1,format=yuv420p[v]"
                    listOf("-y", "-loop", "1", "-framerate", "30", "-i", o.second, "-i", o.input) +
                        (if (o.wave) listOf("-filter_complex", graph, "-map", "[v]") else listOf("-map", "0:v:0")) +
                        listOf("-map", "1:a:0") + privacy + encoded("h264_mediacodec", 2500) + listOf("-r", "30", "-g", "30", "-bf", "0", "-t", seconds(duration), out)
                }
            }
            JobType.ROTATE -> video("rotated", when (o.rotate) { 180 -> "transpose=2,transpose=2"; 270 -> "transpose=2"; else -> "transpose=1" }, 4500)
            JobType.CROP -> video("crop", when (o.crop) {
                "16:9" -> "crop='trunc(min(iw,ih*16/9)/2)*2':'trunc(min(ih,iw*9/16)/2)*2',setsar=1"
                "9:16" -> "crop='trunc(min(iw,ih*9/16)/2)*2':'trunc(min(ih,iw*16/9)/2)*2',setsar=1"
                else -> "crop='trunc(min(iw,ih)/2)*2':'trunc(min(iw,ih)/2)*2',setsar=1"
            }, 4500)
            JobType.RESIZE -> video("${o.height}p", scaleFilter(o.height.coerceIn(240, 1080)), 4500)
            else -> error("Неподдерживаемая операция")
        }
    }

    private fun seconds(ms: Long) = "%.3f".format(Locale.US, ms / 1000.0)
}
