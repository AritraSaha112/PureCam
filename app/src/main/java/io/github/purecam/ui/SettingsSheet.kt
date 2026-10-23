package io.github.purecam.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
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
import io.github.purecam.camera.Codec
import io.github.purecam.camera.VideoProfile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(engine: CameraEngine, onDismiss: () -> Unit) {
    val ui = engine.ui
    var showReport by remember { mutableStateOf(false) }
    if (showReport) DiagnosticsDialog(engine) { showReport = false }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF16181C),
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            Header("Photos")
            Note(
                "Pick a look with the ✦ button above the viewfinder. Natural switches the phone's processing off. " +
                    "The other looks use full-quality processing plus a colour grade you see live.",
            )
            Toggle(
                "Correct ultra-wide distortion in Natural",
                "Straightens the curved edges of the ultra-wide lens. Off = untouched optics. The other looks always correct it.",
                ui.uwCorrection, engine::setUwCorrection,
            )
            Toggle(
                "Also save RAW (DNG)",
                if (ui.caps.raw) "Saves the untouched sensor data next to each JPEG for Lightroom or Snapseed (about 24 MB each). Not used in Portrait."
                else "This lens doesn't offer RAW to apps.",
                ui.raw, engine::setRaw, enabled = ui.caps.raw,
            )

            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
            Header("Video")
            VideoProfile.entries.forEach { p ->
                Choice(p.title, p.detail, ui.videoProfile == p, enabled = p != VideoProfile.LOG || ui.caps.log) { engine.setVideoProfile(p) }
            }
            Note(
                "PureLog looks grey and flat on purpose. In DaVinci Resolve, CapCut or Premiere, apply the LUT " +
                    "\"PureLog_to_Rec709.cube\" from the PureCam GitHub page, then grade. For YouTube without editing, use Standard.",
            )
            Codec.entries.forEach { c ->
                Choice("Codec: ${c.title}", c.detail, ui.codec == c) { engine.setCodec(c) }
            }
            Toggle(
                "Video stabilisation",
                "Electronic stabilisation for smooth handheld shots (crops slightly). Optical stabilisation is always on.",
                ui.stabilization, engine::setStabilization,
            )

            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
            Header("Shooting")
            Toggle("Grid lines", "Rule-of-thirds guide on the viewfinder.", ui.grid, engine::setGrid)
            Toggle("Volume keys take photos / start video", "Press either volume key.", ui.volumeShutter, engine::setVolumeShutter)
            Toggle("Shutter sounds", null, ui.sound, engine::setSound)

            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
            Header("Lenses found on this phone")
            val all = (ui.photoLenses + ui.videoLenses).distinctBy { it.key }
            all.forEach { l ->
                val blocked = l.key in ui.blocked
                Text(
                    (if (blocked) "✕ " else "• ") + l.description + if (blocked) " · not usable by apps" else "",
                    Modifier.padding(horizontal = 24.dp, vertical = 3.dp),
                    color = Color.White.copy(alpha = if (blocked) 0.4f else 0.8f),
                    fontSize = 13.sp,
                )
            }
            ui.activeSensor?.let { Note("Sensor in use right now: camera ID $it") }
            ListItem(
                modifier = Modifier.clickable { showReport = true },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                headlineContent = { Text("Camera diagnostics", color = Amber) },
                supportingContent = {
                    Text(
                        "Lists every camera the phone reveals to apps. Copy or share it to help add a missing lens.",
                        color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp,
                    )
                },
            )
            Note("Photos and videos are saved in Gallery › DCIM › PureCam.")
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
private fun Note(text: String) {
    Text(text, Modifier.padding(horizontal = 24.dp, vertical = 6.dp), color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp)
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

@Composable
private fun Choice(title: String, subtitle: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            RadioButton(selected = selected, onClick = null, enabled = enabled, colors = RadioButtonDefaults.colors(selectedColor = Amber))
        },
        headlineContent = { Text(title, color = Color.White.copy(alpha = if (enabled) 1f else 0.4f)) },
        supportingContent = { Text(subtitle, color = Color.White.copy(alpha = if (enabled) 0.6f else 0.3f), fontSize = 13.sp) },
    )
}


@Composable
private fun DiagnosticsDialog(engine: CameraEngine, onDismiss: () -> Unit) {
    val report = remember { engine.diagnostics() }
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF101215)) {
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(12.dp)) {
                Row {
                    Text("Camera report", Modifier.weight(1f).padding(top = 12.dp), color = Color.White, fontWeight = FontWeight.Bold)
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(report))
                        engine.toast("Report copied")
                    }) { Text("Copy", color = Amber) }
                    TextButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report)
                        ctx.startActivity(Intent.createChooser(send, "Share camera report"))
                    }) { Text("Share", color = Amber) }
                    TextButton(onClick = onDismiss) { Text("Close", color = Color.White) }
                }
                Text(
                    report,
                    Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 14.sp,
                )
            }
        }
    }
}
