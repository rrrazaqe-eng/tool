package com.michastrong.toolsstrong

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

enum class ToolScreen {
    HOME,
    QUICK,
    COMPRESS,
    TRIM,
    TRIM_COMPRESS,
    VIDEO_TO_AUDIO,
    AUDIO_TO_VIDEO,
    ROTATE,
    CROP,
    RESIZE,
    FILE_CONVERTER,
    ABOUT,
}

enum class JobType {
    QUICK,
    COMPRESS,
    TRIM_KEEP,
    TRIM_REMOVE,
    TRIM_COMPRESS_KEEP,
    TRIM_COMPRESS_REMOVE,
    VIDEO_TO_AUDIO,
    AUDIO_TO_VIDEO,
    ROTATE,
    CROP,
    RESIZE,
    IMAGE_TO_PDF,
    IMAGE_CONVERT,
    AUDIO_CONVERT,
}

data class MediaInfo(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val durationMs: Long = 0L,
)

data class JobUiState(
    val running: Boolean = false,
    val progress: Int = 0,
    val title: String = "",
    val message: String = "",
    val resultUri: String? = null,
    val resultMime: String? = null,
    val resultName: String? = null,
    val originalUri: String? = null,
    val originalName: String? = null,
    val originalSize: Long = 0L,
    val resultSize: Long = 0L,
    val canDeleteOriginal: Boolean = false,
    val cancelled: Boolean = false,
) {
    val isResult: Boolean get() = !running && !resultUri.isNullOrBlank()
}

data class HistoryItem(
    val name: String,
    val uri: String,
    val mime: String,
    val size: Long,
    val timestamp: Long,
)

object JobStore {
    private const val PREF = "tools_strong_job"
    private const val KEY = "state"

    fun save(context: Context, state: JobUiState) {
        val o = JSONObject().apply {
            put("running", state.running)
            put("progress", state.progress)
            put("title", state.title)
            put("message", state.message)
            put("resultUri", state.resultUri ?: JSONObject.NULL)
            put("resultMime", state.resultMime ?: JSONObject.NULL)
            put("resultName", state.resultName ?: JSONObject.NULL)
            put("originalUri", state.originalUri ?: JSONObject.NULL)
            put("originalName", state.originalName ?: JSONObject.NULL)
            put("originalSize", state.originalSize)
            put("resultSize", state.resultSize)
            put("canDeleteOriginal", state.canDeleteOriginal)
            put("cancelled", state.cancelled)
        }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, o.toString()).apply()
    }

    fun load(context: Context): JobUiState {
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null) ?: return JobUiState()
        return try {
            val o = JSONObject(raw)
            JobUiState(
                running = o.optBoolean("running", false),
                progress = o.optInt("progress", 0),
                title = o.optString("title", ""),
                message = o.optString("message", ""),
                resultUri = o.optNullableString("resultUri"),
                resultMime = o.optNullableString("resultMime"),
                resultName = o.optNullableString("resultName"),
                originalUri = o.optNullableString("originalUri"),
                originalName = o.optNullableString("originalName"),
                originalSize = o.optLong("originalSize", 0L),
                resultSize = o.optLong("resultSize", 0L),
                canDeleteOriginal = o.optBoolean("canDeleteOriginal", false),
                cancelled = o.optBoolean("cancelled", false),
            )
        } catch (_: Throwable) {
            JobUiState()
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}

object HistoryStore {
    private const val PREF = "tools_strong_history"
    private const val KEY = "items"
    private const val LIMIT = 6

    fun add(context: Context, item: HistoryItem) {
        val list = load(context).toMutableList()
        list.removeAll { it.uri == item.uri }
        list.add(0, item)
        while (list.size > LIMIT) list.removeAt(list.lastIndex)
        save(context, list)
    }

    fun load(context: Context): List<HistoryItem> {
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        HistoryItem(
                            name = o.optString("name"),
                            uri = o.optString("uri"),
                            mime = o.optString("mime"),
                            size = o.optLong("size"),
                            timestamp = o.optLong("timestamp"),
                        )
                    )
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun save(context: Context, list: List<HistoryItem>) {
        val arr = JSONArray()
        list.take(LIMIT).forEach { item ->
            arr.put(JSONObject().apply {
                put("name", item.name)
                put("uri", item.uri)
                put("mime", item.mime)
                put("size", item.size)
                put("timestamp", item.timestamp)
            })
        }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }
}

private fun JSONObject.optNullableString(name: String): String? {
    if (!has(name) || isNull(name)) return null
    return optString(name).takeIf { it.isNotBlank() }
}
