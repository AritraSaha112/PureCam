package io.github.purecam.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.roundToInt

/** Post-capture work for looks and Portrait mode. RAW (DNG) files are never touched. */
object PhotoProcessor {

    class Result(val jpeg: ByteArray, val personFound: Boolean)

    suspend fun process(ctx: Context, jpeg: ByteArray, look: Look, aperture: Float?, crop: Float = 1f): Result {
        val exif = ExifInterface(ByteArrayInputStream(jpeg))
        val rotation = exif.rotationDegrees
        var bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return Result(jpeg, true)
        if (rotation != 0) bmp = swap(bmp, Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation.toFloat()) }, true))
        if (crop > 1.01f) {
            val cw = (bmp.width / crop).roundToInt().coerceAtLeast(1)
            val chh = (bmp.height / crop).roundToInt().coerceAtLeast(1)
            bmp = swap(bmp, Bitmap.createBitmap(bmp, (bmp.width - cw) / 2, (bmp.height - chh) / 2, cw, chh))
        }

        var person = true
        if (aperture != null) {
            val blurred = portrait(bmp, aperture)
            if (blurred == null) person = false else bmp = swap(bmp, blurred)
        }
        look.matrix()?.let { bmp = swap(bmp, applyMatrix(bmp, it)) }

        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 96, out)
        bmp.recycle()
        return Result(copyExif(ctx, exif, out.toByteArray()), person)
    }

    private fun swap(old: Bitmap, new: Bitmap): Bitmap {
        if (old !== new) old.recycle()
        return new
    }

    fun applyMatrix(src: Bitmap, m: ColorMatrix): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(m) })
        return out
    }

    // ---------------------------------------------------------------- portrait

    /** Returns the blurred-background photo, or null when no person is found. */
    private suspend fun portrait(full: Bitmap, fNumber: Float): Bitmap? {
        val w = full.width
        val h = full.height
        val s = 1024f / max(w, h)
        val small = Bitmap.createScaledBitmap(full, (w * s).roundToInt().coerceAtLeast(1), (h * s).roundToInt().coerceAtLeast(1), true)

        val segmenter = Segmentation.getClient(
            SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).build(),
        )
        val mask = try {
            segmenter.process(InputImage.fromBitmap(small, 0)).await()
        } finally {
            segmenter.close()
        }
        small.recycle()

        val mw = mask.width
        val mh = mask.height
        val buf = mask.buffer
        buf.rewind()
        buf.order(ByteOrder.nativeOrder())
        val px = IntArray(mw * mh)
        var covered = 0
        for (i in 0 until mw * mh) {
            val c = buf.float
            if (c > 0.5f) covered++
            val t = ((c - 0.35f) / 0.4f).coerceIn(0f, 1f)
            val a = (t * t * (3 - 2 * t) * 255).roundToInt()
            px[i] = a shl 24
        }
        if (covered < mw * mh * 0.01f) return null
        boxBlur(px, mw, mh, 2, alphaOnly = true)
        val maskBmp = Bitmap.createBitmap(px, mw, mh, Bitmap.Config.ARGB_8888)

        // Background: shrink, blur hard, scale back up. A wider aperture (smaller f-number) gives more blur.
        val bs = 512f / max(w, h)
        val bgSmall = Bitmap.createScaledBitmap(full, (w * bs).roundToInt().coerceAtLeast(1), (h * bs).roundToInt().coerceAtLeast(1), true)
        val bw = bgSmall.width
        val bh = bgSmall.height
        val bgPx = IntArray(bw * bh)
        bgSmall.getPixels(bgPx, 0, bw, 0, 0, bw, bh)
        bgSmall.recycle()
        val radius = (20f / fNumber).roundToInt().coerceIn(1, 16)
        repeat(3) { boxBlur(bgPx, bw, bh, radius, alphaOnly = false) }
        val bgBlur = Bitmap.createBitmap(bgPx, bw, bh, Bitmap.Config.ARGB_8888)

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(result)
        val dst = Rect(0, 0, w, h)
        val smooth = Paint(Paint.FILTER_BITMAP_FLAG)
        c.drawBitmap(bgBlur, null, dst, smooth)
        val layer = c.saveLayer(null, null)
        c.drawBitmap(full, 0f, 0f, null)
        c.drawBitmap(maskBmp, null, dst, Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
        c.restoreToCount(layer)
        bgBlur.recycle()
        maskBmp.recycle()
        return result
    }

    /** Separable sliding-window box blur over ARGB pixels (3 passes ≈ Gaussian). */
    private fun boxBlur(px: IntArray, w: Int, h: Int, r: Int, alphaOnly: Boolean) {
        val tmp = IntArray(px.size)
        pass(px, tmp, w, h, r, horizontal = true, alphaOnly)
        pass(tmp, px, w, h, r, horizontal = false, alphaOnly)
    }

    private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean, alphaOnly: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val div = 2 * r + 1
        for (line in 0 until lines) {
            fun idx(i: Int): Int {
                val k = i.coerceIn(0, len - 1)
                return if (horizontal) line * w + k else k * w + line
            }
            var sa = 0; var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val p = src[idx(i)]
                sa += p ushr 24
                if (!alphaOnly) { sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF }
            }
            for (i in 0 until len) {
                dst[idx(i)] = if (alphaOnly) (sa / div) shl 24
                else ((sa / div) shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val add = src[idx(i + r + 1)]
                val rem = src[idx(i - r)]
                sa += (add ushr 24) - (rem ushr 24)
                if (!alphaOnly) {
                    sr += ((add shr 16) and 0xFF) - ((rem shr 16) and 0xFF)
                    sg += ((add shr 8) and 0xFF) - ((rem shr 8) and 0xFF)
                    sb += (add and 0xFF) - (rem and 0xFF)
                }
            }
        }
    }

    // ---------------------------------------------------------------- EXIF

    private val TAGS = listOf(
        ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL,
        ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_APERTURE_VALUE, ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_SHUTTER_SPEED_VALUE, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE, ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_FLASH, ExifInterface.TAG_METERING_MODE,
        ExifInterface.TAG_EXPOSURE_PROGRAM, ExifInterface.TAG_EXPOSURE_MODE,
    )

    private fun copyExif(ctx: Context, from: ExifInterface, jpeg: ByteArray): ByteArray = runCatching {
        val tmp = File(ctx.cacheDir, "exif_tmp.jpg")
        tmp.writeBytes(jpeg)
        val to = ExifInterface(tmp)
        for (t in TAGS) from.getAttribute(t)?.let { to.setAttribute(t, it) }
        to.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        to.setAttribute(ExifInterface.TAG_SOFTWARE, "PureCam")
        to.saveAttributes()
        tmp.readBytes().also { tmp.delete() }
    }.getOrDefault(jpeg)
}
