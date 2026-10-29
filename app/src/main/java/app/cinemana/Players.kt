package app.cinemana

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Parcelable

object Players {
    val MODES = listOf("Built-in", "MX Player", "VLC", "Ask each time")

    /** Opens [media] directly in the chosen player. Returns false when that player isn't installed. */
    fun launch(ctx: Context, mode: String, media: Uri, title: String, subs: List<Uri>): Boolean {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(media, "video/*").putExtra("title", title)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        if (subs.isNotEmpty()) {
            val arr: Array<Parcelable> = subs.toTypedArray<Parcelable>()
            i.putExtra("subs", arr).putExtra("subs.enable", arrayOf<Parcelable>(subs.first()))   // MX Player
            i.putExtra("subtitles_location", subs.first().toString())                                 // VLC
            i.clipData = ClipData.newRawUri("", subs.first()).also { c -> subs.drop(1).forEach { c.addItem(ClipData.Item(it)) } }
        }
        fun start(pkg: String) = runCatching { ctx.startActivity(Intent(i).setPackage(pkg)) }.isSuccess
        return when (mode) {
            "MX Player" -> start("com.mxtech.videoplayer.pro") || start("com.mxtech.videoplayer.ad")
            "VLC" -> start("org.videolan.vlc")
            else -> runCatching { ctx.startActivity(Intent.createChooser(i, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        }
    }
}
