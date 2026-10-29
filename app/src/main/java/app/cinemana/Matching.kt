package app.cinemana

import java.text.Normalizer

data class Title(val id: String, val en: String?, val ar: String?, val year: String?, val imdb: String?, val score: Double = 0.0, val thumb: String? = null) {
    val name get() = en ?: ar ?: "?"
}
data class Episode(val id: String, val title: String, val season: Int, val number: Int, val thumb: String? = null) {
    val code get() = "S%02dE%02d".format(season, number)
}
data class Source(val name: String, val url: String)
data class Sub(val lang: String, val url: String, val fmt: String)

object Matching {
    const val DEFAULT_TEMPLATE = "{title} - S{s}E{e}"
    val SUB_MODES = listOf("Arabic + English", "Arabic", "English", "All languages", "None")

    fun pickSubs(all: List<Sub>, mode: String): List<Sub> = when (mode) {
        "Arabic + English" -> all.filter { it.lang in listOf("ar", "en") }
        "Arabic" -> all.filter { it.lang == "ar" }
        "English" -> all.filter { it.lang == "en" }
        "None" -> emptyList()
        else -> all
    }.distinctBy { it.lang }

    fun episodeFile(template: String, title: String, e: Episode) = safeName(template.replace("{title}", title)
        .replace("{s}", "%02d".format(e.season)).replace("{e}", "%02d".format(e.number)).replace("{name}", e.title))

    fun normalize(s: String?): String =
        Normalizer.normalize((s ?: "").trim().lowercase(), Normalizer.Form.NFKD)
            .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
            .replace(Regex("[^a-z0-9\\u0600-\\u06ff]+"), " ").trim()

    /** "Batman 2022" -> ("Batman", 2022) */
    fun splitYear(q: String): Pair<String, Int?> {
        val m = Regex("^(.*?)\\s*\\(?((?:19|20)\\d\\d)\\)?$").find(q.trim())
        return if (m != null && m.groupValues[1].isNotBlank()) m.groupValues[1] to m.groupValues[2].toInt() else q.trim() to null
    }

    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1); cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return 1.0 - prev[b.length].toDouble() / maxOf(a.length, b.length)
    }

    fun score(query: String, year: Int?, t: Title, imdb: String? = null): Double {
        if (imdb != null && t.imdb == imdb.lowercase()) return 100.0
        val q = normalize(query)
        val names = listOfNotNull(t.en, t.ar).map(::normalize)
        var s = (names.maxOfOrNull { similarity(q, it) } ?: 0.0) * 70
        if (names.any { it == q }) s += 20
        if (year != null && t.year?.toIntOrNull()?.let { kotlin.math.abs(it - year) <= 1 } == true) s += 10
        return minOf(s, 99.0)
    }

    fun safeName(n: String) = n.replace(Regex("[\\x00-\\x1f<>:\"/\\\\|?*]+"), " ").replace(Regex("\\s+"), " ").trim().take(120).ifEmpty { "video" }

    fun quality(s: Source) = Regex("(\\d+)").find(s.name)?.value?.toInt() ?: 0

    /** want = "Highest", "Lowest" or a substring such as "720"; unmatched falls back to highest. */
    fun pickSource(all: List<Source>, want: String): Source? = when {
        all.isEmpty() -> null
        want == "Lowest" -> all.minByOrNull { quality(it) }
        want == "Highest" -> all.maxByOrNull { quality(it) }
        else -> all.firstOrNull { want in it.name } ?: all.maxByOrNull { quality(it) }
    }

    /** "1,3,5-8" or "S01E02" */
    fun selectEpisodes(spec: String, all: List<Episode>): List<Episode> {
        val out = LinkedHashSet<Episode>()
        for (tok in spec.split(Regex("[,\\s]+")).filter { it.isNotBlank() }) {
            val se = Regex("[Ss](\\d+)[Ee](\\d+)").matchEntire(tok)
            if (se != null) {
                out += all.filter { it.season == se.groupValues[1].toInt() && it.number == se.groupValues[2].toInt() }
                continue
            }
            val rg = Regex("(\\d+)-(\\d+)").matchEntire(tok)
            if (rg != null) {
                val a = rg.groupValues[1].toInt(); val b = rg.groupValues[2].toInt()
                out += all.filter { it.number in minOf(a, b)..maxOf(a, b) }
                continue
            }
            tok.toIntOrNull()?.let { n -> out += all.filter { it.number == n } }
        }
        return out.sortedWith(compareBy({ it.season }, { it.number }))
    }
}
