package io.github.purecam.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import java.util.Locale

/**
 * Plain-text report of everything the phone tells apps about its cameras. Used to find out how
 * (or whether) a lens such as the 3× telephoto can be reached on a given phone and Android build.
 */
object CameraReport {

    private val CAPS = mapOf(
        0 to "BACKWARD_COMPATIBLE", 1 to "MANUAL_SENSOR", 2 to "MANUAL_POST_PROCESSING", 3 to "RAW",
        4 to "PRIVATE_REPROCESSING", 5 to "READ_SENSOR_SETTINGS", 6 to "BURST_CAPTURE", 7 to "YUV_REPROCESSING",
        8 to "DEPTH_OUTPUT", 9 to "HIGH_SPEED_VIDEO", 10 to "MOTION_TRACKING", 11 to "LOGICAL_MULTI_CAMERA",
        12 to "MONOCHROME", 13 to "SECURE_IMAGE_DATA", 14 to "SYSTEM_CAMERA", 15 to "OFFLINE_PROCESSING",
        16 to "ULTRA_HIGH_RESOLUTION", 17 to "REMOSAIC_REPROCESSING", 18 to "TEN_BIT", 19 to "STREAM_USE_CASE",
        20 to "COLOR_SPACE_PROFILES",
    )
    private val LEVELS = mapOf(0 to "LIMITED", 1 to "FULL", 2 to "LEGACY", 3 to "LEVEL_3", 4 to "EXTERNAL")

    fun build(ctx: Context, ui: CamUi): String {
        val cm = ctx.getSystemService(CameraManager::class.java)
        val sb = StringBuilder()
        sb.appendLine("PureCam camera report")
        sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE} / SDK ${Build.VERSION.SDK_INT}")
        sb.appendLine("Build: ${Build.DISPLAY}")
        val listed = runCatching { cm.cameraIdList.toList() }.getOrElse { emptyList() }
        sb.appendLine("Public camera IDs: $listed")
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching { cm.concurrentCameraIds.map { it.sorted() } }.getOrNull()?.let { sb.appendLine("Concurrent sets: $it") }
        }
        sb.appendLine()

        val ids = LinkedHashSet(listed).apply { for (i in 0..127) add(i.toString()) }
        val physicalSeen = mutableSetOf<String>()
        for (id in ids) {
            val ch = try {
                cm.getCameraCharacteristics(id)
            } catch (e: Exception) {
                if (id in listed) sb.appendLine("ID $id: listed but not readable (${e.javaClass.simpleName})")
                continue
            }
            describe(sb, id, ch, listed = id in listed)
            if (Build.VERSION.SDK_INT >= 28) {
                for (pid in ch.physicalCameraIds) {
                    if (!physicalSeen.add(pid)) continue
                    val pch = runCatching { cm.getCameraCharacteristics(pid) }.getOrNull()
                    if (pch == null) sb.appendLine("   sub-camera $pid: not readable")
                    else describe(sb, "$id › sub $pid", pch, listed = false, indent = "   ")
                }
            }
        }

        sb.appendLine()
        sb.appendLine("Lens buttons built from this:")
        (ui.photoLenses + ui.videoLenses).distinctBy { it.key }.forEach { sb.appendLine(" - ${it.description}") }
        if (ui.blocked.isNotEmpty()) sb.appendLine("Refused when opening: ${ui.blocked}")
        ui.lens?.let { l ->
            sb.appendLine(String.format(Locale.US, "Current: %s, zoom %.2f, reported focal length %.2f mm", l.description, ui.zoom, ui.liveFocal))
        }
        sb.appendLine()
        sb.appendLine(
            "Note: a lens marked SYSTEM_CAMERA, or one that never appears above, is reserved by the phone maker " +
                "for its own camera app and cannot be opened by any other app.",
        )
        return sb.toString()
    }

    private fun describe(sb: StringBuilder, id: String, ch: CameraCharacteristics, listed: Boolean, indent: String = "") {
        val facing = when (ch.get(CameraCharacteristics.LENS_FACING)) {
            CameraMetadata.LENS_FACING_BACK -> "rear"
            CameraMetadata.LENS_FACING_FRONT -> "front"
            CameraMetadata.LENS_FACING_EXTERNAL -> "external"
            else -> "?"
        }
        val focals = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.joinToString("/") { String.format(Locale.US, "%.2f", it) }
        val apertures = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.joinToString("/") { String.format(Locale.US, "f%.1f", it) }
        val phys = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val active = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.map { CAPS[it] ?: it.toString() }
        val level = LEVELS[ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ?: -1] ?: "?"
        val zoom = if (Build.VERSION.SDK_INT >= 30) ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let { "${it.lower}–${it.upper}" } else null
        val digital = ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
        sb.appendLine("${indent}ID $id${if (listed) "" else " (hidden)"}: $facing, focal $focals mm, $apertures, level $level")
        sb.appendLine(
            "$indent   sensor ${phys?.let { String.format(Locale.US, "%.2fx%.2f mm", it.width, it.height) }}, " +
                "active ${active?.width()}x${active?.height()}, zoom-ratio $zoom, max digital zoom $digital",
        )
        sb.appendLine("$indent   capabilities: ${caps?.joinToString(", ")}")
        if (Build.VERSION.SDK_INT >= 28 && ch.physicalCameraIds.isNotEmpty()) sb.appendLine("$indent   sub-cameras: ${ch.physicalCameraIds}")
    }
}
