package app.cinemana.dl

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.IBinder
import app.cinemana.R
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class DownloadService : Service() {
    companion object { const val PUMP = "pump"; const val PAUSE = "pause"; const val RESUME = "resume"; const val CANCEL = "cancel"; const val PAUSE_ALL = "pause_all"; const val CH = "downloads" }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val running = ConcurrentHashMap<String, Job>()
    private val engine = SegmentDownloader(OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build())
    private val nm by lazy { getSystemService(NotificationManager::class.java) }
    private fun partDir() = File(getExternalFilesDir(null), "parts").apply { mkdirs() }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate(); Store.init(this)
        nm.createNotificationChannel(NotificationChannel(CH, "Downloads", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, summary(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(netCb)
    }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        val id = i?.getStringExtra("id")
        when (i?.action) {
            PAUSE -> id?.let { pause(it) }
            PAUSE_ALL -> Store.jobs.value.filter { it.state == St.RUNNING || it.state == St.QUEUED }.forEach { pause(it.id) }
            RESUME -> id?.let { Store.update(it) { d -> if (d.state == St.PAUSED || d.state == St.FAILED) d.copy(state = St.QUEUED, error = null) else d }; nm.cancel(it.hashCode()) }
            CANCEL -> id?.let { running.remove(it)?.cancel(); Store.remove(it); nm.cancel(it.hashCode())
                File(partDir(), "$it.part").delete(); File(partDir(), "$it.part.state").delete() }
        }
        pump(); return START_STICKY
    }

    private val netCb = object : ConnectivityManager.NetworkCallback() { override fun onCapabilitiesChanged(n: Network, c: NetworkCapabilities) { pump() } }
    private fun allowed() = !Prefs.wifiOnly(this) || !getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered
    override fun onDestroy() { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(netCb) }; scope.cancel(); super.onDestroy() }

    private fun pause(id: String) {
        Store.update(id) { it.copy(state = St.PAUSED, bps = 0.0, etaSec = -1, conns = 0) }
        running.remove(id)?.cancel(); Store.get(id)?.let(::notifyState)
    }

    @Synchronized private fun pump() {
        val jobs = Store.jobs.value
        for (d in jobs) { if (running.size >= Prefs.maxActive(this) || !allowed()) break; if (d.state == St.QUEUED && !running.containsKey(d.id)) start(d) }
        if (running.isEmpty() && jobs.none { it.state == St.QUEUED }) { stopForeground(STOP_FOREGROUND_DETACH); nm.cancel(1); stopSelf() }
        else nm.notify(1, summary())
    }

    private fun start(d0: Dl) {
        val id = d0.id
        val job = scope.launch(start = CoroutineStart.LAZY) {
            Store.update(id) { it.copy(state = St.RUNNING, error = null) }
            val part = File(partDir(), "$id.part")
            try {
                engine.download(d0.url, part, Prefs.connections(this@DownloadService)) { p ->
                    Store.update(id, false) { it.copy(done = p.done, total = p.total, bps = p.bps, etaSec = p.etaSec, conns = p.conns, segs = p.segs) }
                    Store.get(id)?.let(::notifyProgress); nm.notify(1, summary())
                }
                val tree = Prefs.tree(this@DownloadService)
                val uri = Storage.publish(this@DownloadService, part, d0.title, d0.file, tree)
                val subDone = d0.subs.mapNotNull { s -> runCatching {
                    val tmp = File(partDir(), "${id}_${s.name}")
                    engine.http.newCall(Request.Builder().url(s.url).build()).execute().use { r ->
                        require(r.isSuccessful); tmp.outputStream().use { o -> r.body!!.byteStream().copyTo(o) } }
                    SubFile(s.name, Storage.publish(this@DownloadService, tmp, d0.title, s.name, tree).toString()) }.getOrNull() }
                Store.update(id) { it.copy(state = St.DONE, uri = uri.toString(), subDone = subDone, segs = emptyList(), bps = 0.0, etaSec = -1, conns = 0) }
                Store.get(id)?.let(::notifyState)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                Store.update(id) { it.copy(state = St.FAILED, error = e.message ?: "Download failed", bps = 0.0, etaSec = -1, conns = 0) }
                Store.get(id)?.let(::notifyState)
            } finally { running.remove(id); pump() }
        }
        running[id] = job; job.start()
    }

    private fun pi(action: String, id: String?) = PendingIntent.getForegroundService(this, (action + id).hashCode(),
        Intent(this, DownloadService::class.java).setAction(action).putExtra("id", id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun summary(): Notification {
        val jobs = Store.jobs.value; val act = jobs.filter { it.state == St.RUNNING }; val q = jobs.count { it.state == St.QUEUED }
        return NotificationCompat.Builder(this, CH).setSmallIcon(R.drawable.ic_stat_dm)
            .setContentTitle(if (act.isEmpty()) "CinemanaDM" else "${act.size} downloading" + if (q > 0) " · $q queued" else "")
            .setContentText(Fmt.speed(act.sumOf { it.bps })).setOngoing(true).setOnlyAlertOnce(true).setGroup("dl").setGroupSummary(true)
            .addAction(0, "Pause all", pi(PAUSE_ALL, null)).build()
    }

    private fun notifyProgress(d: Dl) {
        val pct = if (d.total > 0) (d.done * 100 / d.total).toInt() else 0
        nm.notify(d.id.hashCode(), NotificationCompat.Builder(this, CH).setSmallIcon(R.drawable.ic_stat_dm).setContentTitle(d.label)
            .setContentText("$pct% · ${Fmt.speed(d.bps)} · ${Fmt.eta(d.etaSec)} left · ${Fmt.bytes(d.done)} / ${Fmt.bytes(d.total)}")
            .setSubText("${d.quality} · ${d.conns} connections").setProgress(100, pct, d.total <= 0).setOngoing(true).setOnlyAlertOnce(true).setGroup("dl")
            .addAction(0, "Pause", pi(PAUSE, d.id)).addAction(0, "Cancel", pi(CANCEL, d.id)).build())
    }

    private fun notifyState(d: Dl) {
        val b = NotificationCompat.Builder(this, CH).setContentTitle(d.label).setGroup("dl").setAutoCancel(true)
        when (d.state) {
            St.DONE -> b.setSmallIcon(android.R.drawable.stat_sys_download_done).setContentText("Saved · ${Fmt.bytes(d.total)}")
            St.FAILED -> b.setSmallIcon(android.R.drawable.stat_notify_error).setContentText(d.error ?: "Failed").addAction(0, "Retry", pi(RESUME, d.id))
            St.PAUSED -> b.setSmallIcon(android.R.drawable.ic_media_pause).setAutoCancel(false)
                .setContentText("Paused at ${Fmt.bytes(d.done)} / ${Fmt.bytes(d.total)}")
                .addAction(0, "Resume", pi(RESUME, d.id)).addAction(0, "Cancel", pi(CANCEL, d.id))
            else -> return
        }
        nm.notify(d.id.hashCode(), b.build())
    }
}
