package com.michastrong.toolsstrong

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.SystemClock
import android.os.Environment
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/**
 * Captures the actual running application during Android 16 CI. No UI/image overlays.
 * Retrieve files with adb exec-out run-as com.michastrong.toolsstrong.debug cat
 * files/store-assets/<name>. A production signing key is unnecessary for asset capture.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 36)
class StoreAssetCaptureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val automation get() = instrumentation.uiAutomation
    private val directory get() = File(context.filesDir, "store-assets").apply { mkdirs() }
    private val captures = JSONArray()

    @Test(timeout = 120_000)
    fun captureRealAppScreensAndExistingIcon() {
        assertFalse("Capture requires an idle, isolated emulator", ProcessingService.isRunning)
        context.getSharedPreferences("tools_strong_job", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("tools_strong_history", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        automation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val serviceInfo = automation.serviceInfo ?: AccessibilityServiceInfo()
        serviceInfo.flags = serviceInfo.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = serviceInfo

        ActivityScenario.launch(MainActivity::class.java).use {
            waitForLabel("STRONG")
            waitForLabel("Сжать")
            screenshot("01-home.png", "Главный экран")

            clickLabel("Сжать")
            waitForLabel("Сжать видео")
            waitForLabel("Разрешение")
            screenshot("02-video-compression.png", "Сжатие видео")

            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitForLabel("STRONG")
            clickLabel("Аудио → видео")
            waitForLabel("Выбрать аудио")
            waitForLabel("Название")
            waitForLabel("Автор")
            screenshot("03-audio-cover.png", "Видео с обложкой из аудио")
        }

        exportExistingIcon()
        val manifest = JSONObject().apply {
            put("applicationId", context.packageName)
            put("versionName", BuildConfig.VERSION_NAME)
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("androidApi", Build.VERSION.SDK_INT)
            put("captureMethod", "Unmodified UiAutomation screenshots of the running MainActivity")
            put("iconSource", "R.drawable.ic_launcher_foreground rendered by Android Canvas at 512x512")
            put("files", captures)
        }
        File(directory, "capture-manifest.json").writeText(manifest.toString(2))
        assertTrue(File(directory, "01-home.png").isFile)
        assertTrue(File(directory, "02-video-compression.png").isFile)
        assertTrue(File(directory, "03-audio-cover.png").isFile)
        assertTrue(File(directory, "icon-512.png").isFile)
        exportForHost()
    }

    private fun screenshot(name: String, description: String) {
        settle()
        val bitmap = requireNotNull(automation.takeScreenshot()) { "Android returned no screenshot for $description" }
        try {
            assertTrue("Screenshot is too small", bitmap.width >= 320 && bitmap.height >= 320)
            save(bitmap, name, description)
        } finally { bitmap.recycle() }
    }

    private fun exportExistingIcon() {
        val icon = requireNotNull(context.getDrawable(R.drawable.ic_launcher_foreground))
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        bitmap.setHasAlpha(false)
        try {
            icon.setBounds(0, 0, 512, 512)
            icon.draw(Canvas(bitmap))
            save(bitmap, "icon-512.png", "Existing Tools Strong launcher artwork")
        } finally { bitmap.recycle() }
    }

    private fun save(bitmap: Bitmap, name: String, description: String) {
        val file = File(directory, name)
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        captures.put(JSONObject().apply {
            put("name", name)
            put("description", description)
            put("width", bitmap.width)
            put("height", bitmap.height)
            put("sha256", digest)
        })
    }

    private fun exportForHost() {
        // connectedAndroidTest uninstalls the debug package before returning.
        // Shared Downloads survive that uninstall, so the host can retrieve them.
        for (name in listOf("01-home.png", "02-video-compression.png", "03-audio-cover.png", "icon-512.png", "capture-manifest.json")) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, if (name.endsWith(".png")) "image/png" else "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ToolsStrongStoreAssets/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
            try {
                requireNotNull(resolver.openOutputStream(uri)).use { output ->
                    File(directory, name).inputStream().use { input -> input.copyTo(output) }
                }
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (error: Throwable) {
                resolver.delete(uri, null, null)
                throw error
            }
        }
    }

    private fun waitForLabel(label: String) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (nodesForLabel(label).isNotEmpty()) return
            SystemClock.sleep(100)
        }
        error("Visible label '$label' was not found. Actual UI: ${visibleLabels()}")
    }

    private fun clickLabel(label: String) {
        repeat(5) {
            for (node in nodesForLabel(label)) {
                var candidate: AccessibilityNodeInfo? = node
                repeat(6) {
                    val current = candidate
                    if (current != null && current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        settle()
                        return
                    }
                    candidate = current?.parent
                }
            }
            val scrollable = allNodes().firstOrNull { it.isVisibleToUser && it.isScrollable }
            if (scrollable?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) != true)
                error("Could not click '$label'. Actual UI: ${visibleLabels()}")
            settle()
        }
        error("Could not locate '$label' after scrolling. Actual UI: ${visibleLabels()}")
    }

    private fun nodesForLabel(label: String): List<AccessibilityNodeInfo> =
        // Compose exposes virtual nodes; Android 16's provider text search can return
        // an empty list even when the same label is present in the visible tree.
        allNodes().filter { node ->
            node.isVisibleToUser && listOf(node.text, node.hintText, node.contentDescription)
                .filterNotNull().any { text -> text.toString().lines().any { it.trim() == label } }
        }

    private fun allNodes(): List<AccessibilityNodeInfo> {
        val root = automation.rootInActiveWindow ?: return emptyList()
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        while (queue.isNotEmpty() && nodes.size < 500) {
            val node = queue.removeFirst()
            nodes += node
            repeat(node.childCount) { index -> node.getChild(index)?.let(queue::addLast) }
        }
        return nodes
    }

    private fun visibleLabels() = allNodes().filter { it.isVisibleToUser }
        .mapNotNull { it.text?.toString() }.distinct().take(40).joinToString(" | ")

    private fun settle() {
        instrumentation.waitForIdleSync()
        automation.waitForIdle(250, 5000)
        SystemClock.sleep(200)
    }
}
