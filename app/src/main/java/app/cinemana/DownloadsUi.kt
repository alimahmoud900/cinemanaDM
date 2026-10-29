package app.cinemana

import android.widget.Toast
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.cinemana.dl.*

@Composable
fun DownloadsScreen(onPlay: (Source, List<Sub>) -> Unit, modifier: Modifier) {
    val ctx = LocalContext.current
    val all by Store.jobs.collectAsState()
    var filter by remember { mutableStateOf("All") }
    val shown = all.filter { when (filter) {
        "Active" -> it.state == St.RUNNING; "Queued" -> it.state == St.QUEUED || it.state == St.PAUSED
        "Done" -> it.state == St.DONE; "Failed" -> it.state == St.FAILED; else -> true } }
    val speed = all.sumOf { it.bps }
    Column(modifier.padding(16.dp)) {
        Text(if (speed > 0) "Downloading at ${Fmt.speed(speed)}" else "Downloads", style = MaterialTheme.typography.titleLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("All", "Active", "Queued", "Done", "Failed").forEach { f -> FilterChip(filter == f, { filter = f }, { Text(f) }) } }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton({ Downloads.send(ctx, DownloadService.PAUSE_ALL, null) }) { Text("Pause all") }
            TextButton({ all.filter { it.state == St.PAUSED || it.state == St.FAILED }.forEach { Downloads.send(ctx, DownloadService.RESUME, it.id) } }) { Text("Resume all") }
            TextButton({ all.filter { it.state == St.DONE }.forEach { Store.remove(it.id) } }) { Text("Clear finished") }
        }
        if (shown.isEmpty()) Text("Nothing here yet. Select episodes or a movie in Browse and tap Download.", Modifier.padding(top = 24.dp))
        else LazyColumn { items(shown, key = { it.id }) { DlCard(it, onPlay) } }
    }
}

@Composable
private fun DlCard(d: Dl, onPlay: (Source, List<Sub>) -> Unit) {
    val ctx = LocalContext.current
    val frac = if (d.total > 0) (d.done.toFloat() / d.total).coerceIn(0f, 1f) else 0f
    val sizes = "${Fmt.bytes(d.done)} / ${Fmt.bytes(d.total)}"
    val info = when (d.state) {
        St.RUNNING -> "${(frac * 100).toInt()}% · $sizes · ${Fmt.speed(d.bps)} · ${Fmt.eta(d.etaSec)} left · ${d.conns} connections"
        St.QUEUED -> "Waiting for a free slot"
        St.PAUSED -> "Paused · $sizes"
        St.FAILED -> d.error ?: "Failed"
        St.DONE -> "Saved · ${Fmt.bytes(d.total)}"
    }
    fun act(a: String) = Downloads.send(ctx, a, d.id)
    fun play() {
        val mode = Prefs.str(ctx, "player", "Built-in"); val uri = d.uri ?: return
        val subs = d.subDone.map { Sub(it.name.substringBeforeLast('.').takeLast(2), it.url, it.name.substringAfterLast('.')) }
        if (mode == "Built-in") onPlay(Source(d.quality, uri), subs)
        else if (!Players.launch(ctx, mode, Uri.parse(uri), d.label, subs.map { Uri.parse(it.url) })) Toast.makeText(ctx, "$mode isn't installed.", Toast.LENGTH_SHORT).show()
    }
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) { Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) { Thumb(d.thumb, 64.dp, 92.dp); Column(Modifier.weight(1f)) {
        Text(d.label, style = MaterialTheme.typography.titleMedium, maxLines = 2)
        Text(d.quality, style = MaterialTheme.typography.bodySmall)
        if (d.state != St.DONE) SegBar(d.segs, frac, Modifier.fillMaxWidth().padding(vertical = 8.dp))
        Text(info, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            when (d.state) {
                St.RUNNING, St.QUEUED -> { TextButton({ act(DownloadService.PAUSE) }) { Text("Pause") }; TextButton({ act(DownloadService.CANCEL) }) { Text("Cancel") } }
                St.PAUSED -> { TextButton({ act(DownloadService.RESUME) }) { Text("Resume") }; TextButton({ act(DownloadService.CANCEL) }) { Text("Cancel") } }
                St.FAILED -> { TextButton({ act(DownloadService.RESUME) }) { Text("Retry") }; TextButton({ act(DownloadService.CANCEL) }) { Text("Remove") } }
                St.DONE -> { TextButton({ play() }) { Text("Play") }
                    TextButton({ Store.remove(d.id) }) { Text("Remove from list") } }
            }
        }
    } } }
}
