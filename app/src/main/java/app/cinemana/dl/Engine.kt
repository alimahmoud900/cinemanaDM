package app.cinemana.dl

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicInteger

/** One byte range of the file. [pos] is the next byte to write. */
class Seg(val start: Long, val end: Long, @Volatile var pos: Long) { val left get() = end - pos + 1 }
data class Saved(val total: Long, val ranged: Boolean, val segs: List<Seg>)
data class Progress(val done: Long, val total: Long, val bps: Double, val etaSec: Long, val conns: Int, val segs: List<Float> = emptyList())

object Planner {
    /** Split [0,total) into up to [connections] ranges, each at least [minSeg] bytes. */
    fun plan(total: Long, connections: Int, minSeg: Long = 2L shl 20): List<LongRange> {
        val n = maxOf(1L, minOf(connections.toLong(), total / minSeg))
        val size = (total + n - 1) / n
        return (0 until n).map { val s = it * size; s..minOf(total - 1, s + size - 1) }.filter { it.first <= it.last }
    }
}

object SegState {
    fun encode(s: Saved) = "${s.total},${s.ranged}\n" + s.segs.joinToString("\n") { "${it.start},${it.end},${it.pos}" }
    fun decode(text: String): Saved? = runCatching {
        val l = text.lines().filter { it.isNotBlank() }; val h = l[0].split(",")
        Saved(h[0].toLong(), h[1].toBoolean(), l.drop(1).map { v -> v.split(",").let { Seg(it[0].toLong(), it[1].toLong(), it[2].toLong()) } })
    }.getOrNull()
}

class SpeedMeter(startBytes: Long = 0, private val clock: () -> Long = { System.nanoTime() }) {
    private var lastT = clock(); private var lastB = startBytes
    var bps = 0.0; private set
    fun sample(done: Long): Double {
        val t = clock(); val dt = (t - lastT) / 1e9
        if (dt > 0) { val inst = (done - lastB) / dt; bps = if (bps == 0.0) inst else bps * 0.7 + inst * 0.3; lastT = t; lastB = done }
        return bps
    }
    fun etaSeconds(remaining: Long) = if (bps > 1) (remaining / bps).toLong() else -1L
}

object Fmt {
    fun bytes(n: Long): String { var v = n.toDouble().coerceAtLeast(0.0)
        for (u in listOf("B", "KB", "MB", "GB")) { if (v < 1024) return "%.1f %s".format(v, u); v /= 1024 }
        return "%.1f TB".format(v) }
    fun speed(bps: Double) = bytes(bps.toLong()) + "/s"
    fun eta(s: Long) = when { s < 0 -> "--:--"; s >= 3600 -> "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60); else -> "%02d:%02d".format(s / 60, s % 60) }
}

/** Multi-connection HTTP Range downloader with resume. Writes into a pre-sized [part] file plus a ".state" file. */
class SegmentDownloader(val http: OkHttpClient) {
    class Probe(val ranged: Boolean, val total: Long)

    fun probe(url: String): Probe {
        val req = Request.Builder().url(url).header("Range", "bytes=0-0").header("Accept-Encoding", "identity").build()
        http.newCall(req).execute().use { r ->
            return when (r.code) {
                206 -> Probe(true, r.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: -1)
                200 -> Probe(false, r.body?.contentLength() ?: -1)
                else -> error("Server returned HTTP ${r.code}")
            }
        }
    }

    suspend fun download(url: String, part: File, connections: Int, onProgress: (Progress) -> Unit) = coroutineScope {
        part.parentFile?.mkdirs()
        val stateFile = File(part.path + ".state")
        val s = (if (part.exists() && stateFile.exists()) SegState.decode(stateFile.readText()) else null) ?: withContext(Dispatchers.IO) {
            val p = probe(url); require(p.total > 0) { "Server did not report the file size." }
            RandomAccessFile(part, "rw").use { it.setLength(p.total) }
            val plan = if (p.ranged) Planner.plan(p.total, connections) else listOf(0L until p.total)
            Saved(p.total, p.ranged, plan.map { Seg(it.first, it.last, it.first) })
        }
        fun doneNow() = s.total - s.segs.sumOf { it.left }
        fun persist() = stateFile.writeText(SegState.encode(s))
        val meter = SpeedMeter(doneNow()); val active = AtomicInteger(0)
        val ticker = launch {
            while (true) { delay(500); val d = doneNow(); val b = meter.sample(d)
                onProgress(Progress(d, s.total, b, meter.etaSeconds(s.total - d), active.get(),
                    s.segs.map { ((it.pos - it.start).toFloat() / (it.end - it.start + 1)).coerceIn(0f, 1f) })); persist() }
        }
        try {
            RandomAccessFile(part, "rw").use { raf ->
                s.segs.filter { it.left > 0 }.map { seg ->
                    async(Dispatchers.IO) { active.incrementAndGet(); try { work(url, raf.channel, seg, s.ranged) } finally { active.decrementAndGet() } }
                }.awaitAll()
            }
        } finally { ticker.cancel(); persist() }
        check(s.segs.all { it.left == 0L }) { "Download incomplete." }
        onProgress(Progress(s.total, s.total, 0.0, 0, 0)); stateFile.delete()
    }

    private suspend fun work(url: String, ch: FileChannel, seg: Seg, ranged: Boolean) {
        var fails = 0
        while (seg.left > 0) {
            currentCoroutineContext().ensureActive()
            try {
                if (!ranged) seg.pos = seg.start
                val rb = Request.Builder().url(url).header("Accept-Encoding", "identity")
                if (ranged) rb.header("Range", "bytes=${seg.pos}-${seg.end}")
                http.newCall(rb.build()).execute().use { r ->
                    if (r.code != (if (ranged) 206 else 200)) error("HTTP ${r.code}")
                    val src = r.body!!.source(); val buf = ByteArray(64 * 1024)
                    while (seg.left > 0) {
                        currentCoroutineContext().ensureActive()
                        val n = src.read(buf, 0, minOf(buf.size.toLong(), seg.left).toInt()); if (n < 0) break
                        val bb = ByteBuffer.wrap(buf, 0, n); var p = seg.pos
                        while (bb.hasRemaining()) p += ch.write(bb, p)
                        seg.pos = p; fails = 0
                    }
                }
                if (seg.left > 0) error("Connection closed early")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (++fails > 5) throw e; delay(fails * 1000L) }
        }
    }
}
