package io.github.purecam.camera

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import java.io.OutputStream

/** Writes photos to DCIM/PureCam so they show up in Samsung Gallery. */
object Saver {
    private const val DIR = "DCIM/PureCam"

    private fun write(ctx: Context, name: String, mime: String, body: (OutputStream) -> Unit): Uri {
        val r = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, DIR)
            put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("Could not create the photo file")
        try {
            r.openOutputStream(uri)?.use(body) ?: error("Could not write the photo")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            r.update(uri, values, null, null)
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

    fun latest(ctx: Context): Uri? = runCatching {
        ctx.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.MIME_TYPE} = ?",
            arrayOf("$DIR%", "image/jpeg"),
            "${MediaStore.MediaColumns.DATE_ADDED} DESC",
        )?.use { c ->
            if (c.moveToFirst()) ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)) else null
        }
    }.getOrNull()

    fun thumbnail(ctx: Context, uri: Uri): Bitmap? =
        runCatching { ctx.contentResolver.loadThumbnail(uri, Size(240, 240), null) }.getOrNull()
}
