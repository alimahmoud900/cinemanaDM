package app.cinemana

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object Api {
    private const val BASE = "https://cinemana.shabakaty.cc/api/android"
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val cache = ConcurrentHashMap<String, Pair<Long, Any>>()

    private suspend fun get(path: String, vararg q: Pair<String, String>): Any = withContext(Dispatchers.IO) {
        val url = (BASE + path).toHttpUrl().newBuilder().apply { q.forEach { addQueryParameter(it.first, it.second) } }.build()
        cache[url.toString()]?.takeIf { System.currentTimeMillis() - it.first < 300_000 }?.let { return@withContext it.second }
        val req = Request.Builder().url(url).header("User-Agent", "CinemanaDM/3.1 (Android)").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("Server returned HTTP ${r.code}. Try again in a moment.")
            JSONTokener(r.body!!.string()).nextValue().also { cache[url.toString()] = System.currentTimeMillis() to it }
        }
    }

    private fun items(d: Any): List<JSONObject> {
        val arr = when (d) {
            is JSONArray -> d
            is JSONObject -> listOf("data", "results", "items", "videos", "episodes").firstNotNullOfOrNull { d.optJSONArray(it) } ?: JSONArray().put(d)
            else -> JSONArray()
        }
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    private fun JSONObject.str(k: String) = if (has(k) && !isNull(k)) get(k).toString().ifBlank { null } else null

    private fun img(o: JSONObject) = o.str("imgObjUrl") ?: o.str("imgMediumThumbObj") ?: o.str("imgThumbObj") ?: o.str("imgThumb")

    suspend fun search(raw: String, series: Boolean): List<Title> {
        val (q, year) = Matching.splitYear(raw)
        val out = LinkedHashMap<String, Title>()
        for (page in 0..2) {
            val batch = items(get("/AdvancedSearch", "level" to "0", "videoTitle" to q,
                "year" to (year?.let { "$it,$it" } ?: ""), "type" to if (series) "series" else "movies", "page" to "$page"))
            if (batch.isEmpty()) break
            for (o in batch) {
                val id = o.str("nb") ?: continue
                val ref = o.str("imdbUrlRef") ?: o.str("externalUrl") ?: ""
                out.putIfAbsent(id, Title(id, o.str("en_title"), o.str("ar_title"), o.str("year"),
                    Regex("(tt\\d+)", RegexOption.IGNORE_CASE).find(ref)?.value?.lowercase(), thumb = img(o)))
            }
        }
        return out.values.map { it.copy(score = Matching.score(q, year, it)) }.sortedByDescending { it.score }
    }

    suspend fun episodes(seriesId: String): List<Episode> = items(get("/videoSeason/id/$seriesId")).mapNotNull { o ->
        val id = o.str("nb") ?: return@mapNotNull null
        Episode(id, o.str("en_title") ?: o.str("ar_title") ?: "Untitled", o.str("season")?.toIntOrNull() ?: 0, o.str("episodeNummer")?.toIntOrNull() ?: 0, img(o))
    }.sortedWith(compareBy({ it.season }, { it.number }))

    suspend fun sources(id: String): List<Source> = items(get("/transcoddedFiles/id/$id")).mapNotNull { o ->
        o.str("videoUrl")?.let { Source(o.str("name") ?: o.str("resolution") ?: "?", it) }
    }.sortedByDescending { Matching.quality(it) }

    suspend fun subtitles(id: String): List<Sub> {
        val urls = LinkedHashSet<String>()
        fun walk(x: Any?) {
            when (x) {
                is JSONObject -> x.keys().forEach { k -> walk(x.opt(k)) }
                is JSONArray -> for (i in 0 until x.length()) walk(x.opt(i))
                is String -> if (x.startsWith("http") && (".srt" in x.lowercase() || ".vtt" in x.lowercase())) urls += x
            }
        }
        walk(get("/translationFiles/id/$id"))
        val lang = Regex("(?:^|[_./-])(ar|en|fr|es|de|tr|fa|ku|ur)(?:[_./-]|$)", RegexOption.IGNORE_CASE)
        return urls.map { Sub(lang.find(it)?.groupValues?.get(1)?.lowercase() ?: "und", it, if (".vtt" in it.lowercase()) "vtt" else "srt") }
    }

}
