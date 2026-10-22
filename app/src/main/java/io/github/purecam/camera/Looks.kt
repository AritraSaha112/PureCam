package io.github.purecam.camera

import android.graphics.ColorMatrix
import kotlin.math.log10
import kotlin.math.pow

/**
 * Photo looks. [natural] = the camera's processing is switched off. Every other look uses the
 * phone's full-quality processing (noise reduction + sharpening) plus a colour grade that is
 * shown live in the viewfinder and applied to the saved JPEG.
 */
enum class Look(val title: String, val natural: Boolean) {
    NATURAL("Natural", true),
    STANDARD("Standard", false),
    VIVID("Vivid", false),
    RICH("Rich contrast", false),
    WARM("Warm", false),
    COOL("Cool", false),
    FILM("Film", false),
    MONO("Mono", false);

    /** null = no colour change. */
    fun matrix(): ColorMatrix? = when (this) {
        NATURAL, STANDARD -> null
        VIVID -> grade(saturation = 1.32f, contrast = 1.10f, brightness = 2f)
        RICH -> grade(saturation = 1.08f, contrast = 1.24f, brightness = -8f)
        WARM -> grade(saturation = 1.06f, contrast = 1.05f, r = 1.07f, g = 1.01f, b = 0.90f)
        COOL -> grade(saturation = 1.02f, contrast = 1.05f, r = 0.93f, g = 1.0f, b = 1.08f)
        FILM -> grade(saturation = 0.84f, contrast = 0.88f, brightness = 10f, r = 1.04f, g = 1.0f, b = 0.94f)
        MONO -> grade(saturation = 0f, contrast = 1.18f)
    }

    private fun grade(
        saturation: Float = 1f,
        contrast: Float = 1f,
        brightness: Float = 0f,
        r: Float = 1f,
        g: Float = 1f,
        b: Float = 1f,
    ): ColorMatrix {
        val m = ColorMatrix()
        m.setSaturation(saturation)
        val t = (1f - contrast) * 128f + brightness
        m.postConcat(
            ColorMatrix(
                floatArrayOf(
                    contrast, 0f, 0f, 0f, t,
                    0f, contrast, 0f, 0f, t,
                    0f, 0f, contrast, 0f, t,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )
        m.postConcat(ColorMatrix().apply { setScale(r, g, b, 1f) })
        return m
    }
}

/**
 * PureLog: a flat log curve applied through the camera's tone-map (scene-linear in, code value out).
 *   y = 0.06 + 0.88 · log10(A·x + 1) / log10(A + 1),  A = 16  (18% grey ≈ 0.48)
 * The matching LUT in /luts converts it back to standard Rec.709 / sRGB.
 */
object PureLog {
    private const val A = 16f
    private val norm = log10(A + 1f)

    fun encode(x: Float): Float = 0.06f + 0.88f * log10(A * x + 1f) / norm

    /** Curve points (x0, y0, x1, y1, …), denser in the shadows. */
    fun curve(maxPoints: Int): FloatArray {
        val n = maxPoints.coerceIn(2, 64)
        val out = FloatArray(n * 2)
        for (i in 0 until n) {
            val x = (i / (n - 1f)).pow(2.2f)
            out[2 * i] = x
            out[2 * i + 1] = encode(x).coerceIn(0f, 1f)
        }
        return out
    }
}
