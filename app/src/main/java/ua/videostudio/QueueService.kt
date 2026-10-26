package ua.videostudio

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.*

class QueueService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (i?.action == "STOP") { job?.cancel(); stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("q", "Черга", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, notif("Запуск..."))
        wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vs:q").apply { acquire(6 * 3600_000L) }
        if (job?.isActive != true) job = scope.launch { loop(nm) }
        return START_STICKY
    }

    private fun notif(t: String) = Notification.Builder(this, "q").setContentTitle("AI Video Studio")
        .setContentText(t).setSmallIcon(android.R.drawable.ic_media_play).setOngoing(true).build()

    private suspend fun loop(nm: NotificationManager) {
        Store.running.value = true
        try {
            while (true) {
                val s = Store.next() ?: break
                nm.notify(1, notif("${s.notebook} · сцена %02d (спроба ${s.attempts + 1})".format(s.number)))
                process(s)
                if (Store.next() != null) delay(Store.interval * 1000L)
            }
        } finally { Store.running.value = false; wake?.release(); stopSelf() }
    }

    private suspend fun process(s: Scene) {
        Store.update(s.id) { it.copy(status = Status.GENERATING, error = null) }
        try {
            val key = Store.apiKey.ifBlank { error("Не вказано API-ключ у налаштуваннях") }
            var att: ByteArray? = null; var mime: String? = null
            s.attachment?.let { u -> val uri = Uri.parse(u); mime = contentResolver.getType(uri)
                att = contentResolver.openInputStream(uri)?.use { it.readBytes() } }
            val video = Api.generate(key, s.model, s, att, mime)
            if (s.voice.isNotBlank()) Api.checkVoice(key, video, s.voice)?.let { error("Якість озвучки: $it") }
            val out = Api.saveVideo(this, s.notebook, s.number, video)
            Store.update(s.id) { it.copy(status = Status.DONE, output = out, error = null) }
        } catch (e: CancellationException) {
            Store.update(s.id) { it.copy(status = Status.QUEUED) }; throw e
        } catch (e: Exception) {
            val fails = s.attempts + 1
            // 1 спроба + 5 повторів; на 6-й невдачі — «Не активний»
            val st = if (fails > Store.maxRetries) Status.INACTIVE else Status.QUEUED
            Store.update(s.id) { it.copy(status = st, attempts = fails, error = e.message) }
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        fun start(c: Context) = c.startForegroundService(Intent(c, QueueService::class.java))
        fun stop(c: Context) = c.startService(Intent(c, QueueService::class.java).setAction("STOP"))
    }
}
