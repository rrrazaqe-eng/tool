package com.michastrong.toolsstrong

import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class MediaCommandsTest {
    private fun options(type: JobType = JobType.COMPRESS) = MediaCommands.Options(
        type = type, input = "fixture:landscape", originalName = "Мой ролик.mp4", durationMs = 4_000,
    )

    private fun List<String>.value(flag: String) = this[indexOf(flag).also { assertTrue("Missing $flag", it >= 0) } + 1]

    @Test fun keepTrimUsesExactSubsecondDurationAndOutputDurationFlag() {
        val spec = MediaCommands.build(options(JobType.TRIM_KEEP).copy(startMs = 200, endMs = 500))
        val args = spec.args("result.mp4")
        assertEquals(300L, spec.durationMs)
        assertEquals("0.200", args.value("-ss"))
        assertEquals("0.300", args.value("-t"))
        assertFalse(args.contains("-to"))
        assertTrue(args.indexOf("-ss") < args.indexOf("-i"))
        assertTrue(args.indexOf("-t") > args.indexOf("-i"))
    }

    @Test fun removeMiddleUsesOnlyRemainingDurationAndAppliesCompressionResolution() {
        val spec = MediaCommands.build(options(JobType.TRIM_COMPRESS_REMOVE).copy(startMs = 1_000, endMs = 3_000, resolution = "480p"))
        val args = spec.args("result.mp4")
        val graph = args.value("-filter_complex")
        assertEquals(2_000L, spec.durationMs)
        assertTrue(graph.contains("concat=n=2:v=1:a=1"))
        assertTrue(graph.contains("[joined]${MediaCommands.scaleFilter(480)}[v]"))
        assertEquals(listOf("[v]", "[a]"), args.windowed(2).filter { it[0] == "-map" }.map { it[1] })
    }

    @Test fun removeBeginningAndEndDoNotGenerateEmptyConcatInputs() {
        for ((start, end) in listOf(0L to 1_000L, 3_000L to 4_000L)) {
            val graph = MediaCommands.build(options(JobType.TRIM_REMOVE).copy(startMs = start, endMs = end)).args("out.mp4").value("-filter_complex")
            assertTrue(graph.contains("concat=n=1:v=1:a=1"))
            assertFalse(graph.contains("trim=start=0.000:end=0.000"))
            assertFalse(graph.contains("trim=start=4.000:end=4.000"))
        }
    }

    @Test fun silentVideoDoesNotRequireOrInventAudio() {
        val args = MediaCommands.build(options(JobType.TRIM_REMOVE).copy(hasAudio = false, startMs = 1_000, endMs = 3_000)).args("out.mp4")
        val graph = args.value("-filter_complex")
        assertTrue(graph.contains("concat=n=2:v=1:a=0"))
        assertFalse(graph.contains("[0:a"))
        assertFalse(args.contains("[a]"))
        val compressArgs = MediaCommands.build(options().copy(hasAudio = false)).args("out.mp4")
        assertTrue(compressArgs.contains("0:a:0?"))
    }

    @Test fun emptyAndFullRemovalAreRejected() {
        for (type in listOf(JobType.TRIM_KEEP, JobType.TRIM_REMOVE, JobType.TRIM_COMPRESS_KEEP, JobType.TRIM_COMPRESS_REMOVE)) {
            assertThrows(IllegalArgumentException::class.java) { MediaCommands.build(options(type).copy(startMs = 2_000, endMs = 2_000)) }
        }
        assertThrows(IllegalArgumentException::class.java) { MediaCommands.build(options(JobType.TRIM_REMOVE)) }
        assertThrows(IllegalArgumentException::class.java) { MediaCommands.build(options(JobType.TRIM_COMPRESS_REMOVE)) }
    }

    @Test fun targetSizeReservesAudioAndContainerSpaceAndUsesTrimmedDuration() {
        val spec = MediaCommands.build(options(JobType.TRIM_COMPRESS_KEEP).copy(startMs = 1_000, endMs = 3_000, targetMb = 1.0))
        assertEquals(1_048_576L, spec.targetBytes)
        assertEquals(2_000L, spec.durationMs)
        val args = spec.args("out.mp4")
        val rate = args.value("-b:v").removeSuffix("k").toInt()
        val totalBits = (rate + args.value("-b:a").removeSuffix("k").toInt()) * 1000.0 * 2
        assertTrue(totalBits < 1_048_576.0 * 8)
        val retry = MediaCommands.build(options().copy(targetMb = 1.0), bitrateScale = .75).args("out.mp4")
        val first = MediaCommands.build(options().copy(targetMb = 1.0)).args("out.mp4")
        assertTrue(retry.value("-b:v").removeSuffix("k").toInt() < first.value("-b:v").removeSuffix("k").toInt())
    }

    @Test fun impossibleTargetAndUnknownDurationAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { MediaCommands.build(options().copy(targetMb = .001)) }
        assertThrows(IllegalArgumentException::class.java) { MediaCommands.build(options().copy(targetMb = 1.0, durationMs = 0)) }
    }

    @Test fun h265UsesHevcCompatibilityTagAndH264UsesAvcEncoder() {
        val hevc = MediaCommands.build(options().copy(codec = "H.265")).args("out.mp4")
        assertEquals("hevc_mediacodec", hevc.value("-c:v"))
        assertEquals("hvc1", hevc.value("-tag:v"))
        assertEquals("h264_mediacodec", MediaCommands.build(options()).args("out.mp4").value("-c:v"))
    }

    @Test fun audioFormatChoosesCorrectCodecAndNeverMapsVideo() {
        for ((format, expected) in listOf("MP3" to "libmp3lame", "M4A" to "aac", "WAV" to "pcm_s16le")) {
            for (type in listOf(JobType.VIDEO_TO_AUDIO, JobType.AUDIO_CONVERT)) {
                val spec = MediaCommands.build(options(type).copy(audioFormat = format))
                val args = spec.args("out.${spec.ext}")
                assertEquals(expected, args.value("-c:a"))
                assertEquals("0:a:0", args.value("-map"))
                assertTrue(args.contains("-vn"))
            }
        }
        assertThrows(IllegalArgumentException::class.java) { MediaCommands.build(options(JobType.VIDEO_TO_AUDIO).copy(hasAudio = false)) }
    }

    @Test fun coverRequiresAnImageAndKeepsWaveAwayFromTitleArea() {
        assertThrows(IllegalArgumentException::class.java) { MediaCommands.build(options(JobType.AUDIO_TO_VIDEO)) }
        val args = MediaCommands.build(options(JobType.AUDIO_TO_VIDEO).copy(second = "fixture:cover-16x9", wave = true)).args("out.mp4")
        assertTrue(args.value("-filter_complex").contains("overlay=0:(H-h)/2"))
        assertTrue(args.contains("-shortest"))
        assertEquals("1:a:0", args.windowed(2).last { it[0] == "-map" }[1])
    }

    @Test fun outputArgumentsPreservePathsAndDoNotDependOnDecimalLocale() {
        val old = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ru-RU"))
            val spec = MediaCommands.build(options(JobType.TRIM_KEEP).copy(input = "/tmp/видео с пробелом.mp4", startMs = 500, endMs = 1_500))
            val args = spec.args("/tmp/результат с пробелом.mp4")
            assertEquals("0.500", args.value("-ss"))
            assertEquals("/tmp/видео с пробелом.mp4", args.value("-i"))
            assertEquals("/tmp/результат с пробелом.mp4", args.last())
            assertTrue(spec.displayName.startsWith("Strong_Мой ролик_"))
            assertEquals("-1", args.value("-map_metadata"))
        } finally { Locale.setDefault(old) }
    }

    /** Exports the real Kotlin commands for host FFmpeg integration checks in CI. */
    @Test fun exportMediaSmokeCases() {
        val cases = mutableListOf<String>()
        fun add(name: String, o: MediaCommands.Options, w: Int? = null, h: Int? = null, audioCodec: String? = if (o.hasAudio) "aac" else null, videoCodec: String? = "h264") {
            val spec = MediaCommands.build(o)
            val args = spec.args("fixture:output")
            cases += "{" + listOf(
                "\"name\":${json(name)}",
                "\"args\":[${args.joinToString(",") { json(it) }}]",
                "\"extension\":${json(spec.ext)}",
                "\"durationMs\":${spec.durationMs}",
                "\"width\":${w ?: "null"}", "\"height\":${h ?: "null"}",
                "\"audioCodec\":${audioCodec?.let(::json) ?: "null"}",
                "\"videoCodec\":${videoCodec?.let(::json) ?: "null"}",
                "\"targetBytes\":${spec.targetBytes ?: "null"}",
            ).joinToString(",") + "}"
        }
        for ((input, portrait) in listOf("landscape" to false, "portrait" to true)) {
            for (height in listOf(1080, 720, 480)) {
                val long = height * 16 / 9 / 2 * 2
                for (codec in listOf("H.264", "H.265")) add("compress-$input-${height}p-$codec",
                    options().copy(input = "fixture:$input", resolution = "${height}p", codec = codec),
                    if (portrait) height else long, if (portrait) long else height,
                    videoCodec = if (codec == "H.265") "hevc" else "h264")
            }
        }
        add("quick-telegram-max", options(JobType.QUICK), 1280, 720)
        add("target-size", options().copy(targetMb = .2, resolution = "480p"), 852, 480)
        add("compress-silent", options().copy(input = "fixture:silent", hasAudio = false), 1280, 720)
        for (silent in listOf(false, true)) {
            val o = options().copy(input = if (silent) "fixture:silent" else "fixture:landscape", hasAudio = !silent)
            for (type in listOf(JobType.TRIM_KEEP, JobType.TRIM_COMPRESS_KEEP, JobType.TRIM_REMOVE, JobType.TRIM_COMPRESS_REMOVE)) {
                add("$type-${if (silent) "silent" else "audio"}", o.copy(type = type, startMs = 1_000, endMs = 3_000, resolution = "480p"),
                    if (type in listOf(JobType.TRIM_COMPRESS_KEEP, JobType.TRIM_COMPRESS_REMOVE)) 852 else 1920,
                    if (type in listOf(JobType.TRIM_COMPRESS_KEEP, JobType.TRIM_COMPRESS_REMOVE)) 480 else 1080)
            }
        }
        add("remove-beginning", options(JobType.TRIM_REMOVE).copy(startMs = 0, endMs = 1_000), 1920, 1080)
        add("remove-end", options(JobType.TRIM_REMOVE).copy(startMs = 3_000, endMs = 4_000), 1920, 1080)
        for ((format, codec) in listOf("MP3" to "mp3", "M4A" to "aac", "WAV" to "pcm_s16le")) {
            add("video-to-$format", options(JobType.VIDEO_TO_AUDIO).copy(audioFormat = format), audioCodec = codec, videoCodec = null)
            for (source in listOf("mp3", "m4a", "wav")) add("$source-to-$format",
                options(JobType.AUDIO_CONVERT).copy(input = "fixture:audio-$source", audioFormat = format), audioCodec = codec, videoCodec = null)
        }
        for ((ratio, size) in listOf("16x9" to (1280 to 720), "9x16" to (720 to 1280), "1x1" to (1080 to 1080))) {
            for (wave in listOf(false, true)) add("cover-$ratio-${if (wave) "wave" else "static"}",
                options(JobType.AUDIO_TO_VIDEO).copy(input = "fixture:audio-wav", second = "fixture:cover-$ratio", width = size.first, height = size.second, wave = wave), size.first, size.second)
        }
        add("rotate-90", options(JobType.ROTATE), 1080, 1920)
        add("crop-square", options(JobType.CROP), 1080, 1080)
        add("resize-720p", options(JobType.RESIZE), 1280, 720)
        File("build/media-cases.json").apply { parentFile?.mkdirs(); writeText("[\n${cases.joinToString(",\n")}\n]\n") }
        assertTrue(cases.size >= 40)
    }

    private fun json(value: String): String = buildString {
        append('"')
        value.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
        append('"')
    }
}
