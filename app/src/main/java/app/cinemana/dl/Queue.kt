package app.cinemana.dl

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class St { QUEUED, RUNNING, PAUSED, DONE, FAILED }
data class SubFile(val name: String, val url: String)
data class Dl(
    val id: String, val title: String, val label: String, val quality: String, val url: String, val file: String,
    val subs: List<SubFile>, val thumb: String? = null, val state: St = St.QUEUED, val done: Long = 0, val total: Long = 0,
    val bps: Double = 0.0, val etaSec: Long = -1, val conns: Int = 0, val segs: List<Float> = emptyList(),
    val error: String? = null, val uri: String? = null, val subDone: List<SubFile> = emptyList(),
)

/** Queue state, observable by the UI and persisted to filesDir/queue.json. */
object Store {
    val jobs = MutableStateFlow<List<Dl>>(emptyList())
    private var file: File? = null

    fun init(ctx: Context) {
        if (file != null) return
        file = File(ctx.filesDir, "queue.json")
        jobs.value = load().map { if (it.state == St.RUNNING) it.copy(state = St.QUEUED) else it }
    }
    @Synchronized fun add(d: Dl) { jobs.value = jobs.value + d; save() }
    @Synchronized fun remove(id: String) { jobs.value = jobs.value.filter { it.id != id }; save() }
    @Synchronized fun update(id: String, persist: Boolean = true, f: (Dl) -> Dl) {
        jobs.value = jobs.value.map { if (it.id == id) f(it) else it }; if (persist) save()
    }
    fun get(id: String) = jobs.value.firstOrNull { it.id == id }

    private fun save() { runCatching { file?.writeText(JSONArray(jobs.value.map(::toJson)).toString()) } }
    private fun load(): List<Dl> = runCatching {
        val a = JSONArray(file!!.readText()); (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun subsJson(l: List<SubFile>) = JSONArray(l.map { JSONObject().put("n", it.name).put("u", it.url) })
    private fun subsOf(a: JSONArray?) = (0 until (a?.length() ?: 0)).map { SubFile(a!!.getJSONObject(it).getString("n"), a.getJSONObject(it).getString("u")) }
    private fun toJson(d: Dl) = JSONObject().put("id", d.id).put("title", d.title).put("label", d.label).put("quality", d.quality)
        .put("url", d.url).put("file", d.file).put("thumb", d.thumb).put("state", d.state.name).put("done", d.done).put("total", d.total)
        .put("error", d.error).put("uri", d.uri).put("subs", subsJson(d.subs)).put("subDone", subsJson(d.subDone))
    private fun fromJson(o: JSONObject) = Dl(
        id = o.getString("id"), title = o.getString("title"), label = o.getString("label"), quality = o.getString("quality"),
        url = o.getString("url"), file = o.getString("file"), subs = subsOf(o.optJSONArray("subs")), thumb = o.optString("thumb").ifEmpty { null },
        state = St.valueOf(o.getString("state")), done = o.optLong("done"), total = o.optLong("total"),
        error = o.optString("error").ifEmpty { null }, uri = o.optString("uri").ifEmpty { null }, subDone = subsOf(o.optJSONArray("subDone")))
}

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("prefs", 0)
    fun int(c: Context, k: String, d: Int) = sp(c).getInt(k, d)
    fun str(c: Context, k: String, d: String) = sp(c).getString(k, d) ?: d
    fun bool(c: Context, k: String, d: Boolean) = sp(c).getBoolean(k, d)
    fun put(c: Context, k: String, v: Any?) {
        val e = sp(c).edit()
        when (v) { is Int -> e.putInt(k, v); is Boolean -> e.putBoolean(k, v); is String -> e.putString(k, v); else -> e.remove(k) }
        e.apply()
    }
    fun connections(c: Context) = int(c, "connections", 8)
    fun maxActive(c: Context) = int(c, "maxActive", 2)
    fun wifiOnly(c: Context) = bool(c, "wifi", false)
    fun tree(c: Context): Uri? = sp(c).getString("tree", null)?.let(Uri::parse)
}

object Downloads {
    fun enqueue(ctx: Context, title: String, label: String, quality: String, url: String, file: String, subs: List<SubFile>, thumb: String?) {
        Store.init(ctx); Store.add(Dl(UUID.randomUUID().toString(), title, label, quality, url, file, subs, thumb)); send(ctx, DownloadService.PUMP, null)
    }
    fun send(ctx: Context, action: String, id: String?) =
        ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java).setAction(action).putExtra("id", id))
}
