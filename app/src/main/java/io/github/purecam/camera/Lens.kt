package io.github.purecam.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One lens choice in the UI.
 *
 * - [openId]: camera ID passed to openCamera().
 * - [physicalId]: set when the lens is only reachable as a sub-camera of a logical multi-camera.
 * - [zoomRatio]: set for lenses reached through the phone's multi-camera zoom system
 *   (CONTROL_ZOOM_RATIO). The phone itself switches to the real ultra-wide / telephoto module,
 *   which is how the 3× lens is reached when Samsung hides it from apps. Lenses that share an
 *   [openId] and have a [zoomRatio] switch seamlessly, even while recording video.
 * - [fallback]: another way to reach the same lens if opening this one is refused.
 */
data class Lens(
    val openId: String,
    val physicalId: String?,
    val facing: Int,
    val chars: CameraCharacteristics,
    val equivMm: Float,
    val megapixels: Float,
    val ratio: Float = 1f,
    val label: String = "1",
    val zoomRatio: Float? = null,
    val fallback: Lens? = null,
) {
    val key: String get() = "$openId/${physicalId ?: ""}/${zoomRatio ?: ""}"
    val front: Boolean get() = facing == LENS_FACING_FRONT
    val description: String
        get() {
            val how = when {
                zoomRatio != null && abs(zoomRatio - 1f) < 0.01f -> "ID $openId (zoom route)"
                zoomRatio != null -> "via ${LensCatalog.format(zoomRatio)}× zoom on ID $openId"
                physicalId != null -> "ID $openId, sub-camera $physicalId"
                else -> "ID $openId"
            }
            return String.format(Locale.US, "%s %s× · %.0f mm · %s", if (front) "Front" else "Rear", label, equivMm, how) +
                if (zoomRatio == null) String.format(Locale.US, " · %.0f MP", megapixels) else ""
        }
}

data class LensSet(val photo: List<Lens>, val video: List<Lens>)

object LensCatalog {
    private class Candidate(val lens: Lens, val priority: Int, val dedupeKey: String, val logical: Boolean)

    /**
     * Finds every lens. Samsung keeps some lenses off the public camera list but still lets apps
     * open them by ID, so IDs 0..63 are probed too. When a logical multi-camera with a zoom range
     * exists, it is also used to reach ultra-wide/telephoto through zoom ratios.
     */
    fun discover(cm: CameraManager): LensSet {
        val listed = runCatching { cm.cameraIdList.toList() }.getOrDefault(emptyList())
        val ids = LinkedHashSet(listed).apply { for (i in 0..127) add(i.toString()) }
        val found = mutableListOf<Candidate>()

        for (id in ids) {
            val ch = runCatching { cm.getCameraCharacteristics(id) }.getOrNull() ?: continue
            val facing = ch.get(CameraCharacteristics.LENS_FACING) ?: continue
            if (facing == CameraMetadata.LENS_FACING_EXTERNAL) continue
            val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: continue
            if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE !in caps) continue

            if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in caps) {
                for (pid in ch.physicalCameraIds) {
                    val pch = runCatching { cm.getCameraCharacteristics(pid) }.getOrNull() ?: continue
                    val pcaps = pch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: continue
                    if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE !in pcaps) continue
                    candidate(id, pid, facing, pch, 1, false)?.let { found += it }
                }
                candidate(id, null, facing, ch, 2, true)?.let { found += it }
            } else {
                candidate(id, null, facing, ch, if (id in listed) 4 else 3, false)?.let { found += it }
            }
        }

        // Several IDs can point at the same sensor: keep the most direct way of opening it.
        val unique = found.groupBy { it.dedupeKey }.values.map { g -> g.maxBy { it.priority }.lens }
        val back = label(unique.filter { !it.front })
        val front = label(unique.filter { it.front })
        val mainEquiv = back.minByOrNull { abs(it.ratio - 1f) }?.equivMm ?: 26f

        // Best rear logical camera with a usable zoom range (Android 11+)
        var logical: Triple<Lens, Float, Float>? = null
        if (Build.VERSION.SDK_INT >= 30) {
            logical = found.filter { it.logical && !it.lens.front }.mapNotNull { c ->
                val r = c.lens.chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) ?: return@mapNotNull null
                Triple(c.lens, r.lower, r.upper)
            }.filter { it.third >= 1.5f || it.second <= 0.9f }.maxByOrNull { it.third / it.second }
        }

        var photoBack = back
        var videoBack = back
        if (logical != null) {
            val lg = logical.first
            val lo = logical.second
            val hi = logical.third
            fun virtual(z: Float) = lg.copy(
                physicalId = null, equivMm = mainEquiv * z, ratio = z, label = format(z), zoomRatio = z, fallback = null,
            )
            val p = back.map { l ->
                if (l.ratio in (lo - 0.05f)..(hi + 0.05f) && abs(l.ratio - 1f) > 0.08f) l.copy(fallback = virtual(l.ratio.coerceIn(lo, hi))) else l
            }.toMutableList()
            if (p.none { it.ratio >= 2.5f } && hi >= 2.8f) p += virtual(min(3f, hi))
            if (p.none { it.ratio < 0.8f } && lo < 0.8f) p += virtual(lo)
            photoBack = p.sortedBy { it.ratio }

            // Video: every rear lens through the zoom system, so lens changes are smooth and work mid-recording
            val extra = mutableListOf(1f)
            if (hi >= 2.8f) extra += min(3f, hi)
            if (lo < 0.8f) extra += lo
            videoBack = (back.map { it.ratio } + extra)
                .map { it.coerceIn(lo, hi) }
                .distinctBy { (it * 10).roundToInt() }
                .sorted()
                .map { virtual(it) }
        } else if (Build.VERSION.SDK_INT >= 30) {
            // No multi-camera group is exposed (Galaxy S21 FE). Ask the main camera itself for 3× / 0.5×:
            // on many Samsung phones the camera then switches to the real telephoto / ultra-wide module
            // internally. The app checks the reported focal length at runtime and tells the user
            // whether the telephoto actually took over or it is digital zoom.
            val main = back.minByOrNull { abs(it.ratio - 1f) }
            val r = main?.chars?.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            if (main != null && r != null && (r.upper >= 2.5f || r.lower < 0.9f)) {
                fun viaZoom(z: Float) = main.copy(equivMm = mainEquiv * z, ratio = z, label = format(z), zoomRatio = z, fallback = null)
                val zoomMain = main.copy(zoomRatio = 1f)
                val p = back.map { if (it.key == main.key) zoomMain else it }.toMutableList()
                if (back.none { it.ratio >= 2.5f } && r.upper >= 2.5f) p += viaZoom(min(3f, r.upper))
                if (back.none { it.ratio < 0.8f } && r.lower < 0.9f) p += viaZoom(r.lower)
                photoBack = p.sortedBy { it.ratio }
                videoBack = photoBack
            }
        }
        return LensSet(photo = photoBack + front, video = videoBack + front)
    }

    private fun candidate(openId: String, physicalId: String?, facing: Int, ch: CameraCharacteristics, priority: Int, logical: Boolean): Candidate? {
        val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return null
        val phys = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val pixel = ch.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val active = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
        val diag = if (phys != null) {
            val crop = if (pixel != null && pixel.width > 0) active.width().toFloat() / pixel.width else 1f
            hypot(phys.width, phys.height) * crop
        } else 7f
        val equiv = focal * 43.27f / diag
        val mp = active.width() * active.height() / 1_000_000f
        val key = "$facing|${(focal * 100).roundToInt()}|${active.width()}x${active.height()}"
        return Candidate(Lens(openId, physicalId, facing, ch, equiv, mp), priority, key, logical)
    }

    private fun label(lenses: List<Lens>): List<Lens> {
        if (lenses.isEmpty()) return lenses
        val main = lenses.minBy { abs(it.equivMm - 26f) }
        return lenses.map { l ->
            val r = l.equivMm / main.equivMm
            l.copy(ratio = r, label = format(r))
        }.sortedBy { it.ratio }
    }

    fun format(r: Float): String = when {
        abs(r - 1f) < 0.08f -> "1"
        r < 1f -> String.format(Locale.US, "%.1f", r).removePrefix("0")
        abs(r - r.roundToInt()) < 0.2f -> r.roundToInt().toString()
        else -> String.format(Locale.US, "%.1f", r)
    }

    fun main(lenses: List<Lens>, front: Boolean): Lens? =
        lenses.filter { it.front == front }.minByOrNull { abs(it.ratio - 1f) }
}
