package ua.videostudio

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val MODELS = listOf("veo-3.0-generate-001" to "Veo 3", "veo-3.0-fast-generate-001" to "Veo 3 Fast")

class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Store.init(this)
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        setContent { MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF1F6FEB), background = Color(0xFF0D1117), surface = Color(0xFF161B22))) { App() } }
    }
}

@Composable fun CardBox(content: @Composable ColumnScope.() -> Unit) =
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content) }

@Composable fun Pick(label: String, items: List<String>, value: String, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Text(label, style = MaterialTheme.typography.titleSmall)
    Box { OutlinedButton({ open = true }, Modifier.fillMaxWidth()) { Text(value.ifEmpty { "—" }) }
        DropdownMenu(open, { open = false }) { items.forEachIndexed { i, t -> DropdownMenuItem({ Text(t) }, { onPick(i); open = false }) } } }
}

@Composable fun App() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val nbs by Store.notebooks.collectAsState(); val scenes by Store.scenes.collectAsState(); val running by Store.running.collectAsState()
    var name by remember { mutableStateOf("") }; var prompt by remember { mutableStateOf("") }; var voice by remember { mutableStateOf("") }
    var nb by remember { mutableStateOf("") }; var model by remember { mutableStateOf(MODELS[0]) }
    var att by remember { mutableStateOf<Uri?>(null) }; var key by remember { mutableStateOf(Store.apiKey) }
    var interval by remember { mutableStateOf(Store.interval.toString()) }
    var attachFor by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            attachFor?.let { id -> Store.update(id) { it.copy(attachment = u.toString()) } } ?: run { att = u }; attachFor = null }
    }
    if (nb.isEmpty() && nbs.isNotEmpty()) nb = nbs[0]

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("🎬 AI Video Studio", style = MaterialTheme.typography.headlineMedium) }
        item { CardBox {
            Text("⚙️ Налаштування", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(key, { key = it; Store.apiKey = it }, label = { Text("Gemini API key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(interval, { interval = it; it.toIntOrNull()?.let { v -> Store.interval = v } }, label = { Text("Інтервал між відео, сек") }, singleLine = true)
            Text("Повторів при помилці: ${Store.maxRetries}. Після цього — «Не активний».", style = MaterialTheme.typography.bodySmall)
        } }
        item { CardBox {
            Text("📁 Створити блокнот", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(name, { name = it }, label = { Text("Назва...") }, modifier = Modifier.fillMaxWidth())
            Button({ Store.addNotebook(name); nb = name.trim(); name = "" }, Modifier.fillMaxWidth()) { Text("Створити ➕") }
        } }
        item { CardBox {
            OutlinedTextField(prompt, { prompt = it }, label = { Text("Промпт (сцена, камера, стиль)") }, minLines = 3, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(voice, { voice = it }, label = { Text("Текст диктора") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            OutlinedButton({ attachFor = null; picker.launch(arrayOf("image/*")) }, Modifier.fillMaxWidth()) { Text(if (att == null) "📎 Прикріпити фото" else "📎 Фото прикріплено ✓") }
            Pick("Блокнот:", nbs, nb) { nb = nbs[it] }
            Pick("Модель:", MODELS.map { it.second }, MODEL_NAME(model)) { model = MODELS[it] }
            Button({ if (nb.isNotEmpty() && prompt.isNotBlank()) { Store.addScene(nb, prompt, voice, att?.toString(), model.first); prompt = ""; voice = ""; att = null } },
                Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7B2CBF))) { Text("Додати в чергу 🚀") }
        } }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("📊 Черга (${scenes.count { it.status == Status.QUEUED }})", style = MaterialTheme.typography.titleLarge)
            if (running) Button({ QueueService.stop(ctx) }) { Text("⏸ Пауза") } else Button({ QueueService.start(ctx) }) { Text("▶️ Старт") }
        } }
        items(scenes.sortedWith(compareBy({ it.notebook }, { it.number })), key = { it.id }) { s -> CardBox {
            Text("${s.notebook} · %02d · ${s.status.ua}".format(s.number), style = MaterialTheme.typography.titleSmall)
            Text(s.prompt, maxLines = 3, style = MaterialTheme.typography.bodySmall)
            if (s.attempts > 0) Text("Невдалих спроб: ${s.attempts}", style = MaterialTheme.typography.bodySmall)
            s.error?.let { Text(it, color = Color(0xFFFF7B72), style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (s.status == Status.INACTIVE || s.status == Status.DONE) TextButton({ Store.update(s.id) { it.copy(status = Status.QUEUED, attempts = 0, error = null) }; QueueService.start(ctx) }) { Text("В чергу") }
                if (s.status != Status.GENERATING) TextButton({ attachFor = s.id; picker.launch(arrayOf("image/*")) }) { Text(if (s.attachment != null) "📎 ✓" else "📎") }
                if (s.output != null) TextButton({ ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.output)).setDataAndType(Uri.parse(s.output), "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }) { Text("▶ Перегляд") }
                TextButton({ Store.delete(s.id) }) { Text("🗑") }
            }
        } }
    }
}
fun MODEL_NAME(m: Pair<String, String>) = m.second
