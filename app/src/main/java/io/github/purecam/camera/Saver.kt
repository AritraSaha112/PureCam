package io.github.purecam.camera

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Size
import java.io.OutputStream

/** Writes photos and videos to DCIM/PureCam so they show up in Samsung Gallery. */
object Saver {
    private const val DIR = "DCIM/PureCam"

    private fun pending(name: String, mime: String) = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, mime)
        put(MediaStore.MediaColumns.RELATIVE_PATH, DIR)
        put(MediaStore.MediaColumns.DATE_TAKEN, System.currentTimeMillis())
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }

    private fun write(ctx: Context, name: String, mime: String, body: (OutputStream) -> Unit): Uri {
        val r = ctx.contentResolver
        val uri = r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, pending(name, mime)) ?: error("Could not create the photo file")
        try {
            r.openOutputStream(uri)?.use(body) ?: error("Could not write the photo")
            publish(ctx, uri)
        } catch (e: Exception) {
            r.delete(uri, null, null)
            throw e
        }
        return uri
    }

    fun saveJpeg(ctx: Context, name: String, bytes: ByteArray): Uri =
        write(ctx, "$name.jpg", "image/jpeg") { it.write(bytes) }

    fun saveDng(ctx: Context, name: String, body: (OutputStream) -> Unit): Uri =
        write(ctx, "$name.dng", "image/x-adobe-dng", body)

    /** Creates a pending video entry; call [publish] when recording finished, or [discard]. */
    fun createVideo(ctx: Context, name: String): Pair<Uri, ParcelFileDescriptor> {
        val r = ctx.contentResolver
        val uri = r.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, pending("$name.mp4", "video/mp4"))
            ?: error("Could not create the video file")
        val pfd = r.openFileDescriptor(uri, "w") ?: run { r.delete(uri, null, null); error("Could not open the video file") }
        return uri to pfd
    }

    fun publish(ctx: Context, uri: Uri) {
        ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    }

    fun discard(ctx: Context, uri: Uri) {
        runCatching { ctx.contentResolver.delete(uri, null, null) }
    }

    private fun newest(ctx: Context, collection: Uri, mime: String): Pair<Uri, Long>? = runCatching {
        ctx.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.MIME_TYPE} = ?",
            arrayOf("$DIR%", mime),
            "${MediaStore.MediaColumns.DATE_ADDED} DESC",
        )?.use { c ->
            if (c.moveToFirst()) ContentUris.withAppendedId(collection, c.getLong(0)) to c.getLong(1) else null
        }
    }.getOrNull()

    /** Most recent PureCam photo or video, and whether it is a video. */
    fun latest(ctx: Context): Pair<Uri, Boolean>? {
        val img = newest(ctx, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/jpeg")
        val vid = newest(ctx, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video/mp4")
        return when {
            img == null && vid == null -> null
            vid == null -> img!!.first to false
            img == null -> vid.first to true
            vid.second > img.second -> vid.first to true
            else -> img.first to false
        }
    }

    fun thumbnail(ctx: Context, uri: Uri): Bitmap? =
        runCatching { ctx.contentResolver.loadThumbnail(uri, Size(240, 240), null) }.getOrNull()
}
