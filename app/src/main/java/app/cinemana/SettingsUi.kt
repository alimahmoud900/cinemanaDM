package app.cinemana

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.cinemana.dl.Prefs

@Composable
fun SettingsScreen(modifier: Modifier) {
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    @Suppress("UNUSED_VARIABLE") val t = tick
    fun set(k: String, v: Any?) { Prefs.put(ctx, k, v); tick++ }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u ->
        if (u != null) { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION); set("tree", u.toString()) } }
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { Logo(30.dp); Text("Settings", style = MaterialTheme.typography.titleLarge) }
        Choose("Subtitles to save", Matching.SUB_MODES, Prefs.str(ctx, "subs", Matching.SUB_MODES[0])) { set("subs", it) }
        Choose("Default quality", listOf("Highest", "1080", "720", "480", "Lowest"), Prefs.str(ctx, "quality", "Highest")) { set("quality", it) }
        Choose("Connections per file", listOf("1", "4", "8", "12", "16"), Prefs.connections(ctx).toString()) { set("connections", it.toInt()) }
        Choose("Downloads at once", listOf("1", "2", "3", "4"), Prefs.maxActive(ctx).toString()) { set("maxActive", it.toInt()) }
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Download on Wi-Fi only", Modifier.weight(1f)); Switch(Prefs.wifiOnly(ctx), { set("wifi", it) }) }
        Choose("Open videos with", Players.MODES, Prefs.str(ctx, "player", "Built-in")) { set("player", it) }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Save to", style = MaterialTheme.typography.titleSmall)
            Text(Prefs.tree(ctx)?.lastPathSegment ?: "Movies/Cinemana (default)")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ folder.launch(null) }) { Text("Choose folder") }
                if (Prefs.tree(ctx) != null) TextButton({ set("tree", null) }) { Text("Use default") }
            }
        }
        OutlinedTextField(Prefs.str(ctx, "template", Matching.DEFAULT_TEMPLATE), { set("template", it) }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("Episode file name") }, supportingText = { Text("Use {title} {s} {e} {name}") })
    }
}

@Composable
private fun Choose(title: String, options: List<String>, selected: String, onPick: (String) -> Unit) = Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(title, style = MaterialTheme.typography.titleSmall)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { FilterChip(it == selected, { onPick(it) }, { Text(it) }) } }
}
