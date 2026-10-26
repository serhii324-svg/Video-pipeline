package ua.videostudio

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class Status(val ua: String) { QUEUED("В черзі"), GENERATING("Генерується"), DONE("Готово"), INACTIVE("Не активний") }

data class Scene(
    val id: String = UUID.randomUUID().toString(),
    val notebook: String, val number: Int,
    val prompt: String, val voice: String,
    val attachment: String? = null, val model: String,
    val status: Status = Status.QUEUED, val attempts: Int = 0,
    val error: String? = null, val output: String? = null
)

object Store {
    private lateinit var file: File
    lateinit var prefs: android.content.SharedPreferences
    val notebooks = MutableStateFlow<List<String>>(emptyList())
    val scenes = MutableStateFlow<List<Scene>>(emptyList())
    val running = MutableStateFlow(false)

    var apiKey get() = prefs.getString("key", "") ?: ""; set(v) = prefs.edit().putString("key", v).apply()
    var interval get() = prefs.getInt("interval", 30); set(v) = prefs.edit().putInt("interval", v).apply()
    var maxRetries get() = prefs.getInt("retries", 5); set(v) = prefs.edit().putInt("retries", v).apply()

    fun init(c: Context) {
        prefs = c.getSharedPreferences("s", 0); file = File(c.filesDir, "data.json")
        if (!file.exists()) return
        val j = JSONObject(file.readText())
        notebooks.value = j.getJSONArray("nb").let { a -> List(a.length()) { a.getString(it) } }
        scenes.value = j.getJSONArray("sc").let { a -> List(a.length()) { i ->
            val o = a.getJSONObject(i)
            Scene(o.getString("id"), o.getString("nb"), o.getInt("n"), o.getString("p"), o.getString("v"),
                o.optString("att").ifEmpty { null }, o.getString("m"), Status.valueOf(o.getString("st")),
                o.getInt("at"), o.optString("err").ifEmpty { null }, o.optString("out").ifEmpty { null })
        } }.map { if (it.status == Status.GENERATING) it.copy(status = Status.QUEUED) else it }
    }

    @Synchronized private fun save() {
        val j = JSONObject().put("nb", JSONArray(notebooks.value)).put("sc", JSONArray(scenes.value.map {
            JSONObject().put("id", it.id).put("nb", it.notebook).put("n", it.number).put("p", it.prompt).put("v", it.voice)
                .put("att", it.attachment ?: "").put("m", it.model).put("st", it.status.name).put("at", it.attempts)
                .put("err", it.error ?: "").put("out", it.output ?: "")
        }))
        file.writeText(j.toString())
    }

    fun addNotebook(n: String) { if (n.isNotBlank() && n !in notebooks.value) { notebooks.value += n.trim(); save() } }
    fun addScene(nb: String, p: String, v: String, att: String?, model: String) {
        val num = (scenes.value.filter { it.notebook == nb }.maxOfOrNull { it.number } ?: 0) + 1
        scenes.value += Scene(notebook = nb, number = num, prompt = p, voice = v, attachment = att, model = model); save()
    }
    fun update(id: String, f: (Scene) -> Scene) { scenes.value = scenes.value.map { if (it.id == id) f(it) else it }; save() }
    fun delete(id: String) { scenes.value = scenes.value.filter { it.id != id }; save() }
    fun next() = scenes.value.filter { it.status == Status.QUEUED }.minWithOrNull(compareBy({ it.notebook }, { it.number }))
}
