package io.github.purecam.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.purecam.camera.CameraEngine

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(engine: CameraEngine, onDismiss: () -> Unit) {
    val ui = engine.ui
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF16181C),
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            Header("Image processing")
            Toggle(
                "Natural mode (processing off)",
                "Turns off noise reduction, sharpening, the phone's punchy tone curve and lens-distortion correction. " +
                    "Photos look like what the sensor saw: more grain, but real detail and no smudged textures. " +
                    "Turn off to compare with the phone's standard processing.",
                ui.natural, engine::setNatural,
            )
            Toggle(
                "Correct ultra-wide distortion",
                "Straightens the curved edges of the ultra-wide lens. Off = untouched optics.",
                ui.uwCorrection, engine::setUwCorrection, enabled = ui.natural,
            )
            Toggle(
                "Also save RAW (DNG)",
                if (ui.caps.raw) "Saves the unprocessed sensor data next to each JPEG, for Lightroom or Snapseed. About 24 MB per photo."
                else "This lens doesn't offer RAW to apps.",
                ui.raw, engine::setRaw, enabled = ui.caps.raw,
            )
            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
            Header("Shooting")
            Toggle("Grid lines", "Rule-of-thirds guide on the viewfinder.", ui.grid, engine::setGrid)
            Toggle("Volume keys take photos", "Press either volume key to shoot.", ui.volumeShutter, engine::setVolumeShutter)
            Toggle("Shutter sound", null, ui.sound, engine::setSound)
            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
            Header("Lenses found on this phone")
            ui.lenses.forEach { l ->
                Text(
                    (if (l.key in ui.blocked) "✕ " else "• ") + l.description + if (l.key in ui.blocked) " — not usable by apps" else "",
                    Modifier.padding(horizontal = 24.dp, vertical = 3.dp),
                    color = Color.White.copy(alpha = if (l.key in ui.blocked) 0.4f else 0.8f),
                    fontSize = 13.sp,
                )
            }
            Text(
                "Photos are saved in Gallery › DCIM › PureCam.",
                Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text.uppercase(),
        Modifier.padding(start = 24.dp, top = 16.dp, bottom = 4.dp),
        color = Amber, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
    )
}

@Composable
private fun Toggle(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(title, color = Color.White.copy(alpha = if (enabled) 1f else 0.4f)) },
        supportingContent = subtitle?.let { { Text(it, color = Color.White.copy(alpha = if (enabled) 0.6f else 0.3f), fontSize = 13.sp) } },
        trailingContent = {
            Switch(
                checked = checked && enabled,
                onCheckedChange = onChange,
                enabled = enabled,
                colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = Amber),
            )
        },
    )
}
