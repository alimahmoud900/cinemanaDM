package app.cinemana

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

// Brand: a play wedge cut into parallel segments (the engine's byte ranges); the last, still-arriving segment is teal.
internal val Ink = Color(0xFF0F1B2D); internal val Slate = Color(0xFF1A2A41); internal val Track = Color(0xFF2B4060)
internal val Sand = Color(0xFFEBDDC1); internal val Teal = Color(0xFF3FB6A8)

@Composable
fun AppTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(primary = Teal, onPrimary = Ink, background = Ink, onBackground = Sand,
        surface = Slate, onSurface = Sand, surfaceVariant = Slate, onSurfaceVariant = Sand),
    typography = Typography(
        titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp, letterSpacing = (-0.4).sp),
        titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
        titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp)),
    content = content)

@Composable
fun Logo(side: Dp) = Canvas(Modifier.size(side)) {
    val sx = size.width / 48f; val sy = size.height / 52f
    fun h(x: Float) = 26f * (48f - x) / 48f
    listOf(0f to 11f, 13f to 24f, 26f to 37f, 39f to 48f).forEachIndexed { i, (a, b) ->
        val p = Path().apply { moveTo(a * sx, (26 - h(a)) * sy); lineTo(b * sx, (26 - h(b)) * sy); lineTo(b * sx, (26 + h(b)) * sy); lineTo(a * sx, (26 + h(a)) * sy); close() }
        drawPath(p, if (i == 3) Teal else Sand)
    }
}

/** One bar per connection, each filled by that connection's own progress. */
@Composable
fun SegBar(segs: List<Float>, frac: Float, modifier: Modifier = Modifier) = Canvas(modifier.height(8.dp)) {
    val n = maxOf(1, segs.size); val gap = 3.dp.toPx(); val w = (size.width - gap * (n - 1)) / n; val r = CornerRadius(4.dp.toPx())
    for (i in 0 until n) {
        val x = i * (w + gap); drawRoundRect(Track, Offset(x, 0f), Size(w, size.height), r)
        val f = if (segs.isEmpty()) frac else segs[i]
        if (f > 0f) drawRoundRect(Teal, Offset(x, 0f), Size(w * f, size.height), r)
    }
}

@Composable
fun Thumb(url: String?, w: Dp, h: Dp) {
    val m = Modifier.size(w, h).clip(RoundedCornerShape(6.dp))
    if (url == null) Box(m.background(Track)) else AsyncImage(url, null, m, contentScale = ContentScale.Crop)
}
