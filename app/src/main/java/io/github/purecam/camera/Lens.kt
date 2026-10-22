package io.github.purecam.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * One physical lens the app can shoot with.
 * [openId] is the camera ID passed to openCamera(). [physicalId] is set only when the lens
 * is reachable solely as a sub-camera of a logical multi-camera.
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
) {
    val key: String get() = "$openId/${physicalId ?: ""}"
    val front: Boolean get() = facing == LENS_FACING_FRONT
    val description: String
        get() = String.format(
            Locale.US, "%s %s× · %.0f mm · %.0f MP · ID %s%s",
            if (front) "Front" else "Rear", label, equivMm, megapixels, openId,
            physicalId?.let { " (sub-camera $it)" } ?: "",
        )
}

object LensCatalog {
    private class Candidate(val lens: Lens, val priority: Int, val dedupeKey: String)

    /**
     * Finds every lens. Samsung keeps some lenses (often ultra-wide/telephoto) off the public
     * camera list but still lets apps open them by ID, so IDs 0..63 are probed as well.
     */
    fun discover(cm: CameraManager): List<Lens> {
        val listed = runCatching { cm.cameraIdList.toList() }.getOrDefault(emptyList())
        val ids = LinkedHashSet(listed).apply { for (i in 0..63) add(i.toString()) }
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
                    candidate(id, pid, facing, pch, 1)?.let { found += it }
                }
                candidate(id, null, facing, ch, 2)?.let { found += it }
            } else {
                candidate(id, null, facing, ch, if (id in listed) 4 else 3)?.let { found += it }
            }
        }

        // Several IDs can point at the same sensor: keep the most direct way of opening it.
        val unique = found.groupBy { it.dedupeKey }.values.map { group -> group.maxBy { it.priority }.lens }
        return label(unique.filter { !it.front }) + label(unique.filter { it.front })
    }

    private fun candidate(openId: String, physicalId: String?, facing: Int, ch: CameraCharacteristics, priority: Int): Candidate? {
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
        return Candidate(Lens(openId, physicalId, facing, ch, equiv, mp), priority, key)
    }

    private fun label(lenses: List<Lens>): List<Lens> {
        if (lenses.isEmpty()) return lenses
        val main = lenses.minBy { abs(it.equivMm - 26f) }
        return lenses.map { l ->
            val r = l.equivMm / main.equivMm
            l.copy(ratio = r, label = format(r))
        }.sortedBy { it.ratio }
    }

    private fun format(r: Float): String = when {
        abs(r - 1f) < 0.08f -> "1"
        r < 1f -> String.format(Locale.US, "%.1f", r).removePrefix("0")
        abs(r - r.roundToInt()) < 0.2f -> r.roundToInt().toString()
        else -> String.format(Locale.US, "%.1f", r)
    }

    fun main(lenses: List<Lens>, front: Boolean): Lens? =
        lenses.filter { it.front == front }.minByOrNull { abs(it.ratio - 1f) }
}
