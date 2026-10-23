package io.github.purecam.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.FlashAuto
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.FlashlightOff
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import io.github.purecam.camera.CameraEngine
import io.github.purecam.camera.Flash
import io.github.purecam.camera.Lens
import io.github.purecam.camera.Look
import io.github.purecam.camera.Mode
import io.github.purecam.camera.VideoProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

private val RecordRed = Color(0xFFFF3B30)
private val APERTURES = listOf(1.4f, 2f, 2.8f, 4f, 5.6f, 8f, 11f, 16f)

@Composable
fun CameraScreen(engine: CameraEngine) {
    val ui = engine.ui
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val rot by animateFloatAsState(ui.rotation.toFloat(), tween(250), label = "iconRotation")
    var showSettings by remember { mutableStateOf(false) }
    var showLooks by remember { mutableStateOf(false) }
    var showAperture by remember { mutableStateOf(false) }
    var proControl by remember { mutableStateOf<ProControl?>(null) }

    // ---- blur-snapshot transition when the camera has to be reopened (lens / mode change)
    var textureView by remember { mutableStateOf<TextureView?>(null) }
    var snapshot by remember { mutableStateOf<ImageBitmap?>(null) }
    val snapAlpha = remember { Animatable(0f) }
    fun transition() {
        val tv = textureView ?: return
        if (tv.width <= 0 || !tv.isAvailable) return
        tv.getBitmap(tv.width / 4, tv.height / 4)?.let {
            snapshot = it.asImageBitmap()
            scope.launch { snapAlpha.snapTo(1f) }
        }
    }
    LaunchedEffect(ui.streamSeq) {
        if (snapshot != null) {
            delay(90)
            snapAlpha.animateTo(0f, tween(320))
            snapshot = null
        }
    }
    LaunchedEffect(snapshot) {
        if (snapshot != null) {
            delay(2500)
            snapAlpha.animateTo(0f, tween(200)); snapshot = null
        }
    }

    fun pickLens(l: Lens) {
        if (engine.needsReopen(l)) transition()
        engine.selectLens(l)
    }
    fun changeMode(m: Mode) {
        if (m == ui.mode || ui.recording) return
        if (m.video != ui.mode.video) transition()
        showLooks = false; showAperture = false; proControl = null
        engine.setMode(m)
    }

    // ---- microphone permission the first time Video is opened
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(ui.mode) {
        if (ui.mode.video && ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val topInset = WindowInsets.displayCutout.union(WindowInsets.statusBars).asPaddingValues().calculateTopPadding()
    val aspect by animateFloatAsState(ui.previewAspect, spring(stiffness = Spring.StiffnessMediumLow), label = "aspect")
    val topPad by animateDpAsState(if (ui.mode.video) 0.dp else topInset + 56.dp, spring(stiffness = Spring.StiffnessMediumLow), label = "topPad")

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // ---------------- viewfinder
        Box(
            Modifier
                .align(if (ui.mode.video) Alignment.Center else Alignment.TopCenter)
                .padding(top = topPad)
                .fillMaxWidth()
                .aspectRatio(aspect)
                .clip(RoundedCornerShape(if (ui.mode.video) 0.dp else 4.dp)),
        ) {
            Viewfinder(engine, if (ui.mode.video) null else ui.look) { textureView = it }
            snapshot?.let {
                Image(
                    it, null,
                    Modifier.fillMaxSize().blur(28.dp).graphicsLayer { alpha = snapAlpha.value },
                    contentScale = ContentScale.Crop,
                )
            }
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var pan = Offset.Zero
                        var multi = false
                        var swiped = false
                        do {
                            val ev = awaitPointerEvent()
                            val pressed = ev.changes.count { it.pressed }
                            if (pressed >= 2) {
                                multi = true
                                val z = ev.calculateZoom()
                                if (z != 1f) engine.pinch(z)
                                ev.changes.forEach { it.consume() }
                            } else if (!multi && !swiped) {
                                pan += ev.calculatePan()
                                if (abs(pan.x) > 140f && abs(pan.x) > 2 * abs(pan.y)) {
                                    swiped = true
                                    val modes = Mode.entries
                                    val i = modes.indexOf(engine.ui.mode) + if (pan.x < 0) 1 else -1
                                    if (i in modes.indices) changeMode(modes[i])
                                }
                            }
                        } while (ev.changes.any { it.pressed })
                        if (!multi && !swiped && pan.getDistance() < viewConfiguration.touchSlop) {
                            engine.tapFocus(down.position.x / size.width, down.position.y / size.height)
                        }
                    }
                },
            )
            if (ui.grid) GridOverlay()
            FocusRing(ui.focusPoint, ui.focusLocked)
            ShutterBlink(ui.shutterBlink)

            val effZoom = (ui.lens?.ratio ?: 1f) * ui.zoom
            if (abs(ui.zoom - 1f) > 0.01f) {
                Pill(
                    String.format(Locale.US, "%.1f×", effZoom),
                    Modifier.align(Alignment.TopCenter).padding(top = if (ui.mode.video) topInset + 64.dp else 12.dp).rotate(rot),
                )
            }
            // Telephoto check: does the camera report the telephoto focal length, or is it digital zoom?
            val lensNow = ui.lens
            if (lensNow != null && lensNow.zoomRatio != null && effZoom >= 2.4f && ui.liveFocal > 0f) {
                val tele = isTelephotoActive(lensNow.chars, ui.liveFocal)
                Pill(
                    if (tele) "TELEPHOTO LENS ✓" else "DIGITAL ZOOM · main lens",
                    Modifier.align(Alignment.TopCenter).padding(top = if (ui.mode.video) topInset + 98.dp else 46.dp).rotate(rot),
                    highlight = tele,
                )
            }
            if (ui.pro && ui.liveIso > 0) {
                Pill(
                    "ISO ${ui.liveIso} · ${formatShutter(ui.liveExposure)}" + (ui.activeSensor?.let { " · cam $it" } ?: ""),
                    Modifier.align(Alignment.TopStart).padding(10.dp),
                )
            }
            if (ui.countdown > 0) {
                Text(
                    "${ui.countdown}", Modifier.align(Alignment.Center).rotate(rot),
                    color = Color.White, fontSize = 120.sp, fontWeight = FontWeight.Light,
                )
            }
            if (ui.processing) {
                Pill(if (ui.mode == Mode.PORTRAIT) "Blurring background…" else "Applying ${ui.look.title}…", Modifier.align(Alignment.Center))
            }
            if (!ui.ready && snapshot == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center).size(36.dp), color = Color.White, strokeWidth = 2.dp)
            }

            // Bottom overlays (stacked)
            Column(Modifier.align(Alignment.BottomCenter).padding(bottom = if (ui.mode.video) 230.dp else 0.dp)) {
                if (ui.mode == Mode.PORTRAIT) {
                    AnimatedVisibility(showAperture, enter = fadeIn(), exit = fadeOut()) { ApertureSlider(engine) }
                    Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.Center) {
                        Pill(
                            "ƒ " + fmtF(ui.aperture),
                            Modifier.clip(RoundedCornerShape(50)).clickable { showAperture = !showAperture }.rotate(rot),
                            highlight = showAperture,
                        )
                    }
                }
                if (showLooks && !ui.mode.video) LooksStrip(engine)
                if (ui.pro) ProSliderOverlay(engine, proControl, Modifier)
            }
        }

        // ---------------- top bar
        TopBar(
            engine, rot, topInset,
            onSettings = { showSettings = true },
            looksOpen = showLooks,
            onLooks = { showLooks = !showLooks },
            onVideoReopen = { transition() },
        )

        // ---------------- bottom controls
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = if (ui.mode.video) 0.55f else 0f))))
                .navigationBarsPadding()
                .padding(top = 8.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (ui.mode == Mode.PORTRAIT) {
                Text(
                    "Background blur is added when you take the photo · works best with people",
                    color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp,
                )
            }
            LensChips(engine, rot, ::pickLens)
            if (ui.pro) ProChips(engine, proControl) { proControl = if (proControl == it) null else it }
            AnimatedVisibility(!ui.recording, enter = fadeIn(), exit = fadeOut()) {
                ModeCarousel(ui.mode, ::changeMode)
            }
            ShutterRow(engine, rot) { transition(); engine.flip() }
        }

        if (showSettings) SettingsSheet(engine) { showSettings = false }
    }
}

// ============================================================================ viewfinder

@Composable
private fun Viewfinder(engine: CameraEngine, look: Look?, onView: (TextureView) -> Unit) {
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
                onView(this)
            }
        },
        update = { tv ->
            // Live preview of the selected look (the same colour grade is applied to the saved photo)
            val m = look?.matrix()
            tv.setLayerPaint(if (m == null) null else Paint().apply { colorFilter = ColorMatrixColorFilter(m) })
        },
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
    val scale = remember(point) { Animatable(1.5f) }
    LaunchedEffect(point) { scale.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium)) }
    Canvas(Modifier.fillMaxSize()) {
        val c = Offset(point.x * size.width, point.y * size.height)
        val s = 34.dp.toPx() * scale.value
        val col = if (locked) Amber else Color.White
        drawRoundRect(
            col,
            topLeft = Offset(c.x - s, c.y - s),
            size = androidx.compose.ui.geometry.Size(2 * s, 2 * s),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()),
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
        alpha.animateTo(0f, tween(200))
    }
    if (alpha.value > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = alpha.value)))
}

// ============================================================================ top bar

@Composable
private fun TopBar(
    engine: CameraEngine,
    rot: Float,
    topInset: androidx.compose.ui.unit.Dp,
    onSettings: () -> Unit,
    looksOpen: Boolean,
    onLooks: () -> Unit,
    onVideoReopen: () -> Unit,
) {
    val ui = engine.ui
    Box(
        Modifier
            .fillMaxWidth()
            .background(
                if (ui.mode.video) Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent))
                else Brush.verticalGradient(listOf(Color.Black, Color.Black)),
            )
            .padding(top = topInset)
            .height(56.dp)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (ui.recording) {
            RecordingPill(ui.recordStart)
            return@Box
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TopIcon(Icons.Outlined.Settings, "Settings", rot, onClick = onSettings)
            if (ui.mode.video) {
                if (ui.caps.flash) {
                    TopIcon(if (ui.torch) Icons.Outlined.FlashlightOn else Icons.Outlined.FlashlightOff, "Torch", rot, active = ui.torch) {
                        engine.setTorch(!ui.torch)
                    }
                }
                TapChip(if (ui.effRes >= 2160) "4K" else "HD", rot, active = ui.effRes >= 2160, enabled = ui.caps.uhd) {
                    onVideoReopen(); engine.setVideoRes(if (ui.videoRes == 2160) 1080 else 2160)
                }
                TapChip("${ui.effFps}", rot, active = ui.effFps == 60) {
                    if (!ui.caps.fps60 && ui.videoFps == 30) engine.toast("60 fps isn't offered to apps at this resolution on this lens")
                    else { onVideoReopen(); engine.setVideoFps(if (ui.videoFps == 60) 30 else 60) }
                }
                TapChip(ui.videoProfile.short, rot, active = ui.videoProfile != VideoProfile.STANDARD) {
                    val all = VideoProfile.entries
                    engine.setVideoProfile(all[(all.indexOf(ui.videoProfile) + 1) % all.size])
                }
            } else {
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
                if (ui.caps.raw && ui.mode != Mode.PORTRAIT) {
                    TopIcon(if (ui.raw) Icons.Outlined.RawOn else Icons.Outlined.RawOff, "RAW", rot, active = ui.raw) { engine.setRaw(!ui.raw) }
                }
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(if (looksOpen) Amber.copy(alpha = 0.2f) else Scrim).clickable(onClick = onLooks)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(16.dp).rotate(rot), tint = Amber)
                    Spacer(Modifier.width(5.dp))
                    Text(ui.look.title.uppercase(), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun RecordingPill(start: Long) {
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(start) {
        while (true) { now = android.os.SystemClock.elapsedRealtime(); delay(250) }
    }
    val secs = ((now - start) / 1000).coerceAtLeast(0)
    val blink = rememberInfiniteTransition(label = "rec")
    val dot by blink.animateFloat(1f, 0.2f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "dot")
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).background(RecordRed).padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).alpha(dot).clip(CircleShape).background(Color.White))
        Spacer(Modifier.width(8.dp))
        Text(
            String.format(Locale.US, "%02d:%02d:%02d", secs / 3600, (secs / 60) % 60, secs % 60),
            color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun TopIcon(icon: ImageVector, desc: String, rot: Float, active: Boolean = false, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Icon(icon, desc, Modifier.rotate(rot), tint = if (active) Amber else Color.White)
    }
}

@Composable
private fun TapChip(text: String, rot: Float, active: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text,
        Modifier
            .rotate(rot)
            .clip(RoundedCornerShape(6.dp))
            .border(1.2.dp, (if (active) Amber else Color.White).copy(alpha = if (enabled) 1f else 0.35f), RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        color = (if (active) Amber else Color.White).copy(alpha = if (enabled) 1f else 0.35f),
        fontSize = 12.sp, fontWeight = FontWeight.Bold,
    )
}

@Composable
fun Pill(text: String, modifier: Modifier = Modifier, highlight: Boolean = false) {
    Text(
        text,
        modifier.clip(RoundedCornerShape(50)).background(Scrim).padding(horizontal = 10.dp, vertical = 4.dp),
        color = if (highlight) Amber else Color.White,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

// ============================================================================ overlays

@Composable
private fun LooksStrip(engine: CameraEngine) {
    val ui = engine.ui
    androidx.compose.foundation.lazy.LazyRow(
        Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).padding(vertical = 10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(Look.entries.size) { i ->
            val look = Look.entries[i]
            val sel = look == ui.look
            Column(
                Modifier.clip(RoundedCornerShape(10.dp)).clickable { engine.setLook(look) }.padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                LookSwatch(look, sel)
                Spacer(Modifier.height(4.dp))
                Text(look.title, color = if (sel) Amber else Color.White, fontSize = 11.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

/** A small sample image (sky, skin, foliage) rendered through the look's colour matrix. */
@Composable
private fun LookSwatch(look: Look, selected: Boolean) {
    val m = look.matrix()
    val cols = listOf(Color(0xFF5B8FD6), Color(0xFFE2A27E), Color(0xFF4F8A3C), Color(0xFF2B2B2B))
    fun graded(c: Color): Color {
        if (m == null) return c
        val a = m.array
        val r = c.red * 255; val g = c.green * 255; val b = c.blue * 255
        fun ch(o: Int) = ((a[o] * r + a[o + 1] * g + a[o + 2] * b + a[o + 4]) / 255f).coerceIn(0f, 1f)
        return Color(ch(0), ch(5), ch(10))
    }
    Box(
        Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(2.dp, if (selected) Amber else Color.Transparent, RoundedCornerShape(10.dp))
            .background(Brush.verticalGradient(cols.map { graded(it) })),
        contentAlignment = Alignment.Center,
    ) {
        if (look.natural) Text("RAW\nLOOK", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold, lineHeight = 9.sp)
    }
}

@Composable
private fun ApertureSlider(engine: CameraEngine) {
    val ui = engine.ui
    val idx = APERTURES.indices.minBy { abs(APERTURES[it] - ui.aperture) }
    Column(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row {
            Text("More blur", Modifier.weight(1f), color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
            Text("Less blur", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
        }
        Slider(
            value = idx.toFloat(),
            onValueChange = { engine.setAperture(APERTURES[it.roundToInt().coerceIn(0, APERTURES.lastIndex)]) },
            valueRange = 0f..APERTURES.lastIndex.toFloat(),
            steps = APERTURES.size - 2,
            colors = SliderDefaults.colors(thumbColor = Amber, activeTrackColor = Amber, inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
        )
    }
}

private fun fmtF(f: Float) = if (f < 10f && f != f.roundToInt().toFloat()) String.format(Locale.US, "%.1f", f) else f.roundToInt().toString()

// ============================================================================ bottom controls

@Composable
private fun LensChips(engine: CameraEngine, rot: Float, onPick: (Lens) -> Unit) {
    val ui = engine.ui
    val cur = ui.lens ?: return
    val lenses = ui.lenses.filter { it.front == cur.front }
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
            val disabled = ui.recording && engine.needsReopen(l)
            val label = if (selected) {
                if (abs(ui.zoom - 1f) > 0.01f) String.format(Locale.US, "%.1f×", l.ratio * ui.zoom) else "${l.label}×"
            } else l.label
            val size by animateFloatAsState(if (selected) 48f else 38f, spring(dampingRatio = 0.6f), label = "chip")
            Box(
                Modifier
                    .size(size.dp)
                    .alpha(if (disabled) 0.3f else 1f)
                    .clip(CircleShape)
                    .background(if (selected) Color.Black.copy(alpha = 0.65f) else Color.Transparent)
                    .clickable(enabled = !disabled) { onPick(l) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label, Modifier.rotate(rot),
                    color = if (selected) Amber else Color.White,
                    fontSize = if (selected) 14.sp else 12.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                )
            }
        }
    }
}

/** iOS-style sliding mode picker: the selected mode always sits in the centre. */
@Composable
private fun ModeCarousel(selected: Mode, onSelect: (Mode) -> Unit) {
    val centers = remember { mutableStateMapOf<Mode, Float>() }
    var width by remember { mutableIntStateOf(0) }
    val target = width / 2f - (centers[selected] ?: (width / 2f))
    val offset by animateFloatAsState(target, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow), label = "carousel")
    Box(
        Modifier.fillMaxWidth().height(34.dp).onSizeChanged { width = it.width }.clipToBounds(),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            Modifier.wrapContentWidth(Alignment.Start, unbounded = true).graphicsLayer { translationX = offset },
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Mode.entries.forEach { m ->
                val sel = m == selected
                val color by animateColorAsState(if (sel) Amber else Color.White.copy(alpha = 0.75f), label = "modeColor")
                Text(
                    m.label,
                    Modifier
                        .onGloballyPositioned { c -> centers[m] = c.positionInParent().x + c.size.width / 2f }
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onSelect(m) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    color = color,
                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 13.sp,
                    letterSpacing = 1.2.sp,
                )
            }
        }
    }
}

@Composable
private fun ShutterRow(engine: CameraEngine, rot: Float, onFlip: () -> Unit) {
    val ui = engine.ui
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 36.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Last photo / video
        Box(
            Modifier
                .size(54.dp)
                .alpha(if (ui.recording) 0f else 1f)
                .rotate(rot)
                .clip(CircleShape)
                .border(2.dp, Color.White.copy(alpha = 0.8f), CircleShape)
                .background(Color.White.copy(alpha = 0.1f))
                .clickable(enabled = ui.lastUri != null && !ui.recording) {
                    ui.lastUri?.let { uri ->
                        runCatching {
                            ctx.startActivity(
                                Intent(Intent.ACTION_VIEW).setDataAndType(uri, if (ui.lastIsVideo) "video/mp4" else "image/jpeg")
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                            )
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            val t = ui.thumb
            if (t != null) Image(t.asImageBitmap(), "Last capture", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(Icons.Outlined.PhotoLibrary, null, tint = Color.White.copy(alpha = 0.7f))
        }

        // Shutter: white circle for photos, red circle for video that morphs into a rounded square while recording
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val press by animateFloatAsState(if (pressed) 0.88f else 1f, tween(90), label = "press")
        val video = ui.mode.video
        val inner by animateDpAsState(if (ui.recording) 30.dp else 64.dp, spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow), label = "inner")
        val corner by animateDpAsState(if (ui.recording) 7.dp else 32.dp, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow), label = "corner")
        val fill by animateColorAsState(if (video) RecordRed else Color.White, tween(250), label = "fill")
        Box(
            Modifier
                .size(82.dp)
                .border(4.dp, Color.White, CircleShape)
                .clip(CircleShape)
                .clickable(interactionSource = interaction, indication = null, enabled = ui.ready && !ui.capturing) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    engine.shutter()
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(inner).scale(press).clip(RoundedCornerShape(corner))
                    .background(if (ui.ready) fill else fill.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center,
            ) {
                if (ui.capturing) CircularProgressIndicator(Modifier.size(28.dp), color = Color.Black, strokeWidth = 3.dp)
                else if (!video && ui.timer > 0) Icon(Icons.Outlined.Timer, null, tint = Color.Black)
            }
        }

        // Flip
        val canFlip = ui.lenses.any { it.front } && ui.lenses.any { !it.front } && !ui.recording
        Box(
            Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = if (canFlip) 0.14f else 0f))
                .clickable(enabled = canFlip, onClick = onFlip),
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
        if (s >= 1 && abs(s - Math.round(s)) < 0.05) "${Math.round(s)}s" else String.format(Locale.US, "%.1fs", s)
    } else "1/${Math.round(1 / s)}"
}


/** True when the reported focal length is clearly longer than the main lens' focal length. */
private fun isTelephotoActive(ch: CameraCharacteristics, liveFocal: Float): Boolean {
    val focals = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: return false
    if (focals.isEmpty()) return false
    val phys = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
    val diag = if (phys != null) hypot(phys.width, phys.height) else 7f
    val mainFocal = focals.minBy { abs(it * 43.27f / diag - 26f) }
    return liveFocal > mainFocal * 1.6f
}
