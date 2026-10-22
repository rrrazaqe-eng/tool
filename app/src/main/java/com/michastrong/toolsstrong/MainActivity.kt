package com.michastrong.toolsstrong

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.app.RecoverableSecurityException
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import android.content.pm.PackageManager
import android.net.Uri
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Bundle
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val StrongYellow = Color(0xFFFFD400)
private val StrongBlack = Color(0xFF0B0B0B)
private val StrongSurface = Color(0xFF181818)
private val StrongMuted = Color(0xFFAFAFAF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ToolsStrongApp() }
    }
}

@Composable
fun ToolsStrongApp() {
    val context = LocalContext.current
    var screen by remember { mutableStateOf(ToolScreen.HOME) }
    var jobState by remember { mutableStateOf(JobStore.load(context).let { state ->
        if (state.running && !ProcessingService.isRunning) state.copy(running = false, message = "Обработка прервана. Запустите её снова").also { JobStore.save(context, it) }
        else state
    }) }
    BackHandler(enabled = screen != ToolScreen.HOME) { screen = ToolScreen.HOME }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                jobState = JobStore.load(context)
            }
        }
        val filter = IntentFilter(ProcessingService.ACTION_STATE)
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = StrongYellow,
            onPrimary = Color.Black,
            background = StrongBlack,
            surface = StrongSurface,
            onSurface = Color.White,
            secondary = StrongYellow,
        )
    ) {
        Scaffold(
            containerColor = StrongBlack,
            bottomBar = {
                if (jobState.running || jobState.isResult || jobState.message.isNotBlank()) {
                    JobBar(jobState) { jobState = JobStore.load(context) }
                }
            }
        ) { pad ->
            Surface(Modifier.fillMaxSize().padding(pad), color = StrongBlack) {
                when (screen) {
                    ToolScreen.HOME -> HomeScreen(onOpen = { screen = it }, lastResult = jobState.resultUri)
                    ToolScreen.QUICK -> QuickScreen({ screen = ToolScreen.HOME })
                    ToolScreen.COMPRESS -> CompressScreen({ screen = ToolScreen.HOME })
                    ToolScreen.TRIM -> TrimScreen(false, { screen = ToolScreen.HOME })
                    ToolScreen.TRIM_COMPRESS -> TrimScreen(true, { screen = ToolScreen.HOME })
                    ToolScreen.VIDEO_TO_AUDIO -> VideoToAudioScreen({ screen = ToolScreen.HOME })
                    ToolScreen.AUDIO_TO_VIDEO -> AudioToVideoScreen({ screen = ToolScreen.HOME })
                    ToolScreen.ROTATE -> RotateScreen({ screen = ToolScreen.HOME })
                    ToolScreen.CROP -> CropScreen({ screen = ToolScreen.HOME })
                    ToolScreen.RESIZE -> ResizeScreen({ screen = ToolScreen.HOME })
                    ToolScreen.FILE_CONVERTER -> FileConverterScreen({ screen = ToolScreen.HOME })
                    ToolScreen.ABOUT -> AboutScreen({ screen = ToolScreen.HOME })
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(onOpen: (ToolScreen) -> Unit, lastResult: String?) {
    val context = LocalContext.current
    var history by remember { mutableStateOf(HistoryStore.load(context)) }
    LaunchedEffect(lastResult) { history = HistoryStore.load(context) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("TOOLS", fontSize = 39.sp, fontWeight = FontWeight.Black, color = Color.White)
        Text("STRONG", fontSize = 39.sp, fontWeight = FontWeight.Black, color = StrongYellow)
        Text("Видео, аудио и файлы. Всё на телефоне.", color = StrongMuted, fontSize = 15.sp)
        Spacer(Modifier.height(4.dp))

        HeroCard("⚡", "Быстро для Telegram / MAX", "Выбрать видео и сразу получить удобный файл") { onOpen(ToolScreen.QUICK) }
        SectionTitle("Видео")
        GridRows(
            listOf(
                ToolItem("🎬", "Сжать", ToolScreen.COMPRESS),
                ToolItem("✂️", "Обрезать", ToolScreen.TRIM),
                ToolItem("⚡", "Обрезать + сжать", ToolScreen.TRIM_COMPRESS),
                ToolItem("🎵", "Видео → аудио", ToolScreen.VIDEO_TO_AUDIO),
                ToolItem("↻", "Повернуть", ToolScreen.ROTATE),
                ToolItem("▣", "Кадр", ToolScreen.CROP),
                ToolItem("↕", "Разрешение", ToolScreen.RESIZE),
                ToolItem("🖼", "Аудио → видео", ToolScreen.AUDIO_TO_VIDEO),
            ), onOpen
        )
        SectionTitle("Файлы")
        HeroCard("⇄", "Конвертер файлов", "PNG / JPG / WEBP → PDF и изображения между форматами") { onOpen(ToolScreen.FILE_CONVERTER) }

        if (history.isNotEmpty()) {
            SectionTitle("Последние 6 файлов")
            history.take(6).forEach { item ->
                HistoryCard(item)
            }
        }

        OutlinedButton(onClick = { onOpen(ToolScreen.ABOUT) }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
            Text("О приложении")
        }
        Text("MICHa Strong", modifier = Modifier.align(Alignment.CenterHorizontally), color = StrongMuted, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
    }
}

private data class ToolItem(val icon: String, val name: String, val screen: ToolScreen)

@Composable
private fun GridRows(items: List<ToolItem>, onOpen: (ToolScreen) -> Unit) {
    items.chunked(2).forEach { rowItems ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            rowItems.forEach { item ->
                Card(
                    modifier = Modifier.weight(1f).height(112.dp).clickable { onOpen(item.screen) },
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = StrongSurface),
                ) {
                    Column(Modifier.fillMaxSize().padding(15.dp), verticalArrangement = Arrangement.SpaceBetween) {
                        Text(item.icon, fontSize = 29.sp)
                        Text(item.name, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                }
            }
            if (rowItems.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun HeroCard(icon: String, title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = StrongYellow),
    ) {
        Row(Modifier.fillMaxWidth().padding(19.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(icon, fontSize = 34.sp)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, fontSize = 19.sp, fontWeight = FontWeight.Black, color = Color.Black)
                Text(subtitle, fontSize = 13.sp, color = Color.Black.copy(alpha = .72f))
            }
        }
    }
}

@Composable private fun SectionTitle(text: String) { Text(text, fontSize = 20.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(top = 10.dp)) }

@Composable
private fun HistoryCard(item: HistoryItem) {
    val context = LocalContext.current
    val date = remember(item.timestamp) { SimpleDateFormat("dd.MM  HH:mm", Locale("ru")).format(Date(item.timestamp)) }
    Row(
        Modifier.fillMaxWidth().background(StrongSurface, RoundedCornerShape(16.dp)).clickable { MediaUtils.open(context, Uri.parse(item.uri), item.mime) }.padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(42.dp).background(StrongYellow, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Text("S", color = Color.Black, fontWeight = FontWeight.Black) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, fontWeight = FontWeight.Bold, maxLines = 1)
            Text("${MediaUtils.formatFileSize(item.size)}  •  $date", color = StrongMuted, fontSize = 12.sp)
        }
        Text("›", color = StrongYellow, fontSize = 28.sp)
    }
}

@Composable
private fun JobBar(state: JobUiState, refresh: () -> Unit) {
    val context = LocalContext.current
    var askDelete by remember { mutableStateOf(false) }
    fun deleted() {
        JobStore.save(context, state.copy(canDeleteOriginal = false, message = "Исходный файл удалён"))
        refresh()
    }
    val deleteRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            // API 29 grants permission; API 30+ performs deletion itself.
            val ok = Build.VERSION.SDK_INT >= 30 || state.originalUri?.let { MediaUtils.tryDeleteOriginal(context, Uri.parse(it)) } == true
            if (ok) deleted()
            else Toast.makeText(context, "Не удалось удалить исходный файл", Toast.LENGTH_LONG).show()
        }
    }
    Column(Modifier.fillMaxWidth().background(Color(0xFF222222)).navigationBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        if (state.running) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(state.title.ifBlank { "Tools Strong" }, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("${state.progress}%", color = StrongYellow, fontWeight = FontWeight.Black)
            }
            LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth().height(7.dp), color = StrongYellow)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.message, color = StrongMuted, modifier = Modifier.weight(1f), fontSize = 12.sp)
                OutlinedButton(onClick = { ProcessingService.cancel(context) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Отменить", fontSize = 14.sp) }
            }
        } else if (state.isResult) {
            Text("✓ Готово: ${state.resultName}", fontWeight = FontWeight.Bold, color = StrongYellow, maxLines = 1)
            if (state.originalSize > 0 && state.resultSize > 0) {
                val saved = ((1.0 - state.resultSize.toDouble() / state.originalSize.toDouble()) * 100).roundToInt()
                Text("${MediaUtils.formatFileSize(state.originalSize)} → ${MediaUtils.formatFileSize(state.resultSize)}${if (saved > 0) "  •  меньше на $saved%" else ""}", color = StrongMuted, fontSize = 12.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StrongSmallButton("Открыть") { MediaUtils.open(context, Uri.parse(state.resultUri.orEmpty()), state.resultMime ?: "*/*") }
                StrongSmallButton("Поделиться") { MediaUtils.share(context, Uri.parse(state.resultUri.orEmpty()), state.resultMime ?: "*/*") }
            }
            if (state.canDeleteOriginal) OutlinedButton(onClick = { askDelete = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Удалить исходник") }
            OutlinedButton(
                onClick = { JobStore.clear(context); refresh() },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Сделать ещё") }
        } else if (state.message.isNotBlank()) {
            Text(state.message, color = if (state.cancelled) StrongMuted else Color(0xFFFF8A80), fontSize = 13.sp)
        }
    }
    if (askDelete) {
        AlertDialog(
            onDismissRequest = { askDelete = false },
            title = { Text("Удалить исходный файл?") },
            text = { Text("Готовый файл останется. Android может дополнительно попросить подтверждение удаления.") },
            confirmButton = {
                Button(onClick = {
                    askDelete = false
                    val uri = state.originalUri?.let(Uri::parse) ?: return@Button
                    try {
                        val ok = MediaUtils.deleteOriginal(context, uri)
                        if (ok) deleted()
                        else Toast.makeText(context, "Источник не разрешил удаление. Удалите файл через приложение «Мои файлы»", Toast.LENGTH_LONG).show()
                    } catch (e: SecurityException) {
                        val sender = if (Build.VERSION.SDK_INT >= 30 && uri.authority == "media")
                            runCatching { MediaStore.createDeleteRequest(context.contentResolver, listOf(uri)).intentSender }.getOrNull()
                        else if (Build.VERSION.SDK_INT == 29 && e is RecoverableSecurityException) e.userAction.actionIntent.intentSender
                        else null
                        if (sender != null) deleteRequest.launch(IntentSenderRequest.Builder(sender).build())
                        else Toast.makeText(context, "Нет разрешения на удаление. Используйте приложение «Мои файлы»", Toast.LENGTH_LONG).show()
                    } catch (_: Exception) {
                        Toast.makeText(context, "Не удалось удалить исходный файл", Toast.LENGTH_LONG).show()
                    }
                }) { Text("Удалить") }
            },
            dismissButton = { OutlinedButton(onClick = { askDelete = false }) { Text("Отмена") } },
        )
    }
}

@Composable private fun RowScope.StrongSmallButton(text: String, onClick: () -> Unit) { Button(onClick = onClick, modifier = Modifier.weight(1f).heightIn(min = 48.dp), colors = ButtonDefaults.buttonColors(containerColor = StrongYellow, contentColor = Color.Black)) { Text(text, fontSize = 14.sp, fontWeight = FontWeight.Bold) } }

@Composable
private fun ToolPage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("‹", fontSize = 44.sp, color = StrongYellow, modifier = Modifier.clickable(onClick = onBack).padding(end = 14.dp))
            Text(title, fontSize = 25.sp, fontWeight = FontWeight.Black)
        }
        content()
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().height(58.dp), shape = RoundedCornerShape(17.dp), colors = ButtonDefaults.buttonColors(containerColor = StrongYellow, contentColor = Color.Black)) {
        Text(text, fontWeight = FontWeight.Black, fontSize = 16.sp)
    }
}

@Composable
private fun FileCard(info: MediaInfo?) {
    if (info == null) return
    Column(Modifier.fillMaxWidth().background(StrongSurface, RoundedCornerShape(17.dp)).padding(15.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(info.name, fontWeight = FontWeight.Bold, maxLines = 2)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(MediaUtils.formatFileSize(info.sizeBytes), color = StrongMuted, fontSize = 12.sp)
            if (info.durationMs > 0) Text(MediaUtils.formatDuration(info.durationMs), color = StrongMuted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun rememberOpenDocument(mimeTypes: Array<String>, onSelected: (MediaInfo) -> Unit): androidx.activity.result.ActivityResultLauncher<Array<String>> {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                .recoverCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            scope.launch {
                try {
                    val info = withContext(Dispatchers.IO) { MediaUtils.info(context, uri) }
                    onSelected(info)
                } catch (_: Exception) {
                    Toast.makeText(context, "Не удалось открыть выбранный файл", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

@Composable
private fun ChoiceRow(title: String, choices: List<String>, selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, fontWeight = FontWeight.Bold)
        choices.forEach { choice ->
            Row(Modifier.fillMaxWidth().clickable { onSelect(choice) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = choice == selected, onClick = { onSelect(choice) })
                Text(choice)
            }
        }
    }
}

private fun startJob(context: Context, type: JobType, input: MediaInfo, configure: Intent.() -> Unit = {}) {
    if (ProcessingService.isRunning) {
        Toast.makeText(context, "Дождитесь завершения или отмените текущую операцию", Toast.LENGTH_SHORT).show()
        return
    }
    val i = Intent().apply {
        putExtra(ProcessingService.EXTRA_TYPE, type.name)
        putExtra(ProcessingService.EXTRA_INPUT, input.uri.toString())
        putExtra(ProcessingService.EXTRA_NAME, input.name)
        putExtra(ProcessingService.EXTRA_DURATION, input.durationMs)
        putExtra(ProcessingService.EXTRA_SIZE, input.sizeBytes)
        configure()
    }
    try { ProcessingService.start(context, i) }
    catch (_: Exception) { Toast.makeText(context, "Не удалось запустить обработку. Откройте приложение и повторите", Toast.LENGTH_LONG).show() }
}

@Composable
private fun QuickScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var input by remember { mutableStateOf<MediaInfo?>(null) }
    val picker = rememberOpenDocument(arrayOf("video/*")) { input = it }
    ToolPage("Быстро для Telegram / MAX", onBack) {
        Text("Оптимальный 720p-файл без лишних настроек. Подходит, когда нужно просто уменьшить ролик и отправить.", color = StrongMuted)
        PrimaryButton(if (input == null) "Выбрать видео" else "Выбрать другое") { picker.launch(arrayOf("video/*")) }
        FileCard(input)
        PrimaryButton("Сделать быстро", input != null) { input?.let { startJob(context, JobType.QUICK, it) } }
    }
}

@Composable
private fun CompressScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var input by remember { mutableStateOf<MediaInfo?>(null) }
    var mode by remember { mutableStateOf("По качеству") }
    var preset by remember { mutableStateOf("Оптимальное") }
    var codec by remember { mutableStateOf("H.264") }
    var resolution by remember { mutableStateOf("720p") }
    var target by remember { mutableStateOf("50") }
    val picker = rememberOpenDocument(arrayOf("video/*")) { input = it }
    ToolPage("Сжать видео", onBack) {
        PrimaryButton(if (input == null) "Выбрать видео" else "Выбрать другое") { picker.launch(arrayOf("video/*")) }
        FileCard(input)
        ChoiceRow("Режим", listOf("По качеству", "До размера"), mode) { mode = it }
        if (mode == "По качеству") ChoiceRow("Качество", listOf("Высокое", "Оптимальное", "Максимальное"), preset) { preset = it }
        else OutlinedTextField(target, { target = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(7) }, label = { Text("Желаемый размер, МБ") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
        ChoiceRow("Разрешение", listOf("Оригинал", "1080p", "720p", "480p"), resolution) { resolution = it }
        ChoiceRow("Кодек", listOf("H.264", "H.265"), codec) { codec = it }
        Text("H.264 открывается почти везде. H.265 обычно даёт меньший файл.", color = StrongMuted, fontSize = 12.sp)
        PrimaryButton("Сжать видео", input != null && (mode != "До размера" || (target.replace(',', '.').toDoubleOrNull() ?: 0.0) > 0)) {
            input?.let { m ->
                startJob(context, JobType.COMPRESS, m) {
                    putExtra(ProcessingService.EXTRA_PRESET, preset)
                    putExtra(ProcessingService.EXTRA_CODEC, codec)
                    putExtra(ProcessingService.EXTRA_RESOLUTION, resolution)
                    putExtra(ProcessingService.EXTRA_TARGET_MB, if (mode == "До размера") target.replace(',', '.').toDoubleOrNull() ?: 0.0 else 0.0)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrimScreen(compress: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    var input by remember { mutableStateOf<MediaInfo?>(null) }
    var range by remember { mutableStateOf(0f..1f) }
    var mode by remember { mutableStateOf("Оставить фрагмент") }
    var compressMode by remember { mutableStateOf("По качеству") }
    var preset by remember { mutableStateOf("Оптимальное") }
    var codec by remember { mutableStateOf("H.264") }
    var resolution by remember { mutableStateOf("720p") }
    var target by remember { mutableStateOf("50") }
    val picker = rememberOpenDocument(arrayOf("video/*")) { input = it; range = 0f..1f }
    ToolPage(if (compress) "Обрезать + сжать" else "Обрезать видео", onBack) {
        PrimaryButton(if (input == null) "Выбрать видео" else "Выбрать другое") { picker.launch(arrayOf("video/*")) }
        FileCard(input)
        input?.let { m ->
            VideoPreview(m.uri)
            TimelineThumbnails(m.uri, m.durationMs)
            val d = m.durationMs.coerceAtLeast(1L)
            Text("От ${MediaUtils.formatDuration((range.start * d).toLong())} до ${MediaUtils.formatDuration((range.endInclusive * d).toLong())}", fontWeight = FontWeight.Bold, color = StrongYellow)
            RangeSlider(value = range, onValueChange = { range = it }, valueRange = 0f..1f)
            Text("Двигай два жёлтых ползунка, чтобы выбрать нужный участок.", color = StrongMuted, fontSize = 12.sp)
        }
        ChoiceRow("Что сделать", listOf("Оставить фрагмент", "Вырезать середину"), mode) { mode = it }
        if (compress) {
            ChoiceRow("Режим сжатия", listOf("По качеству", "До размера"), compressMode) { compressMode = it }
            if (compressMode == "По качеству") {
                ChoiceRow("Качество", listOf("Высокое", "Оптимальное", "Максимальное"), preset) { preset = it }
            } else {
                OutlinedTextField(target, { target = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(7) }, label = { Text("Желаемый размер, МБ") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            }
            ChoiceRow("Разрешение", listOf("Оригинал", "1080p", "720p", "480p"), resolution) { resolution = it }
            ChoiceRow("Кодек", listOf("H.264", "H.265"), codec) { codec = it }
        }
        PrimaryButton(if (compress) "Обрезать и сжать" else "Обрезать", input != null && range.endInclusive > range.start && (mode != "Вырезать середину" || range.endInclusive - range.start < .9999f) && (!compress || compressMode != "До размера" || (target.replace(',', '.').toDoubleOrNull() ?: 0.0) > 0)) {
            input?.let { m ->
                val d = m.durationMs.coerceAtLeast(1L)
                val remove = mode == "Вырезать середину"
                val type = when {
                    compress && remove -> JobType.TRIM_COMPRESS_REMOVE
                    compress -> JobType.TRIM_COMPRESS_KEEP
                    remove -> JobType.TRIM_REMOVE
                    else -> JobType.TRIM_KEEP
                }
                startJob(context, type, m) {
                    putExtra(ProcessingService.EXTRA_START_MS, (range.start * d).toLong())
                    putExtra(ProcessingService.EXTRA_END_MS, (range.endInclusive * d).toLong())
                    putExtra(ProcessingService.EXTRA_PRESET, preset)
                    putExtra(ProcessingService.EXTRA_CODEC, codec)
                    putExtra(ProcessingService.EXTRA_RESOLUTION, resolution)
                    putExtra(ProcessingService.EXTRA_TARGET_MB, if (compress && compressMode == "До размера") target.replace(',', '.').toDoubleOrNull() ?: 0.0 else 0.0)
                }
            }
        }
    }
}

@Composable
private fun VideoPreview(uri: Uri) {
    AndroidView(
        factory = { ctx -> VideoView(ctx).apply { setVideoURI(uri); setOnPreparedListener { it.isLooping = true; start() } } },
        update = { view -> if (view.tag != uri.toString()) { view.tag = uri.toString(); view.setVideoURI(uri); view.setOnPreparedListener { it.isLooping = true; view.start() } } },
        modifier = Modifier.fillMaxWidth().height(210.dp).background(Color.Black, RoundedCornerShape(16.dp)),
    )
}

@Composable
private fun TimelineThumbnails(uri: Uri, durationMs: Long) {
    val context = LocalContext.current
    val frames by produceState<List<Bitmap>>(initialValue = emptyList(), uri, durationMs) {
        value = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val duration = durationMs.coerceAtLeast(1L)
                (0 until 6).mapNotNull { index ->
                    val timeUs = ((duration * index) / 5L) * 1000L
                    retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 160, 90)
                }
            } catch (_: Throwable) {
                emptyList()
            } finally {
                runCatching { retriever.release() }
            }
        }
    }
    if (frames.isNotEmpty()) {
        Row(
            Modifier.fillMaxWidth().height(58.dp).background(Color.Black, RoundedCornerShape(10.dp)),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            frames.forEach { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.weight(1f).height(58.dp),
                )
            }
        }
    }
}

@Composable
private fun VideoToAudioScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var input by remember { mutableStateOf<MediaInfo?>(null) }
    var format by remember { mutableStateOf("MP3") }
    var bitrate by remember { mutableStateOf("192") }
    val picker = rememberOpenDocument(arrayOf("video/*", "audio/*")) { input = it }
    ToolPage("Видео → аудио", onBack) {
        PrimaryButton(if (input == null) "Выбрать видео" else "Выбрать другое") { picker.launch(arrayOf("video/*")) }
        FileCard(input)
        ChoiceRow("Формат", listOf("MP3", "M4A", "WAV"), format) { format = it }
        if (format != "WAV") ChoiceRow("Качество", listOf("128", "192", "256", "320"), bitrate) { bitrate = it }
        PrimaryButton("Получить аудио", input != null) {
            input?.let { startJob(context, JobType.VIDEO_TO_AUDIO, it) { putExtra(ProcessingService.EXTRA_AUDIO_FORMAT, format); putExtra(ProcessingService.EXTRA_AUDIO_BITRATE, bitrate.toInt()) } }
        }
    }
}

@Composable
private fun AudioToVideoScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var audio by remember { mutableStateOf<MediaInfo?>(null) }
    var cover by remember { mutableStateOf<MediaInfo?>(null) }
    var format by remember { mutableStateOf("16:9") }
    var title by remember { mutableStateOf("") }
    var author by remember { mutableStateOf("") }
    var wave by remember { mutableStateOf(true) }
    val audioPicker = rememberOpenDocument(arrayOf("audio/*")) { audio = it }
    val coverPicker = rememberOpenDocument(arrayOf("image/*")) { cover = it }
    ToolPage("Аудио → видео", onBack) {
        PrimaryButton(if (audio == null) "Выбрать аудио" else "Выбрать другое аудио") { audioPicker.launch(arrayOf("audio/*")) }
        FileCard(audio)
        OutlinedButton(onClick = { coverPicker.launch(arrayOf("image/*")) }, modifier = Modifier.fillMaxWidth().height(55.dp), shape = RoundedCornerShape(16.dp)) { Text(if (cover == null) "Выбрать обложку" else "Обложка: ${cover!!.name}") }
        ChoiceRow("Формат", listOf("16:9", "9:16", "1:1"), format) { format = it }
        OutlinedTextField(title, { title = it.take(80) }, label = { Text("Название") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(author, { author = it.take(60) }, label = { Text("Автор") }, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { wave = !wave }) { Checkbox(wave, { wave = it }); Text("Добавить жёлтую аудиоволну") }
        Text("Обложка автоматически получит затемнение, название, автора и небольшой фирменный знак Tools Strong.", color = StrongMuted, fontSize = 12.sp)
        PrimaryButton("Сделать видео", audio != null && cover != null) {
            val a = audio ?: return@PrimaryButton
            val c = cover ?: return@PrimaryButton
            val (w, h) = when (format) { "9:16" -> 720 to 1280; "1:1" -> 1080 to 1080; else -> 1280 to 720 }
            startJob(context, JobType.AUDIO_TO_VIDEO, a) {
                putExtra(ProcessingService.EXTRA_SECOND_INPUT, c.uri.toString())
                putExtra(ProcessingService.EXTRA_WIDTH, w)
                putExtra(ProcessingService.EXTRA_HEIGHT, h)
                putExtra(ProcessingService.EXTRA_TITLE, title)
                putExtra(ProcessingService.EXTRA_AUTHOR, author)
                putExtra(ProcessingService.EXTRA_WAVE, wave)
            }
        }
    }
}

@Composable
private fun RotateScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var input by remember { mutableStateOf<MediaInfo?>(null) }
    var degrees by remember { mutableIntStateOf(90) }
    val picker = rememberOpenDocument(arrayOf("video/*")) { input = it }
    ToolPage("Повернуть видео", onBack) {
        PrimaryButton(if (input == null) "Выбрать видео" else "Выбрать другое") { picker.launch(arrayOf("video/*")) }
        FileCard(input)
        ChoiceRow("Поворот", listOf("90°", "180°", "270°"), "$degrees°") { degrees = it.removeSuffix("°").toInt() }
        PrimaryButton("Повернуть", input != null) { input?.let { startJob(context, JobType.ROTATE, it) { putExtra(ProcessingService.EXTRA_ROTATE, degrees) } } }
    }
}

@Composable
private fun CropScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var input by remember { mutableStateOf<MediaInfo?>(null) }
    var crop by remember { mutableStateOf("1:1") }
    val picker = rememberOpenDocument(arrayOf("video/*")) { input = it }
    ToolPage("Обрезать края кадра", onBack) {
        PrimaryButton(if (input == null) "Выбрать видео" else "Выбрать другое") { picker.launch(arrayOf("video/*")) }
        FileCard(input)
        ChoiceRow("Новый кадр", listOf("16:9", "9:16", "1:1"), crop) { crop = it }
        Text("Кадр обрежется по центру. Подходит для обычного видео, Shorts/клипов и квадратных публикаций.", color = StrongMuted, fontSize = 12.sp)
        PrimaryButton("Обрезать кадр", input != null) { input?.let { startJob(context, JobType.CROP, it) { putExtra(ProcessingService.EXTRA_CROP, crop) } } }
    }
}

@Composable
private fun ResizeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var input by remember { mutableStateOf<MediaInfo?>(null) }
    var res by remember { mutableStateOf("720p") }
    var codec by remember { mutableStateOf("H.264") }
    val picker = rememberOpenDocument(arrayOf("video/*")) { input = it }
    ToolPage("Изменить разрешение", onBack) {
        PrimaryButton(if (input == null) "Выбрать видео" else "Выбрать другое") { picker.launch(arrayOf("video/*")) }
        FileCard(input)
        ChoiceRow("Разрешение", listOf("1080p", "720p", "480p"), res) { res = it }
        ChoiceRow("Кодек", listOf("H.264", "H.265"), codec) { codec = it }
        PrimaryButton("Изменить разрешение", input != null) { input?.let { startJob(context, JobType.RESIZE, it) { putExtra(ProcessingService.EXTRA_HEIGHT, res.removeSuffix("p").toInt()); putExtra(ProcessingService.EXTRA_CODEC, codec) } } }
    }
}

@Composable
private fun FileConverterScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var input by remember { mutableStateOf<MediaInfo?>(null) }
    var mode by remember { mutableStateOf("Изображение → PDF") }
    var imageFormat by remember { mutableStateOf("JPG") }
    var audioFormat by remember { mutableStateOf("MP3") }
    var quality by remember { mutableIntStateOf(92) }
    var audioBitrate by remember { mutableStateOf("192") }
    val picker = rememberOpenDocument(arrayOf("image/*", "audio/*")) { selected ->
        input = if (mode.startsWith("Изображение")) selected.copy(durationMs = 0) else selected
    }
    ToolPage("Конвертер файлов", onBack) {
        Text("Конвертация выполняется полностью офлайн.", color = StrongMuted)
        ChoiceRow(
            "Операция",
            listOf("Изображение → PDF", "Изображение → другой формат", "Аудио → другой формат"),
            mode,
        ) { selected ->
            mode = selected
            input = null
        }

        val audioMode = mode == "Аудио → другой формат"
        PrimaryButton(
            when {
                input != null && audioMode -> "Выбрать другое аудио"
                input != null -> "Выбрать другое изображение"
                audioMode -> "Выбрать MP3 / M4A / WAV"
                else -> "Выбрать PNG / JPG / WEBP"
            }
        ) {
            picker.launch(if (audioMode) arrayOf("audio/*") else arrayOf("image/*"))
        }
        FileCard(input)

        when (mode) {
            "Изображение → другой формат" -> {
                ChoiceRow("Формат результата", listOf("JPG", "PNG", "WEBP"), imageFormat) { imageFormat = it }
                Text("Качество: $quality%", fontWeight = FontWeight.Bold)
                Slider(value = quality.toFloat(), onValueChange = { quality = it.roundToInt() }, valueRange = 50f..100f)
            }
            "Аудио → другой формат" -> {
                ChoiceRow("Формат результата", listOf("MP3", "M4A", "WAV"), audioFormat) { audioFormat = it }
                if (audioFormat != "WAV") ChoiceRow("Качество", listOf("128", "192", "256", "320"), audioBitrate) { audioBitrate = it }
            }
        }

        PrimaryButton("Конвертировать", input != null) {
            input?.let { m ->
                when (mode) {
                    "Изображение → PDF" -> startJob(context, JobType.IMAGE_TO_PDF, m)
                    "Изображение → другой формат" -> startJob(context, JobType.IMAGE_CONVERT, m) {
                        putExtra(ProcessingService.EXTRA_IMAGE_FORMAT, imageFormat)
                        putExtra(ProcessingService.EXTRA_IMAGE_QUALITY, quality)
                    }
                    else -> startJob(context, JobType.AUDIO_CONVERT, m) {
                        putExtra(ProcessingService.EXTRA_AUDIO_FORMAT, audioFormat)
                        putExtra(ProcessingService.EXTRA_AUDIO_BITRATE, audioBitrate.toInt())
                    }
                }
            }
        }
        Text("Поддерживается: PNG, JPG/JPEG, WEBP, PDF, MP3, M4A и WAV. PDF создаётся из одного изображения за операцию. Очень большие фотографии уменьшаются для безопасной работы с памятью.", color = StrongMuted, fontSize = 12.sp)
    }
}

@Composable
private fun AboutScreen(onBack: () -> Unit) {
    ToolPage("О Tools Strong", onBack) {
        Box(Modifier.size(86.dp).background(StrongYellow, RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) { Text("S", color = Color.Black, fontWeight = FontWeight.Black, fontSize = 52.sp) }
        Text("Tools Strong", fontSize = 28.sp, fontWeight = FontWeight.Black)
        Text("Версия 1.0", color = StrongYellow, fontWeight = FontWeight.Bold)
        Text("Приложение для обработки видео, аудио и файлов прямо на Android. Без аккаунта, рекламы и загрузки личных файлов на сервер.", color = StrongMuted)
        Text("Оптимизировано под современные Samsung Galaxy и Android 16. Максимальное разрешение в интерфейсе — 1080p, как и было задумано.", color = StrongMuted)
        Text("MICHa Strong", fontWeight = FontWeight.Black, color = StrongYellow)
    }
}
