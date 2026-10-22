package io.github.purecam.ui

import android.hardware.camera2.CameraMetadata
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.purecam.camera.CameraEngine
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

enum class ProControl(val title: String) { ISO("ISO"), SHUTTER("SPEED"), FOCUS("FOCUS"), WB("WB"), EV("EV") }

private val ISO_STOPS = listOf(25, 32, 40, 50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000, 12800)
private val SHUTTER_STOPS: List<Long> = listOf(
    8000, 6400, 4000, 3200, 2000, 1600, 1000, 800, 500, 400, 250, 200, 125, 100, 60, 50, 30, 25, 15, 10, 8, 6, 4, 3, 2,
).map { 1_000_000_000L / it } + listOf(0.7, 1.0, 2.0, 4.0, 8.0, 15.0, 30.0).map { (it * 1e9).toLong() }

private val WB_OPTIONS = listOf(
    CameraMetadata.CONTROL_AWB_MODE_AUTO to "Auto",
    CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT to "Daylight",
    CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT to "Cloudy",
    CameraMetadata.CONTROL_AWB_MODE_SHADE to "Shade",
    CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT to "Tungsten",
    CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT to "Fluorescent",
)

private fun availableControls(engine: CameraEngine): List<ProControl> {
    val c = engine.ui.caps
    return buildList {
        if (c.manualSensor) { add(ProControl.ISO); add(ProControl.SHUTTER) }
        if (c.minFocus > 0f) add(ProControl.FOCUS)
        if (c.awbModes.size > 1) add(ProControl.WB)
        if (c.evRange.first != c.evRange.last) add(ProControl.EV)
    }
}

private fun valueText(engine: CameraEngine, p: ProControl): String {
    val ui = engine.ui
    return when (p) {
        ProControl.ISO -> ui.iso?.toString() ?: "A"
        ProControl.SHUTTER -> ui.exposure?.let { formatShutter(it) } ?: "A"
        ProControl.FOCUS -> ui.focus?.let { formatFocus(it) } ?: "A"
        ProControl.WB -> WB_OPTIONS.firstOrNull { it.first == ui.awb }?.second ?: "Auto"
        ProControl.EV -> if (ui.ev == 0) "0" else String.format(Locale.US, "%+.1f", ui.ev * ui.caps.evStep)
    }
}

private fun formatFocus(d: Float) = if (d <= 0.01f) "∞" else {
    val m = 1f / d
    if (m < 1f) "${(m * 100).roundToInt()}cm" else String.format(Locale.US, "%.1fm", m)
}

@Composable
fun ProChips(engine: CameraEngine, selected: ProControl?, onSelect: (ProControl) -> Unit) {
    val controls = availableControls(engine)
    if (controls.isEmpty()) {
        Text("This lens has no manual controls", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
        return
    }
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        controls.forEach { p ->
            val active = p == selected
            Column(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (active) Amber.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.08f))
                    .clickable { onSelect(p) }
                    .padding(horizontal = 12.dp, vertical = 5.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(p.title, color = if (active) Amber else Color.White.copy(alpha = 0.7f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text(valueText(engine, p), color = if (active) Amber else Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Slider panel drawn over the bottom of the viewfinder for the selected Pro control. */
@Composable
fun ProSliderOverlay(engine: CameraEngine, control: ProControl?, modifier: Modifier) {
    val ui = engine.ui
    if (!ui.pro || control == null || control !in availableControls(engine)) return
    val caps = ui.caps
    Column(
        modifier
            .fillMaxWidth()
            .background(PanelBg)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${control.title}  ${valueText(engine, control)}",
                Modifier.weight(1f),
                color = Color.White, fontWeight = FontWeight.SemiBold,
            )
            if (control != ProControl.WB) {
                val isAuto = when (control) {
                    ProControl.ISO -> ui.iso == null
                    ProControl.SHUTTER -> ui.exposure == null
                    ProControl.FOCUS -> ui.focus == null
                    else -> ui.ev == 0
                }
                Text(
                    if (control == ProControl.EV) "RESET" else "AUTO",
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (isAuto) Amber else Color.White.copy(alpha = 0.15f))
                        .clickable {
                            when (control) {
                                ProControl.ISO -> engine.setIso(null)
                                ProControl.SHUTTER -> engine.setExposure(null)
                                ProControl.FOCUS -> engine.setFocus(null)
                                else -> engine.setEv(0)
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    color = if (isAuto) Color.Black else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                )
            }
        }
        val colors = SliderDefaults.colors(thumbColor = Amber, activeTrackColor = Amber, inactiveTrackColor = Color.White.copy(alpha = 0.3f))
        when (control) {
            ProControl.ISO -> {
                val stops = (ISO_STOPS.filter { it in caps.isoRange } + caps.isoRange.first + caps.isoRange.last).distinct().sorted()
                val cur = ui.iso ?: ui.liveIso
                StepSlider(stops.size, stops.indices.minBy { abs(stops[it] - cur) }, colors) { engine.setIso(stops[it]) }
            }
            ProControl.SHUTTER -> {
                val stops = SHUTTER_STOPS.filter { it in caps.expRange }.sorted()
                if (stops.size >= 2) {
                    val cur = ui.exposure ?: ui.liveExposure
                    StepSlider(stops.size, stops.indices.minBy { abs(stops[it] - cur) }, colors) { engine.setExposure(stops[it]) }
                }
            }
            ProControl.FOCUS -> {
                Slider(
                    value = ui.focus ?: 0f,
                    onValueChange = { engine.setFocus(it) },
                    valueRange = 0f..caps.minFocus,
                    colors = colors,
                )
                Row(Modifier.fillMaxWidth()) {
                    Text("∞ far", Modifier.weight(1f), color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                    Text("near", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                }
            }
            ProControl.EV -> {
                val n = caps.evRange.last - caps.evRange.first + 1
                StepSlider(n, ui.ev - caps.evRange.first, colors) { engine.setEv(caps.evRange.first + it) }
            }
            ProControl.WB -> {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    WB_OPTIONS.filter { it.first in caps.awbModes }.forEach { (mode, label) ->
                        val sel = ui.awb == mode
                        Text(
                            label,
                            Modifier
                                .clip(RoundedCornerShape(50))
                                .background(if (sel) Amber else Color.White.copy(alpha = 0.15f))
                                .clickable { engine.setAwb(mode) }
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                            color = if (sel) Color.Black else Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

private val PanelBg = Color.Black.copy(alpha = 0.55f)

@Composable
private fun StepSlider(count: Int, index: Int, colors: androidx.compose.material3.SliderColors, onIndex: (Int) -> Unit) {
    if (count < 2) return
    Slider(
        value = index.coerceIn(0, count - 1).toFloat(),
        onValueChange = { onIndex(it.roundToInt().coerceIn(0, count - 1)) },
        valueRange = 0f..(count - 1).toFloat(),
        steps = (count - 2).coerceAtLeast(0),
        colors = colors,
    )
}
