package app.cinemana

import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import app.cinemana.dl.*
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        if (Build.VERSION.SDK_INT >= 33) registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { AppTheme { Surface(Modifier.fillMaxSize(), color = Ink) { Root() } } }
    }
}

@Composable
fun Root() {
    var tab by remember { mutableStateOf(0) }
    var playing by remember { mutableStateOf<Pair<Source, List<Sub>>?>(null) }
    val jobs by Store.jobs.collectAsState()
    val active = jobs.count { it.state == St.RUNNING || it.state == St.QUEUED }
    val play = { s: Source, subs: List<Sub> -> playing = s to subs }
    BackHandler(playing != null) { playing = null }
    playing?.let { Player(it.first, it.second); return }
    Scaffold(containerColor = Ink, bottomBar = {
        NavigationBar(containerColor = Slate) {
            NavigationBarItem(tab == 0, { tab = 0 }, {}, label = { Text("Browse") })
            NavigationBarItem(tab == 1, { tab = 1 }, {}, label = { BadgedBox({ if (active > 0) Badge { Text("$active") } }) { Text("Downloads") } })
            NavigationBarItem(tab == 2, { tab = 2 }, {}, label = { Text("Settings") })
        }
    }) { pad ->
        when (tab) {
            0 -> BrowseScreen(play, { tab = 1 }, Modifier.padding(pad))
            1 -> DownloadsScreen(play, Modifier.padding(pad))
            else -> SettingsScreen(Modifier.padding(pad))
        }
    }
}

data class Choice(val id: String, val label: String, val base: String, val thumb: String?, val sources: List<Source>)

@Composable
fun BrowseScreen(onPlay: (Source, List<Sub>) -> Unit, openDownloads: () -> Unit, modifier: Modifier) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var series by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<Title>?>(null) }
    var picked by remember { mutableStateOf<Title?>(null) }
    var episodes by remember { mutableStateOf<List<Episode>>(emptyList()) }
    var season by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var rangeMode by remember { mutableStateOf(false) }
    var rangeFrom by remember { mutableStateOf<Int?>(null) }
    var quality by remember { mutableStateOf(Prefs.str(ctx, "quality", "Highest")) }
    var choice by remember { mutableStateOf<Choice?>(null) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    val subMode = { Prefs.str(ctx, "subs", Matching.SUB_MODES[0]) }

    fun run(block: suspend () -> Unit) { scope.launch { busy = true; msg = null
        try { block() } catch (e: Exception) { msg = e.message ?: "Couldn't reach Cinemana. Check your connection." }
        busy = false } }
    suspend fun enqueue(c: Choice, s: Source, title: String) {
        val subs = Matching.pickSubs(Api.subtitles(c.id), subMode()).map { SubFile("${c.base}_${it.lang}.${it.fmt}", it.url) }
        Downloads.enqueue(ctx, title, c.label, s.name, s.url, "${c.base}.mp4", subs, c.thumb)
    }
    fun open(c: Choice) = run { val s = Api.sources(c.id); if (s.isEmpty()) msg = "No playable files for ${c.label}." else choice = c.copy(sources = s) }
    fun search() = run { picked = null; selected = emptySet(); results = Api.search(query, series) }
    fun pick(t: Title) = run {
        picked = t; selected = emptySet(); rangeMode = false
        if (series) { episodes = Api.episodes(t.id); season = episodes.firstOrNull()?.season ?: 0 }
        else open(Choice(t.id, t.name, Matching.safeName(t.name), t.thumb, emptyList())) }
    fun downloadSelected(p: Title) = run {
        val chosen = episodes.filter { it.id in selected }; var added = 0
        for (e in chosen) runCatching {
            val src = Matching.pickSource(Api.sources(e.id), quality) ?: error("no files")
            val base = Matching.episodeFile(Prefs.str(ctx, "template", Matching.DEFAULT_TEMPLATE), p.name, e)
            enqueue(Choice(e.id, "${p.name} ${e.code} · ${e.title}", base, e.thumb ?: p.thumb, emptyList()), src, p.name); added++
        }
        selected = emptySet()
        msg = if (added == chosen.size) "Added $added to Downloads." else "Added $added of ${chosen.size}. The rest had no playable files."
    }

    BackHandler(picked != null) { picked = null; selected = emptySet() }

    Column(modifier.padding(16.dp)) {
        Row(Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Logo(30.dp); Text("CinemanaDM", style = MaterialTheme.typography.titleLarge) }
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Title, optionally with year") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search() }))
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!series, { series = false }, { Text("Movies") }); FilterChip(series, { series = true }, { Text("Series") })
            Spacer(Modifier.weight(1f)); Button({ search() }, enabled = query.isNotBlank() && !busy) { Text("Search") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Teal)
        msg?.let { m -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(m, Modifier.weight(1f).padding(vertical = 8.dp), color = if (m.startsWith("Added")) Teal else Color(0xFFFF8A80))
            if (m.startsWith("Added")) TextButton(openDownloads) { Text("View") } } }
        val p = picked
        if (p != null && series) {
            val shown = episodes.filter { it.season == season }
            Text(p.name, style = MaterialTheme.typography.titleMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                episodes.map { it.season }.distinct().forEach { s -> FilterChip(season == s, { season = s }, { Text("Season $s") }) } }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton({ selected = selected + shown.map { it.id } }) { Text("Select season") }
                TextButton({ selected = selected + episodes.map { it.id } }) { Text("All seasons") }
                TextButton({ selected = emptySet() }) { Text("Clear") }
                TextButton({ rangeMode = !rangeMode; rangeFrom = null }) { Text(if (rangeMode) "Cancel range" else "Select range") }
            }
            if (rangeMode) Text(if (rangeFrom == null) "Tap the first episode of the range." else "Now tap the last episode.", color = Teal)
            LazyColumn(Modifier.weight(1f)) { itemsIndexed(shown) { i, e ->
                ListItem({ Text("${e.code}  ${e.title}") }, Modifier.clickable {
                    if (!rangeMode) open(Choice(e.id, "${p.name} ${e.code}", Matching.episodeFile(Prefs.str(ctx, "template", Matching.DEFAULT_TEMPLATE), p.name, e), e.thumb ?: p.thumb, emptyList()))
                    else { val f = rangeFrom
                        if (f == null) rangeFrom = i
                        else { selected = selected + shown.subList(minOf(f, i), maxOf(f, i) + 1).map { it.id }; rangeMode = false; rangeFrom = null } }
                }, leadingContent = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(e.id in selected, { c -> selected = if (c) selected + e.id else selected - e.id }); Thumb(e.thumb ?: p.thumb, 64.dp, 40.dp) } },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)) } }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Highest", "1080", "720", "480", "Lowest").forEach { q -> FilterChip(quality == q, { quality = q }, { Text(if (q.first().isDigit()) "${q}p" else q) }) } }
            Button({ downloadSelected(p) }, Modifier.fillMaxWidth().padding(top = 8.dp), enabled = selected.isNotEmpty() && !busy) { Text("Download ${selected.size} selected") }
        } else when {
            results == null -> Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Logo(72.dp); Text("Find a movie or series to watch or save.") }
            results!!.isEmpty() -> Text("No matches. Try the original title or add the year.", Modifier.padding(top = 24.dp))
            else -> LazyColumn { items(results!!) { t ->
                ListItem({ Text(t.name) }, Modifier.clickable { pick(t) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = { Thumb(t.thumb, 48.dp, 68.dp) },
                    supportingContent = { Text(listOfNotNull(t.ar?.takeIf { it != t.name }, t.year).joinToString("  ")) }) } }
        }
    }

    choice?.let { c ->
        AlertDialog(onDismissRequest = { choice = null }, title = { Text(c.label) }, confirmButton = {},
            dismissButton = { TextButton({ choice = null }) { Text("Cancel") } },
            text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { c.sources.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.name, Modifier.weight(1f))
                    TextButton({ choice = null; run {
                        val subs = Matching.pickSubs(Api.subtitles(c.id), subMode()); val mode = Prefs.str(ctx, "player", "Built-in")
                        if (mode == "Built-in") onPlay(s, subs)
                        else if (!Players.launch(ctx, mode, Uri.parse(s.url), c.label, subs.map { Uri.parse(it.url) })) msg = "$mode isn't installed." } }) { Text("Play") }
                    TextButton({ choice = null; run { enqueue(c, s, picked?.name ?: c.base); msg = "Added to Downloads." } }) { Text("Save") }
                } } } })
    }
}

@Composable
fun Player(src: Source, subs: List<Sub>) {
    val ctx = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(ctx).build().apply {
            val cfg = subs.map { MediaItem.SubtitleConfiguration.Builder(Uri.parse(it.url))
                .setMimeType(if (it.fmt == "vtt") MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP).setLanguage(it.lang).build() }
            setMediaItem(MediaItem.Builder().setUri(src.url).setSubtitleConfigurations(cfg).build()); prepare(); playWhenReady = true
        }
    }
    DisposableEffect(Unit) { onDispose { player.release() } }
    AndroidView({ PlayerView(it).apply { this.player = player } }, Modifier.fillMaxSize())
}
