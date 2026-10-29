package app.cinemana.dl

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File

object Storage {
    /** Moves a finished file into Movies/Cinemana/<folder> (video) or Download/Cinemana/<folder> (subtitles),
     *  or into the user-chosen [tree] folder. Returns the content Uri. */
    fun publish(ctx: Context, src: File, folder: String, name: String, tree: Uri?): Uri {
        val r = ctx.contentResolver; val video = name.endsWith(".mp4")
        val mime = if (video) "video/mp4" else "text/plain"
        val uri = if (tree != null) {
            DocumentsContract.createDocument(r, DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)), mime, name)!!
        } else {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, (if (video) "Movies" else "Download") + "/Cinemana/$folder")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            r.insert(if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)!!
        }
        r.openOutputStream(uri)!!.use { o -> src.inputStream().use { it.copyTo(o, 256 * 1024) } }
        if (tree == null) r.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        src.delete(); return uri
    }
}
