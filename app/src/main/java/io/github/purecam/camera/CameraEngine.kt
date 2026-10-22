package io.github.purecam.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.TonemapCurve
import android.media.ExifInterface
import android.media.Image
import android.media.ImageReader
import android.media.MediaActionSound
import android.media.MediaCodec
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Range
import android.util.Size
import android.view.Surface
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileDescriptor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class LensCaps(
    val manualSensor: Boolean = false,
    val isoRange: IntRange = 100..100,
    val expRange: LongRange = 1_000_000L..1_000_000L,
    val minFocus: Float = 0f,
    val evRange: IntRange = 0..0,
    val evStep: Float = 1f,
    val flash: Boolean = false,
    val raw: Boolean = false,
    val awbModes: List<Int> = listOf(CameraMetadata.CONTROL_AWB_MODE_AUTO),
    val minZoom: Float = 1f,
    val maxZoom: Float = 1f,
    val uhd: Boolean = false,
    val fps60: Boolean = false,
    val log: Boolean = false,
)

/** All state the UI draws. Written from the camera thread and read by Compose. */
class CamUi {
    var photoLenses by mutableStateOf<List<Lens>>(emptyList())
    var videoLenses by mutableStateOf<List<Lens>>(emptyList())
    var blocked by mutableStateOf<Set<String>>(emptySet())
    var lens by mutableStateOf<Lens?>(null)
    var caps by mutableStateOf(LensCaps())
    var mode by mutableStateOf(Mode.PHOTO)
    var previewAspect by mutableFloatStateOf(0.75f)
    var ready by mutableStateOf(false)
    var streamSeq by mutableIntStateOf(0)
    var capturing by mutableStateOf(false)
    var processing by mutableStateOf(false)
    var countdown by mutableIntStateOf(0)
    var shutterBlink by mutableIntStateOf(0)
    var thumb by mutableStateOf<Bitmap?>(null)
    var lastUri by mutableStateOf<Uri?>(null)
    var lastIsVideo by mutableStateOf(false)
    var focusPoint by mutableStateOf<Offset?>(null)
    var focusLocked by mutableStateOf(false)
    var zoom by mutableFloatStateOf(1f)
    var liveIso by mutableIntStateOf(0)
    var liveExposure by mutableLongStateOf(0L)
    var activeSensor by mutableStateOf<String?>(null)
    var rotation by mutableIntStateOf(0)

    // Video
    var recording by mutableStateOf(false)
    var recordStart by mutableLongStateOf(0L)
    var effRes by mutableIntStateOf(1080)
    var effFps by mutableIntStateOf(30)
    var torch by mutableStateOf(false)

    // Pro (manual) controls; null = automatic
    val pro: Boolean get() = mode == Mode.PRO
    var iso by mutableStateOf<Int?>(null)
    var exposure by mutableStateOf<Long?>(null)
    var focus by mutableStateOf<Float?>(null)
    var awb by mutableIntStateOf(CameraMetadata.CONTROL_AWB_MODE_AUTO)
    var ev by mutableIntStateOf(0)

    // Settings
    var look by mutableStateOf(Look.NATURAL)
    var aperture by mutableFloatStateOf(2.8f)
    var videoProfile by mutableStateOf(VideoProfile.STANDARD)
    var videoRes by mutableIntStateOf(2160)
    var videoFps by mutableIntStateOf(30)
    var codec by mutableStateOf(Codec.H264)
    var stabilization by mutableStateOf(true)
    var raw by mutableStateOf(false)
    var grid by mutableStateOf(true)
    var sound by mutableStateOf(true)
    var volumeShutter by mutableStateOf(true)
    var uwCorrection by mutableStateOf(false)
    var flash by mutableStateOf(Flash.OFF)
    var timer by mutableIntStateOf(0)

    val lenses: List<Lens> get() = (if (mode.video) videoLenses else photoLenses).filter { it.key !in blocked }
}

class CameraEngine(private val app: Context) {
    private val cm = app.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("PureCam-camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private val scope = CoroutineScope(SupervisorJob() + handler.asCoroutineDispatcher())
    private val mutex = Mutex()
    private val main = Handler(Looper.getMainLooper())
    private val prefs = Settings(app)
    private val sound = MediaActionSound().apply {
        load(MediaActionSound.SHUTTER_CLICK)
        load(MediaActionSound.START_VIDEO_RECORDING)
        load(MediaActionSound.STOP_VIDEO_RECORDING)
    }
    val ui = CamUi()

    @Volatile private var texture: SurfaceTexture? = null
    @Volatile private var resumed = false
    @Volatile private var previewPending = false
    @Volatile private var orientation = 0
    @Volatile private var firstFrame = false

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null
    private var openLens: Lens? = null
    private var openVideo = false
    private var lastIso = 100
    private var lastExposure = 16_666_666L
    private var afRegion: MeteringRectangle? = null
    private var afResetJob: Job? = null
    private var zoomJob: Job? = null
    private var lastUiUpdate = 0L

    // Video recording
    private var recorderSurface: Surface? = null
    private var recorder: MediaRecorder? = null
    private var recordUri: Uri? = null
    private var recordPfd: ParcelFileDescriptor? = null
    private var videoSize = Size(1920, 1080)
    private var videoFps = 30

    init {
        val set = LensCatalog.discover(cm)
        ui.photoLenses = set.photo
        ui.videoLenses = set.video
        ui.raw = prefs.raw
        ui.grid = prefs.grid
        ui.sound = prefs.sound
        ui.volumeShutter = prefs.volumeShutter
        ui.uwCorrection = prefs.uwCorrection
        ui.flash = prefs.flash
        ui.timer = prefs.timer
        ui.look = prefs.look
        ui.aperture = prefs.aperture
        ui.videoProfile = prefs.videoProfile
        ui.videoRes = prefs.videoRes
        ui.videoFps = prefs.videoFps
        ui.codec = prefs.codec
        ui.stabilization = prefs.stabilization
        val lens = set.photo.firstOrNull { it.key == prefs.lastLens } ?: LensCatalog.main(set.photo, front = false) ?: set.photo.firstOrNull()
        ui.lens = lens
        lens?.let { ui.caps = capsOf(it) }
        refreshThumbnail()
    }

    private fun refreshThumbnail() {
        scope.launch(Dispatchers.IO) {
            val (uri, video) = Saver.latest(app) ?: return@launch
            val t = Saver.thumbnail(app, uri)
            main.post { ui.lastUri = uri; ui.lastIsVideo = video; ui.thumb = t }
        }
    }

    // ------------------------------------------------------------------ lifecycle

    fun onResume() { resumed = true; reopen() }

    fun onPause() {
        resumed = false
        scope.launch { mutex.withLock { closeLocked() } }
    }

    fun onSurfaceAvailable(t: SurfaceTexture) { texture = t; reopen() }

    fun onSurfaceDestroyed(t: SurfaceTexture) {
        texture = null
        scope.launch { mutex.withLock { closeLocked(); t.release() } }
    }

    fun setDeviceOrientation(degrees: Int) {
        val r = ((degrees + 45) / 90 * 90) % 360
        if (r == orientation) return
        orientation = r
        var target = -r
        val cur = ui.rotation
        while (target - cur > 180) target -= 360
        while (target - cur < -180) target += 360
        ui.rotation = target
    }

    // ------------------------------------------------------------------ lenses & zoom

    private fun sameCamera(a: Lens, b: Lens) = a.zoomRatio != null && b.zoomRatio != null && a.openId == b.openId

    /** True when switching to [l] needs the camera to be reopened (the UI shows a blur transition). */
    fun needsReopen(l: Lens): Boolean {
        val cur = ui.lens ?: return true
        return cur.key != l.key && !sameCamera(cur, l)
    }

    fun selectLens(l: Lens) {
        val cur = ui.lens ?: return
        if (l.key == cur.key) {
            if (abs(ui.zoom - 1f) > 0.01f) animateZoom(1f)
            return
        }
        if (sameCamera(cur, l)) {
            // Smooth zoom to the other lens; the phone hands over to the right module by itself.
            val eff = cur.zoomRatio!! * ui.zoom
            ui.lens = l
            ui.zoom = eff / l.zoomRatio!!
            rememberLens(l)
            animateZoom(1f)
            return
        }
        if (ui.recording) {
            toast("This lens can't be switched to while recording")
            return
        }
        ui.lens = l
        rememberLens(l)
        ui.zoom = 1f
        resetManual()
        ui.caps = capsOf(l)
        clearFocus()
        reopen()
    }

    private fun rememberLens(l: Lens) { if (!ui.mode.video) prefs.lastLens = l.key }

    fun flip() {
        val cur = ui.lens ?: return
        if (ui.recording) return
        LensCatalog.main(ui.lenses, front = !cur.front)?.let { selectLens(it) }
    }

    /** Pinch zoom. On zoom-ratio lenses the zoom runs continuously across all lenses, like an iPhone. */
    fun pinch(factor: Float) {
        val l = ui.lens ?: return
        zoomJob?.cancel()
        val zr = l.zoomRatio
        if (zr != null) {
            val eff = (zr * ui.zoom * factor).coerceIn(ui.caps.minZoom, ui.caps.maxZoom)
            val siblings = ui.lenses.filter { sameCamera(it, l) }.sortedBy { it.zoomRatio }
            val chip = siblings.lastOrNull { it.zoomRatio!! <= eff + 0.01f } ?: siblings.firstOrNull() ?: l
            ui.lens = chip
            ui.zoom = eff / chip.zoomRatio!!
        } else {
            ui.zoom = (ui.zoom * factor).coerceIn(1f, ui.caps.maxZoom)
        }
        requestPreview()
    }

    private fun animateZoom(target: Float) {
        zoomJob?.cancel()
        val start = ui.zoom
        zoomJob = scope.launch {
            val steps = 20
            for (i in 1..steps) {
                val t = i / steps.toFloat()
                val e = 1f - (1f - t) * (1f - t) * (1f - t) // ease-out cubic
                // interpolate in log space so the zoom feels even
                val z = kotlin.math.exp(ln(start) + (ln(target) - ln(start)) * e)
                main.post { ui.zoom = z }
                mutex.withLock { zoomLocked = z; startPreviewLocked() }
                delay(16)
            }
            zoomLocked = null
        }
    }

    /** Zoom value used while an animation is running (UI state lags one frame behind). */
    @Volatile private var zoomLocked: Float? = null
    private fun currentZoom() = zoomLocked ?: ui.zoom

    // ------------------------------------------------------------------ modes & settings

    fun setMode(m: Mode) {
        if (m == ui.mode || ui.recording) return
        val videoChange = m.video != ui.mode.video
        ui.mode = m
        if (m != Mode.PRO) resetManual()
        val cur = ui.lens
        val list = ui.lenses
        if (cur == null || list.none { it.key == cur.key }) {
            val front = cur?.front ?: false
            val r = cur?.let { it.ratio * ui.zoom } ?: 1f
            val next = list.filter { it.front == front }.minByOrNull { abs(ln(it.ratio / r)) } ?: list.firstOrNull()
            ui.lens = next
            ui.zoom = 1f
            next?.let { ui.caps = capsOf(it) }
        }
        clearFocus()
        if (videoChange || ui.lens?.key != cur?.key) reopen() else requestPreview()
    }

    fun setFlash(f: Flash) { ui.flash = f; prefs.flash = f; requestPreview() }
    fun setTorch(v: Boolean) { ui.torch = v; requestPreview() }
    fun setTimer(t: Int) { ui.timer = t; prefs.timer = t }
    fun setGrid(v: Boolean) { ui.grid = v; prefs.grid = v }
    fun setSound(v: Boolean) { ui.sound = v; prefs.sound = v }
    fun setVolumeShutter(v: Boolean) { ui.volumeShutter = v; prefs.volumeShutter = v }
    fun setUwCorrection(v: Boolean) { ui.uwCorrection = v; prefs.uwCorrection = v; requestPreview() }
    fun setRaw(v: Boolean) { ui.raw = v; prefs.raw = v; reopen() }
    fun setLook(l: Look) { ui.look = l; prefs.look = l; requestPreview() }
    fun setAperture(f: Float) { ui.aperture = f; prefs.aperture = f }
    fun setVideoProfile(p: VideoProfile) {
        if (p == VideoProfile.LOG && !ui.caps.log) { toast("This lens doesn't allow a custom tone curve, so LOG isn't available"); return }
        ui.videoProfile = p; prefs.videoProfile = p; requestPreview()
    }
    fun setVideoRes(r: Int) { if (ui.recording) return; ui.videoRes = r; prefs.videoRes = r; reopen() }
    fun setVideoFps(f: Int) { if (ui.recording) return; ui.videoFps = f; prefs.videoFps = f; reopen() }
    fun setCodec(c: Codec) { if (ui.recording) return; ui.codec = c; prefs.codec = c; reopen() }
    fun setStabilization(v: Boolean) { ui.stabilization = v; prefs.stabilization = v; requestPreview() }

    private fun resetManual() {
        ui.iso = null; ui.exposure = null; ui.focus = null
        ui.awb = CameraMetadata.CONTROL_AWB_MODE_AUTO; ui.ev = 0
    }
    fun setIso(v: Int?) { ui.iso = v; requestPreview() }
    fun setExposure(v: Long?) { ui.exposure = v; requestPreview() }
    fun setFocus(v: Float?) { ui.focus = v; if (v != null) clearFocus(); requestPreview() }
    fun setAwb(v: Int) { ui.awb = v; requestPreview() }
    fun setEv(v: Int) { ui.ev = v; requestPreview() }

    /** Tap-to-focus/meter. nx, ny are 0..1 positions on the (portrait) preview. */
    fun tapFocus(nx: Float, ny: Float) {
        val l = ui.lens ?: return
        ui.focusPoint = Offset(nx, ny)
        ui.focusLocked = false
        scope.launch {
            mutex.withLock {
                afRegion = meteringRect(l, nx, ny)
                startPreviewLocked()
                if (ui.focus == null) triggerAfLocked()
            }
        }
        afResetJob?.cancel()
        afResetJob = scope.launch {
            delay(if (ui.mode.video) 8000 else 5000)
            mutex.withLock { afRegion = null; main.post { ui.focusPoint = null }; startPreviewLocked() }
        }
    }

    /** Shutter button / volume key. */
    fun shutter() {
        if (ui.mode.video) { toggleRecording(); return }
        if (ui.capturing || ui.countdown > 0 || !ui.ready) return
        scope.launch {
            val t = ui.timer
            if (t > 0) {
                for (i in t downTo 1) { main.post { ui.countdown = i }; delay(1000) }
                main.post { ui.countdown = 0 }
            }
            mutex.withLock { captureLocked() }
        }
    }

    private fun clearFocus() {
        afResetJob?.cancel()
        ui.focusPoint = null
        scope.launch { mutex.withLock { afRegion = null } }
    }

    fun toast(msg: String) = main.post { Toast.makeText(app, msg, Toast.LENGTH_LONG).show() }

    // ------------------------------------------------------------------ open / close

    private fun reopen() {
        scope.launch {
            mutex.withLock {
                closeLocked()
                val t = texture
                val l = ui.lens
                if (!resumed || t == null || l == null) return@withLock
                if (ContextCompat.checkSelfPermission(app, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return@withLock
                try {
                    openLocked(l, t)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    closeLocked()
                    val busy = e is CameraAccessException && e.reason in setOf(
                        CameraAccessException.CAMERA_IN_USE, CameraAccessException.MAX_CAMERAS_IN_USE,
                        CameraAccessException.CAMERA_DISABLED, CameraAccessException.CAMERA_DISCONNECTED,
                    )
                    if (busy) {
                        toast("Camera is busy. Close other camera apps and try again.")
                        return@withLock
                    }
                    // 1) Same lens through the multi-camera zoom system
                    val alt = l.fallback
                    if (alt != null) {
                        replaceLens(l, alt)
                        try {
                            openLocked(alt, t)
                            toast("Using the ${l.label}× lens through the phone's multi-camera system")
                            return@withLock
                        } catch (e2: Exception) {
                            closeLocked()
                        }
                    }
                    // 2) Give up on this lens and go back to the main one
                    val others = ui.lenses.filter { it.key != l.key && it.key != alt?.key }
                    val fb = LensCatalog.main(others, l.front) ?: LensCatalog.main(others, !l.front)
                    ui.blocked = ui.blocked + l.key + listOfNotNull(alt?.key)
                    if (fb == null) { toast("Couldn't open the camera: ${e.message}"); return@withLock }
                    toast("The ${l.label}× lens isn't available to apps on this phone. Switched to ${fb.label}×.")
                    ui.lens = fb
                    ui.caps = capsOf(fb)
                    try { openLocked(fb, t) } catch (e3: Exception) {
                        closeLocked(); toast("Couldn't open the camera: ${e3.message}")
                    }
                }
            }
        }
    }

    private fun replaceLens(old: Lens, new: Lens) {
        ui.photoLenses = ui.photoLenses.map { if (it.key == old.key) new else it }
        ui.videoLenses = ui.videoLenses.map { if (it.key == old.key) new else it }
        ui.lens = new
        ui.caps = capsOf(new)
    }

    @SuppressLint("MissingPermission")
    private suspend fun openLocked(l: Lens, t: SurfaceTexture) {
        val ch = l.chars
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: error("No stream configuration")
        val caps = capsOf(l)
        val video = ui.mode.video
        val outputs = mutableListOf<Surface>()

        val prevSize: Size
        if (video) {
            val recSizes = map.getOutputSizes(MediaRecorder::class.java) ?: emptyArray()
            val uhd = Size(3840, 2160)
            val fhd = Size(1920, 1080)
            videoSize = if (ui.videoRes == 2160 && caps.uhd) uhd else if (recSizes.contains(fhd)) fhd else pick169(recSizes)
            videoFps = if (ui.videoFps == 60 && supports60(l, videoSize)) 60 else 30
            main.post { ui.effRes = videoSize.height; ui.effFps = videoFps }
            prevSize = pickPreview(map.getOutputSizes(SurfaceTexture::class.java), videoSize)
            t.setDefaultBufferSize(prevSize.width, prevSize.height)
            val ps = Surface(t).also { previewSurface = it }
            outputs += ps
            val rs = MediaCodec.createPersistentInputSurface()
            recorderSurface = rs
            val dummy = File(app.cacheDir, "warmup.mp4")
            createRecorder(rs, null, dummy, audio = false, rotation = 0).apply { prepare(); release() }
            dummy.delete()
            outputs += rs
        } else {
            val jpegSize = pick43(map.getOutputSizes(ImageFormat.JPEG))
            prevSize = pickPreview(map.getOutputSizes(SurfaceTexture::class.java), jpegSize)
            t.setDefaultBufferSize(prevSize.width, prevSize.height)
            val ps = Surface(t).also { previewSurface = it }
            outputs += ps
            val jr = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2).also { jpegReader = it }
            outputs += jr.surface
            if (caps.raw && ui.raw && ui.mode != Mode.PORTRAIT) {
                val rs = map.getOutputSizes(ImageFormat.RAW_SENSOR).maxBy { it.width.toLong() * it.height }
                val rr = ImageReader.newInstance(rs.width, rs.height, ImageFormat.RAW_SENSOR, 2)
                rawReader = rr
                outputs += rr.surface
            }
        }

        val dev = openDevice(l.openId)
        device = dev
        openLens = l
        openVideo = video
        session = try {
            createSession(dev, outputs, l.physicalId)
        } catch (e: Exception) {
            val rr = rawReader ?: throw e
            outputs.remove(rr.surface)
            rr.close(); rawReader = null
            toast("RAW isn't available on this lens; saving JPEG only.")
            createSession(dev, outputs, l.physicalId)
        }
        val so = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val aspect = if (so % 180 == 0) prevSize.width.toFloat() / prevSize.height else prevSize.height.toFloat() / prevSize.width
        firstFrame = true
        main.post { ui.previewAspect = aspect; ui.caps = caps; ui.ready = true }
        startPreviewLocked()
    }

    private suspend fun openDevice(id: String): CameraDevice = suspendCancellableCoroutine { cont ->
        cm.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) {
                if (cont.isActive) cont.resume(d) else d.close()
            }
            override fun onDisconnected(d: CameraDevice) {
                d.close()
                if (cont.isActive) cont.resumeWithException(CameraAccessException(CameraAccessException.CAMERA_DISCONNECTED))
                else lost()
            }
            override fun onError(d: CameraDevice, error: Int) {
                d.close()
                if (cont.isActive) cont.resumeWithException(IllegalStateException("Camera error $error"))
                else lost()
            }
        }, handler)
    }

    private fun lost() {
        toast("The camera was interrupted. Reopen the app to continue.")
        scope.launch { mutex.withLock { closeLocked() } }
    }

    private suspend fun createSession(dev: CameraDevice, surfaces: List<Surface>, physicalId: String?): CameraCaptureSession =
        suspendCancellableCoroutine { cont ->
            val outs = surfaces.map { s -> OutputConfiguration(s).apply { if (physicalId != null) setPhysicalCameraId(physicalId) } }
            val cb = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) { if (cont.isActive) cont.resume(s) }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("Session configuration failed"))
                }
            }
            dev.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outs, executor, cb))
        }

    private fun closeLocked() {
        if (recorder != null) stopRecordingLocked()
        runCatching { session?.close() }
        session = null
        runCatching { device?.close() }
        device = null
        jpegReader?.close(); jpegReader = null
        rawReader?.close(); rawReader = null
        previewSurface?.release(); previewSurface = null
        recorderSurface?.release(); recorderSurface = null
        openLens = null
        main.post { ui.ready = false }
    }

    // ------------------------------------------------------------------ requests

    private fun requestPreview() {
        if (previewPending) return
        previewPending = true
        scope.launch { mutex.withLock { previewPending = false; startPreviewLocked() } }
    }

    private fun startPreviewLocked() {
        val dev = device ?: return
        val s = session ?: return
        val ps = previewSurface ?: return
        try {
            val b = dev.createCaptureRequest(if (openVideo) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(ps)
            if (recorder != null) recorderSurface?.let { b.addTarget(it) }
            applyCommon(b, still = false)
            s.setRepeatingRequest(b.build(), previewCallback, handler)
        } catch (_: Exception) {
            // session is closing; a new one will start its own preview
        }
    }

    private fun triggerAfLocked() {
        val dev = device ?: return
        val s = session ?: return
        val ps = previewSurface ?: return
        try {
            val b = dev.createCaptureRequest(if (openVideo) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(ps)
            if (recorder != null) recorderSurface?.let { b.addTarget(it) }
            applyCommon(b, still = false)
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            s.capture(b.build(), previewCallback, handler)
        } catch (_: Exception) {
        }
    }

    private val previewCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
            if (firstFrame) {
                firstFrame = false
                main.post { ui.streamSeq++ }
            }
            res.get(CaptureResult.SENSOR_SENSITIVITY)?.let { lastIso = it }
            res.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { lastExposure = it }
            val now = SystemClock.uptimeMillis()
            if (now - lastUiUpdate > 250) {
                lastUiUpdate = now
                val i = lastIso
                val e = lastExposure
                val active = res.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
                main.post { ui.liveIso = i; ui.liveExposure = e; ui.activeSensor = active }
            }
            if (afRegion != null) {
                val st = res.get(CaptureResult.CONTROL_AF_STATE)
                if (st == CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED || st == CameraMetadata.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {
                    main.post { ui.focusLocked = true }
                }
            }
        }
    }

    private fun pick(avail: IntArray?, vararg order: Int): Int? =
        if (avail == null) null else order.firstOrNull { it in avail }

    private fun manualExposureActive(caps: LensCaps) = ui.mode == Mode.PRO && caps.manualSensor && (ui.iso != null || ui.exposure != null)

    /** Settings shared by preview, recording and still capture, including the processing pipeline. */
    private fun applyCommon(b: CaptureRequest.Builder, still: Boolean) {
        val l = openLens ?: return
        val ch = l.chars
        val caps = capsOf(l)
        val video = openVideo
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)

        // ---- Focus
        val afModes = ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
        val manualFocus = if (ui.mode == Mode.PRO) ui.focus else null
        val region = afRegion
        when {
            manualFocus != null && caps.minFocus > 0f -> {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                b.set(CaptureRequest.LENS_FOCUS_DISTANCE, manualFocus)
            }
            region != null && CameraMetadata.CONTROL_AF_MODE_AUTO in afModes -> {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
                if ((ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) > 0) b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
            }
            video && CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO in afModes ->
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE in afModes ->
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            CameraMetadata.CONTROL_AF_MODE_OFF in afModes ->
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
        }

        // ---- Exposure, frame rate, flash
        if (video) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            b.set(CaptureRequest.FLASH_MODE, if (ui.torch && caps.flash) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF)
            fpsRange(ch, videoFps)?.let { b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            if (region != null && (ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0) > 0) b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
        } else {
            val flash = if (caps.flash) ui.flash else Flash.OFF
            if (manualExposureActive(caps)) {
                val exp = (ui.exposure ?: lastExposure).coerceIn(caps.expRange)
                b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                b.set(CaptureRequest.SENSOR_SENSITIVITY, (ui.iso ?: lastIso).coerceIn(caps.isoRange))
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exp)
                b.set(CaptureRequest.SENSOR_FRAME_DURATION, max(exp, 33_333_333L))
                b.set(
                    CaptureRequest.FLASH_MODE,
                    when {
                        flash == Flash.TORCH -> CameraMetadata.FLASH_MODE_TORCH
                        still && flash == Flash.ON -> CameraMetadata.FLASH_MODE_SINGLE
                        else -> CameraMetadata.FLASH_MODE_OFF
                    },
                )
            } else {
                b.set(
                    CaptureRequest.CONTROL_AE_MODE,
                    when (flash) {
                        Flash.AUTO -> CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH
                        Flash.ON -> CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                        else -> CameraMetadata.CONTROL_AE_MODE_ON
                    },
                )
                b.set(CaptureRequest.FLASH_MODE, if (flash == Flash.TORCH) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF)
                if (ui.mode == Mode.PRO) b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, ui.ev.coerceIn(caps.evRange))
                if (region != null && (ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0) > 0) {
                    b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
                }
            }
        }

        // ---- White balance
        val awb = if (ui.mode == Mode.PRO) ui.awb else CameraMetadata.CONTROL_AWB_MODE_AUTO
        b.set(CaptureRequest.CONTROL_AWB_MODE, if (awb in caps.awbModes) awb else CameraMetadata.CONTROL_AWB_MODE_AUTO)

        // ---- Zoom
        val z = currentZoom()
        val zr = l.zoomRatio
        if (zr != null && Build.VERSION.SDK_INT >= 30) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, (zr * z).coerceIn(caps.minZoom, caps.maxZoom))
        } else if (l.physicalId == null && z > 1.001f) {
            b.set(CaptureRequest.SCALER_CROP_REGION, cropRect(l, z))
        }

        // ---- Stabilisation: optical is mechanical and always on; electronic only for processed video
        val ois = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
        pick(ois, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)?.let { b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, it) }
        val eisModes = ch.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
        val wantEis = video && ui.stabilization && ui.videoProfile != VideoProfile.NATURAL
        b.set(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            if (wantEis) pick(eisModes, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON) ?: CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
            else CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
        )

        // ---- Processing pipeline
        val natural = if (video) ui.videoProfile == VideoProfile.NATURAL else ui.look.natural
        val log = video && ui.videoProfile == VideoProfile.LOG && caps.log
        val nrModes = ch.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
        val edgeModes = ch.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)
        when {
            natural -> {
                pick(nrModes, CameraMetadata.NOISE_REDUCTION_MODE_OFF, CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL, CameraMetadata.NOISE_REDUCTION_MODE_FAST)
                    ?.let { b.set(CaptureRequest.NOISE_REDUCTION_MODE, it) }
                pick(edgeModes, CameraMetadata.EDGE_MODE_OFF, CameraMetadata.EDGE_MODE_FAST)?.let { b.set(CaptureRequest.EDGE_MODE, it) }
                pick(
                    ch.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES),
                    CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_OFF, CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_FAST,
                )?.let { b.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, it) }
                // A plain, global sRGB curve instead of the phone's punchy tone curve
                val tm = ch.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES) ?: intArrayOf()
                if (CameraMetadata.TONEMAP_MODE_PRESET_CURVE in tm) {
                    b.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_PRESET_CURVE)
                    b.set(CaptureRequest.TONEMAP_PRESET_CURVE, CameraMetadata.TONEMAP_PRESET_CURVE_SRGB)
                } else if (CameraMetadata.TONEMAP_MODE_GAMMA_VALUE in tm) {
                    b.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_GAMMA_VALUE)
                    b.set(CaptureRequest.TONEMAP_GAMMA, 2.2f)
                }
            }
            log -> {
                pick(nrModes, CameraMetadata.NOISE_REDUCTION_MODE_FAST, CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL)
                    ?.let { b.set(CaptureRequest.NOISE_REDUCTION_MODE, it) }
                pick(edgeModes, CameraMetadata.EDGE_MODE_OFF, CameraMetadata.EDGE_MODE_FAST)?.let { b.set(CaptureRequest.EDGE_MODE, it) }
                val pts = PureLog.curve(ch.get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS) ?: 32)
                b.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)
                b.set(CaptureRequest.TONEMAP_CURVE, TonemapCurve(pts, pts.copyOf(), pts.copyOf()))
            }
            video -> {
                // Standard: clean, sharp, YouTube-ready Rec.709
                pick(nrModes, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY, CameraMetadata.NOISE_REDUCTION_MODE_FAST)
                    ?.let { b.set(CaptureRequest.NOISE_REDUCTION_MODE, it) }
                pick(edgeModes, CameraMetadata.EDGE_MODE_FAST)?.let { b.set(CaptureRequest.EDGE_MODE, it) }
            }
            still -> {
                pick(nrModes, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY)?.let { b.set(CaptureRequest.NOISE_REDUCTION_MODE, it) }
                pick(edgeModes, CameraMetadata.EDGE_MODE_HIGH_QUALITY)?.let { b.set(CaptureRequest.EDGE_MODE, it) }
            }
        }
        val dc = ch.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES)
        val dcMode = if (natural && !ui.uwCorrection) {
            pick(dc, CameraMetadata.DISTORTION_CORRECTION_MODE_OFF)
        } else {
            pick(dc, if (still) CameraMetadata.DISTORTION_CORRECTION_MODE_HIGH_QUALITY else CameraMetadata.DISTORTION_CORRECTION_MODE_FAST, CameraMetadata.DISTORTION_CORRECTION_MODE_FAST)
        }
        if (dcMode != null) b.set(CaptureRequest.DISTORTION_CORRECTION_MODE, dcMode)

        if (still) {
            b.set(CaptureRequest.JPEG_QUALITY, 100.toByte())
            b.set(CaptureRequest.JPEG_ORIENTATION, sensorRotation(l))
        }
    }

    private fun fpsRange(ch: CameraCharacteristics, fps: Int): Range<Int>? {
        val ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null
        return ranges.firstOrNull { it.lower == fps && it.upper == fps }
            ?: ranges.filter { it.upper == fps }.maxByOrNull { it.lower }
            ?: ranges.filter { it.upper >= fps }.minByOrNull { it.upper }
    }

    private fun supports60(l: Lens, size: Size): Boolean {
        val ch = l.chars
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return false
        val minDur = runCatching { map.getOutputMinFrameDuration(MediaRecorder::class.java, size) }.getOrDefault(Long.MAX_VALUE)
        val ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return false
        return minDur in 1..16_700_000L && ranges.any { it.upper >= 60 }
    }

    // ------------------------------------------------------------------ photo capture

    private suspend fun captureLocked() {
        val dev = device ?: return
        val s = session ?: return
        val l = openLens ?: return
        val jr = jpegReader ?: return
        val rr = rawReader
        val caps = capsOf(l)
        main.post { ui.capturing = true }
        val usesFlash = caps.flash && !manualExposureActive(caps) && (ui.flash == Flash.AUTO || ui.flash == Flash.ON)
        var jpegImg: Image? = null
        var rawImg: Image? = null
        try {
            if (usesFlash) precaptureLocked(dev, s)

            jr.acquireLatestImage()?.close()
            rr?.acquireLatestImage()?.close()
            val jpegD = CompletableDeferred<Image>()
            val rawD = CompletableDeferred<Image>()
            jr.setOnImageAvailableListener({ r -> r.acquireNextImage()?.let { if (!jpegD.complete(it)) it.close() } }, handler)
            rr?.setOnImageAvailableListener({ r -> r.acquireNextImage()?.let { if (!rawD.complete(it)) it.close() } }, handler)
            val resultD = CompletableDeferred<TotalCaptureResult>()

            val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            b.addTarget(jr.surface)
            rr?.let { b.addTarget(it.surface) }
            applyCommon(b, still = true)
            val playSound = ui.sound
            s.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureStarted(session: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
                    if (playSound) sound.play(MediaActionSound.SHUTTER_CLICK)
                    main.post { ui.shutterBlink++ }
                }
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    resultD.complete(result)
                }
                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    resultD.completeExceptionally(IllegalStateException("Capture failed (${failure.reason})"))
                }
            }, handler)

            val result = withTimeout(10_000) { resultD.await() }
            jpegImg = withTimeout(10_000) { jpegD.await() }
            if (rr != null) rawImg = withTimeoutOrNull(10_000) { rawD.await() }

            val buf = jpegImg.planes[0].buffer
            var bytes = ByteArray(buf.remaining()).also { buf.get(it) }
            jpegImg.close(); jpegImg = null

            // Looks and Portrait blur are applied off the camera thread
            val portrait = ui.mode == Mode.PORTRAIT
            val look = ui.look
            if (portrait || look.matrix() != null) {
                main.post { ui.processing = true }
                try {
                    val res = withContext(Dispatchers.Default) {
                        PhotoProcessor.process(app, bytes, look, if (portrait) ui.aperture else null)
                    }
                    bytes = res.jpeg
                    if (!res.personFound) toast("No person found, so the photo was saved without background blur")
                } catch (e: Exception) {
                    toast("Couldn't apply the effect; saved the plain photo")
                } finally {
                    main.post { ui.processing = false }
                }
            }

            val name = "PURE_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            val rotation = sensorRotation(l)
            val raw = rawImg
            val finalBytes = bytes
            val uri = withContext(Dispatchers.IO) {
                val u = Saver.saveJpeg(app, name, finalBytes)
                if (raw != null) {
                    Saver.saveDng(app, name) { out ->
                        val dng = DngCreator(l.chars, result)
                        try {
                            dng.setOrientation(exifOrientation(rotation))
                            dng.writeImage(out, raw)
                        } finally {
                            dng.close()
                        }
                    }
                }
                u
            }
            val thumb = withContext(Dispatchers.IO) { Saver.thumbnail(app, uri) }
            main.post { ui.lastUri = uri; ui.lastIsVideo = false; if (thumb != null) ui.thumb = thumb }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            toast("Photo not saved: ${e.message}")
        } finally {
            jpegImg?.close()
            rawImg?.close()
            jr.setOnImageAvailableListener(null, null)
            rr?.setOnImageAvailableListener(null, null)
            if (usesFlash) startPreviewLocked()
            main.post { ui.capturing = false }
        }
    }

    /** Lets auto-exposure measure the scene with the pre-flash before a flash shot. */
    private suspend fun precaptureLocked(dev: CameraDevice, s: CameraCaptureSession) {
        val ps = previewSurface ?: return
        val done = CompletableDeferred<Unit>()
        var frames = 0
        val cb = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                frames++
                val ae = result.get(CaptureResult.CONTROL_AE_STATE)
                if (frames > 3 && ae != CameraMetadata.CONTROL_AE_STATE_PRECAPTURE && ae != CameraMetadata.CONTROL_AE_STATE_SEARCHING) {
                    done.complete(Unit)
                }
            }
        }
        val rep = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(ps); applyCommon(this, false) }
        s.setRepeatingRequest(rep.build(), cb, handler)
        val trig = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(ps)
            applyCommon(this, false)
            set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START)
        }
        s.capture(trig.build(), cb, handler)
        withTimeoutOrNull(2000) { done.await() }
    }

    // ------------------------------------------------------------------ video recording

    private fun toggleRecording() {
        if (!ui.ready) return
        scope.launch { mutex.withLock { if (recorder != null) stopRecordingLocked() else startRecordingLocked() } }
    }

    private fun bitrate(): Int {
        val uhd = videoSize.width >= 3840
        val hi = videoFps >= 60
        val h264 = when {
            uhd && hi -> 80_000_000
            uhd -> 50_000_000
            hi -> 30_000_000
            else -> 20_000_000
        }
        return if (ui.codec == Codec.HEVC) (h264 * 0.7f).toInt() else h264
    }

    @Suppress("DEPRECATION")
    private fun createRecorder(surface: Surface, fd: FileDescriptor?, file: File?, audio: Boolean, rotation: Int): MediaRecorder {
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(app) else MediaRecorder()
        if (audio) r.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
        r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        if (fd != null) r.setOutputFile(fd) else r.setOutputFile(file!!.absolutePath)
        r.setVideoEncodingBitRate(bitrate())
        r.setVideoFrameRate(videoFps)
        r.setVideoSize(videoSize.width, videoSize.height)
        r.setVideoEncoder(if (ui.codec == Codec.HEVC) MediaRecorder.VideoEncoder.HEVC else MediaRecorder.VideoEncoder.H264)
        if (audio) {
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(48_000)
            r.setAudioEncodingBitRate(256_000)
            r.setAudioChannels(2)
        }
        r.setInputSurface(surface)
        r.setOrientationHint(rotation)
        return r
    }

    private fun startRecordingLocked() {
        val surf = recorderSurface ?: return
        val l = openLens ?: return
        val audio = ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val name = "PURE_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        var uri: Uri? = null
        var pfd: ParcelFileDescriptor? = null
        try {
            val created = Saver.createVideo(app, name)
            uri = created.first
            pfd = created.second
            val rec = createRecorder(surf, created.second.fileDescriptor, null, audio, sensorRotation(l))
            rec.prepare()
            recorder = rec
            recordUri = uri
            recordPfd = pfd
            startPreviewLocked()
            rec.start()
            if (ui.sound) sound.play(MediaActionSound.START_VIDEO_RECORDING)
            val now = SystemClock.elapsedRealtime()
            main.post { ui.recordStart = now; ui.recording = true }
            if (!audio) toast("Recording without sound (microphone permission is off)")
        } catch (e: Exception) {
            runCatching { recorder?.release() }
            recorder = null
            runCatching { pfd?.close() }
            uri?.let { Saver.discard(app, it) }
            recordUri = null; recordPfd = null
            startPreviewLocked()
            toast("Couldn't start recording: ${e.message}")
        }
    }

    private fun stopRecordingLocked() {
        val rec = recorder ?: return
        val ok = runCatching { rec.stop() }.isSuccess
        runCatching { rec.release() }
        recorder = null
        runCatching { recordPfd?.close() }
        recordPfd = null
        val uri = recordUri
        recordUri = null
        if (uri != null) {
            if (ok) Saver.publish(app, uri) else { Saver.discard(app, uri); toast("Video too short, not saved") }
        }
        if (ui.sound) sound.play(MediaActionSound.STOP_VIDEO_RECORDING)
        main.post { ui.recording = false }
        startPreviewLocked()
        if (ok && uri != null) {
            scope.launch(Dispatchers.IO) {
                val t = Saver.thumbnail(app, uri)
                main.post { ui.lastUri = uri; ui.lastIsVideo = true; if (t != null) ui.thumb = t }
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun capsOf(l: Lens): LensCaps {
        val ch = l.chars
        val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val manual = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val iso = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val exp = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val ev = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        var minZoom = 1f
        var maxZoom = if (l.physicalId == null) (ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f).coerceIn(1f, 10f) else 1f
        if (l.zoomRatio != null && Build.VERSION.SDK_INT >= 30) {
            ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let { minZoom = it.lower; maxZoom = min(it.upper, 10f) }
        }
        val recSizes = map?.getOutputSizes(MediaRecorder::class.java) ?: emptyArray()
        val uhd = recSizes.contains(Size(3840, 2160))
        val tm = ch.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES) ?: intArrayOf()
        return LensCaps(
            manualSensor = manual && iso != null && exp != null,
            isoRange = if (iso != null) iso.lower..iso.upper else 100..100,
            expRange = if (exp != null) exp.lower..min(exp.upper, 30_000_000_000L) else 1_000_000L..1_000_000L,
            minFocus = if (manual) ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f else 0f,
            evRange = if (ev != null) ev.lower..ev.upper else 0..0,
            evStep = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 1f,
            flash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
            raw = l.physicalId == null && l.zoomRatio == null &&
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW in caps &&
                map?.outputFormats?.contains(ImageFormat.RAW_SENSOR) == true,
            awbModes = (ch.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: intArrayOf(CameraMetadata.CONTROL_AWB_MODE_AUTO)).toList(),
            minZoom = minZoom,
            maxZoom = maxZoom,
            uhd = uhd,
            fps60 = supports60(l, if (uhd && ui.videoRes == 2160) Size(3840, 2160) else Size(1920, 1080)),
            log = CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE in tm,
        )
    }

    private fun cropRect(l: Lens, zoom: Float): Rect {
        val a = l.chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return Rect(0, 0, 1, 1)
        val z = zoom.coerceAtLeast(1f)
        val w = (a.width() / z).roundToInt()
        val h = (a.height() / z).roundToInt()
        val left = (a.width() - w) / 2
        val top = (a.height() - h) / 2
        return Rect(left, top, left + w, top + h)
    }

    private fun meteringRect(l: Lens, nx: Float, ny: Float): MeteringRectangle {
        val so = l.chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val x = if (l.front) 1f - nx else nx
        val (sx, sy) = when (so) {
            90 -> ny to 1f - x
            180 -> 1f - x to 1f - ny
            270 -> 1f - ny to x
            else -> x to ny
        }
        val z = currentZoom()
        val crop = if (l.zoomRatio == null && l.physicalId == null && z > 1.001f) cropRect(l, z)
        else l.chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let { Rect(0, 0, it.width(), it.height()) } ?: Rect(0, 0, 1, 1)
        val half = (min(crop.width(), crop.height()) * 0.07f).roundToInt().coerceAtLeast(10)
        val cx = (crop.left + sx * crop.width()).roundToInt()
        val cy = (crop.top + sy * crop.height()).roundToInt()
        val r = Rect(
            (cx - half).coerceAtLeast(crop.left), (cy - half).coerceAtLeast(crop.top),
            (cx + half).coerceAtMost(crop.right - 1), (cy + half).coerceAtMost(crop.bottom - 1),
        )
        return MeteringRectangle(r, MeteringRectangle.METERING_WEIGHT_MAX - 1)
    }

    private fun sensorRotation(l: Lens): Int {
        val so = l.chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val d = if (l.front) -orientation else orientation
        return (so + d + 360) % 360
    }

    private fun exifOrientation(deg: Int) = when (deg) {
        90 -> ExifInterface.ORIENTATION_ROTATE_90
        180 -> ExifInterface.ORIENTATION_ROTATE_180
        270 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }

    private fun ratio(s: Size) = s.width.toFloat() / s.height

    private fun pick43(sizes: Array<Size>): Size {
        val fourThree = sizes.filter { abs(ratio(it) - 4f / 3f) < 0.02f }
        return (fourThree.ifEmpty { sizes.toList() }).maxBy { it.width.toLong() * it.height }
    }

    private fun pick169(sizes: Array<Size>): Size {
        val wide = sizes.filter { abs(ratio(it) - 16f / 9f) < 0.02f && it.width <= 1920 }
        return wide.maxByOrNull { it.width.toLong() * it.height } ?: sizes.maxByOrNull { it.width.toLong() * it.height } ?: Size(1280, 720)
    }

    private fun pickPreview(sizes: Array<Size>, target: Size): Size {
        val r = ratio(target)
        val same = sizes.filter { abs(ratio(it) - r) < 0.02f && it.width <= 2048 && it.height <= 1536 }
        if (same.isNotEmpty()) return same.maxBy { it.width.toLong() * it.height }
        return sizes.filter { it.width <= 1920 && it.height <= 1440 }.maxByOrNull { it.width.toLong() * it.height } ?: sizes.first()
    }
}
