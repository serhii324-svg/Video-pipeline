package ua.videostudio

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object Api {
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta"
    private val http = OkHttpClient.Builder().readTimeout(180, TimeUnit.SECONDS).callTimeout(10, TimeUnit.MINUTES).build()

    private fun call(url: String, key: String, body: String? = null): JSONObject {
        val b = Request.Builder().url(url).header("x-goog-api-key", key)
        if (body != null) b.post(body.toRequestBody("application/json".toMediaType()))
        http.newCall(b.build()).execute().use {
            val t = it.body!!.string()
            if (!it.isSuccessful) error("HTTP ${it.code}: ${t.take(300)}")
            return JSONObject(t)
        }
    }

    /** Генерація відео через Veo. Кидає виняток із причиною, якщо не вийшло. */
    suspend fun generate(key: String, model: String, s: Scene, att: ByteArray?, mime: String?): ByteArray {
        val text = buildString {
            append(s.prompt)
            if (s.voice.isNotBlank())
                append("\nДиктор українською мовою чітко, повністю, без повторів і без проковтнутих слів, спокійним темпом каже: \"${s.voice}\"")
        }
        val inst = JSONObject().put("prompt", text)
        if (att != null && mime?.startsWith("image") == true)
            inst.put("image", JSONObject().put("bytesBase64Encoded", Base64.encodeToString(att, Base64.NO_WRAP)).put("mimeType", mime))
        val op = call("$BASE/models/$model:predictLongRunning", key,
            JSONObject().put("instances", JSONArray().put(inst)).toString()).getString("name")

        repeat(60) {
            delay(10_000)
            val r = call("$BASE/$op", key)
            if (!r.optBoolean("done")) return@repeat
            r.optJSONObject("error")?.let { error("Помилка: ${it.optString("message")}") }
            val g = r.getJSONObject("response").getJSONObject("generateVideoResponse")
            val samples = g.optJSONArray("generatedSamples")
            if (samples == null || samples.length() == 0)
                error("Не можу генерувати за цим запитом: ${g.optJSONArray("raiMediaFilteredReasons") ?: "заблоковано фільтром"}")
            val uri = samples.getJSONObject(0).getJSONObject("video").getString("uri")
            http.newCall(Request.Builder().url(uri).header("x-goog-api-key", key).build()).execute().use { d ->
                if (!d.isSuccessful) error("Завантаження: HTTP ${d.code}")
                return d.body!!.bytes()
            }
        }
        error("Тайм-аут генерації")
    }

    /** Перевірка озвучки Gemini. null = все добре, інакше — опис проблеми. Якщо сама перевірка впала — пропускаємо. */
    fun checkVoice(key: String, video: ByteArray, voice: String): String? = try {
        val q = "Ти контролер якості озвучки. Розпізнай мовлення диктора у відео і порівняй з очікуваним текстом:\n\"$voice\"\n" +
            "Відповідай ЛИШЕ JSON: {\"complete\":bool (весь текст прозвучав),\"swallowed\":bool (слова проковтнуті/обрізані),\"repeated\":bool (слова повторюються),\"heard\":\"що почув\"}"
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
            .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "video/mp4").put("data", Base64.encodeToString(video, Base64.NO_WRAP))))
            .put(JSONObject().put("text", q)))))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
        val t = call("$BASE/models/gemini-2.5-flash:generateContent", key, body.toString())
            .getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
        val r = JSONObject(t)
        when {
            r.optBoolean("repeated") -> "Диктор повторює слова (почуто: ${r.optString("heard")})"
            r.optBoolean("swallowed") -> "Диктор ковтає слова (почуто: ${r.optString("heard")})"
            !r.optBoolean("complete", true) -> "Текст озвучено не повністю (почуто: ${r.optString("heard")})"
            else -> null
        }
    } catch (e: Exception) { null }

    /** Downloads/<Блокнот>/01.mp4 — папку система створює сама. */
    fun saveVideo(c: Context, notebook: String, number: Int, bytes: ByteArray): String {
        val folder = notebook.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val v = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "%02d.mp4".format(number))
            put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$folder")
        }
        val uri = c.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)!!
        c.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
        return uri.toString()
    }
}
