package io.github.purecam.ui

import android.content.Intent
import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.FlashAuto
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.FlashlightOn
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.RawOff
import androidx.compose.material.icons.outlined.RawOn
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Timer10
import androidx.compose.material.icons.outlined.Timer3
import androidx.compose.material.icons.outlined.TimerOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.purecam.camera.CameraEngine
import io.github.purecam.camera.Flash
import java.util.Locale

@Composable
fun CameraScreen(engine: CameraEngine) {
    val ui = engine.ui
    val rot by animateFloatAsState(ui.rotation.toFloat(), tween(250), label = "iconRotation")
    var showSettings by remember { mutableStateOf(false) }
    var proControl by remember { mutableStateOf<ProControl?>(null) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {
            TopBar(engine, rot) { showSettings = true }

            // ---------- viewfinder ----------
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(ui.previewAspect)
                    .clip(RoundedCornerShape(4.dp)),
            ) {
                Viewfinder(engine)
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures { o -> engine.tapFocus(o.x / size.width, o.y / size.height) }
                        }
                        .pointerInput(Unit) {
                            detectTransformGestures { _, _, zoom, _ -> if (zoom != 1f) engine.setZoom(engine.ui.zoom * zoom) }
                        },
                )
                if (ui.grid) GridOverlay()
                FocusRing(ui.focusPoint, ui.focusLocked)
                ShutterBlink(ui.shutterBlink)

                if (ui.zoom > 1.01f) {
                    Pill(
                        String.format(Locale.US, "%.1f×", (ui.lens?.ratio ?: 1f) * ui.zoom),
                        Modifier.align(Alignment.TopCenter).padding(top = 12.dp).rotate(rot),
                    )
                }
                if (ui.pro && ui.liveIso > 0) {
                    Pill(
                        "ISO ${ui.liveIso} · ${formatShutter(ui.liveExposure)}",
                        Modifier.align(Alignment.TopStart).padding(10.dp),
                    )
                }
                if (ui.countdown > 0) {
                    Text(
                        "${ui.countdown}",
                        Modifier.align(Alignment.Center).rotate(rot),
                        color = Color.White, fontSize = 120.sp, fontWeight = FontWeight.Light,
                    )
                }
                if (!ui.ready) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center).size(36.dp), color = Color.White, strokeWidth = 2.dp)
                }
                ProSliderOverlay(engine, proControl, Modifier.align(Alignment.BottomCenter))
            }

            // ---------- controls ----------
            Column(
                Modifier.fillMaxWidth().weight(1f).navigationBarsPadding(),
                verticalArrangement = Arrangement.SpaceEvenly,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                LensChips(engine, rot)
                ModeTabs(ui.pro) { engine.setPro(it); if (!it) proControl = null }
                if (ui.pro) ProChips(engine, proControl) { proControl = if (proControl == it) null else it }
                ShutterRow(engine, rot)
            }
        }
        if (showSettings) SettingsSheet(engine) { showSettings = false }
    }
}

@Composable
private fun Viewfinder(engine: CameraEngine) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { c ->
            TextureView(c).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = engine.onSurfaceAvailable(st)
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        engine.onSurfaceDestroyed(st)
                        return false
                    }
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                }
            }
        },
    )
}

@Composable
private fun TopBar(engine: CameraEngine, rot: Float, onSettings: () -> Unit) {
    val ui = engine.ui
    Row(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.displayCutout.union(WindowInsets.statusBars))
            .height(56.dp)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TopIcon(Icons.Outlined.Settings, "Settings", rot, onClick = onSettings)
        if (ui.caps.flash) {
            val (icon, next) = when (ui.flash) {
                Flash.OFF -> Icons.Outlined.FlashOff to Flash.AUTO
                Flash.AUTO -> Icons.Outlined.FlashAuto to Flash.ON
                Flash.ON -> Icons.Outlined.FlashOn to Flash.TORCH
                Flash.TORCH -> Icons.Outlined.FlashlightOn to Flash.OFF
            }
            TopIcon(icon, "Flash", rot, active = ui.flash != Flash.OFF) { engine.setFlash(next) }
        }
        val (timerIcon, nextTimer) = when (ui.timer) {
            3 -> Icons.Outlined.Timer3 to 10
            10 -> Icons.Outlined.Timer10 to 0
            else -> Icons.Outlined.TimerOff to 3
        }
        TopIcon(timerIcon, "Timer", rot, active = ui.timer > 0) { engine.setTimer(nextTimer) }
        if (ui.caps.raw) {
            TopIcon(if (ui.raw) Icons.Outlined.RawOn else Icons.Outlined.RawOff, "RAW", rot, active = ui.raw) { engine.setRaw(!ui.raw) }
        }
        Pill(if (ui.natural) "NATURAL" else "STANDARD", Modifier.rotate(rot), highlight = ui.natural)
    }
}

@Composable
private fun TopIcon(icon: ImageVector, desc: String, rot: Float, active: Boolean = false, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Icon(icon, desc, Modifier.rotate(rot), tint = if (active) Amber else Color.White)
    }
}

@Composable
fun Pill(text: String, modifier: Modifier = Modifier, highlight: Boolean = false) {
    Text(
        text,
        modifier
            .clip(RoundedCornerShape(50))
            .background(Scrim)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        color = if (highlight) Amber else Color.White,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun GridOverlay() {
    Canvas(Modifier.fillMaxSize()) {
        val c = Color.White.copy(alpha = 0.35f)
        for (i in 1..2) {
            val x = size.width * i / 3
            val y = size.height * i / 3
            drawLine(c, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
            drawLine(c, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
        }
    }
}

@Composable
private fun FocusRing(point: Offset?, locked: Boolean) {
    if (point == null) return
    val scale = remember(point) { Animatable(1.4f) }
    LaunchedEffect(point) { scale.animateTo(1f, tween(220)) }
    Canvas(Modifier.fillMaxSize()) {
        val c = Offset(point.x * size.width, point.y * size.height)
        drawCircle(
            if (locked) Amber else Color.White,
            radius = 34.dp.toPx() * scale.value,
            center = c,
            style = Stroke(1.5.dp.toPx()),
        )
    }
}

@Composable
private fun ShutterBlink(trigger: Int) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(trigger) {
        if (trigger == 0) return@LaunchedEffect
        alpha.snapTo(0.85f)
        alpha.animateTo(0f, tween(180))
    }
    if (alpha.value > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = alpha.value)))
}

@Composable
private fun LensChips(engine: CameraEngine, rot: Float) {
    val ui = engine.ui
    val cur = ui.lens ?: return
    val lenses = ui.lenses.filter { it.front == cur.front && it.key !in ui.blocked }
    if (lenses.size < 2) {
        Box(Modifier.height(52.dp))
        return
    }
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.08f)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        lenses.forEach { l ->
            val selected = l.key == cur.key
            val label = if (selected) {
                if (ui.zoom > 1.01f) String.format(Locale.US, "%.1f×", l.ratio * ui.zoom) else "${l.label}×"
            } else l.label
            val size by animateFloatAsState(if (selected) 48f else 40f, label = "chip")
            Box(
                Modifier
                    .size(size.dp)
                    .clip(CircleShape)
                    .background(if (selected) Color.Black.copy(alpha = 0.6f) else Color.Transparent)
                    .clickable { engine.selectLens(l) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    Modifier.rotate(rot),
                    color = if (selected) Amber else Color.White,
                    fontSize = if (selected) 14.sp else 13.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun ModeTabs(pro: Boolean, onChange: (Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        listOf(false to "PHOTO", true to "PRO").forEach { (value, label) ->
            Text(
                label,
                Modifier.clip(RoundedCornerShape(8.dp)).clickable { onChange(value) }.padding(horizontal = 10.dp, vertical = 6.dp),
                color = if (pro == value) Amber else Color.White.copy(alpha = 0.75f),
                fontWeight = if (pro == value) FontWeight.Bold else FontWeight.Medium,
                fontSize = 14.sp,
                letterSpacing = 1.sp,
            )
        }
    }
}

@Composable
private fun ShutterRow(engine: CameraEngine, rot: Float) {
    val ui = engine.ui
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 36.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Last photo
        Box(
            Modifier
                .size(54.dp)
                .rotate(rot)
                .clip(CircleShape)
                .border(2.dp, Color.White.copy(alpha = 0.8f), CircleShape)
                .background(Color.White.copy(alpha = 0.1f))
                .clickable(enabled = ui.lastUri != null) {
                    ui.lastUri?.let { uri ->
                        runCatching {
                            ctx.startActivity(
                                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/jpeg")
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                            )
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            val t = ui.thumb
            if (t != null) {
                Image(t.asImageBitmap(), "Last photo", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Icon(Icons.Outlined.PhotoLibrary, null, tint = Color.White.copy(alpha = 0.7f))
            }
        }

        // Shutter
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val scale by animateFloatAsState(if (pressed) 0.86f else 1f, tween(90), label = "shutter")
        Box(
            Modifier
                .size(82.dp)
                .border(4.dp, Color.White, CircleShape)
                .padding(8.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(if (ui.ready) Color.White else Color.White.copy(alpha = 0.4f))
                .clickable(interactionSource = interaction, indication = null, enabled = ui.ready && !ui.capturing) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    engine.shutter()
                },
            contentAlignment = Alignment.Center,
        ) {
            if (ui.capturing) CircularProgressIndicator(Modifier.size(30.dp), color = Color.Black, strokeWidth = 3.dp)
            else if (ui.timer > 0) Icon(Icons.Outlined.Timer, null, tint = Color.Black)
        }

        // Flip
        val canFlip = ui.lenses.any { it.front } && ui.lenses.any { !it.front }
        Box(
            Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = if (canFlip) 0.14f else 0f))
                .clickable(enabled = canFlip) { engine.flip() },
            contentAlignment = Alignment.Center,
        ) {
            if (canFlip) Icon(Icons.Outlined.Cameraswitch, "Switch camera", Modifier.rotate(rot), tint = Color.White)
        }
    }
}

fun formatShutter(ns: Long): String {
    if (ns <= 0) return "–"
    val s = ns / 1e9
    return if (s >= 0.3) {
        if (s >= 1 && kotlin.math.abs(s - Math.round(s)) < 0.05) "${Math.round(s)}s" else String.format(Locale.US, "%.1fs", s)
    } else "1/${Math.round(1 / s)}"
}

@Composable
fun FadeVisible(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) { content() }
}
